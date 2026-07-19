package dev.clawdpad.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingLogicTest {

    // ── RhythmJudge ─────────────────────────────────────────────────────
    @Test
    fun rhythmRampsTempoLinearly() {
        val j = RhythmJudge(90.0, 150.0, 30.0)
        assertEquals(90.0, j.bpmAt(0.0), 1e-9)
        assertEquals(120.0, j.bpmAt(15.0), 1e-9)
        assertEquals(150.0, j.bpmAt(30.0), 1e-9)
        assertEquals(150.0, j.bpmAt(99.0), 1e-9)  // clamped after round
    }

    @Test
    fun rhythmJudgesOnAndOffBeat() {
        val j = RhythmJudge(120.0, 120.0, 30.0)   // constant 120 = 500ms beats
        assertEquals(RhythmJudge.Verdict.PERFECT, j.judge(1.0))     // on beat
        assertEquals(RhythmJudge.Verdict.PERFECT, j.judge(1.05))    // 50ms off
        assertEquals(RhythmJudge.Verdict.GOOD, j.judge(1.15))      // 150ms off
        assertEquals(RhythmJudge.Verdict.MISS, j.judge(1.25))      // half-beat
        // symmetric: just before the beat is as good as just after
        assertEquals(RhythmJudge.Verdict.PERFECT, j.judge(1.95))
    }

    @Test
    fun rhythmPhaseIsContinuousAcrossTheRamp() {
        val j = RhythmJudge(90.0, 150.0, 30.0)
        // phase should sweep 0→1 without jumps: sample densely, deltas small
        var prev = j.beatPhase(10.0)
        for (i in 1..200) {
            val p = j.beatPhase(10.0 + i * 0.005)
            val d = p - prev
            assertTrue("jump at $i: $d", d < 0.05)   // small forward steps…
            if (d < 0)                               // …or a 1→0 wrap
                assertTrue("false wrap at $i: prev=$prev", prev > 0.9)
            prev = p
        }
    }

    // ── BandTracker ─────────────────────────────────────────────────────
    @Test
    fun bandTrackerAccumulatesInBandTime() {
        val b = BandTracker(halfWidth = 0.1f)
        b.update(0.55f, 0.5f, 100)    // in band
        b.update(0.62f, 0.5f, 100)    // edge-out
        b.update(0.50f, 0.5f, 100)    // in band
        assertEquals(300, b.totalMs)
        assertEquals(200, b.inBandMs)
        assertEquals(2f / 3f, b.fraction, 1e-4f)
    }

    @Test
    fun steadyPressureScoresHigherThanJittery() {
        val steady = BandTracker()
        val jittery = BandTracker()
        for (i in 0 until 50) {
            steady.update(0.5f + (i % 2) * 0.01f, 0.5f, 20)
            jittery.update(0.3f + (i % 2) * 0.4f, 0.5f, 20)
        }
        assertTrue(steady.steadiness > 0.9f)
        assertTrue(jittery.steadiness < 0.2f)
    }

    // ── Chase path ──────────────────────────────────────────────────────
    @Test
    fun chaseDotStaysOnThePad() {
        var minX = 99f; var maxX = -99f; var minY = 99f; var maxY = -99f
        var t = 0.0
        while (t < 30.0) {
            val (x, y) = Chase.dotAt(t)
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            t += 0.01
        }
        assertTrue(minX >= 0f && maxX <= 14.5f)
        assertTrue(minY >= 0f && maxY <= 14.5f)
        // and it actually roams, not sits in a corner
        assertTrue(maxX - minX > 8f)
        assertTrue(maxY - minY > 8f)
    }

    // ── MiniGame round structure (pure JVM: no android classes touched) ─
    private class Probe : MiniGame("power", xpPerPoint = 1f) {
        var playCalls = 0
        override fun renderPlay(buf: ByteArray, tPlay: Double) { playCalls++ }
        fun scoreSome() = addScore(5)
        public override fun onGesture(g: Gesture) {}
    }

    @Test
    fun miniGamePhasesCountdownPlayEndDone() {
        val g = Probe()
        g.render(0.5)                     // countdown
        assertEquals(0, g.playCalls)
        assertTrue(!g.done())
        g.render(4.0)                     // play (3..33)
        assertEquals(1, g.playCalls)
        g.scoreSome()
        g.render(34.0)                    // end card
        assertEquals(1, g.playCalls)
        assertEquals(5, g.score)
        assertTrue(!g.done())
        g.render(38.0)                    // past end window → exits
        assertTrue(g.done())
    }

    @Test
    fun miniGameEndCallbackFiresOnceWithXp() {
        var calls = 0; var gotXp = -1
        val g = object : MiniGame("guard", xpPerPoint = 2f,
            onEnd = { _, xp, _ -> calls++; gotXp = xp }) {
            override fun renderPlay(buf: ByteArray, tPlay: Double) {}
        }
        g.render(10.0)
        g.render(34.0)
        g.render(35.0)
        g.render(36.0)
        assertEquals(1, calls)
        assertTrue(gotXp >= 1)
    }
}
