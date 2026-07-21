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

    /** CLAWD COMBAT: fires (snapped, secondDeviceIndex) when the block
     *  topology count crosses 2 — i.e. a second block is snapped on / off.
     *  Delivered on the keeper thread. */
    @Volatile var onSnap: ((Boolean, Int) -> Unit)? = null
    @Volatile private var lastDeviceCount = -1   // -1 = not yet baselined

    /** raw-traffic capture for touch-protocol reverse engineering:
     *  adb logcat -s clawd-capture   (toggled by long-press on status) */
    @Volatile var captureRaw = false

    /** decoded touch stream, dispatched on the keeper thread */
    val touchListeners =
        java.util.concurrent.CopyOnWriteArrayList<(TouchEvent) -> Unit>()

    private var midi: MidiManager? = null
    private var handler: Handler? = null
    private var appCtx: Context? = null
    private var connecting = false
    private var retryDelay = 1000L
    private var lastTouch = 0L
    @Volatile private var sawRealTouch = false

    // gesture classification, keeper thread only; output goes to the scene
    private val gestures = GestureEngine { g -> streamer?.scene?.onGesture(g) }
    private var gestureTicking = false
    private val gestureTick = object : Runnable {
        override fun run() {
            if (streamer?.scene != null) {
                gestures.tick(System.currentTimeMillis())
                handler?.postDelayed(this, 80)
            } else gestureTicking = false
        }
    }

    /** hand the glass to a scene (null = give it back). Any thread. */
    fun setScene(s: Scene?) {
        val h = handler
        if (h == null) { streamer?.scene = s; return }
        h.post {
            gestures.reset()
            streamer?.scene = s
            if (s != null && !gestureTicking) {
                gestureTicking = true
                h.post(gestureTick)
            }
        }
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
        val ht = HandlerThread("clawd-keeper").apply { start() }
        handler = Handler(ht.looper)
        handler?.post { ClawdState.load(appCtx!!) }  // saver binds keeper looper
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
            val asm = SysexAssembler({ sysex ->
                if (captureRaw)
                    android.util.Log.i("clawd-capture", hex(sysex))
                // decode on the MIDI binder thread; hop to the keeper
                // thread so consumers see a single-threaded touch stream
                Blocks.decode(sysex, blocks) { ev ->
                    handler?.post { dispatchTouch(ev) }
                }
                // snap detection: topology device count crossed a threshold.
                // The first topology after connect just sets the baseline (no
                // fire) so the launch state — snapped or not — never auto-starts.
                val dc = blocks.deviceCount
                if (dc > 0 && dc != lastDeviceCount) {
                    val firstSeen = lastDeviceCount < 0
                    lastDeviceCount = dc
                    if (!firstSeen) {
                        val second = blocks.devices
                            .firstOrNull { it.first != blocks.topologyIndex }?.first ?: -1
                        android.util.Log.i("clawd-snap",
                            "SNAP snapped=${dc >= 2} second=$second")
                        handler?.post { onSnap?.invoke(dc >= 2, second) }
                    }
                }
                // legacy probabilistic tap (timestamp-byte coincidence) —
                // only until the real decoder proves itself on this block
                if (!sawRealTouch && Touch.isTouchStart(sysex)) tapReaction()
            }, onStray = { b ->
                if (captureRaw)
                    android.util.Log.i("clawd-capture", "midi:%02X".format(b))
                else
                    android.util.Log.i("clawdpad-midi", "%02X".format(b))
            })
            device.openOutputPort(0)?.connect(
                object : android.media.midi.MidiReceiver() {
                    override fun onSend(msg: ByteArray, off: Int, len: Int,
                                        ts: Long) = asm.feed(msg, off, len)
                })
            streamer = Streamer(ctx.assets, port, ::say, blocks)
                .also { it.start() }
            connecting = false
            retryDelay = 1000L
            // NOW a device is connected → the connectedDevice foreground
            // service's precondition is satisfied. Starting it earlier
            // (at launch, no device) crashes on Android 14+.
            runCatching {
                val i = Intent(ctx, HostService::class.java)
                    .setAction("PROMOTE")
                ctx.startForegroundService(i)
            }
            onHosting()
        }, handler)
    }

    fun stop() {
        wantHosting = false
        streamer?.quit()
        streamer = null
        ClawdState.saveNow()
        say("stopped")
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) sb.append("%02X ".format(b))
        return sb.toString().trimEnd()
    }

    /** keeper thread only */
    private fun dispatchTouch(ev: TouchEvent) {
        if (!sawRealTouch) {
            sawRealTouch = true
            say("real touch decode confirmed 🎯")
        }
        val scene = streamer?.scene
        if (scene != null) {
            scene.onTouch(ev)
            gestures.feed(ev)
        } else if (ev.phase == TouchPhase.START) tapReaction()
        for (l in touchListeners) l(ev)
    }

    private fun tapReaction() {
        if (streamer?.scene != null) return   // a scene owns the pad
        val now = System.currentTimeMillis()
        val double = now - lastTouch < 600
        lastTouch = now
        streamer?.play(if (double) "jump" else "wave")
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
        runCatching { startForeground(NOTE_ID, note(this, Host.lastStatus)) }
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
