package dev.clawdpad.host

/**
 * A scene owns the glass: when Streamer.scene is set, liveLoop renders it
 * instead of the mood/dance/costume stack. Input arrives pre-threaded —
 * onTouch/onGesture are called on the keeper thread, render on the
 * streamer thread — so scenes keep their tiny state @Volatile/synchronized.
 */
interface Scene {
    /** produce a 15x15 RGB888 frame; t = seconds since the scene started */
    fun render(t: Double): ByteArray

    /** raw decoded touch sample (keeper thread) */
    fun onTouch(ev: TouchEvent) {}

    /** classified gesture (keeper thread) */
    fun onGesture(g: Gesture) {}

    /** return true when the scene wants to exit; streamer falls back to
     *  whatever was playing before */
    fun done(): Boolean = false
}
