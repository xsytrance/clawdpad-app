package dev.clawdpad.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ClawdStateTest {

    @Test
    fun statsJsonRoundTrip() {
        val s = Stats(power = 4, guard = 2, finesse = 7, stamina = 3, xp = 1234)
        val back = Stats.fromJson(s.toJson())
        assertEquals(s, back)
        assertEquals(4, back.level)     // (4+2+7+3)/4
    }

    @Test
    fun styleProfileRoundTripKeepsChannelsAndAggression() {
        val rng = Random(7)
        val p = StyleProfile()
        repeat(50) { p.record(Gesture.Strike(1f, 1f, 0.6f + (it % 4) * 0.1f, 0.5f), rng) }
        repeat(10) { p.record(Gesture.Hold(1f, 1f, 900, 0.5f, 0.8f, ongoing = false), rng) }
        p.record(Gesture.Swipe(0f, 0f, 8f, 0f, 45f, 0f), rng)
        p.record(Gesture.Scrub(7f, 7f, 2f, 1.4f, true), rng)

        val back = StyleProfile.fromJson(p.toJson())
        assertEquals(p.experience, back.experience)
        assertEquals(p.aggression, back.aggression, 1e-4f)
        for (k in StyleProfile.Kind.values()) {
            assertEquals("n:$k", p.channel(k).n, back.channel(k).n)
            assertEquals("mean:$k", p.channel(k).mean, back.channel(k).mean, 1e-4f)
            assertEquals("stdev:$k", p.channel(k).stdev, back.channel(k).stdev, 1e-3f)
            assertEquals("samples:$k", p.channel(k).samples, back.channel(k).samples)
        }
        // 50 strikes vs 10 holds → aggressive trainer
        assertTrue(back.aggression > 0.8f)
    }

    @Test
    fun reservoirCapsAtLimitAndSamplingIsDeterministic() {
        val p = StyleProfile()
        val rng = Random(42)
        repeat(500) { p.record(Gesture.Strike(1f, 1f, it / 500f, 0.5f), rng) }
        val ch = p.channel(StyleProfile.Kind.STRIKE)
        assertEquals(500, ch.n)
        assertEquals(StyleProfile.RESERVOIR, ch.samples.size)
        // reservoir keeps a spread, not just the head of the stream
        assertTrue(ch.samples.max() > 0.5f)
        // deterministic draw with a seeded RNG
        val a = ch.sample(Random(1)); val b = ch.sample(Random(1))
        assertEquals(a, b, 0f)
        assertTrue(ch.samples.contains(a))
    }

    @Test
    fun ongoingHoldsAreProgressNotTrainingData() {
        val p = StyleProfile()
        p.record(Gesture.Hold(1f, 1f, 500, 0.5f, 0.9f, ongoing = true))
        assertEquals(0, p.experience)
        p.record(Gesture.Hold(1f, 1f, 900, 0.5f, 0.9f, ongoing = false))
        assertEquals(1, p.experience)
    }

    @Test
    fun xpCurveLevelsUpAndCarriesRemainder() {
        // fresh object state (ClawdState is a singleton; reset via reflection-free path:
        // exercise the math on a fresh Stats through addXp's contract instead)
        val s = Stats()
        assertEquals(100, Stats.xpToNext(s.power))     // level 1 → 100 xp
        assertEquals(500, Stats.xpToNext(5))

        // ClawdState.addXp path (JVM-safe: no prefs loaded → save is a no-op)
        val before = ClawdState.stats.power
        var leveled = false
        var spent = 0
        while (!leveled && spent < 10_000) {
            leveled = ClawdState.addXp("power", 60)
            spent += 60
        }
        assertTrue(leveled)
        assertEquals(before + 1, ClawdState.stats.power)
    }

    @Test
    fun emptyChannelSamplesFallBackToNeutral() {
        val ch = StyleProfile.Channel()
        assertEquals(0.5f, ch.sample(Random(3)), 0f)
        assertFalse(ch.stdev.isNaN())
    }
}
