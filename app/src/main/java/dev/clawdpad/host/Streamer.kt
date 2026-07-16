package dev.clawdpad.host

import android.content.res.AssetManager
import android.media.midi.MidiInputPort
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Plays the precomputed ROLI SysEx stream (assets/stream.json).
 *
 * Handshake -> API mode -> keepalive pings every 300 ms -> animation loop
 * playback. Every loop is standalone (its intro re-syncs the whole heap),
 * so switching moods is just "play the other loop".
 */
private const val PROGRAM_SIZE = 100  // bitmap_led_program() length

class Streamer(
    assets: AssetManager,
    private val port: MidiInputPort,
    private val say: (String) -> Unit,
) : Thread("clawd-streamer") {

    private val doc = JSONObject(
        assets.open("stream.json").readBytes().decodeToString())
    private val ping = Base64.decode(doc.getString("ping"), Base64.DEFAULT)

    @Volatile private var want = "full"
    @Volatile private var running = true
    @Volatile var music: MusicMode? = null   // non-null = LIVE DANCE mode
    @Volatile var live = false               // costume on = live rendering
    @Volatile var lyrics: LyricSparks? = null // words timed to the song
    private var lastPing = 0L
    private var beginUntil = 0L
    private var lastBegin = 0L
    private var nextIndex = 1   // device ACKs counter 0 post-topology; first
                                // data packet it accepts is 1 (10-bit wrap)

    /** Rewrite a SharedDataChange packet's 16-bit index + checksum.
     *  Bit-exact port of the Python renumber() (golden-tested). */
    private fun renumber(pkt: ByteArray, index: Int): ByteArray {
        val p = pkt.copyOf()
        for (k in 0 until 16) {
            val bit = 7 + k
            val bi = 6 + bit / 7
            val off = bit % 7
            val v = (index shr k) and 1
            p[bi] = ((p[bi].toInt() and (1 shl off).inv()) or (v shl off)).toByte()
        }
        var cs = (p.size - 8) and 0xFF
        for (i in 6 until p.size - 2) cs = (cs + (cs * 2 + (p[i].toInt() and 0xFF))) and 0xFF
        p[p.size - 2] = (cs and 0x7F).toByte()
        return p
    }

    @Volatile private var sizeMini = false

    fun play(name: String) {
        if (name == "mini") sizeMini = true
        if (name == "full") sizeMini = false
        want = if (sizeMini && (name == "wave" || name == "jump"))
            "mini_$name" else name
        say("→ $want")
    }

    fun quit() {
        running = false
        interrupt()
        runCatching { port.close() }
    }

    private fun send(bytes: ByteArray) {
        port.send(bytes, 0, bytes.size)
    }

    private fun pump() {
        val now = System.currentTimeMillis()
        if (now - lastPing >= 300) {
            lastPing = now
            send(ping)
        }
        if (now < beginUntil && now - lastBegin >= 800) {
            lastBegin = now
            send(Base64.decode(doc.getJSONArray("handshake").getString(3),
                Base64.DEFAULT))
        }
    }

    /** Live rendering path: ClawdRenderer frames diff-streamed via the
     *  golden-tested HeapStreamer. Runs until music mode is cleared. */
    private fun liveLoop() {
        val hs = HeapStreamer(9, 1)
        hs.adoptState()   // program already on device; never rewrite it
        val t0 = System.currentTimeMillis()
        var first = true
        while (running && (music != null || live)) {
            val t = (System.currentTimeMillis() - t0) / 1000.0
            val m = music
            val spark = lyrics?.current()
            val frame = when {
                spark != null -> ClawdRenderer.lyric(spark.first,
                    spark.second, spark.third, m?.energy ?: 0.6)
                m != null -> ClawdRenderer.dance(t, m.energy, m.bounce)
                else -> ClawdRenderer.awake(t)
            }
            hs.setBytes(PROGRAM_SIZE, ClawdRenderer.rgb565(frame))
            if (first) {
                hs.seedIndex(nextIndex)
                hs.markUnknownFrameArea(PROGRAM_SIZE)
                first = false
            }
            for (pkt in hs.drain()) send(pkt)
            nextIndex = hs.packetIndex
            pump()
            sleep(80)   // ~12fps live
        }
    }

    private fun sendAll(arr: org.json.JSONArray) {
        for (i in 0 until arr.length()) {
            var pkt = Base64.decode(arr.getString(i), Base64.DEFAULT)
            if (pkt.size > 8 && (pkt[6].toInt() and 0x7F) == 0x02) {
                pkt = renumber(pkt, nextIndex)      // live device-wide counter
                nextIndex = (nextIndex + 1) and 0x3FF
            }
            send(pkt)
            pump()
        }
    }

    override fun run() {
        try {
            say("handshake…")
            val hs = doc.getJSONArray("handshake")
            for (i in 0 until hs.length()) {
                send(Base64.decode(hs.getString(i), Base64.DEFAULT))
                sleep(longArrayOf(700, 900, 400, 600, 400)[i])  // serial, topo, end, begin — generous over BLE
            }
            // blocksd re-sends beginAPIMode until the device engages —
            // a single send gets lost in BLE latency. Court it properly.
            val begin = Base64.decode(hs.getString(3), Base64.DEFAULT)
            repeat(8) { send(begin); sleep(350) }
            lastPing = System.currentTimeMillis()
            beginUntil = lastPing + 12_000   // keep courting during first loops
            sendAll(doc.getJSONArray("boot"))  // LittleFoot program: ONCE.
            // never rewritten — the firmware wipes frame data on program
            // reload (the QR heartbeat experiment, 2026-07-16)
            say("keepalive up — streaming Clawd 🐾")

            var current = ""
            var home = "full"        // what a one-shot returns to
            var frame = 0
            var loop = doc.getJSONObject("loops").getJSONObject("full")
            while (running) {
                if (want != current) {
                    current = want
                    val oneShot = current.endsWith("jump")
                    if (!oneShot) home = current
                    loop = doc.getJSONObject("loops").getJSONObject(current)
                    sendAll(loop.getJSONArray("intro"))
                    frame = 0
                    if (oneShot) {           // play the bounce through ONCE
                        val b = loop.getJSONArray("body")
                        for (i in 0 until b.length()) {
                            sendAll(b.getJSONArray(i))
                            pump()
                            sleep((1000.0 / loop.getInt("fps")).toLong())
                        }
                        want = home          // then back to what was playing
                        continue
                    }
                }
                val m = music
                if (m != null || live) {         // LIVE: render + diff-stream
                    liveLoop()
                    current = ""                 // re-intro on return to baked
                    continue
                }
                val body = loop.getJSONArray("body")
                if (body.length() > 0) {
                    sendAll(body.getJSONArray(frame))
                    frame = (frame + 1) % body.length()
                }
                pump()
                sleep((1000.0 / loop.getInt("fps")).toLong())
            }
        } catch (e: InterruptedException) {
            // quit()
        } catch (e: Exception) {
            Log.e("clawdpad", "streamer died", e)
            say("stream died: ${e.message} — tap Connect to retry")
        }
    }
}
