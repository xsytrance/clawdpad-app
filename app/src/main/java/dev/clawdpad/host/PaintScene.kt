package dev.clawdpad.host

/**
 * Finger-paint debug scene: every decoded touch lights its cell
 * (pressure → brightness, touchIndex → hue) with decaying trails.
 * This is the end-to-end verification of the touch pipeline AND the
 * BLE-contention rig — painting while trails animate exercises inbound
 * touch bursts against outbound frame diffs simultaneously.
 */
class PaintScene : Scene {
    private val W = ClawdRenderer.W
    private val H = ClawdRenderer.H

    // float RGB accumulation buffer; only render() and the synchronized
    // touch handler mutate it
    private val paint = FloatArray(W * H * 3)
    @Volatile private var exit = false

    // one distinct hue per touch index (coral, teal, gold, violet, green)
    private val HUES = arrayOf(
        intArrayOf(217, 119, 87), intArrayOf(87, 190, 217),
        intArrayOf(230, 195, 80), intArrayOf(180, 110, 230),
        intArrayOf(110, 220, 130))

    override fun onTouch(ev: TouchEvent) {
        val x = ev.x.toInt().coerceIn(0, W - 1)
        val y = ev.y.toInt().coerceIn(0, H - 1)
        val hue = HUES[ev.touchIndex % HUES.size]
        // strikes get full brightness even before pressure builds
        val level = maxOf(ev.z, ev.velocity, 0.25f)
        synchronized(paint) {
            val i = (y * W + x) * 3
            for (c in 0..2)
                paint[i + c] = maxOf(paint[i + c], hue[c] * level)
        }
    }

    override fun render(t: Double): ByteArray {
        val buf = ByteArray(W * H * 3)
        synchronized(paint) {
            for (i in paint.indices) {
                buf[i] = paint[i].toInt().coerceIn(0, 255).toByte()
                paint[i] *= 0.93f          // trail decay
            }
        }
        return buf
    }

    fun stop() { exit = true }
    override fun done(): Boolean = exit
}
