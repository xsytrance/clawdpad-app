package dev.clawdpad.host

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Training mini-games: 30-second pad games, one per stat. Every scoring
 * gesture also feeds the StyleProfile — training literally teaches Clawd
 * how you fight. Pure Kotlin (JVM-testable); rendering uses the same
 * procedural vocabulary as ClawdRenderer.
 */

// ── tiny drawing kit (15x15 RGB888, same conventions as ClawdRenderer) ──
internal object Draw {
    const val W = 15
    const val H = 15

    fun px(buf: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int, s: Float = 1f) {
        if (x in 0 until W && y in 0 until H) {
            val i = (y * W + x) * 3
            buf[i] = (r * s).toInt().coerceIn(0, 255).toByte()
            buf[i + 1] = (g * s).toInt().coerceIn(0, 255).toByte()
            buf[i + 2] = (b * s).toInt().coerceIn(0, 255).toByte()
        }
    }

    fun rect(buf: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int,
             c: IntArray, s: Float = 1f) {
        for (y in y0 until y1) for (x in x0 until x1)
            px(buf, x, y, c[0], c[1], c[2], s)
    }

    /** 3x5 FONT text; x,y = top-left of first glyph */
    fun text(buf: ByteArray, str: String, x: Int, y: Int, c: IntArray,
             s: Float = 1f) {
        var gx = x
        for (ch in str.uppercase()) {
            val rows = ClawdRenderer.FONT[ch] ?: ClawdRenderer.FONT['?']!!
            for (rr in 0 until 5) for (cc in 0 until 3)
                if (rows[rr][cc] == '1') px(buf, gx + cc, y + rr, c[0], c[1], c[2], s)
            gx += 4
        }
    }

    /** centered text */
    fun textCenter(buf: ByteArray, str: String, y: Int, c: IntArray, s: Float = 1f) =
        text(buf, str, (W - (str.length * 4 - 1)) / 2, y, c, s)
}

/**
 * Shared round structure: 3-2-1 countdown → 30s of play → score card.
 * Subclasses implement renderPlay + gesture handling and add to [score].
 */
abstract class MiniGame(
    private val statKey: String,
    /** score → XP conversion; keep rounds worth roughly 20..120 XP */
    private val xpPerPoint: Float = 1f,
    val onEnd: (score: Int, xp: Int, leveled: Boolean) -> Unit = { _, _, _ -> },
) : Scene {
    companion object {
        const val COUNTDOWN_S = 3.0
        const val PLAY_S = 30.0
        const val END_S = 4.0
    }

    protected val rng = Random()
    var score = 0
        private set
    private var ended = false
    private var leveled = false
    private var xp = 0
    @Volatile private var exit = false

    protected val CORAL = ClawdRenderer.CORAL
    protected val GOOD = intArrayOf(110, 220, 130)
    protected val BAD = intArrayOf(210, 70, 60)
    protected val DIMW = intArrayOf(90, 84, 78)

    protected fun addScore(points: Int) { score += points }

    /** record a scoring gesture into the trainer's style fingerprint */
    protected fun learn(g: Gesture) {
        ClawdState.style.record(g, rng)
        ClawdState.saveSoon()
    }

    protected val playing: Boolean get() = phase == 1
    private var phase = 0   // 0 countdown, 1 play, 2 end

    final override fun render(t: Double): ByteArray {
        val buf = ByteArray(Draw.W * Draw.H * 3)
        when {
            t < COUNTDOWN_S -> {
                phase = 0
                val n = (COUNTDOWN_S - t).toInt() + 1
                val pulse = 0.5f + 0.5f * (1f - (t % 1.0).toFloat())
                Draw.textCenter(buf, "$n", 5, CORAL, pulse)
            }
            t < COUNTDOWN_S + PLAY_S -> {
                phase = 1
                renderPlay(buf, t - COUNTDOWN_S)
                // time bar: bottom row drains left→right
                val left = ((1 - (t - COUNTDOWN_S) / PLAY_S) * Draw.W).toInt()
                for (x in 0 until left) Draw.px(buf, x, Draw.H - 1,
                    DIMW[0], DIMW[1], DIMW[2])
            }
            else -> {
                phase = 2
                if (!ended) {
                    ended = true
                    xp = (score * xpPerPoint).toInt().coerceAtLeast(1)
                    leveled = ClawdState.addXp(statKey, xp)
                    onEnd(score, xp, leveled)
                }
                val flash = if (leveled && (t * 3).toInt() % 2 == 0) 1f else 0.85f
                Draw.textCenter(buf, "$score", 2, CORAL, flash)
                Draw.textCenter(buf, if (leveled) "UP!" else "XP", 9,
                    if (leveled) GOOD else DIMW)
                if (t > COUNTDOWN_S + PLAY_S + END_S) exit = true
            }
        }
        return buf
    }

    final override fun done(): Boolean = exit
    fun abort() { exit = true }

    /** tPlay = seconds since play began */
    protected abstract fun renderPlay(buf: ByteArray, tPlay: Double)
}

// ── 1. STRIKE THE TARGET (power) ────────────────────────────────────────
class StrikeTarget(onEnd: (Int, Int, Boolean) -> Unit = { _, _, _ -> }) :
    MiniGame("power", xpPerPoint = 0.6f, onEnd = onEnd) {

    @Volatile private var tx = 7
    @Volatile private var ty = 7
    @Volatile private var flash = 0.0   // >0 = hit flash countdown (frames)
    @Volatile private var missFlash = 0.0

    private fun moveTarget() {
        tx = 2 + rng.nextInt(Draw.W - 4)
        ty = 2 + rng.nextInt(Draw.H - 5)  // keep off the time bar
    }

    override fun onGesture(g: Gesture) {
        if (!playing) return
        val (x, y, power) = when (g) {
            is Gesture.Strike -> Triple(g.x, g.y, g.velocity)
            is Gesture.Tap -> Triple(g.x, g.y, g.z * 0.6f)  // soft hit
            else -> return
        }
        val d = hypot(x - (tx + 0.5f), y - (ty + 0.5f))
        if (d < 2.5f) {
            val proximity = (1f - d / 2.5f) * 0.5f + 0.5f
            addScore((power * proximity * 10).toInt().coerceAtLeast(1))
            learn(if (g is Gesture.Strike) g else Gesture.Strike(x, y, power, power))
            flash = 3.0
            moveTarget()
        } else {
            missFlash = 2.0
        }
    }

    override fun renderPlay(buf: ByteArray, tPlay: Double) {
        val breathe = 0.7f + 0.3f * sin(tPlay * 5).toFloat()
        Draw.rect(buf, tx - 1, ty - 1, tx + 2, ty + 2, CORAL, breathe)
        Draw.px(buf, tx, ty, 255, 255, 255, breathe)
        if (flash > 0) {
            Draw.rect(buf, tx - 2, ty - 2, tx + 3, ty + 3, GOOD, (flash / 3).toFloat())
            flash -= 1
        }
        if (missFlash > 0) {
            for (x in 0 until Draw.W) {
                Draw.px(buf, x, 0, BAD[0], BAD[1], BAD[2], 0.5f)
            }
            missFlash -= 1
        }
    }
}

// ── 2. HOLD STEADY (guard) ──────────────────────────────────────────────

/** pure scoring core: time-in-band + steadiness (JVM-tested) */
class BandTracker(private val halfWidth: Float = 0.12f) {
    var inBandMs = 0L; private set
    var totalMs = 0L; private set
    private var n = 0; private var mean = 0f; private var m2 = 0f

    fun update(z: Float, center: Float, dtMs: Long) {
        totalMs += dtMs
        if (abs(z - center) <= halfWidth) inBandMs += dtMs
        n++
        val d = z - mean; mean += d / n; m2 += d * (z - mean)
    }

    val steadiness: Float
        get() = if (n < 2) 0f
            else 1f - min(1f, kotlin.math.sqrt(m2 / (n - 1)) * 6f)
    val fraction: Float get() = if (totalMs == 0L) 0f else inBandMs.toFloat() / totalMs
}

class HoldSteady(onEnd: (Int, Int, Boolean) -> Unit = { _, _, _ -> }) :
    MiniGame("guard", xpPerPoint = 1f, onEnd = onEnd) {

    private val tracker = BandTracker()
    // This block reports no continuous pressure, so "hold steady" is a
    // POSITION game: keep your finger at the drifting target HEIGHT and hold
    // it there. `level` is normalised finger height (top of pad = 1),
    // -1 = not touching.
    @Volatile private var level = -1f
    private var lastT = -1.0
    private var scored = 0

    override fun onTouch(ev: TouchEvent) {
        level = if (ev.phase == TouchPhase.END) -1f
                else (1f - ev.y / 15f).coerceIn(0f, 1f)
    }

    /** target band center drifts slowly: keep your finger at 0.3..0.8 height */
    internal fun bandCenter(tPlay: Double): Float =
        (0.55 + 0.25 * sin(tPlay * 2 * PI / 11)).toFloat()

    override fun renderPlay(buf: ByteArray, tPlay: Double) {
        val dt = if (lastT < 0) 0L else ((tPlay - lastT) * 1000).toLong()
        lastT = tPlay
        val center = bandCenter(tPlay)
        val cur = level
        if (cur >= 0 && dt > 0) {
            tracker.update(cur, center, dt)
            // 1 point per ~600ms held on the target line
            val target = (tracker.inBandMs / 600).toInt()
            while (scored < target) { addScore(1); scored++ }
        }
        // gauge: height 0..1 maps bottom→top over rows 13..1
        fun rowFor(v: Float) = (13 - v * 12).toInt().coerceIn(1, 13)
        val bandTop = rowFor(center + 0.12f)
        val bandBot = rowFor(center - 0.12f)
        val inBand = cur >= 0 && abs(cur - center) <= 0.12f
        for (y in bandTop..bandBot)
            for (x in 5..9)
                Draw.px(buf, x, y, GOOD[0], GOOD[1], GOOD[2],
                    if (inBand) 0.55f else 0.25f)
        if (cur >= 0) {
            val py = rowFor(cur)
            Draw.rect(buf, 4, py, 11, py + 1, if (inBand) GOOD else CORAL)
        } else {
            Draw.textCenter(buf, "-", 6, DIMW)
        }
    }

    override fun onGesture(g: Gesture) {
        if (playing && g is Gesture.Hold && !g.ongoing) learn(g)
    }
}

// ── 3. CHASE THE DOT (finesse) ──────────────────────────────────────────

/** pure path + error core (JVM-tested) */
object Chase {
    /** Lissajous path inside the pad; speed ramps over the round */
    fun dotAt(tPlay: Double): Pair<Float, Float> {
        val ramp = 1.0 + tPlay / 20.0            // 1x → 2.5x speed
        val u = tPlay * ramp
        val x = 7.0 + 5.2 * sin(u * 0.9)
        val y = 7.0 + 5.2 * sin(u * 0.63 + PI / 3)
        return x.toFloat() to y.toFloat()
    }
}

class ChaseDot(onEnd: (Int, Int, Boolean) -> Unit = { _, _, _ -> }) :
    MiniGame("finesse", xpPerPoint = 0.9f, onEnd = onEnd) {

    @Volatile private var fx = -1f
    @Volatile private var fy = -1f
    private var onDotMs = 0L
    private var lastT = -1.0
    private var scored = 0
    private var swipes = 0

    override fun onTouch(ev: TouchEvent) {
        if (ev.phase == TouchPhase.END) { fx = -1f; fy = -1f }
        else { fx = ev.x; fy = ev.y }
    }

    override fun onGesture(g: Gesture) {
        // long tracking swipes are exactly finesse — teach them
        if (playing && g is Gesture.Swipe && swipes < 40) { swipes++; learn(g) }
    }

    override fun renderPlay(buf: ByteArray, tPlay: Double) {
        val dt = if (lastT < 0) 0L else ((tPlay - lastT) * 1000).toLong()
        lastT = tPlay
        val (dx, dy) = Chase.dotAt(tPlay)
        val near = fx >= 0 && hypot(fx - dx, fy - dy) < 1.8f
        if (near && dt > 0) {
            onDotMs += dt
            val target = (onDotMs / 500).toInt()   // 1 pt per 500ms on the dot
            while (scored < target) { addScore(1); scored++ }
        }
        // trail of the immediate future path — a hint of where it's going
        for (k in 1..3) {
            val (hx, hy) = Chase.dotAt(tPlay + k * 0.12)
            Draw.px(buf, hx.toInt(), hy.toInt(), DIMW[0], DIMW[1], DIMW[2],
                1f - k * 0.28f)
        }
        val c = if (near) GOOD else CORAL
        Draw.px(buf, dx.toInt(), dy.toInt(), c[0], c[1], c[2])
        Draw.px(buf, dx.toInt() + 1, dy.toInt(), c[0], c[1], c[2], 0.4f)
        Draw.px(buf, dx.toInt() - 1, dy.toInt(), c[0], c[1], c[2], 0.4f)
        Draw.px(buf, dx.toInt(), dy.toInt() + 1, c[0], c[1], c[2], 0.4f)
        Draw.px(buf, dx.toInt(), dy.toInt() - 1, c[0], c[1], c[2], 0.4f)
    }
}

// ── 4. BEAT KEEPER (stamina) ────────────────────────────────────────────

/** pure rhythm judge (JVM-tested): BPM ramps linearly over the round */
class RhythmJudge(
    private val bpmStart: Double = 90.0,
    private val bpmEnd: Double = 150.0,
    private val roundS: Double = MiniGame.PLAY_S,
) {
    enum class Verdict { PERFECT, GOOD, MISS }

    fun bpmAt(t: Double): Double =
        bpmStart + (bpmEnd - bpmStart) * (t / roundS).coerceIn(0.0, 1.0)

    /** phase 0..1 within the current beat (integral of ramping tempo) */
    fun beatPhase(t: Double): Double {
        val tc = t.coerceIn(0.0, roundS)
        val beats = (bpmStart * tc + (bpmEnd - bpmStart) * tc * tc / (2 * roundS)) / 60.0
        return beats - kotlin.math.floor(beats)
    }

    /** distance to the nearest beat, in ms */
    fun offsetMs(t: Double): Double {
        val phase = beatPhase(t)
        val beatMs = 60_000.0 / bpmAt(t)
        val d = min(phase, 1 - phase)
        return d * beatMs
    }

    fun judge(t: Double): Verdict {
        val off = offsetMs(t)
        return when {
            off <= 90 -> Verdict.PERFECT
            off <= 180 -> Verdict.GOOD
            else -> Verdict.MISS
        }
    }
}

class RhythmTaps(onEnd: (Int, Int, Boolean) -> Unit = { _, _, _ -> }) :
    MiniGame("stamina", xpPerPoint = 0.8f, onEnd = onEnd) {

    private val judge = RhythmJudge()
    @Volatile private var combo = 0
    @Volatile private var lastVerdict: RhythmJudge.Verdict? = null
    @Volatile private var verdictFlash = 0.0
    @Volatile private var tNow = 0.0

    override fun onGesture(g: Gesture) {
        if (!playing) return
        val tap = when (g) {
            is Gesture.Tap -> g.z
            is Gesture.Strike -> g.velocity
            else -> return
        }
        val v = judge.judge(tNow)
        lastVerdict = v
        verdictFlash = 4.0
        when (v) {
            RhythmJudge.Verdict.PERFECT -> {
                combo++
                addScore(3 * (1 + combo / 5))
                learn(Gesture.Strike(7f, 7f, tap.coerceAtLeast(0.3f), tap))
            }
            RhythmJudge.Verdict.GOOD -> {
                combo++
                addScore(1 * (1 + combo / 5))
            }
            RhythmJudge.Verdict.MISS -> combo = 0
        }
    }

    override fun renderPlay(buf: ByteArray, tPlay: Double) {
        tNow = tPlay
        // border pulses on the beat
        val phase = judge.beatPhase(tPlay)
        val pulse = (1.0 - min(phase, 1 - phase) * 4).coerceIn(0.0, 1.0).toFloat()
        for (x in 0 until Draw.W) {
            Draw.px(buf, x, 0, CORAL[0], CORAL[1], CORAL[2], pulse)
            Draw.px(buf, x, Draw.H - 2, CORAL[0], CORAL[1], CORAL[2], pulse * 0.6f)
        }
        for (y in 0 until Draw.H - 1) {
            Draw.px(buf, 0, y, CORAL[0], CORAL[1], CORAL[2], pulse)
            Draw.px(buf, Draw.W - 1, y, CORAL[0], CORAL[1], CORAL[2], pulse)
        }
        if (verdictFlash > 0) {
            val v = lastVerdict
            val (s, c) = when (v) {
                RhythmJudge.Verdict.PERFECT -> "!" to GOOD
                RhythmJudge.Verdict.GOOD -> "OK" to CORAL
                else -> "X" to BAD
            }
            Draw.textCenter(buf, s, 5, c, (verdictFlash / 4).toFloat())
            verdictFlash -= 1
        }
        if (combo >= 5) Draw.textCenter(buf, "$combo", 11, GOOD, 0.7f)
    }
}
