package dev.clawdpad.host

import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Duelist's Hands — the battle engine. Pure Kotlin, deterministic given
 * seeded RNGs: the same seed and combatant scripts replay tick-for-tick
 * (BattleSimTest holds this). One tick ≈ 80ms (the 12fps live loop).
 *
 * Design pillar: every attack TELEGRAPHS for a few ticks before landing.
 * That's the BLE-latency compensator — players react to telegraphs, not
 * to instants — and it's what makes guard/dodge timing a real skill.
 */

enum class Move { STRIKE, GUARD, DASH, CHARGE, SUPER }

/** one intent from a combatant; magnitude 0..1 scales the effect */
data class Action(val move: Move, val magnitude: Float)

/** read-only battlefield snapshot an AI thinks about */
data class BattleView(
    val myHp: Float, val myEnergy: Float,
    val foeHp: Float, val foeEnergy: Float,
    /** ticks until the foe's pending attack lands; 0 = no attack pending */
    val foeTelegraph: Int,
    val foeGuarding: Boolean,
    val tickNo: Long,
)

interface Combatant {
    val stats: Stats
    /** one action per tick, or null to do nothing */
    fun act(view: BattleView, tickNo: Long): Action?
    /** live-gesture implementations queue input here */
    fun offer(g: Gesture) {}
}

/** the human side: gestures become actions. accepts() lets a future
 *  split-pad mode filter by gesture origin. */
class GestureCombatant(
    override val stats: Stats,
    private val accepts: (Gesture) -> Boolean = { true },
) : Combatant {
    private val queue = ConcurrentLinkedQueue<Action>()
    @Volatile private var holdOngoing = false
    @Volatile private var holdMagnitude = 0f

    override fun offer(g: Gesture) {
        if (!accepts(g)) return
        when (g) {
            is Gesture.Strike -> queue.add(Action(Move.STRIKE, g.velocity))
            is Gesture.Hold -> {
                holdOngoing = g.ongoing
                holdMagnitude = (g.firmness * 0.5f + g.steadiness * 0.5f)
            }
            is Gesture.Swipe ->
                queue.add(Action(Move.DASH, (g.speed / 60f).coerceIn(0.1f, 1f)))
            is Gesture.Scrub ->
                queue.add(Action(Move.CHARGE, (g.revolutions / 3f).coerceIn(0.1f, 1f)))
            else -> {}
        }
    }

    override fun act(view: BattleView, tickNo: Long): Action? {
        // sustained pressure = sustained guard (refreshed every tick)
        queue.poll()?.let { return it }
        if (holdOngoing) return Action(Move.GUARD, holdMagnitude)
        return null
    }
}

/**
 * The autonomous side. Fed a StyleProfile it samples move magnitudes and
 * aggression from — give it YOUR profile and it fights like you trained
 * it (the shadow match), give it a ladder personality and it's a rival.
 */
class AiCombatant(
    override val stats: Stats,
    private val style: StyleProfile,
    private val rng: Random,
) : Combatant {
    private var nextThink = 0L
    private var guardTicks = 0

    override fun act(view: BattleView, tickNo: Long): Action? {
        // danger overrides the think timer: react to a telegraph
        if (view.foeTelegraph in 1..3 && guardTicks == 0) {
            val reflex = 0.35f + stats.finesse * 0.05f
            if (rng.nextFloat() < reflex) {
                return if (stats.finesse > stats.guard && view.myEnergy >= 10f) {
                    Action(Move.DASH,
                        style.channel(StyleProfile.Kind.SWIPE).sample(rng))
                } else {
                    guardTicks = 4 + rng.nextInt(4)
                    Action(Move.GUARD,
                        style.channel(StyleProfile.Kind.HOLD).sample(rng))
                }
            }
        }
        if (guardTicks > 0) {
            guardTicks--
            return Action(Move.GUARD,
                style.channel(StyleProfile.Kind.HOLD).sample(rng))
        }
        if (tickNo < nextThink) return null
        nextThink = tickNo + 4 + rng.nextInt(8)      // think 320..880ms
        return when {
            view.myEnergy >= BattleEngine.SUPER_COST &&
                rng.nextFloat() < 0.6f ->
                Action(Move.SUPER, style.channel(StyleProfile.Kind.STRIKE).sample(rng))
            view.myEnergy >= BattleEngine.STRIKE_COST &&
                rng.nextFloat() < style.aggression.coerceIn(0.15f, 0.9f) ->
                Action(Move.STRIKE, style.channel(StyleProfile.Kind.STRIKE).sample(rng))
            view.myEnergy < 35f && rng.nextFloat() < 0.5f ->
                Action(Move.CHARGE, style.channel(StyleProfile.Kind.SCRUB).sample(rng))
            else -> null
        }
    }
}

class BattleEngine(
    private val left: Combatant,
    private val right: Combatant,
    private val onEvent: (String) -> Unit = {},
) {
    companion object {
        const val MAX_HP = 100f
        const val MAX_ENERGY = 100f
        const val STRIKE_COST = 20f
        const val DASH_COST = 10f
        const val SUPER_COST = 100f
        const val GUARD_DRAIN = 0.8f
        const val TELEGRAPH_TICKS = 3
        const val SUPER_TELEGRAPH = 7
        const val GUARD_TTL = 3
        const val PARRY_WINDOW = 2
        const val STAGGER_TICKS = 5
    }

    class Side(val name: String, val stats: Stats) {
        var hp = MAX_HP
        var energy = MAX_ENERGY / 2
        var guardTtl = 0                 // >0 = guarding
        var guardMagnitude = 0f
        var guardAge = 0                 // ticks since guard went up
        var telegraph = 0                // ticks until pending attack lands
        var pending: Action? = null
        var dodge = 0                    // active dodge window, ticks
        var stagger = 0                  // parried: can't act
        // render hints (engine writes, scene reads)
        var lunge = 0
        var hit = 0
        var superFlash = 0
        val guarding: Boolean get() = guardTtl > 0
    }

    val l = Side("L", left.stats)
    val r = Side("R", right.stats)
    var tickNo = 0L
        private set
    var winner: Side? = null
        private set
    val over: Boolean get() = winner != null

    private fun view(me: Side, foe: Side) = BattleView(
        me.hp, me.energy, foe.hp, foe.energy, foe.telegraph, foe.guarding, tickNo)

    fun tick() {
        if (over) return
        tickNo++
        // regen: stamina feeds the pool
        for (s in listOf(l, r)) {
            s.energy = (s.energy + 0.5f + s.stats.stamina * 0.05f)
                .coerceAtMost(MAX_ENERGY)
            if (s.guardTtl > 0) {
                s.guardTtl--
                s.guardAge++
                s.energy = (s.energy - GUARD_DRAIN).coerceAtLeast(0f)
                if (s.energy <= 0f) s.guardTtl = 0
            } else s.guardAge = 0
            if (s.dodge > 0) s.dodge--
            if (s.stagger > 0) s.stagger--
            if (s.lunge > 0) s.lunge--
            if (s.hit > 0) s.hit--
            if (s.superFlash > 0) s.superFlash--
        }
        // intents
        apply(l, r, if (l.stagger > 0) null else left.act(view(l, r), tickNo))
        apply(r, l, if (r.stagger > 0) null else right.act(view(r, l), tickNo))
        // pending attacks march toward landing
        resolve(l, r)
        resolve(r, l)
        // KO?
        if (l.hp <= 0f || r.hp <= 0f) {
            winner = if (l.hp > 0f) l else r
            onEvent("${winner!!.name} wins")
        }
    }

    private fun apply(me: Side, foe: Side, a: Action?) {
        a ?: return
        when (a.move) {
            Move.STRIKE -> if (me.telegraph == 0 && me.energy >= STRIKE_COST) {
                me.energy -= STRIKE_COST
                me.telegraph = TELEGRAPH_TICKS
                me.pending = a
                onEvent("${me.name} winds up")
            }
            Move.SUPER -> if (me.telegraph == 0 && me.energy >= SUPER_COST) {
                me.energy = 0f
                me.telegraph = SUPER_TELEGRAPH
                me.pending = a
                me.superFlash = SUPER_TELEGRAPH
                onEvent("${me.name} SUPER charge")
            }
            Move.GUARD -> {
                if (me.guardTtl == 0) onEvent("${me.name} guards")
                me.guardTtl = GUARD_TTL
                me.guardMagnitude = a.magnitude
            }
            Move.DASH -> if (me.energy >= DASH_COST && me.dodge == 0) {
                me.energy -= DASH_COST
                me.dodge = 3 + me.stats.finesse / 3
                onEvent("${me.name} dashes")
            }
            Move.CHARGE -> {
                val gain = 12f + a.magnitude * 30f
                me.energy = (me.energy + gain).coerceAtMost(MAX_ENERGY)
                onEvent("${me.name} charges +${gain.toInt()}")
            }
        }
    }

    private fun resolve(attacker: Side, defender: Side) {
        if (attacker.telegraph == 0) return
        attacker.telegraph--
        if (attacker.telegraph > 0) return
        val a = attacker.pending ?: return
        attacker.pending = null
        attacker.lunge = 2
        val isSuper = a.move == Move.SUPER
        val base = if (isSuper) 34f else 12f
        var dmg = base * (0.6f + 0.4f * attacker.stats.power / Stats.STAT_CAP) *
                (0.5f + a.magnitude)
        when {
            defender.dodge > 0 && !isSuper -> {
                onEvent("${defender.name} evades")
                return
            }
            defender.dodge > 0 && isSuper -> dmg *= 0.5f
            defender.guarding -> {
                // guard raised just as the blow lands = PARRY
                if (defender.guardAge <= PARRY_WINDOW &&
                    defender.guardMagnitude >= 0.5f && !isSuper) {
                    attacker.stagger = STAGGER_TICKS
                    attacker.hit = 3
                    defender.energy = (defender.energy + 15f)
                        .coerceAtMost(MAX_ENERGY)
                    onEvent("${defender.name} PARRIES")
                    return
                }
                val block = 0.35f + 0.35f *
                        (defender.guardMagnitude * defender.stats.guard /
                                Stats.STAT_CAP.toFloat()).coerceIn(0f, 1f)
                dmg *= (1f - block)
            }
        }
        defender.hp = (defender.hp - dmg).coerceAtLeast(0f)
        defender.hit = 3
        onEvent("${attacker.name} ${if (isSuper) "SUPER " else ""}hits ${dmg.toInt()}")
    }
}
