package dev.pokepad.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.core.PokeData
import dev.pokepad.core.Renderer
import dev.pokepad.core.FEATURES
import dev.pokepad.core.autoFeatures

/**
 * Choose the two fighters. A grid of rendered mon; tap one for your side, tap
 * another for the opponent, and the battle starts. Any of the 386 can be dropped
 * in via the on-screen note; this featured set keeps the picker quick and pretty.
 */
class PickerActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#0B1020")
    private val CARD = Color.parseColor("#141B33")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#8A87A0")
    private val GOLD = Color.parseColor("#F5D246")

    private val featured = listOf(
        "bulbasaur", "charmander", "squirtle", "charizard", "blastoise", "venusaur",
        "pikachu", "gengar", "gyarados", "dragonite", "snorlax", "lapras",
        "mewtwo", "mew", "alakazam", "machamp", "arcanine", "tyranitar",
        "scizor", "umbreon", "espeon", "sceptile", "blaziken", "swampert",
        "gardevoir", "salamence", "metagross", "rayquaza", "groudon", "kyogre",
        "absol", "flygon", "milotic", "aggron", "altaria", "breloom")

    private var pickL: String? = null
    private var header: TextView? = null
    private val tiles = HashMap<String, LinearLayout>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this)
        val dex = PokeData.dex()
        val list = featured.filter { dex.species.containsKey(it) }

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        scroll.addView(root)

        header = TextView(this).apply {
            text = "PICK YOUR FIGHTER"
            setTextColor(GOLD); textSize = 15f; letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        val cols = 4
        val cell = (resources.displayMetrics.widthPixels - dp(32)) / cols
        val grid = GridLayout(this).apply { columnCount = cols }
        list.forEach { id -> grid.addView(tile(id, dex, cell)) }
        root.addView(grid)

        root.addView(TextView(this).apply {
            text = "all 386 species are in the engine — this featured grid keeps picking quick"
            setTextColor(DIM); textSize = 12f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })

        setContentView(scroll)
    }

    private fun tile(id: String, dex: dev.pokepad.core.Dex, cell: Int): LinearLayout {
        val s = dex.species[id]!!
        val feats = FEATURES[id] ?: autoFeatures(s.types)
        val small = Sprite.bitmap(Renderer.render(s.shape, s.types, feats, -1).px)
        val big = Bitmap.createScaledBitmap(small, dp(64), dp(64), false)   // nearest-neighbor = crisp pixels

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = tileBg(false)
            isClickable = true; isFocusable = true
        }
        box.addView(ImageView(this).apply { setImageBitmap(big) },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(TextView(this).apply {
            text = id.replaceFirstChar { it.uppercase() }
            setTextColor(INK); textSize = 10.5f; gravity = Gravity.CENTER
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })

        box.setOnClickListener { onPick(id) }
        tiles[id] = box

        val glp = GridLayout.LayoutParams().apply {
            width = cell; height = GridLayout.LayoutParams.WRAP_CONTENT
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        box.layoutParams = glp
        return box
    }

    private fun onPick(id: String) {
        val first = pickL
        if (first == null) {
            pickL = id
            tiles[id]?.background = tileBg(true)
            header?.text = "NOW PICK THE OPPONENT"
        } else if (id == first) {
            // deselect
            pickL = null
            tiles[id]?.background = tileBg(false)
            header?.text = "PICK YOUR FIGHTER"
        } else {
            startActivity(Intent(this, BattleActivity::class.java)
                .putExtra("left", first).putExtra("right", id))
        }
    }

    private fun tileBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(CARD)
        setStroke(dp(if (selected) 2 else 1), if (selected) GOLD else Color.parseColor("#26314F"))
    }

    companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
