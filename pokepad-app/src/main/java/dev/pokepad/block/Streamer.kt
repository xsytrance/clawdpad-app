package dev.pokepad.block

import android.content.res.AssetManager
import android.media.midi.MidiInputPort
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Drives the block glass for Poképad. A scene-only port of the ClawdPad streamer:
 * the ROLI handshake → live-sync to the block's topology index & packet counter →
 * upload the LittleFoot bitmap program ONCE → then a render loop that diff-streams
 * the current Scene's 15x15 frames (and relays to a snapped-on second block).
 *
 * The moods / music / baked-loop paths are dropped; a battle Scene owns the glass,
 * and a resting Poké Ball shows when idle. The handshake/program/ping bytes come
 * from assets/stream.json (device-agnostic ROLI SysEx, golden-tested).
 */
private const val PROGRAM_SIZE = 100  // bitmap_led_program() length; frame data follows

class Streamer(
    assets: AssetManager,
    private val port: MidiInputPort,
    private val say: (String) -> Unit,
    private val blocks: Blocks.State = Blocks.State(),
) : Thread("pokepad-streamer") {

    @Volatile private var deviceIdx = 9   // replaced by live topology
    @Volatile var scene: Scene? = null    // a battle owns the glass; null = idle ball
    @Volatile private var running = true

    private val doc = JSONObject(assets.open("stream.json").readBytes().decodeToString())
    private val ping = Base64.decode(doc.getString("ping"), Base64.DEFAULT)
    private var lastPing = 0L
    private var beginUntil = 0L
    private var lastBegin = 0L
    private var nextIndex = 1

    fun quit() { running = false; interrupt(); runCatching { port.close() } }

    /** Rewrite a SharedDataChange packet's 16-bit index + checksum (renumber port). */
    private fun renumber(pkt: ByteArray, index: Int): ByteArray {
        val p = pkt.copyOf()
        for (k in 0 until 16) {
            val bit = 7 + k; val bi = 6 + bit / 7; val off = bit % 7
            val v = (index shr k) and 1
            p[bi] = ((p[bi].toInt() and (1 shl off).inv()) or (v shl off)).toByte()
        }
        var cs = (p.size - 8) and 0xFF
        for (i in 6 until p.size - 2) cs = (cs + (cs * 2 + (p[i].toInt() and 0xFF))) and 0xFF
        p[p.size - 2] = (cs and 0x7F).toByte()
        return p
    }

    private fun send(bytes: ByteArray) {
        if (bytes.size > 6 && bytes[0] == 0xF0.toByte() && bytes[4] == 0x77.toByte())
            bytes[5] = (deviceIdx and 0x3F).toByte()
        port.send(bytes, 0, bytes.size)
    }

    // ── two-block relay ───────────────────────────────────────────────────
    @Volatile var secondIdx = -1
    private var hs2: HeapStreamer? = null
    private var idx2 = 1
    private var block2First = true
    private var relayStall = 0

    private fun sendTo(bytes: ByteArray, idx: Int) {
        if (bytes.size > 6 && bytes[0] == 0xF0.toByte() && bytes[4] == 0x77.toByte())
            bytes[5] = (idx and 0x3F).toByte()
        port.send(bytes, 0, bytes.size)
    }

    private fun bootSecond(idx: Int) {
        val hs = doc.getJSONArray("handshake")
        sendTo(Base64.decode(hs.getString(0), Base64.DEFAULT), idx); sleep(60)
        for (i in 1 until hs.length()) { sendTo(Base64.decode(hs.getString(i), Base64.DEFAULT), idx); sleep(120) }
        val begin = Base64.decode(hs.getString(3), Base64.DEFAULT)
        repeat(6) { sendTo(begin, idx); sleep(120) }
        idx2 = 1
        val boot = doc.getJSONArray("boot")
        for (i in 0 until boot.length()) {
            var pkt = Base64.decode(boot.getString(i), Base64.DEFAULT)
            if (pkt.size > 8 && (pkt[6].toInt() and 0x7F) == 0x02) { pkt = renumber(pkt, idx2); idx2 = (idx2 + 1) and 0x3FF }
            sendTo(pkt, idx); sleep(60)
        }
        hs2 = HeapStreamer(idx, idx2).also { it.adoptState() }
        block2First = true
        say("relaying to block $idx 🔗")
    }

    private fun pump() {
        val now = System.currentTimeMillis()
        if (now - lastPing >= 300) { lastPing = now; send(ping) }
        if (now < beginUntil && now - lastBegin >= 800) {
            lastBegin = now
            send(Base64.decode(doc.getJSONArray("handshake").getString(3), Base64.DEFAULT))
        }
    }

    private fun sendAll(arr: org.json.JSONArray) {
        for (i in 0 until arr.length()) {
            var pkt = Base64.decode(arr.getString(i), Base64.DEFAULT)
            if (pkt.size > 8 && (pkt[6].toInt() and 0x7F) == 0x02) {
                pkt = renumber(pkt, nextIndex); nextIndex = (nextIndex + 1) and 0x3FF
            }
            send(pkt); pump()
        }
    }

    override fun run() {
        try {
            say("handshake…")
            val hs = doc.getJSONArray("handshake")
            for (i in 0 until hs.length()) {
                send(Base64.decode(hs.getString(i), Base64.DEFAULT))
                sleep(longArrayOf(700, 900, 400, 600, 400)[i])
            }
            val begin = Base64.decode(hs.getString(3), Base64.DEFAULT)
            repeat(8) { send(begin); sleep(350) }
            // live sync: adopt the block's real index + packet counter
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline &&
                   (blocks.topologyIndex < 0 || blocks.lastAck < 0)) sleep(100)
            if (blocks.topologyIndex >= 0) deviceIdx = blocks.topologyIndex
            nextIndex = if (blocks.lastAck >= 0) (blocks.lastAck + 1) and 0x3FF else 1
            if (blocks.serial.isNotEmpty())
                say("synced to ${blocks.serial} (idx $deviceIdx, battery ${blocks.battery * 100 / 31}%)")
            lastPing = System.currentTimeMillis()
            beginUntil = lastPing + 12_000
            sendAll(doc.getJSONArray("boot"))   // LittleFoot program: ONCE
            say("connected — snap a second block to battle ⚔️")
            sceneLoop()
        } catch (e: InterruptedException) {
            // quit()
        } catch (e: Exception) {
            Log.e("pokepad", "streamer died", e)
            say("stream died: ${e.message} — tap Connect to retry")
        }
    }

    /** render the current scene (or a resting ball) and diff-stream it. */
    private fun sceneLoop() {
        val hs = HeapStreamer(9, 1); hs.adoptState()
        val t0 = System.currentTimeMillis()
        var first = true
        var dbg = 0
        while (running) {
            if ((dbg++ % 24) == 0)
                android.util.Log.i("pokepad-relay", "acks=${blocks.acks} secondIdx=$secondIdx hs2=${hs2 != null} scene=${scene != null}")
            val t = (System.currentTimeMillis() - t0) / 1000.0
            val s = scene
            if (s != null && s.done()) scene = null
            val frame = (scene?.render(t)) ?: BlockFrame.idle()
            hs.setBytes(PROGRAM_SIZE, BlockFrame.rgb565(frame))
            if (first) { hs.seedIndex(nextIndex); hs.markUnknownFrameArea(PROGRAM_SIZE); first = false }
            for (pkt in hs.drain()) send(pkt)
            nextIndex = hs.packetIndex

            val sidx = secondIdx
            if (sidx >= 0 && scene != null) {
                if (hs2 == null) bootSecond(sidx)
                val h = hs2
                if (h != null) {
                    // The relay through the master is lossy, and the heap-streamer
                    // assumes lossless delivery — so a dropped packet silently strands
                    // part of the frame (the "bottom third dark"). Fix: don't trust it.
                    // Every iteration, RESEND the whole current frame starting from
                    // block 2's REAL observed ACK, heavily paced. Any drop is simply
                    // re-sent on the next pass, re-indexed to where block 2 actually is,
                    // so it always converges on a complete image (a few fps, but whole).
                    val obs = blocks.acks[sidx] ?: -1
                    val frameB = scene?.renderSecond(t) ?: frame
                    if (block2First) { android.util.Log.i("pokepad-relay", "block2 first: ack=$obs"); block2First = false }
                    h.seedIndex(if (obs >= 0) (obs + 1) and 0x3FF else idx2)
                    h.setBytes(PROGRAM_SIZE, BlockFrame.rgb565(frameB))
                    h.markUnknownFrameArea(PROGRAM_SIZE)
                    val pkts = h.drain(); for (pkt in pkts) { sendTo(pkt, sidx); sleep(6) }
                    idx2 = h.packetIndex
                }
            } else if (sidx < 0 && hs2 != null) {
                hs2 = null
            }
            pump()
            sleep(80)   // ~12fps
        }
    }
}
