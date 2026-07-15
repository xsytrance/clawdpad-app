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
    private var lastPing = 0L
    private var beginUntil = 0L
    private var lastBegin = 0L

    fun play(name: String) {
        want = name
        say("→ $name")
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

    private fun sendAll(arr: org.json.JSONArray) {
        for (i in 0 until arr.length()) {
            send(Base64.decode(arr.getString(i), Base64.DEFAULT))
            pump()
        }
    }

    override fun run() {
        try {
            say("handshake…")
            val hs = doc.getJSONArray("handshake")
            for (i in 0 until hs.length()) {
                send(Base64.decode(hs.getString(i), Base64.DEFAULT))
                sleep(longArrayOf(700, 900, 400, 600)[i])  // serial, topo, end, begin — generous over BLE
            }
            // blocksd re-sends beginAPIMode until the device engages —
            // a single send gets lost in BLE latency. Court it properly.
            val begin = Base64.decode(hs.getString(3), Base64.DEFAULT)
            repeat(8) { send(begin); sleep(350) }
            lastPing = System.currentTimeMillis()
            beginUntil = lastPing + 12_000   // keep courting during first loops
            say("keepalive up — streaming Clawd 🐾")

            var current = ""
            var frame = 0
            var loop = doc.getJSONObject("loops").getJSONObject("full")
            while (running) {
                if (want != current) {
                    current = want
                    loop = doc.getJSONObject("loops").getJSONObject(current)
                    sendAll(loop.getJSONArray("intro"))
                    frame = 0
                    if (current == "jump")   // one-shot: bounce then home
                        want = "full"
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
