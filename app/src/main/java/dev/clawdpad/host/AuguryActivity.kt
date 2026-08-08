package dev.clawdpad.host

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * THE AUGURY — the matrix, mirrored, in landscape.
 *
 * LANDSCAPE IS NOT A PREFERENCE, IT IS THE INSTRUMENT. The panel is 64x32 —
 * two to one — and that is how the Sovereign has it mounted. A portrait mirror
 * would waste four fifths of the screen on nothing and shrink the only thing
 * worth looking at. So this activity is locked landscape and the board fills
 * it, with the controls tucked down the side.
 *
 * The screen also stays awake here: a wall-mounted readout that blanks after
 * thirty seconds is not a readout. That is scoped to THIS activity, never the
 * hub — MainActivity deliberately omits KEEP_SCREEN_ON for battery kindness.
 *
 * Reads the frame the Archive publishes (auguryd -> frame.json -> /api/augury).
 * Speaking rides the Cadence, which alone decides tiering and quiet hours.
 */
class AuguryActivity : Activity() {

    private lateinit var board: AuguryView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var running = true

    private val BG = Color.parseColor("#0a0806")
    private val MUTED = Color.parseColor("#8a7f74")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        // ── the board, given every pixel it can take ─────────────────
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -1, 3f)
        }
        board = AuguryView(this)
        left.addView(board, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "waiting for the first frame…"
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        left.addView(status)
        root.addView(left)

        // ── controls, down the side where they cost the board nothing ─
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -1, 2f)
        }

        right.addView(TextView(this).apply {
            text = "SAY SOMETHING"
            setTextColor(Color.parseColor("#d97757"))
            textSize = 12f
            letterSpacing = 0.14f
        })

        val input = EditText(this).apply {
            hint = "HELLO SOVEREIGN"
            setHintTextColor(Color.parseColor("#4a423c"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            textSize = 14f
            maxLines = 1
        }
        right.addView(input, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })

        // The panel renders 15 characters. Say so WHILE he types rather than
        // truncating afterwards and letting him wonder where the rest went.
        val limit = TextView(this).apply {
            setTextColor(Color.parseColor("#c47f10"))
            textSize = 10f
            visibility = View.GONE
        }
        right.addView(limit)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = (s?.toString() ?: "").trim()
                if (t.length > 15) {
                    limit.text = "The panel shows 15: " + t.uppercase().take(15)
                    limit.visibility = View.VISIBLE
                } else limit.visibility = View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val send = TextView(this).apply {
            text = "SEND TO THE GLASS"
            setTextColor(Color.parseColor("#1f7f93"))
            textSize = 12f
            letterSpacing = 0.1f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    say(t)
                    input.setText("")
                }
            }
        }
        right.addView(send, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        facts = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 11f
            setPadding(0, dp(18), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        right.addView(facts)
        root.addView(right)

        setContentView(root)
        poll()
    }

    private lateinit var facts: TextView

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** Every 2s, matching the board's own tick. */
    private fun poll() {
        thread {
            while (running) {
                try {
                    val conn = URL("${Prime.API}/api/augury/frame")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    val body = conn.inputStream.bufferedReader().readText()
                    val f = JSONObject(body)
                    ui.post {
                        board.show(f)
                        val live = f.optBoolean("live", false)
                        val age = f.optDouble("age_s", 0.0)
                        // A frozen board must never be presented as a live one.
                        status.text = if (live) "● live · ${age}s ago"
                        else "⚠ ${age}s old — auguryd may be down. NOT live."
                        status.setTextColor(
                            if (live) MUTED else Color.parseColor("#c47f10"))
                        facts.text = listOf(
                            "Drift          ${f.optInt("drift")}",
                            "Commission     ${f.optString("title")}",
                            "Circle         ${f.optString("circle")}",
                            "Failed engines ${f.optInt("failed")}",
                            "The Ark        ${f.optString("ark")}",
                            "Realms         ${f.optJSONArray("realms")?.length() ?: 0}",
                        ).joinToString("\n")
                    }
                } catch (e: Exception) {
                    ui.post {
                        board.show(null)
                        status.text = "✗ cannot reach the Archive at ${Prime.API}"
                        status.setTextColor(Color.parseColor("#c23b3b"))
                    }
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun say(text: String) {
        thread {
            val ok = try {
                val url = URL("${Prime.API}/api/augury/say?text=" +
                        URLEncoder.encode(text, "UTF-8"))
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.responseCode in 200..299
            } catch (e: Exception) {
                false
            }
            ui.post {
                status.text = if (ok) "sent — watch the glass"
                else "the Cadence did not take it"
            }
        }
    }
}

/**
 * Where the Citadel answers. Overridable so the app is not welded to one
 * address: MagicDNS resolves `prime` on the Ley Lines, and the numeric fallback
 * covers a phone that has DNS trouble but still has the tailnet.
 */
object Prime {
    var API: String = "http://100.96.211.44:8801"
}
