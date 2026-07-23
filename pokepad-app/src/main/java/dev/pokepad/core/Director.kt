package dev.pokepad.core

import java.util.Random

/*
 * Poképad — Battle director (engine → visuals, on-device).
 *
 * Runs a REAL Gen-III 1v1 on the engine (Battle.kt), captures its typed event
 * stream (Ev.SendIn / Used / Faint / Win), and turns each event into the matching
 * animation beat (Anim.summon / attack / hurt / faint) rendered as a two-panel
 * pair — one 15x15 creature per block. This is the same pipeline proven in the
 * pokepad repo's BattleReel; here it emits Cells the Scene paints on the LEDs.
 *
 * Fully autonomous: the AI picks moves, the mechanics decide damage, the pixels
 * just report it. HP fractions are tracked from the damage the events carry so
 * the Scene can draw a live HP bar. Movesets are synthesised from the dataset
 * (best reliable damaging move per type + a normal coverage move) so ANY of the
 * 386 species can battle even before real save data supplies a team.
 */

/** one composite tick: the two blocks' creature pixels + HP + a shared banner */
class Cell(val left: IntArray, val right: IntArray,
          val hpL: Float, val hpR: Float,
          val banner: String, val bannerHot: Boolean, val msg: String)

class Reel(val cells: List<Cell>, val leftName: String, val rightName: String, val winnerName: String)

object Director {
    const val FPS = 12                       // matches the streamer's ~80ms live loop
    private val bestByType = HashMap<String, String?>()

    private fun bestOfType(dex: Dex, type: String): String? {
        if (bestByType.containsKey(type)) return bestByType[type]
        val cand = dex.moves.values
            .filter { it.type == type && it.power in 40..120 && it.accuracy >= 85 &&
                      it.name !in RECHARGE && it.name !in SELF_KO && it.minHits == 0 &&
                      it.target.contains("selected") }
            .maxByOrNull { it.power }?.name
        bestByType[type] = cand
        return cand
    }

    private fun movesetFor(dex: Dex, sp: String): List<String> {
        val types = dex.species[sp]!!.types
        val mv = LinkedHashSet<String>()
        for (t in types) bestOfType(dex, t)?.let { mv.add(it) }   // STAB
        bestOfType(dex, "normal")?.let { mv.add(it) }             // coverage
        if (mv.isEmpty()) mv.add(dex.moves.keys.first())          // safety net
        return mv.toList().take(4)
    }

    private fun cap(sp: String) = sp.replaceFirstChar { it.uppercase() }

    fun build(dex: Dex, leftSp: String, rightSp: String, seed: Long, leftBack: Boolean = false): Reel {
        val a = Mon(dex, leftSp, moves = movesetFor(dex, leftSp))
        val b = Mon(dex, rightSp, moves = movesetFor(dex, rightSp))
        val maxL = a.maxHp.toFloat(); val maxR = b.maxHp.toFloat()

        val events = ArrayList<Ev>()
        val winner = Battle(dex, listOf(a), listOf(b), seed = seed, emit = { events.add(it) }).run()

        // ── render helpers (the left/player side can be a back view) ───────
        fun feats(sp: String) = FEATURES[sp] ?: autoFeatures(dex.species[sp]!!.types)
        fun backOf(sp: String) = leftBack && sp == leftSp
        fun still(sp: String) = Renderer.render(dex.species[sp]!!.shape, dex.species[sp]!!.types, feats(sp), -1, backOf(sp))
        fun idle(sp: String, t: Int) = Renderer.render(dex.species[sp]!!.shape, dex.species[sp]!!.types, feats(sp), t, backOf(sp))

        val cells = ArrayList<Cell>()
        val cur = HashMap<String, String>()
        var hpL = maxL; var hpR = maxR
        var gt = 0
        var msg = ""   // the message-box line; lingers through the following pause
        fun push(l: IntArray, r: IntArray, banner: String, hot: Boolean) {
            cells.add(Cell(l, r, (hpL / maxL).coerceIn(0f, 1f), (hpR / maxR).coerceIn(0f, 1f), banner, hot, msg)); gt++
        }
        fun moveName(m: String) = m.replace("-", " ").replaceFirstChar { it.uppercase() }
        fun sideIdle(side: String) = cur[side]?.let { idle(it, gt).px.copyOf() } ?: IntArray(W * W)
        fun compose(actSide: String, actPx: IntArray, banner: String, hot: Boolean) {
            val otherSide = if (actSide == "L") "R" else "L"
            val oth = sideIdle(otherSide)
            push(if (actSide == "L") actPx else oth, if (actSide == "L") oth else actPx, banner, hot)
        }
        fun gap(n: Int, banner: String = "") = repeat(n) { push(sideIdle("L"), sideIdle("R"), banner, false) }

        fun bannerFor(eff: Double, move: String): Pair<String, Boolean> = when {
            eff == 0.0 -> "NO EFF" to false
            eff > 1.0 -> "SUPER!" to true
            eff < 1.0 -> "RESIST" to false
            else -> move.replace("-", " ").take(6).uppercase() to false
        }

        for (ev in events) when (ev) {
            is Ev.SendIn -> {
                cur[ev.side] = ev.species
                msg = "${cap(ev.species)} appeared!"
                for (t in 0 until Anim.SUMMON) compose(ev.side, Anim.summon(still(ev.species), t).px.copyOf(), "", false)
                gap(2)
            }
            is Ev.Used -> {
                val defSide = if (ev.side == "L") "R" else "L"
                if (ev.dmg > 0) { if (defSide == "L") hpL = (hpL - ev.dmg).coerceAtLeast(0f) else hpR = (hpR - ev.dmg).coerceAtLeast(0f) }
                msg = "${cap(ev.species)} used ${moveName(ev.move)}!"
                val (banner, hot) = bannerFor(ev.eff, ev.move)
                for (t in 0 until Anim.ATTACK) {
                    val atk = Anim.attack(still(ev.species), t).px.copyOf()
                    val hurtT = t - 4
                    val def = if (ev.dmg > 0 && hurtT in 0 until Anim.HURT) Anim.hurt(still(cur[defSide]!!), hurtT).px.copyOf()
                              else sideIdle(defSide)
                    push(if (ev.side == "L") atk else def, if (ev.side == "L") def else atk, banner, hot)
                }
                gap(1)
            }
            is Ev.Faint -> {
                msg = "${cap(ev.species)} fainted!"
                for (t in 0 until Anim.FAINT) compose(ev.side, Anim.faint(still(ev.species), t).px.copyOf(), "", false)
                cur.remove(ev.side)
                gap(1)
            }
            is Ev.Win -> { msg = "${cap(ev.species)} wins!"; repeat(16) { compose(ev.side, idle(ev.species, gt).px.copyOf(), "WIN", true) } }
        }

        return Reel(cells, cap(leftSp), cap(rightSp), winner?.let { cap(it.species.name) } ?: "draw")
    }
}
