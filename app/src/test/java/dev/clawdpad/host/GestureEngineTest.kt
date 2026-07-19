package dev.clawdpad.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GestureEngineTest {

    private fun ev(phase: TouchPhase, x: Float, y: Float, z: Float,
                   t: Long, idx: Int = 0, vel: Float = 0f,
                   hostT: Long = t) =
        TouchEvent(idx, x, y, z, vel, phase, t, hostT)

    private fun collect(): Pair<GestureEngine, MutableList<Gesture>> {
        val out = mutableListOf<Gesture>()
        return GestureEngine { out.add(it) } to out
    }

    @Test
    fun straightSwipeSpeedFromDeviceTime() {
        val (eng, out) = collect()
        eng.feed(ev(TouchPhase.START, 2f, 7f, 0.3f, 0))
        var t = 0L
        for (i in 1..10) {
            t = i * 20L
            eng.feed(ev(TouchPhase.MOVE, 2f + i, 7f, 0.3f, t))
        }
        eng.feed(ev(TouchPhase.END, 12f, 7f, 0.1f, 200))
        val swipes = out.filterIsInstance<Gesture.Swipe>()
        assertEquals(1, swipes.size)
        val s = swipes[0]
        assertEquals(10f, s.dx, 0.01f)
        assertEquals(0f, s.dy, 0.01f)
        assertEquals(0f, s.angleDeg, 1f)
        assertEquals(50f, s.speed, 2f)      // 10 cells / 200ms
    }

    @Test
    fun bleBurstArrivalDoesNotCorruptSpeed() {
        // identical device times, host times all bunched at the end
        val (eng, out) = collect()
        eng.feed(ev(TouchPhase.START, 2f, 7f, 0.3f, 0, hostT = 5000))
        for (i in 1..10)
            eng.feed(ev(TouchPhase.MOVE, 2f + i, 7f, 0.3f, i * 20L, hostT = 5001))
        eng.feed(ev(TouchPhase.END, 12f, 7f, 0.1f, 200, hostT = 5002))
        val s = out.filterIsInstance<Gesture.Swipe>().single()
        assertEquals(50f, s.speed, 2f)
    }

    @Test
    fun circleClassifiesAsScrub() {
        val (eng, out) = collect()
        val cx = 7.5f; val cy = 7.5f; val r = 4f
        val n = 72                      // 2 revolutions, 36 samples each
        eng.feed(ev(TouchPhase.START, cx + r, cy, 0.5f, 0))
        for (i in 1..n) {
            val th = 2 * PI * i / 36.0
            eng.feed(ev(TouchPhase.MOVE,
                cx + (r * cos(th)).toFloat(), cy + (r * sin(th)).toFloat(),
                0.5f, i * 14L))
        }
        eng.feed(ev(TouchPhase.END, cx + r, cy, 0.2f, (n + 1) * 14L))
        val scrub = out.filterIsInstance<Gesture.Scrub>().single()
        assertTrue("revs ${scrub.revolutions}", scrub.revolutions > 1.6f)
        assertTrue(scrub.clockwise)     // y-down screen coords
        assertEquals(7.5f, scrub.cx, 0.8f)
        assertEquals(7.5f, scrub.cy, 0.8f)
    }

    @Test
    fun steadyPressBecomesHoldWithProgress() {
        val (eng, out) = collect()
        eng.feed(ev(TouchPhase.START, 5f, 5f, 0.6f, 0))
        eng.feed(ev(TouchPhase.MOVE, 5.2f, 5f, 0.62f, 250))
        eng.feed(ev(TouchPhase.MOVE, 5.1f, 5.1f, 0.61f, 500))
        eng.tick(500)
        val progress = out.filterIsInstance<Gesture.Hold>()
        assertEquals(1, progress.size)
        assertTrue(progress[0].ongoing)
        assertTrue("steadiness ${progress[0].steadiness}",
            progress[0].steadiness > 0.8f)
        eng.feed(ev(TouchPhase.END, 5.1f, 5.1f, 0.5f, 700))
        val fin = out.filterIsInstance<Gesture.Hold>().last()
        assertTrue(!fin.ongoing)
        assertEquals(700, fin.durationMs)
    }

    @Test
    fun quickLightTouchIsTap() {
        val (eng, out) = collect()
        eng.feed(ev(TouchPhase.START, 3f, 9f, 0.4f, 0))
        eng.feed(ev(TouchPhase.END, 3.2f, 9.1f, 0.1f, 120))
        val tap = out.filterIsInstance<Gesture.Tap>().single()
        assertEquals(3f, tap.x, 0.01f)
        assertEquals(0.4f, tap.z, 0.01f)
    }

    @Test
    fun hardFastLandingIsStrikeImmediately() {
        val (eng, out) = collect()
        eng.feed(ev(TouchPhase.START, 8f, 8f, 0.5f, 0, vel = 0.9f))
        val strike = out.filterIsInstance<Gesture.Strike>().single()
        assertEquals(0.9f, strike.velocity, 0.01f)
    }

    @Test
    fun secondFingerEmitsMultiTouch() {
        val (eng, out) = collect()
        eng.feed(ev(TouchPhase.START, 3f, 3f, 0.4f, 0, idx = 0))
        eng.feed(ev(TouchPhase.START, 11f, 11f, 0.4f, 30, idx = 1))
        val mt = out.filterIsInstance<Gesture.MultiTouch>().single()
        assertEquals(2, mt.count)
    }
}
