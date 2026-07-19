package dev.clawdpad.host

import java.util.Random
import kotlin.math.sin

/**
 * Renders a duel on the glass: two chibi fighters, HP up top, energy
 * down low, telegraph flashes, parry sparks, full-screen super cut-ins.
 * Drives BattleEngine ticks off the 12fps render clock.
 */
class BattleScene(
    private val engine: BattleEngine,
    /** non-null = the left side is played live by the trainer */
    private val playerSide: GestureCombatant?,
    private val leftTint: IntArray = ClawdRenderer.CORAL,
    private val rightTint: IntArray = intArrayOf(140, 150, 235),
    private val recordStyle: Boolean = playerSide != null,
    private val onLog: (String) -> Unit = {},
    private val onOver: (leftWon: Boolean) -> Unit = {},
) : Scene {

    companion object {
        const val INTRO_S = 2.0
        const val TICK_S = 0.08
        val TEAL = intArrayOf(87, 190, 217)
        val WHITE = intArrayOf(255, 255, 255)

        /** convenience: player (live gestures) vs a rival AI */
        fun versus(rival: Ladder.Rival, rng: Random = Random(),
                   onLog: (String) -> Unit = {},
                   onOver: (Boolean) -> Unit = {}): BattleScene {
            val me = GestureCombatant(ClawdState.stats)
            val engine = BattleEngine(me,
                AiCombatant(rival.stats, rival.style, rng), onLog)
            return BattleScene(engine, me, rightTint = rival.tint,
                onLog = onLog, onOver = onOver)
        }

        /** the human-free shadow match: YOUR Clawd (stats + trained style)
         *  fights a rival all by himself */
        fun shadow(rival: Ladder.Rival, rng: Random = Random(),
                   onLog: (String) -> Unit = {},
                   onOver: (Boolean) -> Unit = {}): BattleScene {
            val engine = BattleEngine(
                AiCombatant(ClawdState.stats, ClawdState.style, Random(rng.nextLong())),
                AiCombatant(rival.stats, rival.style, Random(rng.nextLong())), onLog)
            return BattleScene(engine, null, rightTint = rival.tint,
                recordStyle = false, onLog = onLog, onOver = onOver)
        }
    }

    private var ticked = 0L
    private var overAt = -1.0
    private var reported = false
    @Volatile private var exit = false
    private val gestureCounts = HashMap<String, Int>()
    private val rng = Random()

    override fun onGesture(g: Gesture) {
        playerSide?.offer(g) ?: return
        val key = when (g) {
            is Gesture.Strike -> "power"
            is Gesture.Hold -> if (g.ongoing) return else "guard"
            is Gesture.Swipe -> "finesse"
            is Gesture.Scrub -> "stamina"
            else -> return
        }
        gestureCounts[key] = (gestureCounts[key] ?: 0) + 1
        if (recordStyle) { ClawdState.style.record(g, rng); ClawdState.saveSoon() }
    }

    override fun render(t: Double): ByteArray {
        val buf = ByteArray(Draw.W * Draw.H * 3)
        if (t < INTRO_S) { renderIntro(buf, t); return buf }
        // engine time marches on the render clock
        val target = ((t - INTRO_S) / TICK_S).toLong()
        while (ticked < target && !engine.over) { engine.tick(); ticked++ }
        if (engine.over && overAt < 0) {
            overAt = t
            if (!reported) {
                reported = true
                val leftWon = engine.winner === engine.l
                awardXp(leftWon)
                onOver(leftWon)
            }
        }
        // full-screen super cut-in trumps the duel view
        val superSide = when {
            engine.l.superFlash > 0 -> engine.l to leftTint
            engine.r.superFlash > 0 -> engine.r to rightTint
            else -> null
        }
        if (superSide != null && !engine.over) {
            val pulse = 0.55 + 0.45 * sin(t * 18)
            return ClawdRenderer.clawd(pulse, 0, 0, eyesOpen = true,
                look = 0, armLdy = -2, armRdy = -2, tint = superSide.second)
        }
        renderDuel(buf, t)
        if (overAt >= 0 && t - overAt > 4.0) exit = true
        return buf
    }

    override fun done(): Boolean = exit
    fun abort() { exit = true }

    private fun awardXp(leftWon: Boolean) {
        if (playerSide == null) return                 // shadow matches: pride only
        val stat = gestureCounts.maxByOrNull { it.value }?.key ?: "power"
        val xp = if (leftWon) 35 else 10
        val leveled = ClawdState.addXp(stat, xp)
        onLog(if (leftWon) "victory! +$xp $stat xp" else "+$xp $stat xp for the effort")
        if (leveled) onLog("LEVEL UP: $stat")
    }

    private fun renderIntro(buf: ByteArray, t: Double) {
        val a = (t / 0.4).coerceAtMost(1.0).toFloat()
        Draw.rect(buf, 1, 4, 6, 9, leftTint, a)
        Draw.rect(buf, 9, 4, 14, 9, rightTint, a)
        Draw.px(buf, 3, 6, 0, 0, 0); Draw.px(buf, 5, 6, 0, 0, 0)
        Draw.px(buf, 11, 6, 0, 0, 0); Draw.px(buf, 13, 6, 0, 0, 0)
        if ((t * 3).toInt() % 2 == 0)
            Draw.textCenter(buf, "VS", 10, WHITE, a)
    }

    private fun renderDuel(buf: ByteArray, t: Double) {
        drawBars(buf)
        drawFighter(buf, engine.l, leftTint, facingRight = true, t)
        drawFighter(buf, engine.r, rightTint, facingRight = false, t)
        if (engine.over) {
            val win = engine.winner!!
            val c = if (win === engine.l) leftTint else rightTint
            if ((t * 2).toInt() % 2 == 0) Draw.textCenter(buf, "KO", 5, c)
        }
    }

    private fun drawBars(buf: ByteArray) {
        // HP row 0: left fills →, right fills ←; color cools as hp drops
        fun hpColor(f: Float) = when {
            f > 0.5f -> intArrayOf(110, 220, 130)
            f > 0.25f -> ClawdRenderer.CORAL
            else -> intArrayOf(210, 70, 60)
        }
        val lf = engine.l.hp / BattleEngine.MAX_HP
        val rf = engine.r.hp / BattleEngine.MAX_HP
        val lc = hpColor(lf); val rc = hpColor(rf)
        val lpx = (lf * 7).toInt().coerceIn(0, 7)
        val rpx = (rf * 7).toInt().coerceIn(0, 7)
        for (x in 0 until lpx) Draw.px(buf, x, 0, lc[0], lc[1], lc[2])
        for (x in 0 until rpx) Draw.px(buf, 14 - x, 0, rc[0], rc[1], rc[2])
        // energy row 14, dim teal
        val le = (engine.l.energy / BattleEngine.MAX_ENERGY * 7).toInt()
        val re = (engine.r.energy / BattleEngine.MAX_ENERGY * 7).toInt()
        for (x in 0 until le) Draw.px(buf, x, 14, TEAL[0], TEAL[1], TEAL[2], 0.5f)
        for (x in 0 until re) Draw.px(buf, 14 - x, 14, TEAL[0], TEAL[1], TEAL[2], 0.5f)
    }

    /** a 5-wide chibi: body, eyes, legs; battle state animates it */
    private fun drawFighter(buf: ByteArray, s: BattleEngine.Side, tint: IntArray,
                            facingRight: Boolean, t: Double) {
        val koLoser = engine.over && engine.winner !== s
        val dir = if (facingRight) 1 else -1
        var x0 = if (facingRight) 2 else 8
        var y0 = 6
        var bright = 1f
        when {
            koLoser -> {
                y0 += 3                        // down for the count
                bright = 0.35f
            }
            s.lunge > 0 -> x0 += dir * 2       // attack lunge
            s.dodge > 0 -> x0 -= dir           // leaning out of danger
            s.hit > 0 -> {
                x0 -= dir                      // knocked back
                bright = if (s.hit % 2 == 0) 1f else 0.5f
            }
            s.telegraph > 0 ->                 // wind-up shimmer
                bright = 0.6f + 0.4f * ((s.telegraph % 2).toFloat())
            engine.over ->                     // the winner bounces
                y0 -= ((sin(t * 6) * 1.5 + 1.5) / 2).toInt()
        }
        Draw.rect(buf, x0, y0, x0 + 5, y0 + 4, tint, bright)
        // eyes look at the foe
        val e1 = x0 + 1 + if (facingRight) 1 else 0
        val e2 = x0 + 3 + if (facingRight) 1 else 0
        if (koLoser) {
            Draw.px(buf, e1 - if (facingRight) 1 else 0, y0 + 1, 0, 0, 0)
            Draw.px(buf, e2 - if (facingRight) 1 else 0, y0 + 1, 0, 0, 0)
        } else {
            Draw.px(buf, e1, y0 + 1, 0, 0, 0)
            Draw.px(buf, e2, y0 + 1, 0, 0, 0)
        }
        // legs
        Draw.px(buf, x0 + 1, y0 + 4, tint[0], tint[1], tint[2], bright)
        Draw.px(buf, x0 + 3, y0 + 4, tint[0], tint[1], tint[2], bright)
        // guard: a shield wall in front
        if (s.guarding) {
            val gx = if (facingRight) x0 + 5 else x0 - 1
            for (y in y0 - 1..y0 + 4)
                Draw.px(buf, gx, y, TEAL[0], TEAL[1], TEAL[2],
                    0.4f + 0.6f * s.guardMagnitude)
        }
        // hit spark
        if (s.hit == 3) {
            val sx = x0 + if (facingRight) 4 else 0
            Draw.px(buf, sx, y0, WHITE[0], WHITE[1], WHITE[2])
            Draw.px(buf, sx, y0 + 2, WHITE[0], WHITE[1], WHITE[2], 0.7f)
        }
    }
}

/** The rival ladder: personalities built from stats + a synthetic style
 *  fingerprint. Tints echo the Clawdrobe cast. */
object Ladder {
    data class Rival(val id: String, val name: String, val emoji: String,
                     val tint: IntArray, val stats: Stats,
                     val style: StyleProfile)

    /** deterministic synthetic profile: a personality in numbers */
    private fun profile(seed: Long, aggression: Float, strike: Float,
                        hold: Float, swipe: Float, scrub: Float): StyleProfile {
        val rng = Random(seed)
        val p = StyleProfile()
        val nAtk = (20 * aggression).toInt().coerceAtLeast(2)
        val nDef = (20 * (1 - aggression)).toInt().coerceAtLeast(2)
        repeat(nAtk) {
            p.record(Gesture.Strike(7f, 7f,
                (strike + rng.nextFloat() * 0.2f - 0.1f).coerceIn(0.1f, 1f), 0.5f), rng)
        }
        repeat(nDef) {
            p.record(Gesture.Hold(7f, 7f, 800,
                0.6f, (hold + rng.nextFloat() * 0.2f - 0.1f).coerceIn(0.1f, 1f),
                ongoing = false), rng)
        }
        repeat(6) {
            p.record(Gesture.Swipe(2f, 7f, 8f, 0f, swipe * 60f, 0f), rng)
            p.record(Gesture.Scrub(7f, 7f, scrub * 3f, scrub * 2f, true), rng)
        }
        return p
    }

    val ALL: List<Rival> by lazy { listOf(
        Rival("wisp", "Wisp", "👻", intArrayOf(170, 190, 230),
            Stats(power = 1, guard = 1, finesse = 2, stamina = 1),
            profile(11, aggression = 0.35f, strike = 0.35f, hold = 0.4f,
                swipe = 0.5f, scrub = 0.3f)),
        Rival("tinbot", "Tinbot", "🤖", intArrayOf(150, 160, 170),
            Stats(power = 3, guard = 5, finesse = 1, stamina = 3),
            profile(22, aggression = 0.3f, strike = 0.5f, hold = 0.85f,
                swipe = 0.2f, scrub = 0.5f)),
        Rival("prowl", "Prowl", "🐱", intArrayOf(230, 170, 90),
            Stats(power = 4, guard = 2, finesse = 7, stamina = 4),
            profile(33, aggression = 0.55f, strike = 0.6f, hold = 0.5f,
                swipe = 0.9f, scrub = 0.4f)),
        Rival("hopps", "Hopps", "🐸", intArrayOf(120, 210, 110),
            Stats(power = 6, guard = 3, finesse = 5, stamina = 6),
            profile(44, aggression = 0.8f, strike = 0.8f, hold = 0.4f,
                swipe = 0.7f, scrub = 0.6f)),
        Rival("zork", "Zork", "👽", intArrayOf(180, 110, 230),
            Stats(power = 8, guard = 7, finesse = 7, stamina = 8),
            profile(55, aggression = 0.65f, strike = 0.9f, hold = 0.8f,
                swipe = 0.8f, scrub = 0.8f)),
    ) }

    /** the next rival worth fighting at Clawd's level */
    fun next(level: Int): Rival =
        ALL.getOrElse((level - 1).coerceAtLeast(0) / 2) { ALL.last() }
}
