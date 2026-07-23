package dev.pokepad.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import dev.pokepad.core.Director
import dev.pokepad.core.Reel

/**
 * The Trainer-mode arena: plays ONE turn's animation (a mini-reel), then holds
 * the final frame and calls onReelDone so the activity can show the move menu
 * again. First-person framing (your mon big & from behind, opponent up & far).
 */
class TrainerView(context: Context) : View(context) {

    private var reel: Reel? = null
    private var leftBmp: List<Bitmap> = emptyList()
    private var rightBmp: List<Bitmap> = emptyList()
    private var startNanos = 0L
    private var fired = false
    private var onReelDone: (() -> Unit)? = null

    private val px = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val src = Rect(0, 0, 15, 15)

    private val BG_TOP = Color.parseColor("#1B2450"); private val BG_BOT = Color.parseColor("#0A0E1E")
    private val PLAT = Color.parseColor("#232C52"); private val BOX = Color.parseColor("#0E1430")
    private val BOX_LINE = Color.parseColor("#33406A"); private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#9AA0C0"); private val GOLD = Color.parseColor("#F5D246")
    private var bgShader: LinearGradient? = null
    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** end-of-battle verdict overlay: null = none, true = victory, false = defeat */
    @Volatile var verdictWin: Boolean? = null

    /** play a turn's reel; onDone fires once it finishes (held on the last frame) */
    fun play(r: Reel, onDone: () -> Unit) {
        reel = r; leftBmp = r.cells.map { Sprite.bitmap(it.left) }; rightBmp = r.cells.map { Sprite.bitmap(it.right) }
        verdictWin = null
        startNanos = 0L; fired = false; onReelDone = onDone; invalidate()
    }

    fun showVerdict(won: Boolean) { verdictWin = won; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), BG_TOP, BG_BOT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = reel ?: return
        if (startNanos == 0L) startNanos = System.nanoTime()
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000.0
        val last = r.cells.size - 1
        val idx = (elapsed / (1000.0 / Director.FPS)).toInt()
        val finished = idx > last
        val c = r.cells[idx.coerceIn(0, last)]

        val w = width.toFloat(); val h = height.toFloat()
        fill.shader = bgShader; canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null

        val oppCx = w * 0.66f; val oppCy = h * 0.30f; val oppSize = w * 0.30f
        val plCx = w * 0.36f; val plCy = h * 0.82f; val plSize = w * 0.52f
        fill.color = PLAT
        canvas.drawOval(RectF(oppCx - oppSize * 0.62f, oppCy - oppSize * 0.10f, oppCx + oppSize * 0.62f, oppCy + oppSize * 0.14f), fill)
        canvas.drawOval(RectF(plCx - plSize * 0.60f, plCy - plSize * 0.06f, plCx + plSize * 0.60f, plCy + plSize * 0.14f), fill)
        val i = idx.coerceIn(0, last)
        canvas.drawBitmap(rightBmp[i], src, RectF(oppCx - oppSize / 2, oppCy - oppSize, oppCx + oppSize / 2, oppCy), px)
        canvas.drawBitmap(leftBmp[i], src, RectF(plCx - plSize / 2, plCy - plSize, plCx + plSize / 2, plCy), px)

        hpBox(canvas, dp(14f), dp(84f), w * 0.52f, r.rightName, c.hpR)   // clear of the status bar
        hpBox(canvas, w - dp(14f) - w * 0.52f, h * 0.44f, w * 0.52f, r.leftName, c.hpL)

        val mbTop = h - dp(70f)
        round(canvas, dp(10f), mbTop, w - dp(10f), h - dp(10f), dp(12f), BOX, BOX_LINE, dp(2f))
        text.color = INK; text.textSize = dp(15f); text.textAlign = Paint.Align.LEFT
        canvas.drawText(c.msg, dp(24f), mbTop + dp(30f), text)
        if (c.banner.isNotEmpty() && c.bannerHot) {
            text.color = GOLD; text.textSize = dp(if (c.banner.length > 8) 22f else 30f)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(c.banner, w / 2f, h * 0.46f, text)
        }

        // ── unmistakable end-of-battle verdict ──
        verdictWin?.let { won ->
            fill.color = Color.parseColor(if (won) "#B0060B18" else "#C0100608")
            canvas.drawRect(0f, 0f, w, h, fill)
            val boxW = w * 0.84f; val boxH = dp(150f)
            val bx = (w - boxW) / 2f; val by = h * 0.34f
            round(canvas, bx, by, bx + boxW, by + boxH, dp(20f),
                Color.parseColor(if (won) "#1A2408" else "#240A0A"),
                if (won) GOLD else Color.parseColor("#E5533F"), dp(3f))
            text.textAlign = Paint.Align.CENTER
            text.color = if (won) GOLD else Color.parseColor("#F0A090")
            text.textSize = dp(40f)
            canvas.drawText(if (won) "🏆 VICTORY!" else "💥 DEFEAT", w / 2f, by + dp(66f), text)
            text.color = if (won) INK else DIM; text.textSize = dp(16f)
            canvas.drawText(if (won) "your Pokémon wins the battle!" else "your Pokémon fainted…",
                w / 2f, by + dp(104f), text)
        }

        if (finished) {
            if (!fired) { fired = true; onReelDone?.invoke() }   // hold last frame, tell the activity
        } else postInvalidateOnAnimation()
    }

    private fun hpBox(canvas: Canvas, x: Float, y: Float, boxW: Float, name: String, frac: Float) {
        val boxH = dp(44f)
        round(canvas, x, y, x + boxW, y + boxH, dp(10f), BOX, BOX_LINE, dp(2f))
        text.textAlign = Paint.Align.LEFT; text.color = INK; text.textSize = dp(13f)
        canvas.drawText(name.uppercase(), x + dp(12f), y + dp(18f), text)
        val barX = x + dp(12f); val barY = y + dp(26f); val barW = boxW - dp(24f); val barH = dp(7f)
        fill.color = Color.parseColor("#2A3050"); canvas.drawRoundRect(RectF(barX, barY, barX + barW, barY + barH), barH / 2, barH / 2, fill)
        fill.color = when { frac > 0.5f -> Color.parseColor("#5FD97A"); frac > 0.22f -> GOLD; else -> Color.parseColor("#E5533F") }
        val fw = (barW * frac.coerceIn(0f, 1f)).coerceAtLeast(if (frac > 0f) dp(2f) else 0f)
        if (fw > 0) canvas.drawRoundRect(RectF(barX, barY, barX + fw, barY + barH), barH / 2, barH / 2, fill)
    }

    private fun round(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, bg: Int, line: Int, sw: Float) {
        fill.color = bg; canvas.drawRoundRect(RectF(l, t, r, b), rad, rad, fill)
        stroke.color = line; stroke.strokeWidth = sw; canvas.drawRoundRect(RectF(l, t, r, b), rad, rad, stroke)
    }
}
