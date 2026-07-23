package dev.pokepad.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.save.SaveData
import dev.pokepad.save.SaveMon
import dev.pokepad.save.SaveTruth
import java.io.File

/**
 * "My Team" — load your real Gen-III save (.sav) and see your actual Pokémon,
 * then tap one to battle with it. Read-only; the file is never modified.
 */
class SaveActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#0B1020")
    private val CARD = Color.parseColor("#141B33")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#8A87A0")
    private val GOLD = Color.parseColor("#F5D246")
    private val RED = Color.parseColor("#E23B3B")
    private val REQ = 42

    private lateinit var teamBox: LinearLayout
    private lateinit var header: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SaveData.ensure(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(44), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "MY TEAM"; setTextColor(GOLD); textSize = 15f; letterSpacing = 0.16f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }, lp(0))
        header = TextView(this).apply {
            text = "Load your real Gen-III save (.sav) to summon your actual Pokémon."
            setTextColor(DIM); textSize = 14f; gravity = Gravity.CENTER
        }
        root.addView(header, lp(12).also { it.width = dp(300) })

        root.addView(button("📁  LOAD .SAV FILE", RED) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ)
        }, lp(24).also { it.width = dp(300); it.height = dp(56) })

        // dev convenience: a save dropped into the app's files dir (adb) for testing
        val test = File(filesDir, "test.sav")
        if (test.exists()) root.addView(button("▶  LOAD TEST SAVE", CARD) {
            runCatching { showTeam(SaveData.parse(test.readBytes())) }
                .onFailure { toast("couldn't read test save: ${it.message}") }
        }, lp(10).also { it.width = dp(300); it.height = dp(48) })

        teamBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(teamBox, lp(20).also { it.width = dp(320) })

        root.addView(TextView(this).apply {
            text = "read-only · your save is never modified\nfacts are sacred · feelings are free"
            setTextColor(DIM); textSize = 11.5f; gravity = Gravity.CENTER
        }, lp(28))

        setContentView(scroll)
        SaveData.truth?.let { showTeam(it) }   // keep showing after returning from a battle
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                showTeam(SaveData.parse(bytes))
            }.onFailure { toast("not a Gen-III save? ${it.message}") }
        }
    }

    private fun showTeam(t: SaveTruth) {
        teamBox.removeAllViews()
        header.text = "${t.trainer.name}'s team · ${t.trainer.game} · ${t.trainer.playHours}h"
        if (t.party.isEmpty()) { toast("no party found in that save"); return }
        for (sm in t.party) teamBox.addView(monRow(sm), lp(10).also { it.width = dp(320) })
    }

    private fun monRow(sm: SaveMon): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD)
                setStroke(dp(1), Color.parseColor("#26314F")) }
            isClickable = true; isFocusable = true
        }
        val sprite: Bitmap? = sm.species?.let { runCatching { Sprite.still(dev.pokepad.core.PokeData.dex(), it) }.getOrNull() }
        row.addView(ImageView(this).apply {
            if (sprite != null) setImageBitmap(Bitmap.createScaledBitmap(sprite, dp(52), dp(52), false))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = sm.nickname + (if (sm.shiny) "  ✨" else "")
            setTextColor(INK); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        val sp = sm.species?.replaceFirstChar { it.uppercase() } ?: "?"
        col.addView(TextView(this).apply {
            text = "$sp · Lv${sm.level} · ${sm.nature}"
            setTextColor(DIM); textSize = 12.5f
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(12) })

        row.addView(TextView(this).apply {
            text = "FIGHT"; setTextColor(GOLD); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        })
        row.setOnClickListener {
            if (sm.species == null) { toast("unknown species — can't battle"); return@setOnClickListener }
            SaveData.battleLead = sm
            startActivity(Intent(this, BattleActivity::class.java).putExtra("fromSave", true))
        }
        return row
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(label: String, bg: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; setTextColor(INK); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(bg)
            setStroke(dp(1), Color.parseColor("#26314F")) }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
