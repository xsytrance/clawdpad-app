package dev.pokepad.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Synthesized retro sound kit (WAVs under assets/sfx — squares, sweeps and
 * noise, generated offline; no sampled/ripped audio). Names: blip, summon,
 * hit, super, resist, faint, victory, defeat, ack, link.
 */
object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()

    fun ensure(ctx: Context) {
        if (pool != null) return
        synchronized(this) {
            if (pool != null) return
            val p = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build()
            val am = ctx.applicationContext.assets
            for (n in listOf("blip", "summon", "hit", "super", "resist", "faint",
                             "victory", "defeat", "ack", "link")) {
                runCatching { am.openFd("sfx/$n.wav").use { fd -> ids[n] = p.load(fd, 1) } }
            }
            pool = p
        }
    }

    fun play(name: String, vol: Float = 1f) {
        val id = ids[name] ?: return
        pool?.play(id, vol, vol, 1, 0, 1f)
    }
}
