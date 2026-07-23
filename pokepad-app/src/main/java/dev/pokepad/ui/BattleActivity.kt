package dev.pokepad.ui

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.core.Director
import dev.pokepad.core.PokeData
import java.util.Random

/**
 * Hosts the on-phone battle. Runs the real engine via the Director and plays the
 * reel in a BattleView. Two camera modes: FIRST-PERSON (default, classic Gen-III
 * framing — your mon big & from behind in the foreground, opponent small &
 * distant) and SIDE (both front-on). The VIEW button flips between them; tapping
 * after a fight ends starts a fresh random rematch.
 */
class BattleActivity : AppCompatActivity() {

    private lateinit var view: BattleView
    private val rng = Random()
    private var firstPerson = true
    private var curL: String? = null
    private var curR: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this)

        view = BattleView(this)
        view.onTapWhenDone = { newBattle(null, null) }
        view.onToggleView = {
            firstPerson = !firstPerson
            newBattle(curL, curR)   // same matchup, new camera
        }
        setContentView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        newBattle(intent.getStringExtra("left"), intent.getStringExtra("right"))
    }

    private fun newBattle(left: String?, right: String?) {
        val ids = PokeData.speciesIds
        val l = left ?: ids[rng.nextInt(ids.size)]
        var r = right ?: ids[rng.nextInt(ids.size)]
        while (r == l) r = ids[rng.nextInt(ids.size)]
        curL = l; curR = r
        view.firstPerson = firstPerson
        val reel = Director.build(PokeData.dex(), l, r, System.currentTimeMillis(), leftBack = firstPerson)
        view.load(reel)
    }
}
