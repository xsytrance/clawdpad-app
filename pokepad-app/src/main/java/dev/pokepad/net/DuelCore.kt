package dev.pokepad.net

import dev.pokepad.core.Battle
import dev.pokepad.core.Dex
import dev.pokepad.core.Director
import dev.pokepad.core.Ev
import dev.pokepad.core.Mon
import dev.pokepad.core.Reel

/*
 * Poképad — duel controllers (pure Kotlin, no Android).
 *
 * HostCore owns the one true Battle; JoinCore mirrors it. Both expose the same
 * surface to the UI: myMoves() for the menu, chooseMove() when the player picks,
 * onLine() for transport input, and callbacks (onReel to animate a turn, onMenu
 * to prompt, onStatus for the waiting line, onOver at the end). The desktop
 * loopback test wires two of these straight together — same code the phones run.
 */
interface DuelSide {
    fun onLine(line: String)
    fun chooseMove(move: String)
    fun myMoves(): List<String>
    fun myName(): String
    fun foeName(): String
}

class HostCore(
    private val dex: Dex,
    private val mySpec: Proto.MonSpec,
    private val seed: Long,
    private val send: (String) -> Unit,
    private val onReel: (Reel) -> Unit,
    private val onMenu: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onOver: (Boolean) -> Unit,
) : DuelSide {
    private var battle: Battle? = null
    private var myMon: Mon? = null
    private var theirMon: Mon? = null
    private var turnN = 0
    private var round = 0
    private var myPick: String? = null
    private var theirPick: String? = null
    private val events = ArrayList<Ev>()
    private var afterReel: (() -> Unit)? = null

    override fun myName() = mySpec.nickname.ifBlank { mySpec.species }
    override fun foeName() = theirMon?.name ?: "challenger"
    override fun myMoves() = battle?.leftMoves() ?: emptyList()

    @Synchronized override fun onLine(line: String) {
        val f = line.split("|")
        when (f[0]) {
            "HELLO" -> {
                val spec = Proto.MonSpec.fromLine(f.getOrElse(2) { "" }) ?: return
                startRound(spec)
            }
            "MOVE" -> {
                theirPick = f.getOrElse(2) { "" }
                    .takeIf { m -> battle?.right?.moves?.contains(m) == true }
                    ?: battle?.right?.moves?.firstOrNull()
                maybeResolve()
            }
            "AGAIN" -> theirMon?.let { startRound(Proto.MonSpec.fromLine(lastJoinLine) ?: return) }
        }
    }

    private var lastJoinLine = ""

    private fun startRound(theirSpec: Proto.MonSpec) {
        lastJoinLine = theirSpec.toLine()
        val a = mySpec.toMon(dex); val b = theirSpec.toMon(dex)
        myMon = a; theirMon = b; turnN = 0; myPick = null; theirPick = null
        round++
        events.clear()
        val bt = Battle(dex, listOf(a), listOf(b), seed = seed + round * 1000L, emit = { events.add(it) })
        battle = bt
        bt.startInteractive()
        val opening = ArrayList(events); events.clear()
        send(Proto.start(seed, mySpec, theirSpec, opening))
        afterReel = { onMenu() }
        onReel(Director.renderEvents(dex, a.species.name, b.species.name, opening,
            a.maxHp.toFloat(), b.maxHp.toFloat(), a.maxHp.toFloat(), b.maxHp.toFloat(),
            leftBack = true, bothOut = false))
    }

    @Synchronized override fun chooseMove(move: String) {
        if (battle?.over != false || myPick != null) return
        myPick = move
        onStatus("waiting for ${foeName()}…")
        maybeResolve()
    }

    private fun maybeResolve() {
        val bt = battle ?: return
        val mine = myPick ?: return
        val theirs = theirPick ?: return
        myPick = null; theirPick = null
        turnN++
        val hpbL = bt.left.hp; val hpbR = bt.right.hp
        events.clear()
        bt.stepPvp(mine, theirs)
        val evs = ArrayList(events); events.clear()
        val winSide = if (bt.over) (if (bt.winner === bt.left) "L" else "R") else null
        send(Proto.turn(turnN, hpbL, hpbR, bt.left.hp, bt.right.hp, bt.over, winSide, evs))
        val a = myMon!!; val b = theirMon!!
        afterReel = if (bt.over) { { onOver(bt.winner === bt.left) } } else { { onMenu() } }
        onReel(Director.renderEvents(dex, a.species.name, b.species.name, evs,
            hpbL.toFloat(), hpbR.toFloat(), a.maxHp.toFloat(), b.maxHp.toFloat(),
            leftBack = true, winnerName = if (winSide == null) "" else
                (if (winSide == "L") a.species.name else b.species.name), bothOut = true))
    }

    /** the turn animation finished — advance to menu / result */
    fun reelDone() { val cb = afterReel; afterReel = null; cb?.invoke() }
}

class JoinCore(
    private val dex: Dex,
    private val mySpec: Proto.MonSpec,
    private val send: (String) -> Unit,
    private val onReel: (Reel) -> Unit,
    private val onMenu: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onOver: (Boolean) -> Unit,
) : DuelSide {
    private var myMon: Mon? = null          // = host's RIGHT; our LEFT on screen
    private var theirMon: Mon? = null       // the host's mon
    private var turnN = 0
    @Volatile private var live = false
    @Volatile private var picked = false
    private var afterReel: (() -> Unit)? = null

    fun helloLine() = Proto.hello(mySpec.nickname, mySpec)
    override fun myName() = mySpec.nickname.ifBlank { mySpec.species }
    override fun foeName() = theirMon?.name ?: "host"
    override fun myMoves() = myMon?.moves?.filter { dex.moves.containsKey(it) } ?: emptyList()

    @Synchronized override fun onLine(line: String) {
        val f = line.split("|")
        when (f[0]) {
            "START" -> {
                val host = Proto.MonSpec.fromLine(f.getOrElse(2) { "" }) ?: return
                val join = Proto.MonSpec.fromLine(f.getOrElse(3) { "" }) ?: return
                theirMon = host.toMon(dex); myMon = join.toMon(dex)
                turnN = 0; live = true; picked = false
                // mirror: our mon is LEFT on our screen
                val opening = Proto.evsFromStr(f.getOrElse(4) { "" }).map { Proto.swap(it) }
                afterReel = { onMenu() }
                onReel(Director.renderEvents(dex, myMon!!.species.name, theirMon!!.species.name, opening,
                    myMon!!.maxHp.toFloat(), theirMon!!.maxHp.toFloat(),
                    myMon!!.maxHp.toFloat(), theirMon!!.maxHp.toFloat(),
                    leftBack = true, bothOut = false))
            }
            "TURN" -> {
                if (!live) return
                turnN = f.getOrElse(1) { "0" }.toIntOrNull() ?: turnN
                val hpbL = f.getOrElse(2) { "0" }.toIntOrNull() ?: 0   // host side
                val hpbR = f.getOrElse(3) { "0" }.toIntOrNull() ?: 0   // our side
                val over = f.getOrElse(6) { "0" } == "1"
                val winSide = f.getOrElse(7) { "-" }
                val evs = Proto.evsFromStr(f.getOrElse(8) { "" }).map { Proto.swap(it) }
                picked = false
                val me = myMon!!; val them = theirMon!!
                if (over) live = false
                afterReel = if (over) { { onOver(winSide == "R") } } else { { onMenu() } }
                onReel(Director.renderEvents(dex, me.species.name, them.species.name, evs,
                    hpbR.toFloat(), hpbL.toFloat(), me.maxHp.toFloat(), them.maxHp.toFloat(),
                    leftBack = true, winnerName = when (winSide) {
                        "R" -> me.species.name; "L" -> them.species.name; else -> "" }, bothOut = true))
            }
        }
    }

    @Synchronized override fun chooseMove(move: String) {
        if (!live || picked) return
        picked = true
        onStatus("waiting for ${foeName()}…")
        send(Proto.move(turnN + 1, move))
    }

    fun again() = send("AGAIN")
    fun reelDone() { val cb = afterReel; afterReel = null; cb?.invoke() }
}
