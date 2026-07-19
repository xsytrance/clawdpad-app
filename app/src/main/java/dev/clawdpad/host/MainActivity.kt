package dev.clawdpad.host

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * clawdpad — Clawd's pocket brain. One screen, made with love:
 * live LED-dot portrait, mood cards with haptics, friendly status,
 * and the whole ROLI host underneath.
 */
class MainActivity : AppCompatActivity() {

    private var streamer: Streamer? = null
    private var lastTouch = 0L
    private var musicMode: MusicMode? = null
    private var sounds: Sounds? = null
    private var lyricSparks: LyricSparks? = null
    private var lyricTimer: android.os.Handler? = null

    private fun lyricTick() {
        val ls = lyricSparks ?: return
        val info = ls.refresh()
        if (musicMode != null) say("🎵 dancing · $info")
        lyricTimer?.postDelayed({ lyricTick() }, 4000)
    }
    @Volatile private var connecting = false

    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var portrait: ClawdView
    private lateinit var danceCard: LinearLayout
    private lateinit var robeShelf: LinearLayout
    private lateinit var statsLine: TextView
    private lateinit var battleLog: TextView
    private val robeTabs = HashMap<String, TextView>()
    private var robeCat = "prop"

    private val BG = Color.parseColor("#171310")
    private val CARD = Color.parseColor("#211B17")
    private val BORDER = Color.parseColor("#3A2F28")
    private val CORAL = Color.parseColor("#D97757")
    private val DIM = Color.parseColor("#A89A8F")
    private val INK = Color.parseColor("#EFE6DF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int = 0, radius: Int = 20) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(2), stroke)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG  // no KEEP_SCREEN_ON: the foreground
        // service keeps him alive with the screen off (battery kindness)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        // ── header: wordmark + connection dot ────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "clawdpad"
            textSize = 34f
            setTextColor(CORAL)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dot = View(this).apply {
            background = rounded(Color.parseColor("#7A4A3A"), radius = 12)
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)))
        root.addView(header)

        status = TextView(this).apply {
            text = "looking for your block…"
            textSize = 14f
            setTextColor(DIM)
            setPadding(0, dp(6), 0, dp(14))
            // dev rig: long-press toggles raw MIDI capture for the touch
            // protocol work — adb logcat -s clawd-capture
            setOnLongClickListener {
                Host.captureRaw = !Host.captureRaw
                say(if (Host.captureRaw)
                    "🎙 capture ON — adb logcat -s clawd-capture"
                    else "capture off")
                true
            }
        }
        root.addView(status)

        // ── the portrait: Clawd lives here too ───────────────────────
        val frame = LinearLayout(this).apply {
            background = rounded(CARD, BORDER, 26)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        portrait = ClawdView(this)
        // dev rig: long-press the portrait toggles finger-paint on the
        // block — end-to-end proof of the touch pipeline
        portrait.setOnLongClickListener {
            val cur = Host.streamer?.scene
            if (cur is PaintScene) {
                cur.stop()
                say("paint over — back to his life")
            } else {
                Host.setScene(PaintScene())
                say("🎨 finger-paint — touch the block, watch it glow")
            }
            true
        }
        frame.addView(portrait, LinearLayout.LayoutParams(dp(240), dp(240)))
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        // ── connect CTA ──────────────────────────────────────────────
        val cta = TextView(this).apply {
            text = "🔌  connect to my block"
            textSize = 17f
            setTextColor(BG)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = rounded(CORAL, radius = 18)
            setPadding(0, dp(15), 0, dp(15))
            pressable { connect() }
        }
        root.addView(cta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        // ── mood cards ───────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "HIS MOODS"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(DIM)
            setPadding(dp(4), 0, 0, dp(8))
        })

        val grid = GridLayout(this).apply {
            columnCount = 2
        }
        fun card(emoji: String, label: String, onTap: () -> Unit): LinearLayout {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(CARD, BORDER, 22)
                setPadding(0, dp(16), 0, dp(14))
                pressable {
                    sounds?.play("boop", 0.4f)
                    onTap()
                }
            }
            c.addView(TextView(this).apply {
                text = emoji; textSize = 30f; gravity = Gravity.CENTER
            })
            c.addView(TextView(this).apply {
                text = label; textSize = 14f
                setTextColor(INK); gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            val p = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(c, p)
            return c
        }

        card("🐾", "big clawd") { setMode("awake"); streamer?.play("full") }
        card("🐜", "chibi") { setMode("awake"); streamer?.play("mini") }
        card("👋", "wave") { streamer?.play("wave") }
        card("🎉", "jump!") { sounds?.play("jingle"); streamer?.play("jump") }
        card("🔳", "QR code") { streamer?.play("qr") }
        danceCard = card("🎵", "dance mode") { toggleDance() }
        root.addView(grid)

        // ── the Clawdrobe: costumes & characters ─────────────────────
        root.addView(TextView(this).apply {
            text = "THE CLAWDROBE"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(DIM)
            setPadding(dp(4), dp(18), 0, dp(8))
        })
        // category tabs — keeps a growing wardrobe scannable
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        for ((key, lbl) in listOf("prop" to "🎩 props", "character" to "🎭 characters")) {
            val tab = TextView(this).apply {
                text = lbl; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(INK)
                background = rounded(CARD, BORDER, 16)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                pressable { robeCat = key; sounds?.play("boop", 0.4f); populateRobe() }
            }
            robeTabs[key] = tab
            tabRow.addView(tab, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (key == "prop") rightMargin = dp(8)
            })
        }
        root.addView(tabRow)
        robeShelf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(robeShelf)
        })
        populateRobe()

        // ── TRAIN & BATTLE: raise him, then let him fight ────────────
        root.addView(TextView(this).apply {
            text = "TRAIN & BATTLE"
            textSize = 12f; letterSpacing = 0.18f
            setTextColor(DIM); setPadding(dp(4), dp(18), 0, dp(4))
        })
        statsLine = TextView(this).apply {
            textSize = 13f
            setTextColor(INK)
            setPadding(dp(4), 0, 0, dp(8))
        }
        root.addView(statsLine)

        val bgrid = GridLayout(this).apply { columnCount = 2 }
        fun bcard(emoji: String, label: String, onTap: () -> Unit) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(CARD, BORDER, 22)
                setPadding(0, dp(16), 0, dp(14))
                pressable { sounds?.play("boop", 0.4f); onTap() }
            }
            c.addView(TextView(this).apply {
                text = emoji; textSize = 30f; gravity = Gravity.CENTER
            })
            c.addView(TextView(this).apply {
                text = label; textSize = 14f
                setTextColor(INK); gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            bgrid.addView(c, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        bcard("⚔️", "battle") { startBattle(shadow = false) }
        bcard("🌙", "shadow match") { startBattle(shadow = true) }
        root.addView(bgrid)

        // training shelf: one mini-game per stat
        val trainShelf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun tcard(emoji: String, label: String, make: () -> MiniGame) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(CARD, BORDER, 20)
                setPadding(dp(16), dp(12), dp(16), dp(10))
                pressable {
                    sounds?.play("boop", 0.4f)
                    startGame("$emoji $label — on the block!", make())
                }
            }
            c.addView(TextView(this).apply {
                text = emoji; textSize = 26f; gravity = Gravity.CENTER
            })
            c.addView(TextView(this).apply {
                text = label; textSize = 12f
                setTextColor(INK); gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
            trainShelf.addView(c, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        tcard("🥊", "strike") { StrikeTarget(trainingEnd("power")) }
        tcard("🛡", "steady") { HoldSteady(trainingEnd("guard")) }
        tcard("🎯", "chase") { ChaseDot(trainingEnd("finesse")) }
        tcard("🥁", "rhythm") { RhythmTaps(trainingEnd("stamina")) }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(trainShelf)
            setPadding(0, dp(4), 0, 0)
        })

        battleLog = TextView(this).apply {
            textSize = 12f
            setTextColor(DIM)
            typeface = Typeface.MONOSPACE
            background = rounded(CARD, BORDER, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(battleLog, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        refreshStats()

        // ── message / art mode: put words on the glass ──────────────
        root.addView(TextView(this).apply {
            text = "PUT WORDS ON HIM"
            textSize = 12f; letterSpacing = 0.18f
            setTextColor(DIM); setPadding(dp(4), dp(18), 0, dp(8))
        })
        val msgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val msgField = EditText(this).apply {
            hint = "type a message…"
            setTextColor(INK); setHintTextColor(DIM); textSize = 15f
            background = rounded(CARD, BORDER, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        msgRow.addView(msgField, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sendMsg = TextView(this).apply {
            text = "📣"; textSize = 22f; gravity = Gravity.CENTER
            background = rounded(CORAL, radius = 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            pressable {
                sounds?.play("boop", 0.4f)
                val txt = msgField.text.toString().trim()
                ClawdRenderer.msg = txt
                updateLive()
                setMode("awake")
                say(if (txt.isEmpty()) "cleared the marquee" else "scrolling: $txt")
            }
        }
        msgRow.addView(sendMsg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(msgRow)

        root.addView(TextView(this).apply {
            text = "he lives on the block · pet him there · clawdpad v0.4"
            textSize = 12f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
        sounds = Sounds(this)

        // the Host service owns discovery, connection, and resurrection
        android.os.Handler(mainLooper).postDelayed({ connect() }, 500)
    }

    /** press animation + haptic on any view */
    private fun View.pressable(onTap: () -> Unit) {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start()
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    v.performClick()
                    onTap()
                }
                MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }
            true
        }
    }

    private fun setDot(color: Int) = runOnUiThread {
        dot.background = rounded(color, radius = 12)
    }

    private fun setMode(m: String) { portrait.mode = m }

    /** live rendering is needed whenever a costume or a message is active */
    private fun updateLive() {
        val on = ClawdRenderer.costume != "none" || ClawdRenderer.msg.isNotEmpty()
        Host.streamer?.live = on
        streamer?.live = on
    }

    private fun wear(id: String) {
        ClawdRenderer.costume = id
        updateLive()                         // costumes are live-rendered
        setMode("awake")
        val c = Clawdrobe.byId(id)
        say(if (id == "none") "back to his birthday suit"
            else "wearing: ${c?.emoji} ${c?.label}")
        populateRobe()                       // refresh the selected ring
    }

    /** Rebuild the costume shelf for the active category, ring the worn one,
     *  and highlight the active tab. "just clawd" always leads for a 1-tap
     *  reset. */
    private fun populateRobe() {
        for ((key, tab) in robeTabs)
            tab.background = rounded(CARD, if (key == robeCat) CORAL else BORDER, 16)
        robeShelf.removeAllViews()
        val worn = ClawdRenderer.costume
        val items = listOf(Clawdrobe.byId("none")!!) +
            Clawdrobe.ALL.filter { it.id != "none" &&
                (if (robeCat == "character") it.isSkin else !it.isSkin) }
        for (c in items) {
            val cc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(CARD, if (c.id == worn) CORAL else BORDER, 20)
                setPadding(dp(16), dp(12), dp(16), dp(10))
                pressable { sounds?.play("boop", 0.4f); wear(c.id) }
            }
            cc.addView(TextView(this).apply {
                text = c.emoji; textSize = 26f; gravity = Gravity.CENTER
            })
            cc.addView(TextView(this).apply {
                text = c.label; textSize = 12f
                setTextColor(INK); gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
            robeShelf.addView(cc, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
    }

    private fun refreshStats() = runOnUiThread {
        val s = ClawdState.stats
        statsLine.text = "Lv ${s.level} · ${s.xp} XP · " +
            "P${s.power} G${s.guard} F${s.finesse} S${s.stamina} · " +
            "style: ${ClawdState.style.experience} moves learned"
    }

    /** shared end-of-round handler for the training mini-games */
    private fun trainingEnd(stat: String): (Int, Int, Boolean) -> Unit =
        { score, xp, leveled ->
            runOnUiThread {
                if (leveled) {
                    sounds?.play("jingle")
                    say("🎉 $stat LEVEL UP! score $score, +$xp xp")
                } else say("scored $score → +$xp $stat xp")
                refreshStats()
            }
        }

    private fun startGame(label: String, game: MiniGame) {
        if (Host.streamer == null) {
            say("connect his block first — the games live on the glass")
            return
        }
        Host.setScene(game)
        setMode("awake")
        say(label)
    }

    private fun startBattle(shadow: Boolean) {
        if (Host.streamer == null) {
            say("connect his block first — the arena is the glass")
            return
        }
        // tapping again mid-fight throws the towel
        (Host.streamer?.scene as? BattleScene)?.let {
            it.abort()
            battleLog.visibility = View.GONE
            say("fight called off")
            return
        }
        val rival = Ladder.next(ClawdState.stats.level)
        runOnUiThread { battleLog.text = ""; battleLog.visibility = View.VISIBLE }
        val log: (String) -> Unit = { line ->
            runOnUiThread { battleLog.append("$line\n") }
        }
        val over: (Boolean) -> Unit = { leftWon ->
            runOnUiThread {
                if (leftWon) sounds?.play("jingle") else sounds?.play("chime")
                say(if (leftWon) "🏆 ${rival.name} is down!"
                    else "${rival.emoji} ${rival.name} took this one — train up")
                refreshStats()
            }
        }
        val scene = if (shadow) BattleScene.shadow(rival, onLog = log, onOver = over)
                    else BattleScene.versus(rival, onLog = log, onOver = over)
        Host.setScene(scene)
        setMode("awake")
        say(if (shadow)
            "🌙 shadow match: your clawd vs ${rival.emoji} ${rival.name} — hands off"
        else
            "⚔️ vs ${rival.emoji} ${rival.name}: strike to hit · hold to guard · swipe to dodge · scrub to charge")
    }

    private fun say(msg: String) = runOnUiThread {
        status.text = msg
        when {
            msg.contains("streaming") || msg.contains("keepalive") -> {
                setDot(Color.parseColor("#6FBF73")); setMode("awake")
            }
            msg.contains("opening") || msg.contains("handshake") ->
                setDot(Color.parseColor("#E0B23E"))
            msg.contains("died") || msg.contains("couldn") ->
                setDot(Color.parseColor("#C25548"))
        }
    }

    private fun toggleDance() {
        if (musicMode != null) {
            streamer?.music = null
            streamer?.lyrics = null
            lyricSparks = null
            lyricTimer?.removeCallbacksAndMessages(null)
            musicMode?.stop()
            musicMode = null
            setMode("awake")
            danceCard.background = rounded(CARD, BORDER, 22)
            say("dance over — back to his life")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
            say("allow audio (that's how he hears), then tap dance again")
            return
        }
        val m = MusicMode()
        if (!m.start()) {
            say("couldn't get ears — check the audio permission?")
            return
        }
        musicMode = m
        streamer?.music = m
        portrait.music = m
        setMode("dance")
        // Lyric Sparks ride along when we can see the media session
        val ls = LyricSparks(this)
        if (!ls.accessGranted()) {
            startActivity(android.content.Intent(
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            say("enable clawdpad in notification access for LYRIC SPARKS, then re-toggle dance")
        } else {
            lyricSparks = ls
            streamer?.lyrics = ls
            lyricTimer = android.os.Handler(mainLooper)
            lyricTick()
        }
        danceCard.background = rounded(CARD, CORAL, 22)
        say("🎵 listening via ${m.source} — play something with a beat")
    }

    private fun connect() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        Host.onStatus = { msg -> say(msg) }
        Host.onHosting = { runOnUiThread { sounds?.play("hello") } }
        Host.start(applicationContext)   // discovery+connect; promotes to
                                         // a foreground service once a
                                         // device is actually open
        streamerBind()
    }

    /** the activity always talks to whatever streamer the Host holds */
    private fun streamerBind() {
        streamer = Host.streamer
        refreshStats()   // cheap; keeps XP/level current after games
        android.os.Handler(mainLooper).postDelayed({ streamerBind() }, 1500)
    }

    private fun name(i: MidiDeviceInfo): String =
        i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "midi device"

    override fun onDestroy() {
        musicMode?.stop()   // ears die with the UI; the HOST lives on —
        super.onDestroy()   // Clawd survives the app closing, by design
    }
}
