package dev.pokepad.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.pokepad.block.Host
import dev.pokepad.block.MirrorScene
import dev.pokepad.core.Cell
import dev.pokepad.core.Director
import dev.pokepad.core.PokeData
import dev.pokepad.core.Reel
import dev.pokepad.net.DuelClient
import dev.pokepad.net.DuelServer
import dev.pokepad.net.DuelSide
import dev.pokepad.net.HostCore
import dev.pokepad.net.JoinCore
import dev.pokepad.net.Proto
import dev.pokepad.net.localIp
import dev.pokepad.save.SaveData
import java.util.Random

/**
 * 2-PLAYER DUEL — the crown feature. Two phones on the same wifi: one hosts,
 * one joins (auto-discovered via the beacon, or by typed IP). Each trainer
 * commands their own mon each turn; the HOST runs the one true engine and both
 * phones animate the same resolved truth. If your Lightpad is connected, your
 * block mirrors YOUR Pokémon during the fight.
 */
class DuelActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#0B1020")
    private val CARD = Color.parseColor("#141B33")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#8A87A0")
    private val GOLD = Color.parseColor("#F5D246")
    private val RED = Color.parseColor("#E23B3B")
    private val ui = Handler(Looper.getMainLooper())
    private val rng = Random()

    // lobby views
    private lateinit var lobby: LinearLayout
    private lateinit var lobbyTitle: TextView
    private lateinit var lobbyStatus: TextView
    private lateinit var hostBtn: TextView
    private lateinit var joinBtn: TextView
    private lateinit var ipRow: LinearLayout
    private lateinit var ipEdit: EditText

    // battle views
    private lateinit var battleBox: LinearLayout
    private lateinit var arena: TrainerView
    private lateinit var prompt: TextView
    private lateinit var grid: GridLayout
    private lateinit var again: TextView
    private lateinit var mic: TextView
    private val moveBtns = ArrayList<TextView>()
    private lateinit var commander: VoiceCommander

    private var server: DuelServer? = null
    private var client: DuelClient? = null
    private var side: DuelSide? = null
    private var isHost = false
    private var autoplay = false
    @Volatile private var searching = false
    @Volatile private var inBattle = false
    private lateinit var mySpec: Proto.MonSpec

    // block mirror
    @Volatile private var curReel: Reel? = null
    @Volatile private var reelStart = 0L
    private var mirror: MirrorScene? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this); SaveData.ensure(this); Sfx.ensure(this)
        mySpec = pickMySpec()
        autoplay = intent.getStringExtra("autoplay") == "1"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        buildLobby(root); buildBattle(root)
        setContentView(root)
        Insets.pad(lobby); Insets.padBottom(battlePanel)
        showLobby()

        // block mirror: if a Lightpad is connected, your block = your mon
        if (Host.streamer?.isAlive == true) {
            mirror = MirrorScene { currentCell() }.also { Host.setScene(it) }
        }

        when (intent.getStringExtra("role")) {
            "host" -> startHost()
            "join" -> { val ip = intent.getStringExtra("ip"); if (ip != null) connectTo(ip) else startJoin() }
        }
    }

    private lateinit var fighterLine: TextView
    private lateinit var changeBtn: TextView
    private var fighterIdx = 0   // index into party; party.size = random

    private fun cycleFighter() {
        val party = SaveData.truth?.party?.filter { it.species != null } ?: emptyList()
        fighterIdx = (fighterIdx + 1) % (party.size + 1)
        mySpec = if (fighterIdx < party.size) {
            val sm = party[fighterIdx]
            val m = SaveData.mon(sm)
            Proto.MonSpec(sm.species!!, sm.level, sm.nickname, sm.ability, sm.nature, sm.ivs, sm.evs, m.moves)
        } else randomSpec()
        refreshFighterLine()
    }

    private fun refreshFighterLine() {
        val tag = if (fighterIdx >= (SaveData.truth?.party?.count { it.species != null } ?: 0)) " (random)" else ""
        fighterLine.text = "Your fighter: ${mySpec.nickname} (${cap(mySpec.species)} Lv${mySpec.level})$tag\nSame wifi, one hosts, one joins."
    }

    private fun currentCell(): Cell? {
        val r = curReel ?: return null
        val i = (((System.currentTimeMillis() - reelStart) / (1000.0 / Director.FPS)).toInt())
            .coerceIn(0, r.cells.size - 1)
        return r.cells[i]
    }

    private fun pickMySpec(): Proto.MonSpec {
        val sm = SaveData.battleLead ?: SaveData.truth?.party?.firstOrNull { it.species != null }
        if (sm?.species != null) {
            val m = SaveData.mon(sm)
            return Proto.MonSpec(sm.species!!, sm.level, sm.nickname, sm.ability, sm.nature, sm.ivs, sm.evs, m.moves)
        }
        return randomSpec()
    }

    private fun randomSpec(): Proto.MonSpec {
        val ids = PokeData.speciesIds
        val sp = ids[rng.nextInt(ids.size)]
        return Proto.MonSpec(sp, 50, sp.replaceFirstChar { it.uppercase() }, null, "hardy",
            listOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 31 },
            listOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 },
            Director.movesetFor(PokeData.dex(), sp))
    }

    // ── lobby ────────────────────────────────────────────────────────────────
    private fun buildLobby(root: LinearLayout) {
        lobby = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        lobbyTitle = TextView(this).apply {
            text = "2-PLAYER DUEL"; setTextColor(GOLD); textSize = 16f; letterSpacing = 0.14f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }
        lobby.addView(lobbyTitle, lp(0))
        fighterLine = TextView(this).apply {
            setTextColor(DIM); textSize = 14f; gravity = Gravity.CENTER
        }
        lobby.addView(fighterLine, lp(10).also { it.width = dp(300) })
        changeBtn = bigBtn("⟳  CHANGE FIGHTER", CARD) { cycleFighter() }
        lobby.addView(changeBtn, lp(10).also { it.width = dp(300); it.height = dp(44) })
        refreshFighterLine()
        lobbyStatus = TextView(this).apply { text = ""; setTextColor(INK); textSize = 15f; gravity = Gravity.CENTER }
        lobby.addView(lobbyStatus, lp(18).also { it.width = dp(300) })

        hostBtn = bigBtn("🏠  HOST A BATTLE", RED) { startHost() }
        joinBtn = bigBtn("🔍  JOIN A BATTLE", CARD) { startJoin() }
        lobby.addView(hostBtn, lp(22).also { it.width = dp(300); it.height = dp(58) })
        lobby.addView(joinBtn, lp(12).also { it.width = dp(300); it.height = dp(58) })

        ipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        ipEdit = EditText(this).apply {
            hint = "host IP e.g. 192.168.1.62"; setHintTextColor(DIM); setTextColor(INK)
            inputType = InputType.TYPE_CLASS_TEXT; textSize = 14f
        }
        ipRow.addView(ipEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ipRow.addView(bigBtn("GO", CARD) { val ip = ipEdit.text.toString().trim(); if (ip.isNotEmpty()) connectTo(ip) },
            LinearLayout.LayoutParams(dp(64), dp(46)))
        lobby.addView(ipRow, lp(14).also { it.width = dp(300) })
        root.addView(lobby, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun startHost() {
        isHost = true; searching = false
        server?.stop(); client?.stop()
        hostBtn.visibility = View.GONE; joinBtn.visibility = View.GONE; ipRow.visibility = View.GONE
        changeBtn.visibility = View.GONE
        lobbyStatus.text = "Waiting for a challenger…\nTell them to tap JOIN.\n(your address: ${localIp()})"
        val core = HostCore(PokeData.dex(), mySpec, System.currentTimeMillis(),
            send = { l -> server?.send(l) },
            onReel = { r -> ui.post { playReel(r) } },
            onMenu = { ui.post { showMenu() } },
            onStatus = { s -> ui.post { prompt.text = s } },
            onOver = { won -> ui.post { showResult(won) } })
        side = core
        server = DuelServer(mySpec.nickname,
            onLine = { l -> core.onLine(l) },
            onJoin = { addr -> ui.post { lobbyStatus.text = "challenger connected ($addr)!" } },
            onDrop = { msg -> ui.post { backToLobby("⚡ $msg") } })
        server?.start()
    }

    private fun startJoin() {
        isHost = false; searching = true
        server?.stop(); client?.stop()
        hostBtn.visibility = View.GONE; joinBtn.visibility = View.GONE; ipRow.visibility = View.VISIBLE
        changeBtn.visibility = View.GONE
        lobbyStatus.text = "Searching for a host on your wifi…"
        scan()
    }

    /** keep scanning until a host appears, the user types an IP, or we leave */
    private fun scan() {
        if (!searching || isFinishing) return
        DuelClient.discover(4, onFound = { ip, name, _ ->
            ui.post { if (searching) { searching = false; lobbyStatus.text = "found $name at $ip — connecting…"; connectTo(ip) } }
        }, onMiss = {
            ui.post { if (searching) { lobbyStatus.text = "searching… (ask them to tap HOST — or type their IP)"; scan() } }
        })
    }

    private fun connectTo(ip: String) {
        searching = false
        client?.stop()
        val core = JoinCore(PokeData.dex(), mySpec,
            send = { l -> client?.send(l) },
            onReel = { r -> ui.post { playReel(r) } },
            onMenu = { ui.post { showMenu() } },
            onStatus = { s -> ui.post { prompt.text = s } },
            onOver = { won -> ui.post { showResult(won) } })
        side = core
        client = DuelClient(
            onLine = { l -> core.onLine(l) },
            onConnected = { client?.send(core.helloLine()); ui.post { lobbyStatus.text = "connected — summoning!" } },
            onDrop = { msg -> ui.post { backToLobby("⚡ $msg") } })
        client?.connect(ip)
    }

    /** a drop never ejects you — back to the lobby, ready to host/join again */
    private fun backToLobby(msg: String) {
        if (isFinishing) return
        inBattle = false; searching = false
        server?.stop(); server = null
        client?.stop(); client = null
        side = null; curReel = null
        showLobby()
        hostBtn.visibility = View.VISIBLE; joinBtn.visibility = View.VISIBLE; ipRow.visibility = View.GONE
        changeBtn.visibility = View.VISIBLE
        lobbyStatus.text = "$msg\nready when you are."
    }

    // ── battle ───────────────────────────────────────────────────────────────
    private lateinit var battlePanel: LinearLayout

    private fun buildBattle(root: LinearLayout) {
        battleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        arena = TrainerView(this)
        battleBox.addView(arena, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        battlePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0E1430"))
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        prompt = TextView(this).apply { setTextColor(INK); textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
        battlePanel.addView(prompt)
        grid = GridLayout(this).apply { columnCount = 2 }
        val cellW = (resources.displayMetrics.widthPixels - dp(40)) / 2
        repeat(4) {
            val btn = TextView(this).apply {
                textSize = 15f; setTextColor(INK); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                isClickable = true; isFocusable = true
            }
            btn.setOnClickListener { (btn.tag as? String)?.let { m -> Sfx.play("blip"); pick(m) } }
            moveBtns.add(btn)
            grid.addView(btn, GridLayout.LayoutParams().apply {
                width = cellW; height = dp(58); setMargins(dp(4), dp(6), dp(4), 0)
            })
        }
        battlePanel.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
        commander = VoiceCommander(this,
            onStatus = { s -> ui.post { if (s.isNotEmpty()) prompt.text = s } },
            onMove = { m -> ui.post { pick(m) } })
        mic = TextView(this).apply {
            text = "🎤  VOICE MODE: OFF"; setTextColor(GOLD); textSize = 14f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#182042")); setStroke(dp(1), GOLD) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val on = commander.toggle()
                mic.text = if (on) "🎤  VOICE MODE: ON" else "🎤  VOICE MODE: OFF"
                if (on && grid.visibility == View.VISIBLE)
                    commander.menuShown(listOf(mySpec.nickname, mySpec.species), side?.myMoves() ?: emptyList())
            }
        }
        battlePanel.addView(mic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        again = TextView(this).apply {
            text = "▶  REMATCH"; setTextColor(INK); textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(RED) }
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener { rematch() }
        }
        battlePanel.addView(again, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        battleBox.addView(battlePanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(battleBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun showLobby() {
        lobby.visibility = View.VISIBLE; battleBox.visibility = View.GONE; inBattle = false
        Music.play(this, "music_menu", 0.35f)
    }
    private fun showBattle() {
        if (!inBattle) Music.play(this, "music_battle")
        lobby.visibility = View.GONE; battleBox.visibility = View.VISIBLE; inBattle = true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inBattle) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Leave the battle?")
                .setMessage("Your opponent will be sent back to their lobby.")
                .setPositiveButton("Leave") { _, _ -> finish() }
                .setNegativeButton("Keep fighting", null)
                .show()
        } else super.onBackPressed()
    }

    private fun playReel(r: Reel) {
        showBattle()
        setMenu(false); again.visibility = View.GONE
        prompt.text = "${r.leftName} vs ${r.rightName}"
        curReel = r; reelStart = System.currentTimeMillis()
        arena.play(r) {
            (side as? HostCore)?.reelDone(); (side as? JoinCore)?.reelDone()
        }
    }

    private fun showMenu() {
        val s = side ?: return
        prompt.text = "What will ${s.myName()} do?"
        val dex = PokeData.dex()
        val moves = s.myMoves()
        for (i in 0 until 4) {
            val btn = moveBtns[i]
            if (i < moves.size) {
                val name = moves[i]; val mv = dex.moves[name]
                val disp = name.replace("-", " ").replaceFirstChar { it.uppercase() }
                btn.text = if ((mv?.power ?: 0) > 0) "$disp\n${mv?.type?.uppercase()} · ${mv?.power}" else "$disp\n${mv?.type?.uppercase()}"
                btn.tag = name
                btn.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat(); setColor(typeColor(mv?.type)); setStroke(dp(1), Color.parseColor("#00000040"))
                }
                btn.visibility = View.VISIBLE
            } else btn.visibility = View.INVISIBLE
        }
        setMenu(true)
        commander.menuShown(listOf(mySpec.nickname, mySpec.species), moves)
        if (autoplay && moves.isNotEmpty()) ui.postDelayed({ pick(moves[0]) }, 500)
    }

    private fun pick(move: String) { setMenu(false); side?.chooseMove(move) }

    private fun showResult(won: Boolean) {
        arena.showVerdict(won)
        prompt.text = if (won) "YOU WON! 🏆" else "you lost… rematch?"
        prompt.setTextColor(if (won) GOLD else INK)
        setMenu(false); again.visibility = View.VISIBLE
        if (autoplay) ui.postDelayed({ if (!isFinishing) rematch() }, 6000)   // let the verdict breathe in demo mode
    }

    private fun rematch() {
        prompt.setTextColor(INK); again.visibility = View.GONE
        if (isHost) (side as? HostCore)?.onLine("AGAIN") else (side as? JoinCore)?.again()
        prompt.text = "waiting for the next round…"
    }

    private fun setMenu(show: Boolean) {
        grid.visibility = if (show) View.VISIBLE else View.GONE
        mic.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) commander.menuHidden()
    }

    override fun onPause() { super.onPause(); Music.stop() }
    override fun onResume() {
        super.onResume()
        Music.play(this, if (inBattle) "music_battle" else "music_menu", if (inBattle) 0.45f else 0.35f)
    }

    override fun onDestroy() {
        super.onDestroy()
        commander.stop()
        server?.stop(); client?.stop()
        mirror?.abort(); if (mirror != null) Host.setScene(null)
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun bigBtn(label: String, bg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(INK); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(bg); setStroke(dp(1), Color.parseColor("#26314F")) }
        isClickable = true; isFocusable = true; setOnClickListener { Sfx.play("blip"); onClick() }
    }
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
