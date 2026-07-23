package dev.pokepad.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/*
 * Poképad — duel transport (pure java.net; no Android deps so the desktop
 * loopback test runs the exact code the phones do).
 *
 * DuelServer: accepts ONE challenger over TCP and, while waiting, shouts a UDP
 * broadcast beacon ("POKEPAD|<name>|<port>") once a second so the joiner can
 * find it with zero typing. DuelClient: listens for the beacon (with a manual
 * IP fallback) and connects. All I/O is off the calling thread; lines arrive
 * on a reader thread, sends go through a single-thread executor.
 */

class DuelServer(
    private val name: String,
    private val onLine: (String) -> Unit,
    private val onJoin: (String) -> Unit,     // remote address
    private val onDrop: (String) -> Unit,
) {
    @Volatile private var running = true
    @Volatile private var joined = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun start(port: Int = Proto.TCP_PORT) {
        Thread({
            try {
                val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(port))
                server = ss
                beacon(port)
                val s = ss.accept()
                socket = s; joined = true
                writer = PrintWriter(s.getOutputStream(), true)
                onJoin(s.inetAddress.hostAddress ?: "?")
                val br = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running) { val l = br.readLine() ?: break; onLine(l) }
                if (running) onDrop("challenger left")
            } catch (e: Exception) { if (running) onDrop(e.message ?: "server error") }
        }, "duel-server").start()
    }

    private fun beacon(port: Int) {
        Thread({
            runCatching {
                val ds = DatagramSocket(); ds.broadcast = true
                val payload = "${Proto.BEACON_PREFIX}$name|$port".toByteArray()
                while (running && !joined) {
                    runCatching {
                        ds.send(DatagramPacket(payload, payload.size,
                            InetAddress.getByName("255.255.255.255"), Proto.UDP_PORT))
                    }
                    Thread.sleep(1000)
                }
                ds.close()
            }
        }, "duel-beacon").start()
    }

    fun send(line: String) { sender.execute { runCatching { writer?.println(line) } } }
    fun stop() { running = false; runCatching { socket?.close() }; runCatching { server?.close() }; sender.shutdown() }
}

class DuelClient(
    private val onLine: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDrop: (String) -> Unit,
) {
    @Volatile private var running = true
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun connect(ip: String, port: Int = Proto.TCP_PORT) {
        Thread({
            try {
                val s = Socket(); s.connect(InetSocketAddress(ip, port), 6000)
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)
                onConnected()
                val br = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running) { val l = br.readLine() ?: break; onLine(l) }
                if (running) onDrop("host left")
            } catch (e: Exception) { if (running) onDrop(e.message ?: "couldn't reach the host") }
        }, "duel-client").start()
    }

    fun send(line: String) { sender.execute { runCatching { writer?.println(line) } } }
    fun stop() { running = false; runCatching { socket?.close() }; sender.shutdown() }

    companion object {
        /** listen for a host beacon for ~[seconds]; onFound(ip, name, port) once. */
        fun discover(seconds: Int = 6, onFound: (String, String, Int) -> Unit, onMiss: () -> Unit) {
            Thread({
                try {
                    val ds = DatagramSocket(null)
                    ds.reuseAddress = true; ds.bind(InetSocketAddress(Proto.UDP_PORT))
                    ds.soTimeout = seconds * 1000
                    val buf = ByteArray(512)
                    val pkt = DatagramPacket(buf, buf.size)
                    ds.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length)
                    ds.close()
                    if (msg.startsWith(Proto.BEACON_PREFIX)) {
                        val f = msg.split("|")
                        onFound(pkt.address.hostAddress ?: "?", f.getOrElse(1) { "host" },
                            f.getOrElse(2) { "${Proto.TCP_PORT}" }.toIntOrNull() ?: Proto.TCP_PORT)
                    } else onMiss()
                } catch (e: Exception) { onMiss() }
            }, "duel-discover").start()
        }
    }
}

/** this device's LAN address, for the "or type my IP" fallback */
fun localIp(): String {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull() ?: "?"
}
