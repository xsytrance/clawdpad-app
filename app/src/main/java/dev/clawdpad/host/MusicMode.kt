package dev.clawdpad.host

import android.media.audiofx.Visualizer
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.SoundPool
import android.media.AudioAttributes
import android.content.Context
import kotlin.math.sqrt

/**
 * Ears for Clawd: taps the phone's audio output (Visualizer, session 0)
 * and turns whatever's playing — Spotify, YT Music, anything — into
 * energy + beat signals that drive ClawdRenderer.dance().
 *
 * Beat detection: RMS energy vs a running average with a refractory
 * period; each detected beat kicks `bounce` to 1.0, decaying ~180ms.
 * Honest vibes, not ML: it grooves, it doesn't transcribe.
 */
class MusicMode {
    @Volatile var energy = 0.0      // 0..1 smoothed loudness
        private set
    @Volatile var bounce = 0.0      // beat envelope 0..1
        private set

    private var visualizer: Visualizer? = null
    private var recorder: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var avg = 0.02
    private var lastBeat = 0L
    private var lastTick = 0L
    var source = "?"
        private set

    private fun feed(rms: Double) {
        val now = System.currentTimeMillis()
        if (lastTick != 0L) {
            val dt = (now - lastTick) / 1000.0
            bounce = (bounce - dt * 5.5).coerceAtLeast(0.0)
        }
        lastTick = now
        energy = (energy * 0.8 + (rms * 2.2).coerceAtMost(1.0) * 0.2)
        avg = avg * 0.985 + rms * 0.015
        if (rms > avg * 1.35 && rms > 0.04 && now - lastBeat > 240) {
            lastBeat = now
            bounce = 1.0
        }
    }

    fun start(): Boolean {
        if (startVisualizer()) { source = "output"; return true }
        if (startMic()) { source = "mic"; return true }
        return false
    }

    private fun startMic(): Boolean = try {
        val rate = 8000
        val minBuf = AudioRecord.getMinBufferSize(rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 3200))
        rec.startRecording()
        recorder = rec
        micRunning = true
        micThread = Thread {
            val win = ShortArray(160)
            while (micRunning) {
                val n = rec.read(win, 0, win.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = win[i] / 32768.0
                    sum += v * v
                }
                feed(kotlin.math.sqrt(sum / n) * 2.5)
            }
        }.apply { isDaemon = true; start() }
        true
    } catch (e: Exception) {
        android.util.Log.e("clawdpad", "mic failed", e)
        false
    }

    private fun startVisualizer(): Boolean = try {
        visualizer = Visualizer(0).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1]
            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?,
                        wave: ByteArray?, rate: Int) {
                    wave ?: return
                    var sum = 0.0
                    for (b in wave) {
                        val s = ((b.toInt() and 0xFF) - 128) / 128.0
                        sum += s * s
                    }
                    feed(sqrt(sum / wave.size))
                }
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?,
                        rate: Int) {}
            }, Visualizer.getMaxCaptureRate(), true, false)
            enabled = true
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("clawdpad", "visualizer unavailable (normal on modern Android)", e)
        false
    }

    fun stop() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        micRunning = false
        runCatching { recorder?.stop(); recorder?.release() }
        recorder = null
    }
}

/** Clawd's voice on the phone: the daemon-synth WAVs via SoundPool. */
class Sounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build())
        .build()
    private val ids = mapOf(
        "jingle" to pool.load(context, R.raw.jingle, 1),
        "hello" to pool.load(context, R.raw.hello, 1),
        "chime" to pool.load(context, R.raw.chime, 1),
        "boop" to pool.load(context, R.raw.boop, 1),
    )

    fun play(name: String, volume: Float = 0.9f) {
        ids[name]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }
}
