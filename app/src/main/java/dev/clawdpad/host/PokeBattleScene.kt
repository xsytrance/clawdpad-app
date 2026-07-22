package dev.clawdpad.host

import dev.clawdpad.poke.Director
import dev.clawdpad.poke.PokeData
import dev.clawdpad.poke.Reel
import kotlin.math.roundToInt

/**
 * A fully autonomous Pokémon battle across two snapped blocks — the REAL thing.
 *
 * The full Gen-III engine (dev.clawdpad.poke.Battle, cross-gated to the Python
 * spec) runs a 1v1, the director turns its event stream into per-block animation
 * beats (Poké-Ball summon → attack lunge → hurt flash → faint tip-over) on one
 * 15x15 creature per block, and this Scene paints those frames on the LEDs with a
 * live HP bar and a hit banner. No human control: the AI picks moves, the type
 * chart + damage formula decide everything.
 *
 * block 1 = the left creature (render), block 2 = the right creature
 * (renderSecond), same two-block split as CLAWD COMBAT.
 */
class PokeBattleScene(
    private val leftId: String,
    private val rightId: String,
    private val seed: Long = 20260722L,
    private val onLog: (String) -> Unit = {},
) : Scene {

    @Volatile private var exit = false
    @Volatile private var reel: Reel? = null
    private var startT = -1.0

    companion object {
        const val HOLD_S = 4.0                       // linger on the WIN before bowing out
        val WHITE = intArrayOf(255, 255, 255)
        val BG = intArrayOf(10, 10, 15)
    }

    fun abort() { exit = true }
    override fun done() = exit

    /** built once, on the streamer thread, on first render (asset+battle+frames) */
    private fun reel(): Reel? {
        reel?.let { return it }
        synchronized(this) {
            reel?.let { return it }
            return try {
                val r = Director.build(PokeData.dex(), leftId, rightId, seed)
                onLog("⚔️ ${r.leftName} vs ${r.rightName} — auto-battle! (winner: ${r.winnerName})")
                reel = r; r
            } catch (e: Exception) {
                onLog("⚠️ battle failed to start: ${e.message}")
                exit = true; null
            }
        }
    }

    private fun index(t: Double, r: Reel): Int {
        if (startT < 0) startT = t
        val i = ((t - startT) * Director.FPS).toInt()
        if (i >= r.cells.size + HOLD_S * Director.FPS) exit = true
        return i.coerceIn(0, r.cells.size - 1)
    }

    // ── renders ─────────────────────────────────────────────────────────
    override fun render(t: Double): ByteArray {          // block 1 = left creature
        val r = reel() ?: return blank()
        val c = r.cells[index(t, r)]
        val buf = toBuf(c.left)
        drawHp(buf, c.hpL)
        drawBanner(buf, c.banner, c.bannerHot)
        return buf
    }

    override fun renderSecond(t: Double): ByteArray {     // block 2 = right creature
        val r = reel() ?: return blank()
        val c = r.cells[index(t, r)]
        val buf = toBuf(c.right)
        drawHp(buf, c.hpR)
        drawBanner(buf, c.banner, c.bannerHot)
        return buf
    }

    // ── paint helpers ───────────────────────────────────────────────────
    private fun blank(): ByteArray {
        val buf = ByteArray(Draw.W * Draw.H * 3)
        for (i in 0 until Draw.W * Draw.H) { buf[i * 3] = BG[0].toByte(); buf[i * 3 + 1] = BG[1].toByte(); buf[i * 3 + 2] = BG[2].toByte() }
        return buf
    }

    /** poke.Frame px (0xRRGGBB, 0 = empty) → RGB888 block frame */
    private fun toBuf(px: IntArray): ByteArray {
        val buf = ByteArray(Draw.W * Draw.H * 3)
        for (i in 0 until Draw.W * Draw.H) {
            val c = px[i]
            if (c == 0) { buf[i * 3] = BG[0].toByte(); buf[i * 3 + 1] = BG[1].toByte(); buf[i * 3 + 2] = BG[2].toByte() }
            else { buf[i * 3] = ((c ushr 16) and 0xFF).toByte(); buf[i * 3 + 1] = ((c ushr 8) and 0xFF).toByte(); buf[i * 3 + 2] = (c and 0xFF).toByte() }
        }
        return buf
    }

    private fun drawHp(buf: ByteArray, frac: Float) {
        val c = when { frac > 0.5f -> intArrayOf(110, 220, 130); frac > 0.25f -> ClawdRenderer.CORAL; else -> intArrayOf(210, 70, 60) }
        val n = (frac.coerceIn(0f, 1f) * 13).roundToInt()
        for (i in 0 until 13) Draw.px(buf, 1 + i, 0, 40, 38, 46)          // track
        for (i in 0 until n) Draw.px(buf, 1 + i, 0, c[0], c[1], c[2])     // fill
    }

    private fun drawBanner(buf: ByteArray, banner: String, hot: Boolean) {
        if (banner.isEmpty()) return
        val c = if (hot) WHITE else intArrayOf(210, 200, 190)
        Draw.textCenter(buf, banner.take(6), 13, c)
    }
}
