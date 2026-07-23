package dev.pokepad.block

import dev.pokepad.core.Director
import dev.pokepad.core.Mon
import dev.pokepad.core.PokeData
import dev.pokepad.core.Reel
import java.util.Random

/**
 * A real autonomous Gen-III battle across two snapped blocks, looping. The full
 * engine runs a 1v1, the Director turns its events into per-block animation beats
 * (Poké-Ball summon → attack lunge → hurt flash → faint), and this Scene paints
 * one creature per block on the LEDs with an HP bar. block 1 = left mon (render),
 * block 2 = right mon (renderSecond). When a fight ends it holds a beat, then a
 * fresh random matchup starts — so the blocks keep battling until you unsnap.
 */
class PokeBlockScene(
    seedBase: Long,
    private val onLog: (String) -> Unit = {},
    /** if non-empty, each fight leads with one of YOUR real save mon (built
     *  fresh each round so battle state never carries over). Empty = all random. */
    private val playerFactories: List<() -> Mon> = emptyList(),
) : Scene {

    private val WW = 15
    @Volatile private var exit = false
    @Volatile private var reel: Reel? = null
    @Volatile private var roundStart = -1.0
    private val rng = Random(seedBase)

    private val HOLD_S = 3.5
    private val BG = intArrayOf(6, 6, 12)

    fun abort() { exit = true }
    override fun done() = exit

    private fun newReel(): Reel? = try {
        val dex = PokeData.dex(); val ids = PokeData.speciesIds
        val aMon = if (playerFactories.isNotEmpty()) playerFactories[rng.nextInt(playerFactories.size)]()
                   else { val s = ids[rng.nextInt(ids.size)]; Mon(dex, s, moves = Director.movesetFor(dex, s)) }
        var bs = ids[rng.nextInt(ids.size)]; while (bs == aMon.species.name) bs = ids[rng.nextInt(ids.size)]
        val bMon = Mon(dex, bs, moves = Director.movesetFor(dex, bs))
        val r = Director.build(dex, aMon, bMon, rng.nextLong())
        onLog("⚔️ ${r.leftName} vs ${r.rightName} — ${r.winnerName} wins!")
        r
    } catch (e: Exception) { onLog("battle failed: ${e.message}"); null }

    /** current cell index for time t; advances to a new round when a fight ends. */
    private fun cellIndex(t: Double, advance: Boolean): Int {
        val r = reel ?: return 0
        if (roundStart < 0) roundStart = t
        var i = ((t - roundStart) * Director.FPS).toInt()
        if (advance && i >= r.cells.size + HOLD_S * Director.FPS) {
            reel = newReel(); roundStart = t; i = 0
        }
        return i.coerceIn(0, (reel?.cells?.size ?: 1) - 1)
    }

    override fun render(t: Double): ByteArray {          // block 1 = left mon (driver)
        if (reel == null) { reel = newReel(); roundStart = t }
        val r = reel ?: return blank()
        val c = r.cells[cellIndex(t, advance = true)]
        return withHp(toBuf(c.left), c.hpL)
    }

    override fun renderSecond(t: Double): ByteArray {     // block 2 = right mon (reader)
        val r = reel ?: return blank()
        val c = r.cells[cellIndex(t, advance = false)]
        return withHp(toBuf(c.right), c.hpR)
    }

    private fun blank(): ByteArray {
        val buf = ByteArray(WW * WW * 3)
        for (i in 0 until WW * WW) { buf[i * 3] = BG[0].toByte(); buf[i * 3 + 1] = BG[1].toByte(); buf[i * 3 + 2] = BG[2].toByte() }
        return buf
    }

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
        for (i in 0 until 13) set(buf, 1 + i, 0, 40, 38, 46)
        val c = when { frac > 0.5f -> intArrayOf(95, 217, 122); frac > 0.22f -> intArrayOf(245, 210, 70); else -> intArrayOf(229, 83, 63) }
        for (i in 0 until n) set(buf, 1 + i, 0, c[0], c[1], c[2])
        return buf
    }
}
