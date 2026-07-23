package dev.pokepad.save

import android.content.Context
import dev.pokepad.core.Director
import dev.pokepad.core.Mon
import dev.pokepad.core.PokeData

/**
 * Loads the on-device save parser (with the index maps from assets) and bridges
 * a parsed party into battle-ready engine Mon — carrying the REAL level, IVs,
 * EVs, nature and moves. This is the seam where "your actual team" enters the
 * sim. Facts are sacred.
 */
object SaveData {
    @Volatile private var parser: Gen3Save? = null
    @Volatile var truth: SaveTruth? = null          // last loaded save
    @Volatile var battleLead: SaveMon? = null        // the mon chosen for a battle

    fun ensure(ctx: Context) {
        if (parser != null) return
        synchronized(this) {
            if (parser != null) return
            PokeData.ensure(ctx)
            val am = ctx.applicationContext.assets
            fun tsv(n: String): Map<Int, String> = am.open("poke/$n").bufferedReader().useLines { seq ->
                seq.filter { it.isNotBlank() }.map { it.split("\t") }.associate { it[0].toInt() to it[1] }
            }
            val dex = PokeData.dex()
            parser = Gen3Save(tsv("gen3_i2n.tsv"), tsv("gen3_moveidx.tsv"),
                { sp -> listOfNotNull(dex.species[sp]?.ab0, dex.species[sp]?.ab1) })
        }
    }

    fun parse(bytes: ByteArray): SaveTruth { val t = parser!!.parse(bytes); truth = t; return t }

    /** SaveMon → engine Mon with its real spread; if it has no damaging move
     *  (e.g. a pure-status set) fall back to a synthesised set so it can fight. */
    fun mon(sm: SaveMon): Mon {
        val dex = PokeData.dex()
        val legal = sm.moves.filter { dex.moves.containsKey(it) }
        val hasDmg = legal.any { (dex.moves[it]?.power ?: 0) > 0 }
        val use = (if (hasDmg) legal else Director.movesetFor(dex, sm.species!!)).ifEmpty { Director.movesetFor(dex, sm.species!!) }
        return Mon(dex, sm.species!!, level = sm.level.coerceIn(1, 100), moves = use,
            nickname = sm.nickname, ability = sm.ability, nature = sm.nature, ivs = sm.ivs, evs = sm.evs)
    }
}
