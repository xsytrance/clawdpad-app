package dev.clawdpad.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * The keeper: Clawd's connection lives here, not in the activity — so
 * closing the app doesn't kill him. A foreground service (connectedDevice
 * type) + a supervisor heartbeat:
 *
 *  - the block ACKs our pings about once a second → that's the pulse.
 *    No ACK for 12s while hosting = the link is half-dead → reconnect.
 *  - streamer thread died (USB yank, BLE drop, exception) → reconnect.
 *  - reconnects back off 1s → 30s and never give up while a block is
 *    visible; live ACK-sync means no power-cycling, ever.
 *
 * Battery: supervision is one cheap check every 4s on a handler thread;
 * frame streaming is already diff-based (idle Clawd is a few bytes per
 * frame); no wakelocks, no screen-on flag.
 */
object Host {
    @Volatile var streamer: Streamer? = null
    val blocks = Blocks.State()
    @Volatile var wantHosting = false
    @Volatile var lastStatus = "idle"
    var onStatus: (String) -> Unit = {}
    var onHosting: () -> Unit = {}

    private var midi: MidiManager? = null
    private var handler: Handler? = null
    private var appCtx: Context? = null
    private var connecting = false
    private var retryDelay = 1000L
    private var lastTouch = 0L

    fun say(msg: String) {
        lastStatus = msg
        onStatus(msg)
        appCtx?.let { HostService.updateNotification(it, msg) }
    }

    fun start(ctx: Context) {
        if (appCtx != null) { connect(); return }
        appCtx = ctx.applicationContext
        midi = appCtx!!.getSystemService(Context.MIDI_SERVICE) as MidiManager
        val ht = HandlerThread("clawd-keeper").apply { start() }
        handler = Handler(ht.looper)
        midi!!.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                say("block appeared — connecting")
                retryDelay = 1000L
                handler?.post { connect() }
            }
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                say("block vanished — will reconnect when it returns")
            }
        }, handler)
        supervise()
        wantHosting = true
        connect()
    }

    private fun supervise() {
        handler?.postDelayed({
            val s = streamer
            val stale = blocks.lastAckAt > 0 &&
                    System.currentTimeMillis() - blocks.lastAckAt > 12_000
            if (wantHosting && !connecting &&
                (s == null || !s.isAlive || (s.isAlive && stale))) {
                if (stale) say("link went quiet — restoring…")
                reconnect()
            }
            supervise()
        }, 4000)
    }

    private fun reconnect() {
        streamer?.quit()
        streamer = null
        blocks.lastAckAt = 0
        handler?.postDelayed({ connect() }, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(30_000)
    }

    fun connect() {
        val ctx = appCtx ?: return
        val m = midi ?: return
        if (connecting) return
        if (streamer?.isAlive == true &&
            System.currentTimeMillis() - blocks.lastAckAt < 12_000) {
            say("hosting ${blocks.serial.ifEmpty { "your block" }} — he's fine")
            return
        }
        val infos = m.devices
        val info = infos.firstOrNull {
            (it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "")
                .contains(Regex("light|block", RegexOption.IGNORE_CASE)) }
            ?: infos.firstOrNull { it.inputPortCount > 0 }
        if (info == null) {
            say("no block in sight — plug in or bridge via MIDI BLE Connect")
            return
        }
        connecting = true
        say("opening ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}…")
        m.openDevice(info, { device: MidiDevice? ->
            val port = device?.openInputPort(0)
            if (port == null) {
                connecting = false
                say("port busy — retrying shortly")
                reconnect()
                return@openDevice
            }
            val asm = SysexAssembler { sysex ->
                Blocks.decode(sysex, blocks)
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
                                        ts: Long) = asm.feed(msg, off, len)
                })
            streamer = Streamer(ctx.assets, port, ::say, blocks)
                .also { it.start() }
            connecting = false
            retryDelay = 1000L
            onHosting()
        }, handler)
    }

    fun stop() {
        wantHosting = false
        streamer?.quit()
        streamer = null
        say("stopped")
    }
}

class HostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Host.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTE_ID, note(this, Host.lastStatus))
        Host.start(this)
        return START_STICKY   // resurrect after system kills
    }

    companion object {
        private const val NOTE_ID = 7
        private const val CHANNEL = "clawd"

        private fun note(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL,
                    "Clawd is alive", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 1,
                Intent(ctx, HostService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Clawd is alive 🐾")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "let him sleep",
                    stop).build())
                .build()
        }

        fun updateNotification(ctx: Context, text: String) {
            runCatching {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(NOTE_ID, note(ctx, text))
            }
        }
    }
}
