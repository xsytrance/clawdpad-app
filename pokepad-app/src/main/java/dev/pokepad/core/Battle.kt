package dev.pokepad.core

/*
 * Poképad — full Gen-III battle engine (the on-device implementation).
 *
 * A faithful port of the pokepad repo's kotlin/Battle.kt. The deterministic math
 * is cross-gated bit-for-bit to the Python spec (Engine.kt). Two changes vs. the
 * desktop original so it runs inside the Android app:
 *   • Dex loads from pre-read asset lines (List<String>) instead of File(...).
 *   • Species carries `shape` (the renderer needs it; the engine ignores it).
 * The JVM demo/self-test main() is dropped (it lives in the pokepad repo).
 */
import java.util.Random

// ── data ────────────────────────────────────────────────────────────────────
val STAT_MAP = mapOf("attack" to "atk", "defense" to "def", "special-attack" to "spa",
        "special-defense" to "spd", "speed" to "spe", "accuracy" to "acc", "evasion" to "eva")
val RECHARGE = setOf("hyper-beam", "blast-burn", "hydro-cannon", "frenzy-plant")
val SELF_KO = setOf("explosion", "self-destruct")
val AILMENT = mapOf("burn" to "brn", "poison" to "psn", "paralysis" to "par", "freeze" to "frz",
        "sleep" to "slp", "toxic" to "tox", "confusion" to "confusion")

class Move(val name: String, val type: String, val power: Int, val accuracy: Int, val pp: Int,
           val priority: Int, val ailment: String, val ailmentChance: Int, val flinch: Int,
           val drain: Int, val healing: Int, val minHits: Int, val maxHits: Int,
           val statChanges: List<Pair<String, Int>>, val target: String) {
    val phys get() = isPhysical(type)
}

class Species(val name: String, val types: List<String>, val hp: Int, val atk: Int, val def: Int,
              val spa: Int, val spd: Int, val spe: Int, val ab0: String?, val ab1: String?,
              val shape: String)

val NAT_UP = mapOf("lonely" to "atk", "brave" to "atk", "adamant" to "atk", "naughty" to "atk",
        "bold" to "def", "relaxed" to "def", "impish" to "def", "lax" to "def",
        "modest" to "spa", "mild" to "spa", "quiet" to "spa", "rash" to "spa",
        "calm" to "spd", "gentle" to "spd", "sassy" to "spd", "careful" to "spd",
        "timid" to "spe", "hasty" to "spe", "jolly" to "spe", "naive" to "spe")
val NAT_DOWN = mapOf("lonely" to "def", "brave" to "spe", "adamant" to "spa", "naughty" to "spd",
        "bold" to "atk", "relaxed" to "spe", "impish" to "spa", "lax" to "spd",
        "modest" to "atk", "mild" to "spd", "quiet" to "spe", "rash" to "spd",
        "calm" to "atk", "gentle" to "def", "sassy" to "spe", "careful" to "spa",
        "timid" to "atk", "hasty" to "def", "jolly" to "spa", "naive" to "spd")
fun natMul(nature: String, stat: String) = when (stat) {
    NAT_UP[nature] -> 1.1; NAT_DOWN[nature] -> 0.9; else -> 1.0
}

class Dex(speciesLines: List<String>, moveLines: List<String>, chartLines: List<String>) {
    val chart = HashMap<String, HashMap<String, Double>>()
    val species = HashMap<String, Species>()
    val moves = HashMap<String, Move>()

    init {
        for (it in chartLines) {
            if (it.isBlank()) continue
            val f = it.split("\t"); chart.getOrPut(f[0]) { HashMap() }[f[1]] = f[2].toDouble()
        }
        for (it in speciesLines) {
            if (it.isBlank()) continue
            val f = it.split("\t")
            val types = if (f[2].isEmpty()) listOf(f[1]) else listOf(f[1], f[2])
            species[f[0]] = Species(f[0], types, f[3].toInt(), f[4].toInt(), f[5].toInt(),
                    f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].ifEmpty { null }, f[10].ifEmpty { null },
                    f.getOrElse(11) { "" })
        }
        for (it in moveLines) {
            if (it.isBlank()) continue
            val f = it.split("\t")
            val sc = if (f[13].isEmpty()) emptyList() else f[13].split(";").map { p ->
                val (s, c) = p.split(":"); (STAT_MAP[s] ?: s) to c.toInt()
            }
            moves[f[0]] = Move(f[0], f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(),
                    f[6], f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(),
                    f[11].toInt(), f[12].toInt(), sc, f[14])
        }
    }

    fun typeEff(moveType: String, defTypes: List<String>) = Engine.typeEff(chart, moveType, defTypes)
}

// ── the battler ─────────────────────────────────────────────────────────────
class Mon(val dex: Dex, speciesName: String, val level: Int = 50,
          val moves: List<String>, val nickname: String? = null,
          ability: String? = null, nature: String = "hardy",
          ivs: Map<String, Int>? = null, evs: Map<String, Int>? = null) {
    val species = dex.species[speciesName]!!
    val types get() = species.types
    val ability: String? = ability ?: species.ab0
    val stats = HashMap<String, Int>()
    val maxHp: Int
    var hp: Int
    var status: String? = null
    var statusCounter = 0
    val stages = hashMapOf("atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0, "acc" to 0, "eva" to 0)
    var confused = 0
    var mustRecharge = false
    var flinched = false
    var tookDamage = false
    var flashFire = false

    init {
        val iv = ivs ?: mapOf("hp" to 31, "atk" to 31, "def" to 31, "spa" to 31, "spd" to 31, "spe" to 31)
        val ev = evs ?: emptyMap()
        for ((k, base) in mapOf("hp" to species.hp, "atk" to species.atk, "def" to species.def,
                "spa" to species.spa, "spd" to species.spd, "spe" to species.spe)) {
            stats[k] = Engine.statCalc(base, iv[k] ?: 31, ev[k] ?: 0, level, k == "hp", natMul(nature, k))
        }
        maxHp = stats["hp"]!!; hp = maxHp
    }

    val name get() = nickname ?: species.name.replaceFirstChar { it.uppercase() }
    val fainted get() = hp <= 0
    fun effSpeed(): Int {
        var v = (stats["spe"]!! * Engine.stageMult(stages["spe"]!!)).toInt()
        if (status == "par") v /= 4
        return maxOf(1, v)
    }
    fun boost(stat: String, n: Int): Int {
        val old = stages[stat]!!; stages[stat] = maxOf(-6, minOf(6, old + n)); return stages[stat]!! - old
    }
    fun heal(amt: Int) { hp = minOf(maxHp, hp + maxOf(0, amt)) }
}

// ── abilities (ported hooks) ────────────────────────────────────────────────
object Abilities {
    val BLAZE = mapOf("overgrow" to "grass", "blaze" to "fire", "torrent" to "water", "swarm" to "bug")
    val ABSORB = mapOf("water-absorb" to "water", "volt-absorb" to "electric")
    val CONTACT = mapOf("static" to "par", "flame-body" to "brn", "poison-point" to "psn")
    val NON_CONTACT = setOf("earthquake", "rock-slide", "rock-throw", "magnitude", "razor-leaf",
            "rock-tomb", "ancient-power", "gust", "twister", "bone-club", "bonemerang")

    fun typeImmunity(def: Mon, mt: String): String? = when {
        def.ability == "levitate" && mt == "ground" -> "immune"
        ABSORB[def.ability] == mt -> "absorb"
        def.ability == "flash-fire" && mt == "fire" -> "flashfire"
        else -> null
    }
    fun wonderGuardBlocks(def: Mon, eff: Double) = def.ability == "wonder-guard" && eff <= 1.0
    fun offenseMult(a: Mon, mt: String, phys: Boolean): Double {
        var m = 1.0
        if (a.ability == "huge-power" && phys) m *= 2.0
        if (a.ability == "guts" && a.status != null && phys) m *= 1.5
        if (BLAZE[a.ability] == mt && a.hp * 3 <= a.maxHp) m *= 1.5
        if (a.flashFire && mt == "fire") m *= 1.5
        return m
    }
    fun defenseMult(d: Mon, mt: String, phys: Boolean): Double {
        var m = 1.0
        if (d.ability == "thick-fat" && (mt == "fire" || mt == "ice")) m *= 0.5
        if (d.ability == "marvel-scale" && d.status != null && phys) m *= 1.5
        return m
    }
    fun statusImmune(d: Mon, code: String) = (d.ability to code) in setOf(
            "limber" to "par", "immunity" to "psn", "immunity" to "tox", "insomnia" to "slp",
            "vital-spirit" to "slp", "water-veil" to "brn", "magma-armor" to "frz", "own-tempo" to "confusion")
    fun blocksStatDrop(d: Mon) = d.ability == "clear-body" || d.ability == "white-smoke"
    fun contactStatus(moveName: String, phys: Boolean, def: Mon, rng: Random): String? {
        if (!phys || moveName in NON_CONTACT) return null
        val a = def.ability
        return if (a in CONTACT && rng.nextInt(100) < 30) CONTACT[a] else null
    }
    fun onSwitchIn(m: Mon, foe: Mon, log: (String) -> Unit) {
        if (m.ability == "intimidate" && !m.fainted && !blocksStatDrop(foe)) {
            if (foe.boost("atk", -1) != 0) log("  ${m.name}'s Intimidate cut ${foe.name}'s attack!")
        }
    }
}

// ── battle events (typed sink for the renderer/Scene; default is no-op) ──────
sealed class Ev {
    data class SendIn(val side: String, val species: String, val name: String) : Ev()
    data class Used(val side: String, val species: String, val move: String, val dmg: Int, val eff: Double) : Ev()
    data class Faint(val side: String, val species: String) : Ev()
    data class Win(val side: String, val species: String) : Ev()
}

// ── the battle (teams + switching) ──────────────────────────────────────────
class Battle(val dex: Dex, left: List<Mon>, right: List<Mon>, seed: Long = 0,
             val log: (String) -> Unit = {}, val emit: (Ev) -> Unit = {}) {
    val ls = left; val rs = right
    var li = 0; var ri = 0
    val rng = Random(seed)
    var over = false; var winner: Mon? = null
    val left get() = ls[li]
    val right get() = rs[ri]
    fun other(p: Mon) = if (p === left) right else left

    fun chooseMove(a: Mon, d: Mon): String {
        var best = a.moves[0]; var bestScore = -1.0
        for (name in a.moves) {
            val mv = dex.moves[name] ?: continue
            val eff = dex.typeEff(mv.type, d.types)
            val A = if (mv.phys) a.stats["atk"]!! else a.stats["spa"]!!
            val D = if (mv.phys) d.stats["def"]!! else d.stats["spd"]!!
            val stab = if (mv.type in a.types) 1.5 else 1.0
            val acc = (if (mv.accuracy < 0) 100 else mv.accuracy) / 100.0
            val score = mv.power * (A.toDouble() / D) * stab * eff * acc
            if (score > bestScore) { bestScore = score; best = name }
        }
        return best
    }

    private fun statusWord(c: String) = mapOf("brn" to "burned", "psn" to "poisoned", "tox" to "badly poisoned",
            "par" to "paralyzed", "slp" to "put to sleep", "frz" to "frozen")[c] ?: c
    private fun typeStatusImmune(p: Mon, code: String) = when {
        code == "brn" && "fire" in p.types -> true
        (code == "psn" || code == "tox") && ("poison" in p.types || "steel" in p.types) -> true
        code == "frz" && "ice" in p.types -> true
        else -> false
    }

    private fun canMove(p: Mon): Boolean {
        if (p.mustRecharge) { p.mustRecharge = false; log("  ${p.name} must recharge!"); return false }
        if (p.status == "frz") {
            if (rng.nextDouble() < 0.20) { p.status = null; log("  ${p.name} thawed out!") }
            else { log("  ${p.name} is frozen solid!"); return false }
        }
        if (p.status == "slp") {
            p.statusCounter--
            if (p.statusCounter <= 0) { p.status = null; log("  ${p.name} woke up!") }
            else { log("  ${p.name} is fast asleep."); return false }
        }
        if (p.flinched) { p.flinched = false; log("  ${p.name} flinched!"); return false }
        if (p.status == "par" && rng.nextDouble() < 0.25) { log("  ${p.name} is fully paralyzed!"); return false }
        if (p.confused > 0) {
            p.confused--
            if (rng.nextDouble() < 0.5) {
                val d = (2 * p.level / 5 + 2) * 40 * p.stats["atk"]!! / p.stats["def"]!! / 50 + 2
                p.hp = maxOf(0, p.hp - d); log("  ${p.name} hurt itself in confusion ($d)!")
                return false
            }
        }
        return true
    }

    private fun battleDamage(a: Mon, d: Mon, mt: String, power: Int, roll: Int, crit: Boolean): Pair<Int, Double> {
        val eff = dex.typeEff(mt, d.types)
        if (eff == 0.0) return 0 to 0.0
        val phys = isPhysical(mt)
        var aS = if (phys) a.stages["atk"]!! else a.stages["spa"]!!
        var dS = if (phys) d.stages["def"]!! else d.stages["spd"]!!
        if (crit) { aS = maxOf(0, aS); dS = minOf(0, dS) }
        val baseA = if (phys) a.stats["atk"]!! else a.stats["spa"]!!
        val baseD = if (phys) d.stats["def"]!! else d.stats["spd"]!!
        var A = maxOf(1, (baseA * Engine.stageMult(aS)).toInt())
        val D = maxOf(1, (baseD * Engine.stageMult(dS)).toInt())
        if (phys && a.status == "brn") A /= 2
        A = maxOf(1, A)
        var dmg = (2 * a.level / 5 + 2) * power * A / D
        dmg = dmg / 50 + 2
        if (crit) dmg *= 2
        if (mt in a.types) dmg = dmg * 3 / 2
        dmg = (dmg * eff).toInt()
        dmg = (dmg * Abilities.offenseMult(a, mt, phys)).toInt()
        dmg = (dmg * Abilities.defenseMult(d, mt, phys)).toInt()
        dmg = dmg * roll / 100
        return maxOf(1, dmg) to eff
    }

    private fun applyEffects(mv: Move, a: Mon, d: Mon, dealt: Int) {
        val code = AILMENT[mv.ailment]
        if (code != null && (mv.ailmentChance == 0 || rng.nextInt(100) < mv.ailmentChance)) {
            if (code == "confusion") { if (d.confused == 0) { d.confused = 2 + rng.nextInt(4); log("  ${d.name} became confused!") } }
            else if (d.status == null && !typeStatusImmune(d, code) && !Abilities.statusImmune(d, code)) {
                d.status = code
                if (code == "slp") d.statusCounter = 1 + rng.nextInt(3)
                if (code == "tox") d.statusCounter = 1
                log("  ${d.name} was ${statusWord(code)}!")
            }
        }
        val self = mv.target.contains("user")
        for ((st, chg) in mv.statChanges) {
            if (mv.ailmentChance != 0 && rng.nextInt(100) >= mv.ailmentChance) continue
            val who = if (self) a else d
            if (chg < 0 && !self && Abilities.blocksStatDrop(who)) continue
            if (who.boost(st, chg) != 0) log("  ${who.name}'s $st ${if (chg > 0) "rose" else "fell"}!")
        }
        if (mv.flinch != 0 && rng.nextInt(100) < mv.flinch) d.flinched = true
        if (mv.drain != 0) a.heal(dealt * mv.drain / 100)
        if (mv.healing != 0) a.heal(a.maxHp * mv.healing / 100)
    }

    private fun act(a: Mon, d: Mon, moveName: String) {
        if (!canMove(a)) return
        val mv = dex.moves[moveName] ?: return
        val nice = moveName.replace("-", " ").replaceFirstChar { it.uppercase() }
        if (moveName == "focus-punch" && a.tookDamage) { log("  ${a.name} lost its focus!"); return }
        if (mv.accuracy >= 0) {
            val effAcc = mv.accuracy * Engine.accMult(a.stages["acc"]!!) / Engine.accMult(d.stages["eva"]!!)
            if (rng.nextDouble() * 100 > effAcc) { log("  ${a.name} used $nice — it missed!"); return }
        }
        var dealt = 0; var lastEff = 1.0
        if (mv.power > 0) {
            val imm = Abilities.typeImmunity(d, mv.type)
            if (imm != null) {
                when (imm) {
                    "absorb" -> { d.heal(d.maxHp / 4); log("  ${d.name} absorbed $nice!") }
                    "flashfire" -> { d.flashFire = true; log("  ${d.name}'s Flash Fire soaked up $nice!") }
                    else -> log("  $nice doesn't affect ${d.name} (${d.ability})!")
                }
                return
            }
            if (Abilities.wonderGuardBlocks(d, dex.typeEff(mv.type, d.types))) { log("  $nice can't break Wonder Guard!"); return }
            val hits = if (mv.minHits > 0) mv.minHits + rng.nextInt(maxOf(1, mv.maxHits - mv.minHits + 1)) else 1
            var total = 0; var eff = 1.0
            repeat(hits) {
                if (d.fainted) return@repeat
                val (dmg, e) = battleDamage(a, d, mv.type, mv.power, 85 + rng.nextInt(16), rng.nextInt(16) == 0)
                d.hp = maxOf(0, d.hp - dmg); total += dmg; d.tookDamage = true; eff = e
            }
            dealt = total; lastEff = eff
            val tag = when { eff > 1 -> " (super effective!)"; eff in 0.0..0.99 -> " (resisted)"; else -> "" }
            log("  ${a.name} used $nice → $total$tag  (${d.name} ${d.hp}/${d.maxHp})")
            val cs = Abilities.contactStatus(moveName, mv.phys, d, rng)
            if (cs != null && a.status == null && !Abilities.statusImmune(a, cs)) {
                a.status = cs; if (cs == "slp") a.statusCounter = 1 + rng.nextInt(3)
                log("  ${a.name} was ${statusWord(cs)} by ${d.ability}!")
            }
        } else log("  ${a.name} used $nice.")
        emit(Ev.Used(if (a === left) "L" else "R", a.species.name, moveName, dealt, lastEff))
        if (!d.fainted) applyEffects(mv, a, d, dealt)
        if (moveName in RECHARGE && mv.power > 0) a.mustRecharge = true
        if (moveName in SELF_KO && mv.power > 0) a.hp = 0
    }

    private fun sideAlive(team: List<Mon>) = team.any { !it.fainted }
    private fun matchup(m: Mon, foe: Mon): Double {
        fun bestPower(x: Mon, y: Mon) = x.moves.mapNotNull { dex.moves[it] }
                .maxOfOrNull { dex.typeEff(it.type, y.types) * it.power } ?: 0.0
        return bestPower(m, foe) - bestPower(foe, m)
    }
    private fun bestSwitch(team: List<Mon>, foe: Mon) =
            team.indices.filter { !team[it].fainted }.maxByOrNull { matchup(team[it], foe) }!!

    private fun handleFaints() {
        for (key in listOf("L", "R")) {
            val team = if (key == "L") ls else rs
            val act = if (key == "L") left else right
            if (!act.fainted) continue
            log("  ${act.name} fainted!")
            emit(Ev.Faint(key, act.species.name))
            if (sideAlive(team)) {
                val foe = if (key == "L") right else left
                val nxt = bestSwitch(team, foe)
                if (key == "L") li = nxt else ri = nxt
                log("  → sends out ${team[nxt].name}!")
                emit(Ev.SendIn(key, team[nxt].species.name, team[nxt].name))
                Abilities.onSwitchIn(team[nxt], foe, log)
            }
        }
        val la = sideAlive(ls); val ra = sideAlive(rs)
        if (!(la && ra)) { over = true; winner = if (la) left else if (ra) right else null
            winner?.let { emit(Ev.Win(if (it === left) "L" else "R", it.species.name)) } }
    }

    private fun endOfTurn(p: Mon) {
        if (p.fainted || over) return
        when (p.status) {
            "brn", "psn" -> { val d = maxOf(1, p.maxHp / 8); p.hp = maxOf(0, p.hp - d); log("  ${p.name} is hurt by its ${if (p.status == "brn") "burn" else "poison"} ($d).") }
            "tox" -> { val d = maxOf(1, p.maxHp * p.statusCounter / 16); p.statusCounter++; p.hp = maxOf(0, p.hp - d); log("  ${p.name} is hurt by toxic ($d).") }
        }
    }

    fun run(maxTurns: Int = 1000): Mon? {
        val teams = ls.size == 1 && rs.size == 1
        log(if (teams) "${left.name} vs ${right.name}" else "${ls.size}v${rs.size} — ${left.name} & ${right.name} lead")
        emit(Ev.SendIn("L", left.species.name, left.name)); emit(Ev.SendIn("R", right.species.name, right.name))
        Abilities.onSwitchIn(left, right, log); Abilities.onSwitchIn(right, left, log)
        var t = 0
        while (!over && t < maxTurns) {
            t++
            left.tookDamage = false; right.tookDamage = false
            val picks = mapOf(left to chooseMove(left, right), right to chooseMove(right, left))
            val order = picks.keys.sortedWith(compareByDescending<Mon> { dex.moves[picks[it]]!!.priority }
                    .thenByDescending { it.effSpeed() }.thenBy { rng.nextDouble() })
            for (a in order) {
                if (over || a.fainted) continue
                act(a, other(a), picks[a]!!); handleFaints()
                if (over) break
            }
            if (over) break
            for (p in listOf(left, right)) { p.flinched = false; endOfTurn(p) }
            handleFaints()
        }
        return winner
    }

    // ── interactive / trainer mode ───────────────────────────────────────────
    // Same rules as run(), but the LEFT side's move comes from the human each
    // turn (RIGHT is the AI). Call start(), then step(move) per turn, reading
    // over / winner / the active mon's legal moves between calls.

    /** legal move names for the currently-active left mon (the human's choices) */
    fun leftMoves(): List<String> = left.moves.filter { dex.moves.containsKey(it) }

    /** send both leads in (emits SendIn) — call once before the first step. */
    fun startInteractive() {
        emit(Ev.SendIn("L", left.species.name, left.name)); emit(Ev.SendIn("R", right.species.name, right.name))
        Abilities.onSwitchIn(left, right, log); Abilities.onSwitchIn(right, left, log)
    }

    /** play one turn with the human's chosen left move; AI picks for the right. */
    fun stepInteractive(leftMove: String) = stepTurn(leftMove, chooseMove(right, left))

    /** PvP: both moves supplied by humans (the two-phone duel). */
    fun stepPvp(leftMove: String, rightMove: String) = stepTurn(leftMove, rightMove)

    private fun stepTurn(leftMove: String, rightMove: String) {
        if (over) return
        left.tookDamage = false; right.tookDamage = false
        val picks = mapOf(left to leftMove, right to rightMove)
        val order = picks.keys.sortedWith(compareByDescending<Mon> { dex.moves[picks[it]]!!.priority }
                .thenByDescending { it.effSpeed() }.thenBy { rng.nextDouble() })
        for (a in order) {
            if (over || a.fainted) continue
            act(a, other(a), picks[a]!!); handleFaints()
            if (over) break
        }
        if (!over) { for (p in listOf(left, right)) { p.flinched = false; endOfTurn(p) }; handleFaints() }
    }
}
