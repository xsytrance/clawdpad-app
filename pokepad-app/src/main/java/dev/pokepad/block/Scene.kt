package dev.pokepad.block

/**
 * A scene owns the block glass: when Streamer.scene is set, the live loop renders
 * it and diff-streams it to the block(s). render = the master block, renderSecond
 * = the snapped-on block (null = mirror). Autonomous battles need no input, so
 * onTouch is optional. Keep scene state @Volatile — render runs on the streamer
 * thread, onTouch on the keeper thread.
 */
interface Scene {
    /** produce a 15x15 RGB888 frame; t = seconds since the scene started */
    fun render(t: Double): ByteArray

    /** optional second-block frame; null = mirror the primary */
    fun renderSecond(t: Double): ByteArray? = null

    /** raw decoded touch sample (keeper thread) */
    fun onTouch(ev: TouchEvent) {}

    /** true when the scene wants to exit (streamer falls back to idle) */
    fun done(): Boolean = false
}
