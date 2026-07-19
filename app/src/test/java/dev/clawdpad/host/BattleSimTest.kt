package dev.clawdpad.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BattleSimTest {

    private class Scripted(override val stats: Stats,
                           private val script: Map<Long, Action>) : Combatant {
        override fun act(view: BattleView, tickNo: Long) = script[tickNo]
    }

    private val even = Stats(power = 5, guard = 5, finesse = 5, stamina = 5)

    private fun run(engine: BattleEngine, maxTicks: Int = 5000): List<String> {
        var i = 0
        while (!engine.over && i++ < maxTicks) engine.tick()
        return emptyList()
    }

    @Test
    fun scriptedStrikeTranscriptIsExact() {
        val log = mutableListOf<String>()
        val e = BattleEngine(
            Scripted(even, mapOf(2L to Action(Move.STRIKE, 0.5f))),
            Scripted(even, emptyMap())) { log.add(it) }
        repeat(6) { e.tick() }
        // 12 * (0.6 + 0.4*5/10) * (0.5+0.5) = 9.6 → "hits 9"
        assertEquals(listOf("L winds up", "L hits 9"), log)
        assertEquals(100f - 9.6f, e.r.hp, 0.01f)
    }

    @Test
    fun lateGuardBlocksEarlyGuardParries() {
        // parry: guard raised 1 tick before the blow lands
        val parryLog = mutableListOf<String>()
        val e1 = BattleEngine(
            Scripted(even, mapOf(2L to Action(Move.STRIKE, 0.5f))),
            Scripted(even, mapOf(3L to Action(Move.GUARD, 0.8f)))) { parryLog.add(it) }
        repeat(6) { e1.tick() }
        assertTrue(parryLog.toString(), parryLog.contains("R PARRIES"))
        assertEquals(100f, e1.r.hp, 0f)
        assertTrue(e1.l.stagger > 0)

        // block: guard held since long before → reduced damage, no parry
        val e2 = BattleEngine(
            Scripted(even, mapOf(4L to Action(Move.STRIKE, 0.5f))),
            Scripted(even, (1L..8L).associateWith { Action(Move.GUARD, 0.8f) }))
        repeat(9) { e2.tick() }
        assertTrue(e2.r.hp < 100f)                    // some damage got through
        assertTrue(e2.r.hp > 100f - 9.6f)             // but less than unguarded
    }

    @Test
    fun dashEvadesTheTelegraphedStrike() {
        val log = mutableListOf<String>()
        val e = BattleEngine(
            Scripted(even, mapOf(2L to Action(Move.STRIKE, 1f))),
            Scripted(even, mapOf(3L to Action(Move.DASH, 0.5f)))) { log.add(it) }
        repeat(6) { e.tick() }
        assertTrue(log.contains("R evades"))
        assertEquals(100f, e.r.hp, 0f)
    }

    @Test
    fun chargeRaisesEnergyAndSuperNeedsFullMeter() {
        val log = mutableListOf<String>()
        val e = BattleEngine(
            Scripted(even, mapOf(
                1L to Action(Move.SUPER, 1f),          // 50 energy: refused
                2L to Action(Move.CHARGE, 1f))),
            Scripted(even, emptyMap())) { log.add(it) }
        repeat(3) { e.tick() }
        assertTrue(log.none { it.contains("SUPER") })
        assertTrue(log.any { it.startsWith("L charges") })
        assertTrue(e.l.energy > 50f)
    }

    @Test
    fun aiVsAiIsDeterministicWithSeededRngs() {
        fun playOnce(): Pair<List<String>, Long> {
            val log = mutableListOf<String>()
            val e = BattleEngine(
                AiCombatant(Ladder.ALL[3].stats, Ladder.ALL[3].style, Random(7)),
                AiCombatant(Ladder.ALL[1].stats, Ladder.ALL[1].style, Random(99))
            ) { log.add(it) }
            run(e)
            assertTrue("battle should end", e.over)
            return log.toList() to e.tickNo
        }
        val (log1, ticks1) = playOnce()
        val (log2, ticks2) = playOnce()
        assertEquals(ticks1, ticks2)
        assertEquals(log1, log2)
        assertTrue(log1.isNotEmpty())
    }

    @Test
    fun strongerStatsWinTheMirrorMatchMostOfTheTime() {
        val strong = Stats(power = 9, guard = 7, finesse = 7, stamina = 8)
        val weak = Stats(power = 2, guard = 2, finesse = 2, stamina = 2)
        val style = Ladder.ALL[3].style        // same aggressive style both sides
        var strongWins = 0
        val sims = 200
        for (i in 0 until sims) {
            val e = BattleEngine(
                AiCombatant(strong, style, Random(i.toLong())),
                AiCombatant(weak, style, Random(10_000L + i)))
            run(e)
            if (e.over && e.winner === e.l) strongWins++
        }
        assertTrue("strong won $strongWins/$sims", strongWins > sims * 7 / 10)
    }

    @Test
    fun gestureCombatantMapsGesturesToMoves() {
        val g = GestureCombatant(even)
        val view = BattleView(100f, 50f, 100f, 50f, 0, false, 1)
        g.offer(Gesture.Strike(3f, 3f, 0.8f, 0.9f))
        assertEquals(Action(Move.STRIKE, 0.8f), g.act(view, 1))
        g.offer(Gesture.Swipe(2f, 7f, 8f, 0f, 30f, 0f))
        assertEquals(Move.DASH, g.act(view, 2)?.move)
        g.offer(Gesture.Scrub(7f, 7f, 3f, 1.5f, true))
        assertEquals(Move.CHARGE, g.act(view, 3)?.move)
        // sustained hold guards every tick until released
        g.offer(Gesture.Hold(5f, 5f, 500, 0.6f, 0.9f, ongoing = true))
        assertEquals(Move.GUARD, g.act(view, 4)?.move)
        assertEquals(Move.GUARD, g.act(view, 5)?.move)
        g.offer(Gesture.Hold(5f, 5f, 900, 0.6f, 0.9f, ongoing = false))
        assertEquals(null, g.act(view, 6))
    }

    @Test
    fun splitPadFilterKeepsForeignGesturesOut() {
        val leftOnly = GestureCombatant(even) { g ->
            when (g) {
                is Gesture.Strike -> g.x < 7.5f
                else -> true
            }
        }
        val view = BattleView(100f, 50f, 100f, 50f, 0, false, 1)
        leftOnly.offer(Gesture.Strike(12f, 3f, 0.9f, 0.9f))   // right half: ignored
        assertEquals(null, leftOnly.act(view, 1))
        leftOnly.offer(Gesture.Strike(3f, 3f, 0.9f, 0.9f))
        assertEquals(Move.STRIKE, leftOnly.act(view, 2)?.move)
    }
}
