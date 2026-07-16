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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG

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
        }
        root.addView(status)

        // ── the portrait: Clawd lives here too ───────────────────────
        val frame = LinearLayout(this).apply {
            background = rounded(CARD, BORDER, 26)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        portrait = ClawdView(this)
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
        val shelf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val shelfCards = mutableMapOf<String, LinearLayout>()
        for (c in Clawdrobe.ALL) {
            val cc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(CARD, BORDER, 20)
                setPadding(dp(16), dp(12), dp(16), dp(10))
                pressable {
                    sounds?.play("boop", 0.4f)
                    wear(c.id, shelfCards)
                }
            }
            cc.addView(TextView(this).apply {
                text = c.emoji; textSize = 26f; gravity = Gravity.CENTER
            })
            cc.addView(TextView(this).apply {
                text = c.label; textSize = 12f
                setTextColor(INK); gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
            shelfCards[c.id] = cc
            shelf.addView(cc, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(shelf)
        })

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

        // auto-host the moment a block appears (USB plug / BLE bridge)
        val midi = getSystemService(MIDI_SERVICE) as MidiManager
        midi.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                runOnUiThread { connect() }
            }
        }, android.os.Handler(mainLooper))
        android.os.Handler(mainLooper).postDelayed({ connect() }, 700)
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

    private fun wear(id: String, cards: Map<String, LinearLayout>) {
        ClawdRenderer.costume = id
        streamer?.live = id != "none"    // costumes are live-rendered
        setMode("awake")
        for ((cid, v) in cards)
            v.background = rounded(CARD,
                if (cid == id) CORAL else BORDER, 20)
        val c = Clawdrobe.byId(id)
        say(if (id == "none") "back to his birthday suit"
            else "wearing: ${c?.emoji} ${c?.label}")
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
        val midi = getSystemService(MIDI_SERVICE) as MidiManager
        val infos = midi.devices
        if (infos.isEmpty()) {
            say("no block found — plug in via USB, or bridge in MIDI BLE Connect")
            return
        }
        val info = infos.firstOrNull { name(it).contains("light", true) ||
                name(it).contains("block", true) }
            ?: infos.firstOrNull { it.inputPortCount > 0 } ?: infos[0]
        if (connecting) return
        if (streamer?.isAlive == true) {
            say("already hosting — he's yours")
            return
        }
        connecting = true
        say("opening ${name(info)}…")
        streamer?.quit()
        streamer = null
        midi.openDevice(info, { device: MidiDevice? ->
            val port = device?.openInputPort(0)
            if (port == null) {
                connecting = false
                say(if (streamer?.isAlive == true) "already hosting — he's yours"
                    else "couldn't open ${name(info)} — toggle in MIDI BLE Connect; if reconnecting, power-cycle the block too")
                return@openDevice
            }
            val asm = SysexAssembler { sysex ->
                android.util.Log.i("clawdpad-rx",
                    sysex.joinToString(" ") { "%02X".format(it) })
                if (Touch.isTouchStart(sysex)) {
                    val now = System.currentTimeMillis()
                    val double = now - lastTouch < 600
                    lastTouch = now
                    streamer?.play(if (double) "jump" else "wave")
                }
            }
            device.openOutputPort(0)?.connect(
                object : android.media.midi.MidiReceiver() {
                    override fun onSend(msg: ByteArray, off: Int, len: Int,
                                        ts: Long) {
                        asm.feed(msg, off, len)
                    }
                })
            streamer = Streamer(assets, port, ::say).also { it.start() }
            connecting = false
            sounds?.play("hello")
        }, null)
    }

    private fun name(i: MidiDeviceInfo): String =
        i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "midi device"

    override fun onDestroy() {
        musicMode?.stop()
        streamer?.quit()
        super.onDestroy()
    }
}
