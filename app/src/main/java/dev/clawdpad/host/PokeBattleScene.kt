package dev.clawdpad.host

import java.util.Random
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A fully autonomous Pokémon battle across two snapped blocks. Real Gen-III
 * stats/mechanics drive it (see PokeBattle); no human control — the two
 * creatures just fight, and moves literally fly across the seam from one
 * block to the other.
 *
 * The 30-wide arena is windowed per block (block 1 = cols 0..14 via render,
 * block 2 = 15..29 via renderSecond), same trick as CLAWD COMBAT.
 */
class PokeBattleScene(
    leftId: String,
    rightId: String,
    seed: Long = 20260721L,
    private val onLog: (String) -> Unit = {},
) : Scene {

    private val left = Mon(Pokedex.byId(leftId))
    private val right = Mon(Pokedex.byId(rightId))
    private val battle = PokeBattle(left, right, seed)
    private val rng = Random(seed)

    private val LX = 7f          // left mon arena-x (block 1)
    private val RX = 22f         // right mon arena-x (block 2)
    private val MY = 7           // mon vertical center row

    private var phase = Phase.INTRO
    private var beatStart = 0.0
    private var overAt = 0.0
    private var current: BattleEvent? = null
    private var impacted = false
    private var dispHpL = left.maxHp.toFloat()
    private var dispHpR = right.maxHp.toFloat()
    private val gibs = ArrayList<Gib>()
    private var shake = 0; private var shakeMag = 0
    private var banner = ""; private var bannerT = 0.0
    @Volatile private var exit = false

    private enum class Phase { INTRO, FIGHT, OVER }
    companion object {
        const val INTRO_S = 1.6
        const val WINDUP = 0.30; const val TRAVEL = 0.40; const val IMPACT = 0.70
        val BEAT = WINDUP + TRAVEL + IMPACT
        val WHITE = intArrayOf(255, 255, 255)
        val FLOOR = intArrayOf(44, 40, 52)
    }

    fun abort() { exit = true }
    override fun done() = exit

    init {
        onLog("⚔️ ${left.name} (Lv${left.level}) vs ${right.name} (Lv${right.level}) — auto-battle!")
        banner = "VS"; bannerT = INTRO_S
    }

    // ── timeline (advanced only in render(t) = block 1) ─────────────────
    private var lastT = -1.0
    private fun advance(t: Double) {
        val dt = if (lastT < 0) 0.0 else t - lastT; lastT = t
        when (phase) {
            Phase.INTRO -> if (t > INTRO_S) { phase = Phase.FIGHT; nextBeat(t); banner = "FIGHT!"; bannerT = 0.6 }
            Phase.FIGHT -> {
                val e = current
                if (e != null && !impacted && t - beatStart > WINDUP + TRAVEL) doImpact(e)
                if (e == null || t - beatStart > BEAT) nextBeat(t)
            }
            Phase.OVER -> if (t - overAt > 5.0) exit = true
        }
        dispHpL += (left.hp - dispHpL) * 0.25f
        dispHpR += (right.hp - dispHpR) * 0.25f
        val gi = gibs.iterator(); while (gi.hasNext()) if (!gi.next().step()) gi.remove()
        if (shake > 0) shake--
        if (bannerT > 0) bannerT -= dt
    }

    private fun nextBeat(t: Double) {
        val e = battle.step()
        if (e == null) {
            phase = Phase.OVER; overAt = t
            val w = if (battle.leftWon) left else right
            banner = "WIN"; bannerT = 99.0
            onLog("🏆 ${w.name} wins!")
            return
        }
        current = e; beatStart = t; impacted = false
    }

    private fun doImpact(e: BattleEvent) {
        impacted = true
        val defX = if (e.attackerIsLeft) RX else LX
        if (e.missed) { banner = "MISS"; bannerT = 0.7; return }
        // type-colored splatter at the defender
        val col = e.move.type.color
        val n = if (e.effectiveness > 1f) 12 else if (e.effectiveness < 1f) 4 else 7
        repeat(n) {
            gibs.add(Gib(defX, MY.toFloat(), (rng.nextFloat() - 0.5f) * 2.6f,
                -rng.nextFloat() * 2.3f - 0.2f, col, 8 + rng.nextInt(8)))
        }
        shakeMag = if (e.effectiveness > 1f) 4 else 2; shake = if (e.crit) 6 else 4
        banner = when {
            e.effectiveness == 0f -> "NO EFF"
            e.effectiveness > 1f -> "SUPER!"
            e.crit -> "CRIT!"
            e.effectiveness < 1f -> "RESIST"
            else -> e.move.name.take(5)
        }
        bannerT = 0.8
        if (e.defenderFainted) {
            // a big poof where the fainter stood
            repeat(20) {
                gibs.add(Gib(defX, MY.toFloat(), (rng.nextFloat() - 0.5f) * 3.4f,
                    -rng.nextFloat() * 3f - 0.2f, col, 12 + rng.nextInt(10)))
            }
            shakeMag = 6; shake = 9
        }
    }

    // ── renders ─────────────────────────────────────────────────────────
    override fun render(t: Double): ByteArray {          // block 1 (cols 0..14)
        advance(t)
        val raw = ByteArray(Draw.W * Draw.H * 3)
        drawArena(raw, xoff = 0)
        drawHp(raw, dispHpL / left.maxHp, leftName = true)
        drawBanner(raw)
        return applyShake(raw)
    }

    override fun renderSecond(t: Double): ByteArray {     // block 2 (cols 15..29)
        val raw = ByteArray(Draw.W * Draw.H * 3)
        drawArena(raw, xoff = 15)
        drawHp(raw, dispHpR / right.maxHp, leftName = false)
        drawBanner(raw)
        return applyShake(raw)
    }

    private fun drawArena(raw: ByteArray, xoff: Int) {
        for (x in 0 until Draw.W) Draw.px(raw, x, 11, FLOOR[0], FLOOR[1], FLOOR[2], 0.5f)
        val t = lastT.coerceAtLeast(0.0)
        drawMon(raw, left, LX, faceRight = true, xoff, t, attacking = current?.attackerIsLeft == true)
        drawMon(raw, right, RX, faceRight = false, xoff, t, attacking = current?.attackerIsLeft == false)
        drawProjectile(raw, xoff, t)
        for (g in gibs) g.render(raw, xoff)
    }

    private fun drawMon(buf: ByteArray, mon: Mon, ax0: Float, faceRight: Boolean, xoff: Int, t: Double, attacking: Boolean) {
        val e = current
        var ax = ax0
        var bright = 1f
        val hurt = e != null && impacted && !e.missed &&
            ((e.attackerIsLeft && mon === right) || (!e.attackerIsLeft && mon === left))
        val phaseT = (t - beatStart)
        when {
            mon.fainted -> bright = 0.28f
            attacking && phaseT < WINDUP -> ax += (if (faceRight) 1 else -1) * (phaseT / WINDUP).toFloat() * 1.6f
            hurt && phaseT < WINDUP + TRAVEL + 0.2 -> bright = if (((t * 30).toInt()) % 2 == 0) 1f else 0.4f
        }
        val cx = ax.roundToInt()
        val dir = if (faceRight) 1 else -1
        val col = mon.species.t1.color
        val yBase = if (mon.fainted) MY + 3 else MY
        // aura pulse in the type color
        if (!mon.fainted) {
            val a = 0.18f + 0.12f * sin(t * 4 + (if (faceRight) 0.0 else 1.5)).toFloat()
            for (dx in -3..3) for (dy in -3..3) {
                if (hypot(dx.toDouble(), dy.toDouble()) in 2.6..3.4)
                    Draw.px(buf, cx + dx - xoff, yBase + dy, col[0], col[1], col[2], a)
            }
        }
        // body: a rounded blob
        for (dy in -2..2) for (dx in -2..2) {
            if (hypot(dx.toDouble(), dy * 1.1) <= 2.4)
                Draw.px(buf, cx + dx - xoff, yBase + dy, col[0], col[1], col[2], bright)
        }
        // secondary-type crest on top
        mon.species.t2?.let { t2 ->
            val c2 = t2.color
            Draw.px(buf, cx - xoff, yBase - 3, c2[0], c2[1], c2[2], bright)
            Draw.px(buf, cx + dir - xoff, yBase - 2, c2[0], c2[1], c2[2], bright)
        }
        // eyes toward the foe (x_x if fainted)
        if (mon.fainted) {
            Draw.px(buf, cx - 1 - xoff, yBase - 1, 15, 12, 16); Draw.px(buf, cx + 1 - xoff, yBase - 1, 15, 12, 16)
        } else {
            Draw.px(buf, cx - xoff, yBase - 1, 12, 10, 16); Draw.px(buf, cx + dir - xoff, yBase - 1, 12, 10, 16)
        }
    }

    private fun drawProjectile(buf: ByteArray, xoff: Int, t: Double) {
        val e = current ?: return
        if (e.missed) return
        val phaseT = t - beatStart
        if (phaseT < WINDUP || phaseT > WINDUP + TRAVEL) return
        val f = ((phaseT - WINDUP) / TRAVEL).toFloat().coerceIn(0f, 1f)
        val from = if (e.attackerIsLeft) LX else RX
        val to = if (e.attackerIsLeft) RX else LX
        val x = (from + (to - from) * f)
        val col = e.move.type.color
        val bx = x.roundToInt() - xoff
        Draw.px(buf, bx, MY, col[0], col[1], col[2])
        Draw.px(buf, bx - 1, MY, col[0], col[1], col[2], 0.6f)
        Draw.px(buf, bx + 1, MY, col[0], col[1], col[2], 0.6f)
        Draw.px(buf, bx, MY - 1, col[0], col[1], col[2], 0.5f)
        Draw.px(buf, bx, MY + 1, col[0], col[1], col[2], 0.5f)
    }

    private fun drawHp(buf: ByteArray, frac: Float, leftName: Boolean) {
        val c = when { frac > 0.5f -> intArrayOf(110, 220, 130); frac > 0.25f -> ClawdRenderer.CORAL; else -> intArrayOf(210, 70, 60) }
        val n = (frac.coerceIn(0f, 1f) * 13).roundToInt()
        for (i in 0 until n) Draw.px(buf, 1 + i, 0, c[0], c[1], c[2])
    }

    private fun drawBanner(buf: ByteArray) {
        if (bannerT <= 0.0 || banner.isEmpty()) return
        val c = if (banner == "SUPER!" || banner == "CRIT!" || banner == "WIN") WHITE else intArrayOf(210, 200, 190)
        Draw.textCenter(buf, banner.take(6), 13, c, if ((bannerT * 8).toInt() % 2 == 0) 1f else 0.7f)
    }

    private fun applyShake(raw: ByteArray): ByteArray {
        if (shake <= 0 || shakeMag <= 0) return raw
        val sx = rng.nextInt(shakeMag * 2 + 1) - shakeMag
        val sy = rng.nextInt(shakeMag * 2 + 1) - shakeMag
        if (sx == 0 && sy == 0) return raw
        val out = ByteArray(raw.size)
        for (y in 0 until Draw.H) for (x in 0 until Draw.W) {
            val srx = x - sx; val sry = y - sy
            if (srx in 0 until Draw.W && sry in 0 until Draw.H) {
                val s = (sry * Draw.W + srx) * 3; val d = (y * Draw.W + x) * 3
                out[d] = raw[s]; out[d + 1] = raw[s + 1]; out[d + 2] = raw[s + 2]
            }
        }
        return out
    }

    private class Gib(private var x: Float, private var y: Float,
                      private var vx: Float, private var vy: Float,
                      private val col: IntArray, private var life: Int) {
        fun step(): Boolean { x += vx; y += vy; vy += 0.3f; life--; return life > 0 && y < Draw.H + 2 }
        fun render(buf: ByteArray, xoff: Int) {
            val f = (life / 16f).coerceIn(0.3f, 1f)
            Draw.px(buf, x.roundToInt() - xoff, y.roundToInt(), col[0], col[1], col[2], f)
        }
    }
}
