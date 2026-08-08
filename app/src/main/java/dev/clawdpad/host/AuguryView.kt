package dev.clawdpad.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import org.json.JSONObject

/**
 * THE AUGURY, ON THE GLASS IN YOUR HAND.
 *
 * Draws the same 64x32 the matrix draws — same font, same palette, same row
 * layout, same `fmt` rule, same colour law — scaled to fill whatever space it
 * is given. Faithful on purpose: a view that "improved" the layout would be
 * showing a different instrument and calling it the same one.
 *
 * The layout constants are copied from dazzler/firmware/code.py. If the board's
 * layout changes they must change with it; that is the cost of a real mirror,
 * and it is cheaper than a phone that confidently shows something the panel is
 * not doing.
 */
class AuguryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val W = 64
        const val H = 32

        /** label, freeKey, totalKey, fracKey, unit, y — verbatim from ROWS. */
        private val ROWS = arrayOf(
            arrayOf("GPU:", "vram_free", "vram_total", "vram_frac", "GB", "0"),
            arrayOf("MEM:", "ram_free", "ram_total", "ram_frac", "GB", "6"),
            arrayOf("SSD:", "nvme_free", "nvme_total", "nvme_frac", "TB", "12"),
        )

        /**
         * The firmware's `fmt`: under ten keeps a decimal, ten and over drops
         * it. The original `("%.1f" % v)[:3]` chopped 13.4 to "13." — a decimal
         * point with nothing after it. Kept identical so the digits match.
         */
        fun fmt(v: Double): String =
            if (v >= 10) Math.round(v).toString() else String.format("%.1f", v)
    }

    private val paint = Paint().apply { isAntiAlias = false }
    private var frame: JSONObject? = null

    /** Null clears the board — an unknown state must never look like a good one. */
    fun show(f: JSONObject?) {
        frame = f
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Always exactly 2:1. Letting the board stretch would misreport how much
        // free space there is, which is the one thing it exists to tell him.
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val scale = minOf(w / W, if (h > 0) h / H else Int.MAX_VALUE).coerceAtLeast(1)
        setMeasuredDimension(W * scale, H * scale)
    }

    override fun onDraw(canvas: Canvas) {
        val s = (width.toFloat() / W)
        paint.color = AuguryFont.BLACK
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val f = frame ?: return
        val v = f.optJSONObject("v") ?: JSONObject()

        fun px(x: Int, y: Int, colour: Int) {
            if (x < 0 || y < 0 || x >= W || y >= H) return
            paint.color = colour
            canvas.drawRect(x * s, y * s, (x + 1) * s, (y + 1) * s, paint)
        }

        fun glyph(x: Int, y: Int, ch: Char, colour: Int) {
            val g = AuguryFont.GLYPHS[ch.uppercaseChar().toString()] ?: return
            for (col in 0..2) for (row in 0..4) {
                if (g[col] and (1 shl row) != 0) px(x + col, y + row, colour)
            }
        }

        fun text(x: Int, y: Int, str: String, colour: Int) {
            for (i in str.indices) glyph(x + i * 4, y, str[i], colour)
        }

        for (r in ROWS) {
            val y = r[5].toInt()
            text(0, y, r[0], AuguryFont.WHITE)
            if (v.isNull(r[1])) continue
            val free = v.optDouble(r[1])
            if (free.isNaN()) continue

            // Colour is the only warning: cyan fine, amber tight, red nearly gone.
            var colour = AuguryFont.CYAN
            if (!v.isNull(r[3])) {
                val frac = v.optDouble(r[3])
                if (!frac.isNaN()) {
                    colour = when {
                        frac < 0.75 -> AuguryFont.CYAN
                        frac < 0.90 -> AuguryFont.AMBER
                        else -> AuguryFont.RED
                    }
                }
            }

            var x = 18
            val num = fmt(free)
            text(x, y, num, colour)
            x += num.length * 4

            if (!v.isNull(r[2])) {
                val total = v.optDouble(r[2])
                if (!total.isNaN()) {
                    // Divider and total stay grey — only the free figure can go
                    // wrong. The divider draws nothing until the board is
                    // reflashed; see AuguryFont.
                    glyph(x, y, '/', AuguryFont.WHITE)
                    x += 4
                    val tot = if (total >= 10) Math.round(total).toString()
                    else String.format("%.1f", total)
                    text(x, y, tot, AuguryFont.WHITE)
                    x += tot.length * 4
                    text(x + 2, y, r[4], AuguryFont.WHITE)
                }
            }
        }

        f.optString("clock").takeIf { it.isNotEmpty() }?.let {
            text(44, 25, it, AuguryFont.WHITE)
        }

        // The floor line. Dim because it is a ground line, not a headline — it
        // was once the brightest object on the panel.
        for (x in 0 until W) px(x, 31, AuguryFont.DIM)
    }
}
