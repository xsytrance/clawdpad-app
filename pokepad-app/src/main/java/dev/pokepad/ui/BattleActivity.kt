package dev.pokepad.ui

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.core.Director
import dev.pokepad.core.Mon
import dev.pokepad.core.PokeData
import dev.pokepad.save.SaveData
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
        Sfx.ensure(this)

        view = BattleView(this)
        view.onTapWhenDone = { newBattle(null, null) }
        view.onToggleView = {
            firstPerson = !firstPerson
            newBattle(curL, curR)   // same matchup, new camera
        }
        setContentView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        fromSave = intent.getBooleanExtra("fromSave", false)
        newBattle(intent.getStringExtra("left"), intent.getStringExtra("right"))
    }

    private var fromSave = false

    override fun onResume() { super.onResume(); Music.play(this, "music_battle") }
    override fun onPause() { super.onPause(); Music.stop() }

    private fun newBattle(left: String?, right: String?) {
        val dex = PokeData.dex()
        val ids = PokeData.speciesIds
        view.firstPerson = firstPerson
        // YOUR real save mon vs a random challenger
        val lead = if (fromSave) SaveData.battleLead else null
        if (lead != null) {
            var r = ids[rng.nextInt(ids.size)]; while (r == lead.species) r = ids[rng.nextInt(ids.size)]
            val a = SaveData.mon(lead)
            val b = Mon(dex, r, moves = Director.movesetFor(dex, r))
            curL = a.species.name; curR = r
            view.load(Director.build(dex, a, b, System.currentTimeMillis(), leftBack = firstPerson))
            return
        }
        val l = left ?: ids[rng.nextInt(ids.size)]
        var r = right ?: ids[rng.nextInt(ids.size)]
        while (r == l) r = ids[rng.nextInt(ids.size)]
        curL = l; curR = r
        view.load(Director.build(dex, l, r, System.currentTimeMillis(), leftBack = firstPerson))
    }
}
