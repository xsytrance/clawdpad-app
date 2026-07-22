package dev.pokepad.block

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
import dev.pokepad.R
import dev.pokepad.ui.MainActivity

/**
 * The Keeper (Poképad edition): the block connection lives in this service, not
 * the activity, so leaving a screen doesn't drop the link. A foreground service
 * + a 4s supervisor that reconnects on a dead streamer or a stale ACK, backing
 * off 1s→30s and never giving up while a block is visible. Scene-only: a battle
 * owns the glass; snap detection fires onSnap when a second block joins/leaves.
 *
 * A trimmed port of ClawdPad's Host — same proven MIDI/keeper/snap logic, minus
 * the mood/music/gesture machinery.
 */
object Host {
    @Volatile var streamer: Streamer? = null
    val blocks = Blocks.State()
    @Volatile var wantHosting = false
    @Volatile var lastStatus = "idle"
    var onStatus: (String) -> Unit = {}
    var onHosting: () -> Unit = {}

    /** fires (snapped, secondDeviceIndex) when the block count crosses 2. */
    @Volatile var onSnap: ((Boolean, Int) -> Unit)? = null
    @Volatile private var lastDeviceCount = -1

    val touchListeners = java.util.concurrent.CopyOnWriteArrayList<(TouchEvent) -> Unit>()

    private var midi: MidiManager? = null
    private var handler: Handler? = null
    private var appCtx: Context? = null
    private var connecting = false
    private var retryDelay = 1000L

    /** hand the block glass to a scene (null = resting ball). Any thread. */
    fun setScene(s: Scene?) {
        val h = handler
        if (h == null) { streamer?.scene = s; return }
        h.post { streamer?.scene = s }
    }

    fun say(msg: String) {
        lastStatus = msg
        onStatus(msg)
        appCtx?.let { HostService.updateNotification(it, msg) }
    }

    fun start(ctx: Context) {
        if (appCtx != null) { connect(); return }
        appCtx = ctx.applicationContext
        midi = appCtx!!.getSystemService(Context.MIDI_SERVICE) as MidiManager
        val ht = HandlerThread("pokepad-keeper").apply { start() }
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
            val stale = blocks.lastAckAt > 0 && System.currentTimeMillis() - blocks.lastAckAt > 12_000
            if (wantHosting && !connecting && (s == null || !s.isAlive || (s.isAlive && stale))) {
                if (stale) say("link went quiet — restoring…")
                reconnect()
            }
            supervise()
        }, 4000)
    }

    private fun reconnect() {
        streamer?.quit(); streamer = null; blocks.lastAckAt = 0
        handler?.postDelayed({ connect() }, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(30_000)
    }

    fun connect() {
        val ctx = appCtx ?: return
        val m = midi ?: return
        if (connecting) return
        if (streamer?.isAlive == true && System.currentTimeMillis() - blocks.lastAckAt < 12_000) {
            say("hosting ${blocks.serial.ifEmpty { "your block" }} — connected")
            return
        }
        val infos = m.devices
        val info = infos.firstOrNull {
            (it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "")
                .contains(Regex("light|block", RegexOption.IGNORE_CASE)) }
            ?: infos.firstOrNull { it.inputPortCount > 0 }
        if (info == null) { say("no block in sight — plug in or bridge via MIDI BLE"); return }
        connecting = true
        say("opening ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}…")
        m.openDevice(info, { device: MidiDevice? ->
            val port = device?.openInputPort(0)
            if (port == null) { connecting = false; say("port busy — retrying shortly"); reconnect(); return@openDevice }
            val asm = SysexAssembler({ sysex ->
                Blocks.decode(sysex, blocks) { ev -> handler?.post { dispatchTouch(ev) } }
                val dc = blocks.deviceCount
                if (dc > 0 && dc != lastDeviceCount) {
                    val firstSeen = lastDeviceCount < 0
                    lastDeviceCount = dc
                    if (!firstSeen) {
                        val second = blocks.devices.firstOrNull { it.first != blocks.topologyIndex }?.first ?: -1
                        android.util.Log.i("pokepad-snap", "SNAP snapped=${dc >= 2} second=$second")
                        handler?.post { onSnap?.invoke(dc >= 2, second) }
                    }
                }
            }, onStray = { })
            device.openOutputPort(0)?.connect(object : android.media.midi.MidiReceiver() {
                override fun onSend(msg: ByteArray, off: Int, len: Int, ts: Long) = asm.feed(msg, off, len)
            })
            streamer = Streamer(ctx.assets, port, ::say, blocks).also { it.start() }
            connecting = false
            retryDelay = 1000L
            runCatching {
                ctx.startForegroundService(Intent(ctx, HostService::class.java).setAction("PROMOTE"))
            }
            onHosting()
        }, handler)
    }

    fun stop() {
        wantHosting = false
        streamer?.quit(); streamer = null
        say("stopped")
    }

    /** keeper thread only */
    private fun dispatchTouch(ev: TouchEvent) {
        streamer?.scene?.onTouch(ev)
        for (l in touchListeners) l(ev)
    }
}

class HostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { Host.stop(); stopSelf(); return START_NOT_STICKY }
        runCatching { startForeground(NOTE_ID, note(this, Host.lastStatus)) }
        return START_STICKY
    }

    companion object {
        private const val NOTE_ID = 7
        private const val CHANNEL = "pokepad"

        private fun note(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL,
                    "Poképad block link", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 1,
                Intent(ctx, HostService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Poképad — block connected")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "disconnect", stop).build())
                .build()
        }

        fun updateNotification(ctx: Context, text: String) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(ctx, text)) }
        }
    }
}
