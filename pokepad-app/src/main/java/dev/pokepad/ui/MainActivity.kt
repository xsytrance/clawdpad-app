package dev.pokepad.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.R
import dev.pokepad.core.PokeData
import java.util.Random

/**
 * Poképad home — the front door. Themed dark "arcade" screen with the Poké Ball
 * mark, a title, and the ways in: a battle (watch two mon fight by real Gen-III
 * mechanics on the phone), the fighter picker, and the blocks (coming).
 */
class MainActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#0B1020")
    private val CARD = Color.parseColor("#141B33")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#8A87A0")
    private val RED = Color.parseColor("#E23B3B")
    private val GOLD = Color.parseColor("#F5D246")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this)   // load the dex once
        dev.pokepad.save.SaveData.ensure(this)   // auto-restores your last-loaded save
        Sfx.ensure(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(56), dp(28), dp(36))
        }
        scroll.addView(root)

        // Poké Ball mark
        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
        }, LinearLayout.LayoutParams(dp(112), dp(112)))

        root.addView(TextView(this).apply {
            text = "Poképad"
            setTextColor(INK); textSize = 40f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, lp(top = 18))

        root.addView(TextView(this).apply {
            text = "REAL GEN-III BATTLES · ON THE BLOCK"
            setTextColor(GOLD); textSize = 11.5f
            letterSpacing = 0.18f; gravity = Gravity.CENTER
        }, lp(top = 6))

        root.addView(TextView(this).apply {
            text = "386 species · authentic stats, type chart & damage · fully autonomous"
            setTextColor(DIM); textSize = 13f; gravity = Gravity.CENTER
        }, lp(top = 14).also { it.width = dp(300) })

        // primary: give the commands yourself — a real turn-based battle
        root.addView(bigButton("🎮  TRAINER BATTLE", RED, INK) {
            startActivity(Intent(this, TrainerActivity::class.java))
        }, lp(top = 34).also { it.width = dp(300); it.height = dp(60) })

        // the crown: two phones, two blocks, one battle
        root.addView(bigButton("👑  2-PLAYER DUEL", CARD, GOLD) {
            startActivity(Intent(this, DuelActivity::class.java))
        }, lp(top = 12).also { it.width = dp(300); it.height = dp(56) })

        // watch a quick auto-battle
        root.addView(bigButton("⚔  QUICK BATTLE", CARD, INK) {
            startActivity(Intent(this, BattleActivity::class.java))   // random matchup
        }, lp(top = 12).also { it.width = dp(300); it.height = dp(56) })

        root.addView(bigButton("◎  CHOOSE FIGHTERS", CARD, INK) {
            startActivity(Intent(this, PickerActivity::class.java))
        }, lp(top = 12).also { it.width = dp(300); it.height = dp(56) })

        teamBtn = bigButton("✨  MY TEAM (LOAD SAVE)", CARD, GOLD) {
            startActivity(Intent(this, SaveActivity::class.java))
        }
        root.addView(teamBtn, lp(top = 12).also { it.width = dp(300); it.height = dp(56) })

        blocksBtn = bigButton("🔗  CONNECT BLOCKS", CARD, INK) {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        root.addView(blocksBtn, lp(top = 12).also { it.width = dp(300); it.height = dp(56) })

        root.addView(TextView(this).apply {
            text = "facts are sacred · feelings are free"
            setTextColor(DIM); textSize = 11.5f; gravity = Gravity.CENTER
        }, lp(top = 30))

        setContentView(scroll)
        Insets.pad(root)
    }

    private var teamBtn: TextView? = null
    private var blocksBtn: TextView? = null

    override fun onResume() {
        super.onResume()
        // live status on the buttons — the app tells you where it's at
        val t = dev.pokepad.save.SaveData.truth
        teamBtn?.text = if (t != null) "✨  MY TEAM — ${t.trainer.name} ✓" else "✨  MY TEAM (LOAD SAVE)"
        val linked = dev.pokepad.block.Host.streamer?.isAlive == true
        blocksBtn?.text = if (linked) "🔗  BLOCKS — CONNECTED ✓" else "🔗  CONNECT BLOCKS"
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun bigButton(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(fg); textSize = 16.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(bg)
                setStroke(dp(1), Color.parseColor("#26314F"))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { Sfx.play("blip"); onClick() }
        }
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()
}
