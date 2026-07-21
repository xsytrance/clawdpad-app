package dev.clawdpad.host

import java.util.Random

/**
 * A real (Gen-III accurate) Pokémon battle core — stats, type chart, damage
 * formula, moves, and an autonomous move-picking AI. Pure Kotlin, JVM-testable.
 *
 * This is the "facts are sacred" engine: given a Mon's real stats (which will
 * come from parsed save data — for now a sample roster with accurate base
 * stats), battles resolve by the actual Gen-III math. No human control; the
 * two snapped blocks just watch their Pokémon fight for real.
 *
 * Gen-III conventions on purpose: 17 types (no Fairy), and the
 * physical/special split is by TYPE, not per-move.
 */

enum class PType {
    NORMAL, FIRE, WATER, ELECTRIC, GRASS, ICE, FIGHTING, POISON, GROUND,
    FLYING, PSYCHIC, BUG, ROCK, GHOST, DRAGON, DARK, STEEL;

    /** Gen-III physical/special split is by the move's type */
    val physical: Boolean
        get() = this in PHYSICAL

    companion object {
        private val PHYSICAL = setOf(NORMAL, FIGHTING, FLYING, GROUND, ROCK, BUG, GHOST, POISON, STEEL)

        /** super-effective / not-very / no-effect tables (attacker -> defenders) */
        private val SE = mapOf(
            FIRE to setOf(GRASS, ICE, BUG, STEEL),
            WATER to setOf(FIRE, GROUND, ROCK),
            ELECTRIC to setOf(WATER, FLYING),
            GRASS to setOf(WATER, GROUND, ROCK),
            ICE to setOf(GRASS, GROUND, FLYING, DRAGON),
            FIGHTING to setOf(NORMAL, ICE, ROCK, DARK, STEEL),
            POISON to setOf(GRASS),
            GROUND to setOf(FIRE, ELECTRIC, POISON, ROCK, STEEL),
            FLYING to setOf(GRASS, FIGHTING, BUG),
            PSYCHIC to setOf(FIGHTING, POISON),
            BUG to setOf(GRASS, PSYCHIC, DARK),
            ROCK to setOf(FIRE, ICE, FLYING, BUG),
            GHOST to setOf(PSYCHIC, GHOST),
            DRAGON to setOf(DRAGON),
            DARK to setOf(PSYCHIC, GHOST),
            STEEL to setOf(ICE, ROCK),
        )
        private val NVE = mapOf(
            NORMAL to setOf(ROCK, STEEL),
            FIRE to setOf(FIRE, WATER, ROCK, DRAGON),
            WATER to setOf(WATER, GRASS, DRAGON),
            ELECTRIC to setOf(ELECTRIC, GRASS, DRAGON),
            GRASS to setOf(FIRE, GRASS, POISON, FLYING, BUG, DRAGON, STEEL),
            ICE to setOf(FIRE, WATER, ICE, STEEL),
            FIGHTING to setOf(POISON, FLYING, PSYCHIC, BUG),
            POISON to setOf(POISON, GROUND, ROCK, GHOST),
            GROUND to setOf(GRASS, BUG),
            FLYING to setOf(ELECTRIC, ROCK, STEEL),
            PSYCHIC to setOf(PSYCHIC, STEEL),
            BUG to setOf(FIRE, FIGHTING, POISON, FLYING, GHOST, STEEL),
            ROCK to setOf(FIGHTING, GROUND, STEEL),
            GHOST to setOf(DARK, STEEL),
            DARK to setOf(FIGHTING, DARK, STEEL),
            STEEL to setOf(FIRE, WATER, ELECTRIC, STEEL),
        )
        private val IMMUNE = mapOf(
            NORMAL to setOf(GHOST),
            ELECTRIC to setOf(GROUND),
            FIGHTING to setOf(GHOST),
            POISON to setOf(STEEL),
            GROUND to setOf(FLYING),
            PSYCHIC to setOf(DARK),
            GHOST to setOf(NORMAL),
        )

        fun effectiveness(atk: PType, def: PType): Float = when (def) {
            in (IMMUNE[atk] ?: emptySet()) -> 0f
            in (SE[atk] ?: emptySet()) -> 2f
            in (NVE[atk] ?: emptySet()) -> 0.5f
            else -> 1f
        }
    }

    /** LED tint for this type (original palette — no ripped assets) */
    val color: IntArray
        get() = when (this) {
            NORMAL -> intArrayOf(190, 180, 160); FIRE -> intArrayOf(240, 110, 60)
            WATER -> intArrayOf(80, 150, 240); ELECTRIC -> intArrayOf(245, 210, 70)
            GRASS -> intArrayOf(110, 200, 100); ICE -> intArrayOf(130, 215, 225)
            FIGHTING -> intArrayOf(200, 90, 90); POISON -> intArrayOf(170, 90, 190)
            GROUND -> intArrayOf(205, 165, 100); FLYING -> intArrayOf(150, 175, 235)
            PSYCHIC -> intArrayOf(245, 110, 165); BUG -> intArrayOf(165, 200, 70)
            ROCK -> intArrayOf(185, 160, 110); GHOST -> intArrayOf(120, 100, 175)
            DRAGON -> intArrayOf(110, 110, 235); DARK -> intArrayOf(110, 95, 90)
            STEEL -> intArrayOf(180, 190, 205)
        }
}

/** power 0 = a status/no-damage move (unused for now); accuracy 0..100 */
data class PokeMove(val name: String, val type: PType, val power: Int, val accuracy: Int) {
    val physical get() = type.physical
}

data class Species(
    val id: String, val name: String,
    val t1: PType, val t2: PType?,
    val hp: Int, val atk: Int, val def: Int, val spa: Int, val spd: Int, val spe: Int,
    val moves: List<PokeMove>,
)

/** a battle-ready Pokémon: real computed Gen-III stats at a level */
class Mon(val species: Species, val level: Int = 50,
          private val iv: Int = 31, private val ev: Int = 0) {
    // Gen-III stat formulas (neutral nature; save data will supply real IV/EV/nature)
    private fun stat(base: Int) = (((2 * base + iv + ev / 4) * level) / 100) + 5
    val maxHp = (((2 * species.hp + iv + ev / 4) * level) / 100) + level + 10
    val atk = stat(species.atk); val def = stat(species.def)
    val spa = stat(species.spa); val spd = stat(species.spd)
    val spe = stat(species.spe)
    var hp = maxHp
    val name get() = species.name
    val fainted get() = hp <= 0
    fun hpFrac() = (hp.toFloat() / maxHp).coerceIn(0f, 1f)

    fun typeEff(move: PokeMove): Float {
        var m = PType.effectiveness(move.type, species.t1)
        species.t2?.let { m *= PType.effectiveness(move.type, it) }
        return m
    }
}

/** one resolved action for the battle scene to animate */
data class BattleEvent(
    val attackerIsLeft: Boolean,
    val move: PokeMove,
    val missed: Boolean,
    val damage: Int,
    val effectiveness: Float,
    val crit: Boolean,
    val defenderHpAfter: Int,
    val defenderFainted: Boolean,
)

/** the autonomous battle: real Gen-III math, AI on both sides, no human input */
class PokeBattle(val left: Mon, val right: Mon, seed: Long = 12345L) {
    private val rng = Random(seed)
    var over = false; private set
    var leftWon = false; private set
    private var order = listOf<Boolean>()   // true = left acts
    private var oi = 0

    private fun rollOrder() {
        val lf = left.spe; val rf = right.spe
        val leftFirst = if (lf != rf) lf > rf else rng.nextBoolean()
        order = if (leftFirst) listOf(true, false) else listOf(false, true)
        oi = 0
    }

    /** damage per the Gen-III formula (single hit) */
    private fun computeDamage(attacker: Mon, defender: Mon, move: PokeMove): Triple<Int, Float, Boolean> {
        if (move.power <= 0) return Triple(0, 1f, false)
        val eff = defender.typeEff(move)
        if (eff == 0f) return Triple(0, 0f, false)
        val a = if (move.physical) attacker.atk else attacker.spa
        val d = if (move.physical) defender.def else defender.spd
        val crit = rng.nextInt(16) == 0                       // ~1/16
        var dmg = (((2 * attacker.level) / 5 + 2) * move.power * a / d) / 50 + 2
        if (crit) dmg *= 2
        val stab = if (move.type == attacker.species.t1 || move.type == attacker.species.t2) 1.5f else 1f
        val rand = (85 + rng.nextInt(16)) / 100f               // 0.85..1.00
        dmg = (dmg * stab * eff * rand).toInt().coerceAtLeast(1)
        return Triple(dmg, eff, crit)
    }

    /** simple but real AI: pick the move that deals the most expected damage */
    private fun chooseMove(attacker: Mon, defender: Mon): PokeMove {
        var best = attacker.species.moves[0]; var bestScore = -1f
        for (m in attacker.species.moves) {
            val eff = defender.typeEff(m)
            val a = if (m.physical) attacker.atk else attacker.spa
            val d = if (m.physical) defender.def else defender.spd
            val stab = if (m.type == attacker.species.t1 || m.type == attacker.species.t2) 1.5f else 1f
            val score = m.power * (a.toFloat() / d) * stab * eff * (m.accuracy / 100f) *
                (0.9f + rng.nextFloat() * 0.2f)               // jitter so it's not fully deterministic
            if (score > bestScore) { bestScore = score; best = m }
        }
        return best
    }

    /** execute the next single action; null when the battle is over */
    fun step(): BattleEvent? {
        if (over) return null
        if (oi >= order.size) rollOrder()
        val leftActs = order[oi]; oi++
        val attacker = if (leftActs) left else right
        val defender = if (leftActs) right else left
        if (attacker.fainted) return step()                    // skip a fainted actor

        val move = chooseMove(attacker, defender)
        val hit = rng.nextInt(100) < move.accuracy
        if (!hit) {
            return BattleEvent(leftActs, move, missed = true, 0, 1f, false, defender.hp, false)
        }
        val (dmg, eff, crit) = computeDamage(attacker, defender, move)
        defender.hp = (defender.hp - dmg).coerceAtLeast(0)
        val fainted = defender.fainted
        if (fainted) { over = true; leftWon = leftActs }
        return BattleEvent(leftActs, move, false, dmg, eff, crit, defender.hp, fainted)
    }
}

/** sample roster with ACCURATE Gen-III base stats + real moves (until save
 *  data supplies the player's actual team) */
object Pokedex {
    private fun m(n: String, t: PType, p: Int, a: Int = 100) = PokeMove(n, t, p, a)
    val ALL: List<Species> = listOf(
        Species("charizard", "CHARIZARD", PType.FIRE, PType.FLYING, 78, 84, 78, 109, 85, 100,
            listOf(m("FLAMETHROWER", PType.FIRE, 95), m("WING ATTACK", PType.FLYING, 60),
                m("SLASH", PType.NORMAL, 70), m("EARTHQUAKE", PType.GROUND, 100))),
        Species("blastoise", "BLASTOISE", PType.WATER, null, 79, 83, 100, 85, 105, 78,
            listOf(m("SURF", PType.WATER, 95), m("ICE BEAM", PType.ICE, 95),
                m("BODY SLAM", PType.NORMAL, 85), m("EARTHQUAKE", PType.GROUND, 100))),
        Species("venusaur", "VENUSAUR", PType.GRASS, PType.POISON, 80, 82, 83, 100, 100, 80,
            listOf(m("RAZOR LEAF", PType.GRASS, 55, 95), m("SLUDGE BOMB", PType.POISON, 90),
                m("BODY SLAM", PType.NORMAL, 85), m("EARTHQUAKE", PType.GROUND, 100))),
        Species("pikachu", "PIKACHU", PType.ELECTRIC, null, 35, 55, 40, 50, 50, 90,
            listOf(m("THUNDERBOLT", PType.ELECTRIC, 95), m("IRON TAIL", PType.STEEL, 100, 75),
                m("QUICK ATTACK", PType.NORMAL, 40), m("SLASH", PType.NORMAL, 70))),
        Species("gengar", "GENGAR", PType.GHOST, PType.POISON, 60, 65, 60, 130, 75, 110,
            listOf(m("SHADOW BALL", PType.GHOST, 80), m("SLUDGE BOMB", PType.POISON, 90),
                m("THUNDERBOLT", PType.ELECTRIC, 95), m("PSYCHIC", PType.PSYCHIC, 90))),
        Species("gyarados", "GYARADOS", PType.WATER, PType.FLYING, 95, 125, 79, 60, 100, 81,
            listOf(m("SURF", PType.WATER, 95), m("EARTHQUAKE", PType.GROUND, 100),
                m("ICE BEAM", PType.ICE, 95), m("BODY SLAM", PType.NORMAL, 85))),
        Species("alakazam", "ALAKAZAM", PType.PSYCHIC, null, 55, 50, 45, 135, 95, 120,
            listOf(m("PSYCHIC", PType.PSYCHIC, 90), m("SHADOW BALL", PType.GHOST, 80),
                m("ICE PUNCH", PType.ICE, 75), m("THUNDER PUNCH", PType.ELECTRIC, 75))),
        Species("machamp", "MACHAMP", PType.FIGHTING, null, 90, 130, 80, 65, 85, 55,
            listOf(m("CROSS CHOP", PType.FIGHTING, 100, 80), m("EARTHQUAKE", PType.GROUND, 100),
                m("ROCK SLIDE", PType.ROCK, 75, 90), m("BODY SLAM", PType.NORMAL, 85))),
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[0]
    fun random(rng: Random) = ALL[rng.nextInt(ALL.size)]
}
