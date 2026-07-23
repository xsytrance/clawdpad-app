package dev.pokepad.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.pokepad.R
import dev.pokepad.block.Host
import dev.pokepad.block.PokeBlockScene
import dev.pokepad.core.PokeData
import java.util.Random

/**
 * The block-connection screen. Starts the Keeper (MIDI + auto-reconnect), shows
 * live status, and turns a snap into a real battle on the LEDs: connect one
 * block, snap a second on, and a Gen-III fight plays across both. Pull them apart
 * to end it.
 */
class ConnectActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#0B1020")
    private val INK = Color.parseColor("#ECEAF6")
    private val DIM = Color.parseColor("#8A87A0")
    private val GOLD = Color.parseColor("#F5D246")
    private lateinit var status: TextView
    private val rng = Random()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PokeData.ensure(this)
        requestPerms()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(28), dp(64), dp(28), dp(36))
        }
        root.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_round) },
            LinearLayout.LayoutParams(dp(88), dp(88)))
        root.addView(TextView(this).apply {
            text = "CONNECT YOUR BLOCK"
            setTextColor(GOLD); textSize = 15f; letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }, lp(18))

        status = TextView(this).apply {
            text = "starting…"; setTextColor(INK); textSize = 16f; gravity = Gravity.CENTER
        }
        root.addView(status, lp(20).also { it.width = dp(300) })

        root.addView(TextView(this).apply {
            text = "1.  Pair the Lightpad Block (USB, or BLE-MIDI via a bridge app)\n\n" +
                   "2.  Wait for “connected”\n\n" +
                   "3.  Snap a second block on → a battle plays across both\n\n" +
                   "4.  Pull them apart to end it"
            setTextColor(DIM); textSize = 14f; gravity = Gravity.START
        }, lp(28).also { it.width = dp(300) })

        setContentView(root)
        Insets.pad(root)

        Host.onStatus = { s -> runOnUiThread { status.text = s } }
        Host.onSnap = { snapped, second -> runOnUiThread { onSnap(snapped, second) } }
        Host.start(this)
    }

    private fun onSnap(snapped: Boolean, second: Int) {
        val streamer = Host.streamer ?: return
        if (snapped) {
            streamer.secondIdx = second
            if (streamer.scene is PokeBlockScene) return   // already battling
            // if you've loaded your save, YOUR mon lead the block battles
            val factories: List<() -> dev.pokepad.core.Mon> =
                dev.pokepad.save.SaveData.truth?.party?.filter { it.species != null }
                    ?.map { sm -> { dev.pokepad.save.SaveData.mon(sm) } } ?: emptyList()
            Host.setScene(PokeBlockScene(System.currentTimeMillis(),
                onLog = { line -> runOnUiThread { status.text = line } }, playerFactories = factories))
        } else {
            streamer.secondIdx = -1
            (streamer.scene as? PokeBlockScene)?.abort()
            status.text = "blocks apart — battle ended"
        }
    }

    private fun requestPerms() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }
}
