package dev.pokepad.ui

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.core.Director
import dev.pokepad.core.PokeData
import java.util.Random

/**
 * Hosts the on-phone battle. Takes two species from the intent (or picks a random
 * matchup), runs the real engine via the Director, and plays the reel in a
 * BattleView. Tap after it ends for a fresh random rematch.
 */
class BattleActivity : AppCompatActivity() {

    private lateinit var view: BattleView
    private val rng = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this)

        val fixedL = intent.getStringExtra("left")
        val fixedR = intent.getStringExtra("right")

        view = BattleView(this)
        view.onTapWhenDone = { newBattle(null, null) }   // rematch = new random fight
        setContentView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        newBattle(fixedL, fixedR)
    }

    private fun newBattle(left: String?, right: String?) {
        val ids = PokeData.speciesIds
        val l = left ?: ids[rng.nextInt(ids.size)]
        var r = right ?: ids[rng.nextInt(ids.size)]
        while (r == l) r = ids[rng.nextInt(ids.size)]
        val reel = Director.build(PokeData.dex(), l, r, System.currentTimeMillis())
        view.load(reel)
    }
}
