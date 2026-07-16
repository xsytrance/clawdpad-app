package dev.clawdpad.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Clawd's portrait: a live 15x15 LED-dot rendering on the phone screen,
 * driven by the same ClawdRenderer that feeds the glass. Breathing proof
 * that somebody's home, before you even look at the block.
 */
class ClawdView(context: Context) : View(context) {

    /** "sleep" | "awake" | "dance" — the activity keeps this current. */
    @Volatile var mode: String = "sleep"
    @Volatile var music: MusicMode? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        val t = (System.currentTimeMillis() - t0) / 1000.0
        val frame = when (mode) {
            "dance" -> {
                val m = music
                if (m != null) ClawdRenderer.dance(t, m.energy, m.bounce)
                else ClawdRenderer.awake(t)
            }
            "awake" -> ClawdRenderer.awake(t)
            else -> ClawdRenderer.clawd(0.22, 0, 0, eyesOpen = false)
        }
        val cell = minOf(width, height) / 15f
        val ox = (width - cell * 15) / 2f
        val oy = (height - cell * 15) / 2f
        val r = cell * 0.36f
        for (y in 0 until 15) {
            for (x in 0 until 15) {
                val i = (y * 15 + x) * 3
                val red = frame[i].toInt() and 0xFF
                val g = frame[i + 1].toInt() and 0xFF
                val b = frame[i + 2].toInt() and 0xFF
                paint.color = if (red + g + b < 12) Color.rgb(32, 26, 22)
                              else Color.rgb(red, g, b)
                canvas.drawCircle(ox + x * cell + cell / 2,
                    oy + y * cell + cell / 2,
                    if (red + g + b < 12) r * 0.45f else r, paint)
            }
        }
        postInvalidateOnAnimation()
    }
}
