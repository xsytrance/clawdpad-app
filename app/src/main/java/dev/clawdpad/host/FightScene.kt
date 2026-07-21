package dev.clawdpad.host

import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CLAWD COMBAT — the arcade brawler. Two block-creatures beat each other
 * apart limb from limb on the 15x15 glass. Training mode: you (left) vs an
 * indestructible sparring dummy (right) that dismembers gloriously and then
 * bolts itself back together so you can keep practising.
 *
 * Gestures → moves (see the table in the app):
 *   hard tap        → JAB at the tapped height (high/mid/low)
 *   swipe toward    → LUNGE (heavy)         swipe up → UPPERCUT (pops the head)
 *   swipe down      → SWEEP (topples legs)  swipe away → HOP BACK (dodge)
 *   hold            → BLOCK                 circle-scrub → charge SUPER
 *   super + attack  → FINISHER (total dismemberment)
 *
 * Rendering on the streamer thread; gestures on the keeper thread — shared
 * state is @Volatile or a concurrent queue.
 */
class FightScene(
    val playerId: String,
    private val onLog: (String) -> Unit = {},
) : Scene {

    private val rng = Random()
    private val debris = ArrayList<Debris>()
    private val gibs = ArrayList<Gib>()

    private val player = Brawler(Fighters.byId(playerId), facingRight = true,
        homeCx = 3, debris, gibs, rng)
    private val dummy = Brawler(Fighters.ROSTER.first { it.id == "tinbot" },
        facingRight = false, homeCx = 11, debris, gibs, rng)

    // ── shared input state ──────────────────────────────────────────────
    private val moves = ConcurrentLinkedQueue<Move>()
    @Volatile private var superMeter = 0f
    @Volatile private var wantGuard = false
    @Volatile private var guardStr = 0f

    // ── sim state (streamer thread) ─────────────────────────────────────
    private var ticked = 0L
    private var shake = 0                 // frames of screen-shake left
    private var shakeMag = 0
    private var hitstop = 0               // frames of freeze for impact
    private var flash = 0                 // white-out frames (finisher)
    private var phase = Phase.INTRO
    private var phaseAt = 0.0
    private var aiTimer = 40
    private var aiState = 0               // 0 idle, 1 telegraph, 2 swing
    @Volatile private var exit = false
    private var banner = ""
    private var bannerT = 0

    private enum class Phase { INTRO, FIGHT, FREEZE }

    companion object {
        const val TICK_S = 0.07
        const val INTRO_S = 1.4
        val WHITE = intArrayOf(255, 255, 255)
        val FLOOR = intArrayOf(46, 40, 52)
    }

    fun abort() { exit = true }
    override fun done(): Boolean = exit

    // ── input: classify gestures into queued moves ──────────────────────
    override fun onGesture(g: Gesture) {
        when (g) {
            is Gesture.Hold -> {
                wantGuard = g.ongoing
                guardStr = (0.35f + 0.65f * (g.firmness * 0.4f + g.steadiness * 0.6f))
                    .coerceIn(0.2f, 1f)
            }
            is Gesture.Scrub -> {
                superMeter = (superMeter + 0.34f).coerceAtMost(1f)
                moves.add(Move.Charge)
            }
            is Gesture.Strike -> moves.add(Move.Attack(regionForY(g.y), heavy = false, g.velocity))
            is Gesture.Tap -> moves.add(Move.Attack(regionForY(g.y), heavy = false, 0.3f))
            is Gesture.Swipe -> {
                val a = g.angleDeg
                val m = when {
                    a > 45 && a < 135 -> Move.Attack(Region.LOW, heavy = true, 0.9f)   // down → sweep
                    a < -45 && a > -135 -> Move.Attack(Region.HIGH, heavy = true, 0.9f) // up → uppercut
                    abs(a) <= 45 -> Move.Attack(Region.MID, heavy = true, 1f)           // toward → lunge
                    else -> Move.Dodge                                                   // away → hop back
                }
                moves.add(m)
            }
            else -> {}
        }
    }

    private fun regionForY(y: Float) = when {
        y < 6f -> Region.HIGH
        y < 10f -> Region.MID
        else -> Region.LOW
    }

    // ── the frame ───────────────────────────────────────────────────────
    override fun render(t: Double): ByteArray {
        val raw = ByteArray(Draw.W * Draw.H * 3)

        // advance the sim on the render clock
        val target = (t / TICK_S).toLong()
        while (ticked < target) { ticked++; step(t) }

        // background floor line
        for (x in 0 until Draw.W) Draw.px(raw, x, 11, FLOOR[0], FLOOR[1], FLOOR[2], 0.5f)

        if (flash > 0) { for (i in raw.indices) raw[i] = 240.toByte(); flash-- }

        // fighters (back-to-front) + particles
        player.render(t, raw)
        dummy.render(t, raw)
        for (d in debris) d.render(raw)
        for (gp in gibs) gp.render(raw)
        drawShield(raw)

        drawHud(raw)
        drawBanner(raw)

        return applyShake(raw)
    }

    private fun step(t: Double) {
        when (phase) {
            Phase.INTRO -> {
                if (banner.isEmpty()) { banner = "${player.kind.name}"; bannerT = 14 }
                if (t - phaseAt > INTRO_S) {
                    phase = Phase.FIGHT; phaseAt = t; setBanner("FIGHT!", 10)
                    onLog("training: ${player.kind.name} vs the dummy 🥊")
                }
                stepParticles(); decayTimers(); return
            }
            Phase.FREEZE -> {
                stepParticles(); decayTimers()
                if (t - phaseAt > 1.7) {
                    dummy.reassemble(); phase = Phase.FIGHT; phaseAt = t
                    setBanner("AGAIN!", 8)
                }
                return
            }
            Phase.FIGHT -> {}
        }

        if (hitstop > 0) { hitstop--; stepParticles(); return }

        // resolve queued player moves
        moves.poll()?.let { applyPlayerMove(it) }

        // dummy AI (gives you something to block / dodge)
        stepAi()

        player.guarding = wantGuard; player.guardMag = guardStr
        player.physics(); dummy.physics()
        stepParticles(); decayTimers()

        if (dummy.dead() && phase == Phase.FIGHT) {
            phase = Phase.FREEZE; phaseAt = t
            setBanner("K.O.!", 16); flash = 2; kick(6, 9)
        }
    }

    private fun applyPlayerMove(m: Move) {
        when (m) {
            is Move.Charge -> { setBanner("CHARGE", 5) }
            is Move.Dodge -> player.hop()
            is Move.Attack -> {
                if (dummy.dead()) return
                player.punch(m.region)
                val finisher = superMeter >= 1f && m.heavy
                if (finisher) {
                    superMeter = 0f
                    setBanner(player.kind.special, 18)
                    dummy.finisher()
                    flash = 2; kick(7, 12)
                    onLog("💥 ${player.kind.special}! the dummy is CONFETTI")
                } else {
                    val dmg = m.base() * player.kind.power
                    dummy.hurt(m.region, dmg)
                    superMeter = (superMeter + 0.05f).coerceAtMost(1f)
                    val impact = dummy.frontOf()
                    burst(impact[0], impact[1], dummy.kind.accent, if (m.heavy) 9 else 5)
                    kick(if (m.heavy) 4 else 2, if (m.heavy) 4 else 2)
                    hitstop = if (m.heavy) 3 else 2
                }
            }
        }
    }

    private fun stepAi() {
        if (dummy.dead()) return
        aiTimer--
        when (aiState) {
            0 -> if (aiTimer <= 0) { aiState = 1; aiTimer = 8; dummy.telegraph = 8 }
            1 -> if (aiTimer <= 0) {   // swing lands
                aiState = 2; aiTimer = 6; dummy.punch(Region.MID)
                when {
                    player.dodging() -> setBanner("WHIFF", 6)
                    player.guarding -> { burst(6f, 7f, FightScene.WHITE, 5); kick(2, 2); setBanner("BLOCK", 6) }
                    else -> { player.flinch(); superMeter = (superMeter - 0.1f).coerceAtLeast(0f); kick(3, 3) }
                }
            }
            else -> if (aiTimer <= 0) { aiState = 0; aiTimer = 30 + rng.nextInt(26) }
        }
    }

    // ── particles / juice ───────────────────────────────────────────────
    private fun burst(x: Float, y: Float, c: IntArray, n: Int) {
        repeat(n) {
            gibs.add(Gib(x, y,
                (rng.nextFloat() - 0.5f) * 2.4f,
                -rng.nextFloat() * 2.2f - 0.3f,
                c, 8 + rng.nextInt(8)))
        }
    }

    private fun stepParticles() {
        val di = debris.iterator()
        while (di.hasNext()) { val d = di.next(); if (!d.step()) di.remove() }
        val gi = gibs.iterator()
        while (gi.hasNext()) { val g = gi.next(); if (!g.step()) gi.remove() }
    }

    private fun decayTimers() {
        if (shake > 0) shake--
        if (bannerT > 0) bannerT--
    }

    private fun kick(mag: Int, frames: Int) { shakeMag = mag; shake = frames }
    private fun setBanner(s: String, frames: Int) { banner = s; bannerT = frames }

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

    // ── HUD ─────────────────────────────────────────────────────────────
    private fun drawHud(buf: ByteArray) {
        // dummy HP (torso integrity), right→left on row 0
        val hpf = dummy.torsoFrac()
        val hc = when { hpf > 0.5f -> intArrayOf(110, 220, 130); hpf > 0.25f -> ClawdRenderer.CORAL; else -> intArrayOf(210, 70, 60) }
        val n = (hpf * 13).roundToInt().coerceIn(0, 13)
        for (i in 0 until n) Draw.px(buf, 13 - i, 0, hc[0], hc[1], hc[2])
        // super meter, row 14, left→right, player accent
        val sc = player.kind.accent
        val sn = (superMeter * 13).roundToInt().coerceIn(0, 13)
        val ready = superMeter >= 1f
        for (i in 0 until sn) Draw.px(buf, i, 14, sc[0], sc[1], sc[2],
            if (ready && (ticked % 4 < 2)) 1f else 0.6f)
    }

    private fun drawBanner(buf: ByteArray) {
        if (bannerT <= 0 || banner.isEmpty()) return
        val c = if (banner == "FIGHT!" || banner.contains("K.O")) WHITE else player.kind.accent
        Draw.textCenter(buf, banner.take(5), 6, c, if (bannerT % 2 == 0) 1f else 0.75f)
    }

    private fun drawShield(buf: ByteArray) {
        if (!player.guarding) return
        val gx = player.cx() + 3
        for (y in 4..10) Draw.px(buf, gx, y, 90, 200, 230, 0.35f + 0.55f * player.guardMag)
    }

    // ── move model ──────────────────────────────────────────────────────
    private enum class Region { HIGH, MID, LOW }
    private sealed class Move {
        object Charge : Move()
        object Dodge : Move()
        data class Attack(val region: Region, val heavy: Boolean, val vel: Float) : Move() {
            fun base(): Float {
                val v = 0.6f + vel
                return when (region) {
                    Region.HIGH -> if (heavy) 5f else 2f * v   // uppercut / high jab
                    Region.MID -> if (heavy) 5.5f else 2f * v  // lunge / body jab
                    Region.LOW -> if (heavy) 5f else 2f * v    // sweep / low jab
                }
            }
        }
    }

    // ── the brawler: a detachable-part creature ─────────────────────────
    private enum class Part { HEAD, TORSO, ARM_F, ARM_B, LEG_L, LEG_R }

    private class Brawler(
        val kind: FighterKind,
        val facingRight: Boolean,
        val homeCx: Int,
        private val debris: MutableList<Debris>,
        private val gibs: MutableList<Gib>,
        private val rng: Random,
    ) {
        private val dir = if (facingRight) 1 else -1
        private val by = 3
        private val hp = FloatArray(Part.values().size) { maxHp(Part.values()[it]) }
        private val attached = BooleanArray(Part.values().size) { true }
        var cxf = homeCx.toFloat()
        private var punchT = 0f          // 1→0 over a punch
        private var punchRegion = Region.MID
        private var hurtT = 0f
        private var dodgeT = 0f
        var guarding = false
        var guardMag = 0f
        var telegraph = 0
        private var koT = 0f

        fun cx() = cxf.roundToInt()
        fun dead() = hp[Part.TORSO.ordinal] <= 0f
        fun dodging() = dodgeT > 0f
        fun torsoFrac() = (hp[Part.TORSO.ordinal] / maxHp(Part.TORSO)).coerceIn(0f, 1f)
        fun frontOf() = floatArrayOf((cx() + dir * 3).toFloat(), (by + 3).toFloat())

        fun physics() {
            cxf += (homeCx - cxf) * 0.35f
            if (punchT > 0f) punchT -= 0.34f
            if (hurtT > 0f) hurtT -= 0.25f
            if (dodgeT > 0f) dodgeT -= 0.2f
            if (telegraph > 0) telegraph--
            if (koT > 0f) koT -= 0.05f
        }

        fun punch(region: Region) { punchT = 1f; punchRegion = region; cxf += dir * 0.6f }
        fun hop() { dodgeT = 1f; cxf -= dir * 2.2f }
        fun flinch() { hurtT = 1f; cxf -= dir * 1.6f }

        fun hurt(region: Region, dmg: Float) {
            hurtT = 1f; cxf -= dir * 1.4f
            val target = when (region) {
                Region.HIGH -> if (attached[Part.HEAD.ordinal]) Part.HEAD else Part.TORSO
                Region.MID -> Part.TORSO
                Region.LOW -> firstAttached(Part.LEG_L, Part.LEG_R) ?: Part.TORSO
            }
            damage(target, dmg)
            if (target != Part.TORSO) damage(Part.TORSO, dmg * 0.35f)  // chip
        }

        private fun damage(part: Part, dmg: Float) {
            val i = part.ordinal
            if (!attached[i]) return
            hp[i] -= dmg
            if (hp[i] <= 0f && part != Part.TORSO) detach(part)
        }

        fun finisher() {
            hp[Part.TORSO.ordinal] = 0f; koT = 1f
            for (p in Part.values()) if (p != Part.TORSO && attached[p.ordinal]) detach(p, big = true)
            // blow the torso apart too, then hide it (reassemble restores it)
            explode(Part.TORSO, big = true)
            attached[Part.TORSO.ordinal] = false
            burst3(kind.accent, 22)
        }

        private fun detach(part: Part, big: Boolean = false) {
            attached[part.ordinal] = false
            explode(part, big)
        }

        private fun explode(part: Part, big: Boolean) {
            val px = partPixels(part, reachNow(), cx(), curBy())
            if (px.isEmpty()) return
            val col = bodyColor(part)
            val cxp = px.map { it[0] }.average().toFloat()
            val cyp = px.map { it[1] }.average().toFloat()
            val vx = dir * (0.6f + rng.nextFloat() * (if (big) 2.2f else 1.1f))
            val vy = -1.4f - rng.nextFloat() * (if (big) 2f else 1.2f)
            debris.add(Debris(px.map { intArrayOf(it[0] - cxp.roundToInt(), it[1] - cyp.roundToInt()) },
                cxp, cyp, vx, vy, col, rng))
            burst(cxp, cyp, kind.accent, if (big) 10 else 6)
        }

        private fun burst(x: Float, y: Float, c: IntArray, n: Int) = repeat(n) {
            gibs.add(Gib(x, y, (rng.nextFloat() - 0.5f) * 2.6f, -rng.nextFloat() * 2.4f - 0.2f, c, 8 + rng.nextInt(8)))
        }
        private fun burst3(c: IntArray, n: Int) = burst(cx().toFloat(), (by + 3).toFloat(), c, n)

        fun reassemble() {
            for (p in Part.values()) { hp[p.ordinal] = maxHp(p); attached[p.ordinal] = true }
            koT = 0f; hurtT = 0f; cxf = homeCx.toFloat()
        }

        private fun firstAttached(vararg ps: Part) = ps.firstOrNull { attached[it.ordinal] }
        private fun reachNow() = if (punchT > 0f) ((1f - abs(punchT - 0.5f) * 2f) * 3f).roundToInt() else 0
        private fun curBy(): Int = by + if (dead()) 3 else 0

        // ── geometry: absolute pixels for a part at the current pose ─────
        private fun partPixels(part: Part, reach: Int, cx: Int, byv: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            fun box(x0: Int, y0: Int, x1: Int, y1: Int) {
                for (y in y0..y1) for (x in x0..x1) out.add(intArrayOf(x, y))
            }
            when (part) {
                Part.HEAD -> box(cx - 1, byv, cx + 1, byv + 2)
                Part.TORSO -> box(cx - 1, byv + 3, cx + 1, byv + 5)
                Part.LEG_L -> box(cx - 1, byv + 6, cx - 1, byv + 7)
                Part.LEG_R -> box(cx + 1, byv + 6, cx + 1, byv + 7)
                Part.ARM_B -> box(cx - 2 * dir, byv + 3, cx - 2 * dir, byv + 4)
                Part.ARM_F -> {
                    val bx = cx + 2 * dir
                    val ex = cx + (2 + reach) * dir
                    box(minOf(bx, ex), byv + 3, maxOf(bx, ex), byv + 4)
                }
            }
            return out
        }

        private fun bodyColor(part: Part): IntArray {
            val t = kind.tint
            return if (part == Part.HEAD) intArrayOf((t[0] + 30).coerceAtMost(255), (t[1] + 30).coerceAtMost(255), (t[2] + 30).coerceAtMost(255)) else t
        }

        fun render(t: Double, buf: ByteArray) {
            val cx = cx()
            var byv = by
            val bright: Float
            when {
                dead() -> { byv += 3; bright = 0.3f }
                hurtT > 0f -> bright = if ((hurtT * 6).toInt() % 2 == 0) 1f else 0.45f
                telegraph > 0 -> bright = 0.55f + 0.45f * (telegraph % 2)
                else -> { byv -= if ((t * 3).toInt() % 2 == 0) 0 else 1; bright = 1f }  // idle bob
            }
            val reach = reachNow()
            // draw order: back arm, torso, legs, head, front arm, eyes
            for (part in arrayOf(Part.ARM_B, Part.TORSO, Part.LEG_L, Part.LEG_R, Part.HEAD, Part.ARM_F)) {
                if (!attached[part.ordinal]) continue
                val c = bodyColor(part)
                for (px in partPixels(part, reach, cx, byv))
                    Draw.px(buf, px[0], px[1], c[0], c[1], c[2], bright)
            }
            // eyes on the head, looking at the foe
            if (attached[Part.HEAD.ordinal] && !dead()) {
                val ey = byv + 1
                val e1 = if (facingRight) cx else cx - 1
                val e2 = if (facingRight) cx + 1 else cx
                Draw.px(buf, e1, ey, 20, 16, 22); Draw.px(buf, e2, ey, 20, 16, 22)
            } else if (dead() && attached[Part.HEAD.ordinal]) {
                // x_x eyes when down
                Draw.px(buf, cx - 1, byv + 1, 20, 16, 22); Draw.px(buf, cx + 1, byv + 1, 20, 16, 22)
            }
        }

        companion object {
            fun maxHp(p: Part) = when (p) {
                Part.HEAD -> 5f; Part.TORSO -> 16f
                Part.ARM_F, Part.ARM_B -> 4f; Part.LEG_L, Part.LEG_R -> 5f
            }
        }
    }

    // ── particles ───────────────────────────────────────────────────────
    /** a detached body part, tumbling off under gravity */
    private class Debris(
        private val shape: List<IntArray>,   // pixel offsets from center
        private var x: Float, private var y: Float,
        private var vx: Float, private var vy: Float,
        private val col: IntArray, private val rng: Random,
    ) {
        private var life = 26
        private val spin = (rng.nextFloat() - 0.5f) * 0.5f
        private var ang = 0f
        fun step(): Boolean {
            x += vx; y += vy; vy += 0.35f; ang += spin; life--
            return life > 0 && y < Draw.H + 3
        }
        fun render(buf: ByteArray) {
            val ox = x.roundToInt(); val oy = y.roundToInt()
            val wob = if (sin(ang.toDouble()) > 0) 1 else 0     // tumble wobble
            val b = 0.5f + 0.5f * cos(ang.toDouble()).toFloat()
            for (s in shape) Draw.px(buf, ox + s[0] + wob, oy + s[1], col[0], col[1], col[2],
                (0.6f + 0.4f * b).coerceIn(0.35f, 1f))
        }
    }

    /** a spray dot — cartoon "gore", tinted to the fighter's accent (silly, not gross) */
    private class Gib(
        private var x: Float, private var y: Float,
        private var vx: Float, private var vy: Float,
        private val col: IntArray, private var life: Int,
    ) {
        fun step(): Boolean {
            x += vx; y += vy; vy += 0.3f; life--
            return life > 0 && y < Draw.H + 2
        }
        fun render(buf: ByteArray) {
            val f = (life / 16f).coerceIn(0.3f, 1f)
            Draw.px(buf, x.roundToInt(), y.roundToInt(), col[0], col[1], col[2], f)
        }
    }
}
