package dev.pokepad.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.pokepad.core.Battle
import dev.pokepad.core.Director
import dev.pokepad.core.Ev
import dev.pokepad.core.Mon
import dev.pokepad.core.PokeData
import dev.pokepad.save.SaveData
import java.util.Random

/**
 * TRAINER MODE — a real turn-based battle. You give the command each turn (the
 * opponent's AI answers), the engine resolves it by true Gen-III mechanics, the
 * arena animates that turn, and you're prompted for the next move. Your lead is
 * your loaded save mon if you have one, else a random challenger.
 */
class TrainerActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#0B1020")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#8A87A0")
    private val GOLD = Color.parseColor("#F5D246")
    private val rng = Random()

    private lateinit var arena: TrainerView
    private lateinit var prompt: TextView
    private lateinit var grid: GridLayout
    private lateinit var again: TextView
    private lateinit var mic: TextView
    private val moveBtns = ArrayList<TextView>()

    private lateinit var dex: dev.pokepad.core.Dex
    private lateinit var battle: Battle
    private lateinit var a: Mon
    private lateinit var b: Mon
    private var maxL = 1f; private var maxR = 1f
    private val turnEvents = ArrayList<Ev>()
    private lateinit var commander: VoiceCommander

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this); SaveData.ensure(this); Sfx.ensure(this)
        dex = PokeData.dex()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        arena = TrainerView(this)
        root.addView(arena, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0E1430"))
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        prompt = TextView(this).apply { setTextColor(INK); textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
        panel.addView(prompt)
        grid = GridLayout(this).apply { columnCount = 2; rowCount = 2 }
        val cellW = (resources.displayMetrics.widthPixels - dp(40)) / 2
        repeat(4) {
            val btn = TextView(this).apply {
                textSize = 15f; setTextColor(INK); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                isClickable = true; isFocusable = true
            }
            btn.setOnClickListener { (btn.tag as? String)?.let { m -> Sfx.play("blip"); onMove(m) } }
            moveBtns.add(btn)
            grid.addView(btn, GridLayout.LayoutParams().apply {
                width = cellW; height = dp(58); setMargins(dp(4), dp(6), dp(4), dp(0))
            })
        }
        panel.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
        commander = VoiceCommander(this,
            onStatus = { s -> runOnUiThread { if (s.isNotEmpty()) prompt.text = s } },
            onMove = { m -> runOnUiThread { onMove(m) } })
        mic = TextView(this).apply {
            text = "🎤  VOICE MODE: OFF"; setTextColor(GOLD); textSize = 14f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#182042")); setStroke(dp(1), GOLD) }
            isClickable = true; isFocusable = true; setOnClickListener { onMicToggle() }
        }
        panel.addView(mic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        again = TextView(this).apply {
            text = "▶  NEW BATTLE"; setTextColor(INK); textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E23B3B")) }
            isClickable = true; isFocusable = true
            setOnClickListener { recreate() }
            setPadding(0, dp(14), 0, dp(14))
        }
        panel.addView(again, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        Insets.padBottom(panel)   // arena stays full-bleed; menu clears the nav bar

        startBattle()
    }

    private fun startBattle() {
        val ids = PokeData.speciesIds
        a = SaveData.battleLead?.let { SaveData.mon(it) }
            ?: SaveData.truth?.party?.firstOrNull { it.species != null }?.let { SaveData.mon(it) }
            ?: run { val s = ids[rng.nextInt(ids.size)]; Mon(dex, s, moves = Director.movesetFor(dex, s)) }
        var rs = ids[rng.nextInt(ids.size)]; while (rs == a.species.name) rs = ids[rng.nextInt(ids.size)]
        b = Mon(dex, rs, moves = Director.movesetFor(dex, rs))
        maxL = a.maxHp.toFloat(); maxR = b.maxHp.toFloat()
        battle = Battle(dex, listOf(a), listOf(b), seed = System.currentTimeMillis(), emit = { turnEvents.add(it) })

        setMenu(false); again.visibility = View.GONE
        prompt.text = "A wild ${b.name} appeared!"
        turnEvents.clear(); battle.startInteractive()
        val reel = Director.renderEvents(dex, a.species.name, b.species.name, ArrayList(turnEvents),
            maxL, maxR, maxL, maxR, leftBack = true, bothOut = false)
        arena.play(reel) { showMenu() }
    }

    private fun onMove(move: String) {
        setMenu(false)
        val hpBeforeL = battle.left.hp.toFloat(); val hpBeforeR = battle.right.hp.toFloat()
        turnEvents.clear(); battle.stepInteractive(move)
        val over = battle.over
        val winnerName = if (over) (battle.winner?.species?.name?.replaceFirstChar { it.uppercase() } ?: "") else ""
        val reel = Director.renderEvents(dex, a.species.name, b.species.name, ArrayList(turnEvents),
            hpBeforeL, hpBeforeR, maxL, maxR, leftBack = true, winnerName = winnerName, bothOut = true)
        arena.play(reel) { if (over) showResult() else showMenu() }
    }

    private fun showMenu() {
        prompt.text = "What will ${a.name} do?"
        val moves = battle.leftMoves()
        for (i in 0 until 4) {
            val btn = moveBtns[i]
            if (i < moves.size) {
                val name = moves[i]; val mv = dex.moves[name]
                val disp = name.replace("-", " ").replaceFirstChar { it.uppercase() }
                btn.text = if ((mv?.power ?: 0) > 0) "$disp\n${mv?.type?.uppercase()} · ${mv?.power}" else "$disp\n${mv?.type?.uppercase()}"
                btn.tag = name
                btn.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(typeColor(mv?.type)); setStroke(dp(1), Color.parseColor("#00000040")) }
                btn.visibility = View.VISIBLE
            } else btn.visibility = View.INVISIBLE
        }
        setMenu(true)
        commander.menuShown(listOf(a.name, a.species.name), moves)
    }

    private fun showResult() {
        val youWon = battle.winner === a
        arena.showVerdict(youWon)
        prompt.text = if (youWon) "You won! ${a.name} is victorious! 🏆" else "${b.name} won… ${a.name} fainted."
        prompt.setTextColor(if (youWon) GOLD else INK)
        setMenu(false); again.visibility = View.VISIBLE
    }

    private fun setMenu(show: Boolean) {
        grid.visibility = if (show) View.VISIBLE else View.GONE
        mic.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) commander.menuHidden()
    }

    // ── voice mode ────────────────────────────────────────────────────────────
    private fun onMicToggle() {
        if (!Voice.available(this)) { toast("voice input isn't available on this device"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        val on = commander.toggle()
        mic.text = if (on) "🎤  VOICE MODE: ON" else "🎤  VOICE MODE: OFF"
        if (on && grid.visibility == View.VISIBLE)
            commander.menuShown(listOf(a.name, a.species.name), battle.leftMoves())
        else if (!on) prompt.text = "What will ${a.name} do?"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) onMicToggle()
    }

    override fun onDestroy() { super.onDestroy(); commander.stop() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun typeColor(type: String?): Int {
        val rgb = when (type) {
            "fire" -> 0xC85A2E; "water" -> 0x3A6AB0; "electric" -> 0xB89A1E; "grass" -> 0x3E8A34
            "ice" -> 0x4A8A96; "fighting" -> 0xA83E3E; "poison" -> 0x7A3A8A; "ground" -> 0x9A7A3E
            "flying" -> 0x5A6EA8; "psychic" -> 0xB03E6E; "bug" -> 0x6E8A28; "rock" -> 0x7A6A3E
            "ghost" -> 0x4E3E7A; "dragon" -> 0x4A4AA8; "dark" -> 0x4A3E3A; "steel" -> 0x5A6470
            else -> 0x445070
        }
        return (0xFF000000.toInt()) or rgb
    }
}
