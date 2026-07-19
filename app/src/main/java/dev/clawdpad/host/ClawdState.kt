package dev.clawdpad.host

import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

/**
 * Clawd's soul: trained stats + the trainer's fighting fingerprint.
 * Persisted as one JSON blob in SharedPreferences (zero new deps).
 *
 * Stats are levels 1..10 per discipline; XP flows in from training
 * mini-games. The StyleProfile records how YOU actually play — its
 * samples power the autonomous shadow-match AI, so Clawd fights the
 * way he was raised.
 */
data class Stats(
    var power: Int = 1,      // strike damage cap
    var guard: Int = 1,      // block strength cap
    var finesse: Int = 1,    // dodge quality, combo window
    var stamina: Int = 1,    // energy pool / regen
    var xp: Int = 0,         // lifetime XP (bragging rights)
) {
    val level: Int get() = (power + guard + finesse + stamina) / 4

    fun toJson(): JSONObject = JSONObject()
        .put("power", power).put("guard", guard)
        .put("finesse", finesse).put("stamina", stamina).put("xp", xp)

    companion object {
        const val STAT_CAP = 10
        /** XP a stat needs to go from level n to n+1 */
        fun xpToNext(level: Int): Int = 100 * level

        fun fromJson(o: JSONObject) = Stats(
            o.optInt("power", 1), o.optInt("guard", 1),
            o.optInt("finesse", 1), o.optInt("stamina", 1),
            o.optInt("xp", 0))
    }
}

/**
 * The trainer's fighting fingerprint: per gesture kind, count + running
 * mean/variance of the key magnitude, plus a reservoir of raw samples
 * (Vitter's algorithm R) the AI replays and mutates in shadow matches.
 */
class StyleProfile {
    enum class Kind { STRIKE, HOLD, SWIPE, SCRUB }

    class Channel {
        var n = 0
        var mean = 0f
        var m2 = 0f
            private set
        val samples = ArrayList<Float>(RESERVOIR)

        fun record(v: Float, rng: Random) {
            n++
            val d = v - mean
            mean += d / n
            m2 += d * (v - mean)
            if (samples.size < RESERVOIR) samples.add(v)
            else {
                val j = rng.nextInt(n)
                if (j < RESERVOIR) samples[j] = v
            }
        }

        val stdev: Float
            get() = if (n < 2) 0f else kotlin.math.sqrt(m2 / (n - 1))

        /** draw a plausible magnitude in this trainer's style */
        fun sample(rng: Random): Float =
            if (samples.isEmpty()) 0.5f
            else samples[rng.nextInt(samples.size)]

        fun toJson(): JSONObject = JSONObject()
            .put("n", n).put("mean", mean.toDouble())
            .put("m2", m2.toDouble())
            .put("samples", JSONArray().also { a ->
                for (s in samples) a.put(s.toDouble()) })

        companion object {
            fun fromJson(o: JSONObject) = Channel().also { c ->
                c.n = o.optInt("n")
                c.mean = o.optDouble("mean", 0.0).toFloat()
                c.m2 = o.optDouble("m2", 0.0).toFloat()
                val a = o.optJSONArray("samples") ?: JSONArray()
                for (i in 0 until a.length())
                    c.samples.add(a.getDouble(i).toFloat())
            }
        }
    }

    private val channels = HashMap<Kind, Channel>()
    /** how often the trainer attacks vs defends (0 = all guard, 1 = all strike) */
    var aggression = 0.5f
        private set
    private var offense = 0
    private var defense = 0

    fun channel(k: Kind): Channel = channels.getOrPut(k) { Channel() }

    fun record(g: Gesture, rng: Random = Random()) {
        when (g) {
            is Gesture.Strike -> { offense++; channel(Kind.STRIKE).record(g.velocity, rng) }
            is Gesture.Hold -> if (!g.ongoing) {
                defense++; channel(Kind.HOLD).record(g.steadiness, rng)
            }
            is Gesture.Swipe -> channel(Kind.SWIPE)
                .record((g.speed / 60f).coerceIn(0f, 1f), rng)
            is Gesture.Scrub -> channel(Kind.SCRUB)
                .record((g.speed / 2f).coerceIn(0f, 1f), rng)
            else -> return
        }
        val total = offense + defense
        if (total > 0) aggression = offense.toFloat() / total
    }

    /** total recorded gestures — a proxy for how trained this Clawd is */
    val experience: Int get() = channels.values.sumOf { it.n }

    fun toJson(): JSONObject = JSONObject()
        .put("offense", offense).put("defense", defense)
        .put("channels", JSONObject().also { o ->
            for ((k, c) in channels) o.put(k.name, c.toJson()) })

    companion object {
        const val RESERVOIR = 32

        fun fromJson(o: JSONObject) = StyleProfile().also { p ->
            p.offense = o.optInt("offense")
            p.defense = o.optInt("defense")
            val total = p.offense + p.defense
            if (total > 0) p.aggression = p.offense.toFloat() / total
            val ch = o.optJSONObject("channels") ?: JSONObject()
            for (key in ch.keys())
                runCatching {
                    p.channels[Kind.valueOf(key)] =
                        Channel.fromJson(ch.getJSONObject(key))
                }
        }
    }
}

object ClawdState {
    @Volatile var stats = Stats()
        private set
    @Volatile var style = StyleProfile()
        private set
    /** XP accumulated toward the next level of each stat */
    private val pending = HashMap<String, Int>()

    private var prefs: android.content.SharedPreferences? = null
    private var saver: android.os.Handler? = null
    private val saveRun = Runnable { saveNow() }

    /** feed XP into one stat ("power"/"guard"/"finesse"/"stamina");
     *  returns true on level-up */
    @Synchronized
    fun addXp(stat: String, points: Int): Boolean {
        val s = stats
        s.xp += points
        val cur = when (stat) {
            "power" -> s.power; "guard" -> s.guard
            "finesse" -> s.finesse; else -> s.stamina
        }
        if (cur >= Stats.STAT_CAP) { saveSoon(); return false }
        val acc = (pending[stat] ?: 0) + points
        val need = Stats.xpToNext(cur)
        return if (acc >= need) {
            pending[stat] = acc - need
            when (stat) {
                "power" -> s.power++
                "guard" -> s.guard++
                "finesse" -> s.finesse++
                else -> s.stamina++
            }
            saveSoon(); true
        } else {
            pending[stat] = acc
            saveSoon(); false
        }
    }

    fun load(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("clawd", android.content.Context.MODE_PRIVATE)
        saver = android.os.Handler(android.os.Looper.myLooper()
            ?: android.os.Looper.getMainLooper())
        runCatching {
            val raw = prefs?.getString("state", null) ?: return
            val o = JSONObject(raw)
            stats = Stats.fromJson(o.getJSONObject("stats"))
            style = StyleProfile.fromJson(o.getJSONObject("style"))
            val p = o.optJSONObject("pending") ?: JSONObject()
            for (k in p.keys()) pending[k] = p.getInt(k)
        }
    }

    /** debounced save — call freely after every scoring event */
    fun saveSoon() {
        val h = saver ?: return
        h.removeCallbacks(saveRun)
        h.postDelayed(saveRun, 2000)
    }

    @Synchronized
    fun saveNow() {
        val o = JSONObject()
            .put("stats", stats.toJson())
            .put("style", style.toJson())
            .put("pending", JSONObject().also { p ->
                for ((k, v) in pending) p.put(k, v) })
        prefs?.edit()?.putString("state", o.toString())?.apply()
    }
}
