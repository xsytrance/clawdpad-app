package dev.pokepad.block

import dev.pokepad.core.Cell

/**
 * Duel-mode block scene: your block is YOUR Pokémon. It mirrors your side of
 * the current turn animation (the reel's left panel + your HP row); between
 * turns it holds the last frame; before a battle it rests on the Poké Ball.
 */
class MirrorScene(private val cell: () -> Cell?) : Scene {
    @Volatile private var exit = false
    private val WW = 15
    private val BG = intArrayOf(6, 6, 12)

    fun abort() { exit = true }
    override fun done() = exit

    override fun render(t: Double): ByteArray {
        val c = cell() ?: return BlockFrame.idle()
        val buf = ByteArray(WW * WW * 3)
        for (i in 0 until WW * WW) {
            val px = c.left[i]
            if (px == 0) { buf[i * 3] = BG[0].toByte(); buf[i * 3 + 1] = BG[1].toByte(); buf[i * 3 + 2] = BG[2].toByte() }
            else { buf[i * 3] = ((px ushr 16) and 0xFF).toByte(); buf[i * 3 + 1] = ((px ushr 8) and 0xFF).toByte(); buf[i * 3 + 2] = (px and 0xFF).toByte() }
        }
        val n = Math.round(c.hpL.coerceIn(0f, 1f) * 13)
        for (i in 0 until 13) set(buf, 1 + i, 0, 40, 38, 46)
        val col = when { c.hpL > 0.5f -> intArrayOf(95, 217, 122); c.hpL > 0.22f -> intArrayOf(245, 210, 70); else -> intArrayOf(229, 83, 63) }
        for (i in 0 until n) set(buf, 1 + i, 0, col[0], col[1], col[2])
        return buf
    }

    private fun set(buf: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val p = (y * WW + x) * 3; buf[p] = r.toByte(); buf[p + 1] = g.toByte(); buf[p + 2] = b.toByte()
    }
}
