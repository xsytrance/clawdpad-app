package dev.pokepad.ui

import android.content.Context
import android.media.MediaPlayer

/**
 * Looping background music — original chiptune themes composed in code (the
 * same zero-samples synth as the SFX kit). Tracks: "music_battle" (driving,
 * A minor) and "music_menu" (calm, C major). One track at a time; switching is
 * idempotent so screens can just declare what should be playing.
 */
object Music {
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var current: String? = null
    @Volatile var enabled = true

    fun play(ctx: Context, name: String, volume: Float = 0.45f) {
        if (!enabled) return
        if (current == name && player?.isPlaying == true) return
        stop()
        runCatching {
            val p = MediaPlayer()
            ctx.applicationContext.assets.openFd("sfx/$name.wav").use { fd ->
                p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            p.isLooping = true
            p.setVolume(volume, volume)
            p.prepare()
            p.start()
            player = p; current = name
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null; current = null
    }
}
