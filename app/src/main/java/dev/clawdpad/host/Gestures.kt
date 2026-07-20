package dev.clawdpad.host

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** How far (pad cells) a hold may wander before it counts as "not steady".
 *  This block reports no continuous pressure, so hold quality is measured
 *  positionally: a steady guard is a finger that stays put. Kept equal to
 *  HOLD_MAX_PATH (a hold that drifts its whole movement budget scores ~0). */
private const val STEADY_SPAN = 1.5f

/**
 * Classified pad gestures with magnitude metrics — the raw material for
 * training scores and battle moves. Pure Kotlin (JVM-testable).
 *
 * All speed/duration math uses DEVICE timestamps: BLE delivers touch
 * samples in bursts, so host arrival times would corrupt magnitudes.
 */
sealed class Gesture {
    /** quick light touch */
    data class Tap(val x: Float, val y: Float, val z: Float) : Gesture()

    /** fast/hard landing — the attack verb. velocity 0..1 */
    data class Strike(val x: Float, val y: Float, val velocity: Float,
                      val zPeak: Float) : Gesture()

    /** sustained press = a guard. Emitted with ongoing=true while held
     *  (progress), once with ongoing=false at release. firmness 0..1 is how
     *  hard it was set down (landing velocity — this block has no continuous
     *  pressure); steadiness 0..1 is how little the finger wandered. */
    data class Hold(val x: Float, val y: Float, val durationMs: Long,
                    val firmness: Float, val steadiness: Float,
                    val ongoing: Boolean) : Gesture()

    /** directional glide. speed in cells/second, angle in degrees
     *  (0 = right, 90 = down — pad y grows downward) */
    data class Swipe(val x0: Float, val y0: Float, val dx: Float,
                     val dy: Float, val speed: Float,
                     val angleDeg: Float) : Gesture()

    /** circular scrub — the charge verb. speed in revolutions/second */
    data class Scrub(val cx: Float, val cy: Float, val revolutions: Float,
                     val speed: Float, val clockwise: Boolean) : Gesture()

    /** a second (or third…) finger landed while others are down */
    data class MultiTouch(val count: Int, val xs: FloatArray,
                          val ys: FloatArray) : Gesture()
}

class GestureEngine(private val onGesture: (Gesture) -> Unit) {

    private class Track(ev: TouchEvent) {
        val x0 = ev.x; val y0 = ev.y
        val t0 = ev.deviceTimeMs
        var lastX = ev.x; var lastY = ev.y
        var lastT = ev.deviceTimeMs
        var pathLen = 0f
        var zPeak = ev.z
        var winding = 0f              // accumulated turn, degrees
        var lastAngle = Float.NaN     // heading of previous segment
        var sumX = ev.x; var sumY = ev.y
        var n = 1
        var maxWander = 0f            // furthest excursion from the landing point
        var holding = false

        fun update(ev: TouchEvent) {
            val ddx = ev.x - lastX
            val ddy = ev.y - lastY
            val seg = hypot(ddx, ddy)
            if (seg > 0.15f) {        // ignore sensor jitter for heading
                val ang = Math.toDegrees(atan2(ddy, ddx).toDouble()).toFloat()
                if (!lastAngle.isNaN()) {
                    var d = ang - lastAngle
                    while (d > 180f) d -= 360f
                    while (d < -180f) d += 360f
                    winding += d
                }
                lastAngle = ang
            }
            pathLen += seg
            lastX = ev.x; lastY = ev.y; lastT = ev.deviceTimeMs
            sumX += ev.x; sumY += ev.y
            zPeak = maxOf(zPeak, ev.z)
            maxWander = maxOf(maxWander, hypot(ev.x - x0, ev.y - y0))
            n++
        }

        val durationMs: Long get() = lastT - t0
        // No continuous pressure on this block, so a "steady" hold is one that
        // barely moves: full marks until the finger drifts STEADY_SPAN cells.
        val steadiness: Float get() = 1f - min(1f, maxWander / STEADY_SPAN)
        val displacement: Float get() = hypot(lastX - x0, lastY - y0)
    }

    private val tracks = HashMap<Int, Track>()

    // tunables (pad cells / device ms / 0..1)
    private val STRIKE_VELOCITY = 0.7f
    private val STRIKE_Z = 0.85f
    private val TAP_MAX_DIST = 1.2f
    private val TAP_MAX_MS = 250L
    private val HOLD_MIN_MS = 400L
    private val HOLD_MAX_PATH = 1.5f
    private val SWIPE_MIN_DIST = 4f
    private val SWIPE_MIN_STRAIGHTNESS = 0.7f
    private val SCRUB_MIN_TURN = 300f
    private val SCRUB_MIN_PATH = 8f

    fun feed(ev: TouchEvent) {
        when (ev.phase) {
            TouchPhase.START -> {
                tracks[ev.touchIndex] = Track(ev)
                if (ev.velocity >= STRIKE_VELOCITY || ev.z >= STRIKE_Z)
                    onGesture(Gesture.Strike(ev.x, ev.y,
                        maxOf(ev.velocity, ev.z), ev.z))
                if (tracks.size >= 2) {
                    val ts = tracks.values.toList()
                    onGesture(Gesture.MultiTouch(ts.size,
                        FloatArray(ts.size) { ts[it].lastX },
                        FloatArray(ts.size) { ts[it].lastY }))
                }
            }
            TouchPhase.MOVE -> tracks[ev.touchIndex]?.update(ev)
            TouchPhase.END -> {
                val tr = tracks.remove(ev.touchIndex) ?: return
                tr.update(ev)
                classify(tr)
            }
        }
    }

    /** call ~every 80ms while a scene is live; drives Hold progress */
    fun tick(nowMs: Long) {
        for (tr in tracks.values) {
            if (tr.durationMs >= HOLD_MIN_MS && tr.pathLen <= HOLD_MAX_PATH) {
                tr.holding = true
                onGesture(Gesture.Hold(tr.lastX, tr.lastY, tr.durationMs,
                    tr.zPeak, tr.steadiness, ongoing = true))
            }
        }
    }

    fun reset() = tracks.clear()

    private fun classify(tr: Track) {
        val d = tr.displacement
        val dur = tr.durationMs
        val turn = kotlin.math.abs(tr.winding)
        when {
            turn >= SCRUB_MIN_TURN && tr.pathLen >= SCRUB_MIN_PATH -> {
                val revs = turn / 360f
                val secs = maxOf(dur, 1L) / 1000f
                onGesture(Gesture.Scrub(tr.sumX / tr.n, tr.sumY / tr.n,
                    revs, revs / secs, clockwise = tr.winding > 0))
            }
            d >= SWIPE_MIN_DIST && tr.pathLen > 0f &&
                d / tr.pathLen >= SWIPE_MIN_STRAIGHTNESS -> {
                val dx = tr.lastX - tr.x0
                val dy = tr.lastY - tr.y0
                val secs = maxOf(dur, 1L) / 1000f
                onGesture(Gesture.Swipe(tr.x0, tr.y0, dx, dy,
                    tr.pathLen / secs,
                    Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()))
            }
            tr.holding ->
                onGesture(Gesture.Hold(tr.lastX, tr.lastY, dur,
                    tr.zPeak, tr.steadiness, ongoing = false))
            d <= TAP_MAX_DIST && dur <= TAP_MAX_MS ->
                onGesture(Gesture.Tap(tr.x0, tr.y0, tr.zPeak))
            // anything else: an indecisive smudge — stay quiet
        }
    }
}
