package dev.clawdpad.poke

val PHYSICAL_TYPES = setOf("normal", "fighting", "flying", "ground", "rock", "bug", "ghost", "poison", "steel")

fun isPhysical(moveType: String) = moveType in PHYSICAL_TYPES

object Engine {
    fun statCalc(base: Int, iv: Int, ev: Int, level: Int, isHp: Boolean, natMul: Double): Int {
        val inner = (2 * base + iv + ev / 4) * level / 100
        return if (isHp) inner + level + 10 else ((inner + 5) * natMul).toInt()
    }

    fun typeEff(chart: Map<String, Map<String, Double>>, moveType: String, defTypes: List<String>): Double {
        var m = 1.0
        for (dt in defTypes) m *= chart[moveType]?.get(dt) ?: 1.0
        return m
    }

    fun stageMult(s: Int): Double = if (s >= 0) (2.0 + s) / 2 else 2.0 / (2 - s)
    fun accMult(s: Int): Double = if (s >= 0) (3.0 + s) / 3 else 3.0 / (3 - s)

    fun damage(chart: Map<String, Map<String, Double>>, level: Int, power: Int, atk: Int, def: Int,
               atkTypes: List<String>, moveType: String, defTypes: List<String>,
               roll: Int, crit: Boolean): Int {
        val eff = typeEff(chart, moveType, defTypes)
        if (eff == 0.0) return 0
        var dmg = (2 * level / 5 + 2) * power * atk / def
        dmg = dmg / 50 + 2
        if (crit) dmg *= 2
        if (moveType in atkTypes) dmg = dmg * 3 / 2
        dmg = (dmg * eff).toInt()
        dmg = dmg * roll / 100
        return maxOf(1, dmg)
    }

    /** stage- & burn-aware damage; crit ignores unfavorable stages. */
    fun damageStage(chart: Map<String, Map<String, Double>>, level: Int, power: Int, baseAtk: Int, baseDef: Int,
                    atkStage: Int, defStage: Int, burn: Boolean, phys: Boolean,
                    atkTypes: List<String>, moveType: String, defTypes: List<String>,
                    roll: Int, crit: Boolean): Int {
        val eff = typeEff(chart, moveType, defTypes)
        if (eff == 0.0) return 0
        var aS = atkStage; var dS = defStage
        if (crit) { aS = maxOf(0, aS); dS = minOf(0, dS) }
        var A = maxOf(1, (baseAtk * stageMult(aS)).toInt())
        val D = maxOf(1, (baseDef * stageMult(dS)).toInt())
        if (phys && burn) A /= 2
        A = maxOf(1, A)
        var dmg = (2 * level / 5 + 2) * power * A / D
        dmg = dmg / 50 + 2
        if (crit) dmg *= 2
        if (moveType in atkTypes) dmg = dmg * 3 / 2
        dmg = (dmg * eff).toInt()
        dmg = dmg * roll / 100
        return maxOf(1, dmg)
    }
}
