package dev.clawdpad.host

import android.media.audiofx.Visualizer
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
    private var avg = 0.02
    private var lastBeat = 0L
    private var lastTick = 0L

    fun start(): Boolean = try {
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
                    val rms = sqrt(sum / wave.size)
                    val now = System.currentTimeMillis()
                    // decay bounce
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
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?,
                        rate: Int) {}
            }, Visualizer.getMaxCaptureRate(), true, false)
            enabled = true
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("clawdpad", "visualizer failed", e)
        false
    }

    fun stop() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
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
