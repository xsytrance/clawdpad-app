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
import dev.pokepad.core.Cell
import dev.pokepad.core.Director
import dev.pokepad.core.Reel

/**
 * The phone battle screen. Plays a Director reel as a classic top/bottom Pokémon
 * battle: opponent up-right, your mon down-left, an HP box for each, a scrolling
 * message box, and a big banner on the hot beats (SUPER! / K.O. / WIN). Every
 * frame is the real engine's output — this view just lays it out and animates it.
 */
class BattleView(context: Context) : View(context) {

    private var reel: Reel? = null
    private var leftBmp: List<Bitmap> = emptyList()
    private var rightBmp: List<Bitmap> = emptyList()
    private var startNanos = 0L
    private var doneHold = false
    var onTapWhenDone: (() -> Unit)? = null
    var onToggleView: (() -> Unit)? = null
    var firstPerson = true
    private val toggleRect = RectF()

    private val px = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val src = Rect(0, 0, 15, 15)

    private val BG_TOP = Color.parseColor("#1B2450")
    private val BG_BOT = Color.parseColor("#0A0E1E")
    private val PLAT = Color.parseColor("#232C52")
    private val BOX = Color.parseColor("#0E1430")
    private val BOX_LINE = Color.parseColor("#33406A")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#9AA0C0")
    private val GOLD = Color.parseColor("#F5D246")
    private var bgShader: LinearGradient? = null

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun load(r: Reel) {
        reel = r
        leftBmp = r.cells.map { Sprite.bitmap(it.left) }
        rightBmp = r.cells.map { Sprite.bitmap(it.right) }
        startNanos = 0L; doneHold = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), BG_TOP, BG_BOT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = reel ?: return
        if (startNanos == 0L) startNanos = System.nanoTime()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val last = r.cells.size - 1
        val idx = (elapsedMs / (1000.0 / Director.FPS)).toInt()
        val finished = idx > last
        val i = idx.coerceIn(0, last)
        val c = r.cells[i]

        val w = width.toFloat(); val h = height.toFloat()
        // background
        fill.shader = bgShader; canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null

        // camera: FIRST-PERSON exaggerates depth (player big & low in front,
        // opponent small & high & far); SIDE is a balanced two-up framing.
        val oppCx: Float; val oppCyBase: Float; val oppSize: Float
        val plCx: Float; val plCyBase: Float; val plSize: Float
        if (firstPerson) {
            oppCx = w * 0.66f; oppCyBase = h * 0.34f; oppSize = w * 0.30f
            plCx = w * 0.36f;  plCyBase = h * 0.78f; plSize = w * 0.56f
        } else {
            oppCx = w * 0.71f; oppCyBase = h * 0.345f; oppSize = w * 0.34f
            plCx = w * 0.31f;  plCyBase = h * 0.665f; plSize = w * 0.44f
        }

        // platforms under each mon
        fill.color = PLAT
        canvas.drawOval(RectF(oppCx - oppSize * 0.62f, oppCyBase - oppSize * 0.10f, oppCx + oppSize * 0.62f, oppCyBase + oppSize * 0.14f), fill)
        canvas.drawOval(RectF(plCx - plSize * 0.60f, plCyBase - plSize * 0.06f, plCx + plSize * 0.60f, plCyBase + plSize * 0.14f), fill)

        // sprites (opponent = right/front, player = left; in first-person the
        // player's frames are already rendered as a back view by the Director)
        drawSprite(canvas, rightBmp[i], oppCx - oppSize / 2, oppCyBase - oppSize, oppSize)
        drawSprite(canvas, leftBmp[i], plCx - plSize / 2, plCyBase - plSize, plSize)

        // HP boxes
        drawHpBox(canvas, dp(14f), dp(28f), w * 0.52f, r.rightName, c.hpR, alignRight = false)
        drawHpBox(canvas, w - dp(14f) - w * 0.52f, h * 0.44f, w * 0.52f, r.leftName, c.hpL, alignRight = true)

        // message box
        val mbTop = h - dp(96f)
        roundRect(canvas, dp(12f), mbTop, w - dp(12f), h - dp(16f), dp(14f), BOX, BOX_LINE, dp(2f))
        text.color = INK; text.textSize = dp(16f); text.textAlign = Paint.Align.LEFT
        canvas.drawText(c.msg, dp(28f), mbTop + dp(34f), text)

        // banner flash on hot beats
        if (c.banner.isNotEmpty() && c.bannerHot) {
            text.color = GOLD; text.textSize = dp(34f); text.textAlign = Paint.Align.CENTER
            canvas.drawText(c.banner, w / 2f, h * 0.50f, text)
        } else if (c.banner == "RESIST" || c.banner == "NO EFF") {
            text.color = DIM; text.textSize = dp(22f); text.textAlign = Paint.Align.CENTER
            canvas.drawText(c.banner.lowercase(), w / 2f, h * 0.50f, text)
        }

        if (finished && elapsedMs > (last * (1000.0 / Director.FPS)) + 2600) {
            doneHold = true
            text.color = GOLD; text.textSize = dp(15f); text.textAlign = Paint.Align.CENTER
            canvas.drawText("▶  TAP FOR A REMATCH", w / 2f, h - dp(52f), text)
        }

        // camera toggle (top-right, clear of the status bar)
        toggleRect.set(w - dp(96f), dp(84f), w - dp(14f), dp(84f) + dp(34f))
        roundRect(canvas, toggleRect.left, toggleRect.top, toggleRect.right, toggleRect.bottom, dp(9f), BOX, BOX_LINE, dp(2f))
        text.color = INK; text.textSize = dp(12f); text.textAlign = Paint.Align.CENTER
        canvas.drawText(if (firstPerson) "◉ 1ST" else "◫ SIDE", toggleRect.centerX(), toggleRect.centerY() + dp(4f), text)

        if (!doneHold) postInvalidateOnAnimation()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            if (toggleRect.contains(event.x, event.y)) { onToggleView?.invoke(); performClick(); return true }
            if (doneHold) onTapWhenDone?.invoke()
            performClick()
        }
        return true
    }

    private fun drawSprite(canvas: Canvas, bmp: Bitmap, x: Float, y: Float, size: Float) {
        canvas.drawBitmap(bmp, src, RectF(x, y, x + size, y + size), px)
    }

    private fun drawHpBox(canvas: Canvas, x: Float, y: Float, boxW: Float, name: String, frac: Float, alignRight: Boolean) {
        val boxH = dp(46f)
        roundRect(canvas, x, y, x + boxW, y + boxH, dp(10f), BOX, BOX_LINE, dp(2f))
        text.textAlign = Paint.Align.LEFT
        text.color = INK; text.textSize = dp(14f)
        canvas.drawText(name.uppercase(), x + dp(12f), y + dp(19f), text)
        text.color = DIM; text.textSize = dp(11f)
        canvas.drawText("Lv50", x + boxW - dp(40f), y + dp(19f), text)
        // HP bar
        val barX = x + dp(12f); val barY = y + dp(28f); val barW = boxW - dp(24f); val barH = dp(7f)
        fill.color = Color.parseColor("#2A3050")
        canvas.drawRoundRect(RectF(barX, barY, barX + barW, barY + barH), barH / 2, barH / 2, fill)
        fill.color = when { frac > 0.5f -> Color.parseColor("#5FD97A"); frac > 0.22f -> GOLD; else -> Color.parseColor("#E5533F") }
        val fw = (barW * frac.coerceIn(0f, 1f)).coerceAtLeast(if (frac > 0f) dp(2f) else 0f)
        if (fw > 0) canvas.drawRoundRect(RectF(barX, barY, barX + fw, barY + barH), barH / 2, barH / 2, fill)
    }

    private fun roundRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, bg: Int, line: Int, sw: Float) {
        fill.color = bg
        canvas.drawRoundRect(RectF(l, t, r, b), rad, rad, fill)
        stroke.color = line; stroke.strokeWidth = sw
        canvas.drawRoundRect(RectF(l, t, r, b), rad, rad, stroke)
    }
}
