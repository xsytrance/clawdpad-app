package dev.clawdpad.poke

import android.content.Context
import java.util.Random

/*
 * Loads the compact Gen-III dataset (386 species / 372 moves / type chart) from
 * app assets once, caches the Dex, and hands out random species for snap-battles.
 * Later this is where a parsed save's real team plugs in.
 */
object PokeData {
    @Volatile private var dex: Dex? = null
    var speciesIds: List<String> = emptyList(); private set

    fun ensure(ctx: Context) {
        if (dex != null) return
        synchronized(this) {
            if (dex != null) return
            val am = ctx.applicationContext.assets
            fun lines(n: String) = am.open("poke/$n").bufferedReader().use { it.readLines() }
            val d = Dex(lines("gen3_species.tsv"), lines("gen3_moves.tsv"), lines("gen3_typechart.tsv"))
            speciesIds = d.species.keys.sorted()
            dex = d
        }
    }

    fun dex(): Dex = dex ?: throw IllegalStateException("PokeData.ensure(context) not called")

    fun random(rng: Random): String = speciesIds[rng.nextInt(speciesIds.size)]
}
