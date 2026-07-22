package dev.pokepad.block

import dev.pokepad.core.Director
import dev.pokepad.core.PokeData
import dev.pokepad.core.Reel

/**
 * A real autonomous Gen-III battle across two snapped blocks. The full engine
 * runs a 1v1, the Director turns its events into per-block animation beats
 * (Poké-Ball summon → attack lunge → hurt flash → faint), and this Scene paints
 * one creature per block on the LEDs with an HP bar. block 1 = left mon (render),
 * block 2 = right mon (renderSecond). Built once on the streamer thread.
 */
class PokeBlockScene(
    private val leftId: String,
    private val rightId: String,
    private val seed: Long,
    private val onLog: (String) -> Unit = {},
) : Scene {

    private val WW = 15
    @Volatile private var exit = false
    @Volatile private var reel: Reel? = null
    private var startT = -1.0

    private val HOLD_S = 4.0
    private val BG = intArrayOf(6, 6, 12)

    fun abort() { exit = true }
    override fun done() = exit

    private fun reel(): Reel? {
        reel?.let { return it }
        synchronized(this) {
            reel?.let { return it }
            return try {
                val r = Director.build(PokeData.dex(), leftId, rightId, seed)
                onLog("⚔️ ${r.leftName} vs ${r.rightName} — ${r.winnerName} wins!")
                reel = r; r
            } catch (e: Exception) { onLog("battle failed: ${e.message}"); exit = true; null }
        }
    }

    private fun index(t: Double, r: Reel): Int {
        if (startT < 0) startT = t
        val i = ((t - startT) * Director.FPS).toInt()
        if (i >= r.cells.size + HOLD_S * Director.FPS) exit = true
        return i.coerceIn(0, r.cells.size - 1)
    }

    override fun render(t: Double): ByteArray {
        val r = reel() ?: return blank()
        val c = r.cells[index(t, r)]
        return withHp(toBuf(c.left), c.hpL)
    }

    override fun renderSecond(t: Double): ByteArray {
        val r = reel() ?: return blank()
        val c = r.cells[index(t, r)]
        return withHp(toBuf(c.right), c.hpR)
    }

    private fun blank(): ByteArray {
        val buf = ByteArray(WW * WW * 3)
        for (i in 0 until WW * WW) { buf[i * 3] = BG[0].toByte(); buf[i * 3 + 1] = BG[1].toByte(); buf[i * 3 + 2] = BG[2].toByte() }
        return buf
    }

    /** core.Frame px (0xRRGGBB, 0 = empty) → RGB888 block frame */
    private fun toBuf(px: IntArray): ByteArray {
        val buf = ByteArray(WW * WW * 3)
        for (i in 0 until WW * WW) {
            val c = px[i]
            if (c == 0) { buf[i * 3] = BG[0].toByte(); buf[i * 3 + 1] = BG[1].toByte(); buf[i * 3 + 2] = BG[2].toByte() }
            else { buf[i * 3] = ((c ushr 16) and 0xFF).toByte(); buf[i * 3 + 1] = ((c ushr 8) and 0xFF).toByte(); buf[i * 3 + 2] = (c and 0xFF).toByte() }
        }
        return buf
    }

    private fun set(buf: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val p = (y * WW + x) * 3; buf[p] = r.toByte(); buf[p + 1] = g.toByte(); buf[p + 2] = b.toByte()
    }

    private fun withHp(buf: ByteArray, frac: Float): ByteArray {
        val n = Math.round(frac.coerceIn(0f, 1f) * 13)
        for (i in 0 until 13) set(buf, 1 + i, 0, 40, 38, 46)                 // track
        val c = when { frac > 0.5f -> intArrayOf(95, 217, 122); frac > 0.22f -> intArrayOf(245, 210, 70); else -> intArrayOf(229, 83, 63) }
        for (i in 0 until n) set(buf, 1 + i, 0, c[0], c[1], c[2])            // fill
        return buf
    }
}
