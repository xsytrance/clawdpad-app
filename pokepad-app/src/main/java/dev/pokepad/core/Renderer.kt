package dev.pokepad.core

/*
 * Poképad — CreatureRenderer (archetype + SIGNATURE FEATURES).
 *
 * A species' shape picks an archetype silhouette; its types pick a palette; and
 * per-species / type-driven FEATURES overlay so same-archetype mon differ. All
 * ORIGINAL 15x15 pixel art — no ripped sprites (the covenant). Returns a
 * block-ready Frame (px = 0xRRGGBB, 0 = empty). Ported from the pokepad repo;
 * the desktop gallery/main() and java.awt bits are dropped for Android.
 */

const val W = 15
val TYPE_COLOR = mapOf(
    "normal" to 0xBEB4A0, "fire" to 0xF06E3C, "water" to 0x5096F0, "electric" to 0xF5D246,
    "grass" to 0x6EC864, "ice" to 0x82D7E1, "fighting" to 0xC85A5A, "poison" to 0xAA5ABE,
    "ground" to 0xCDA564, "flying" to 0x96AFEB, "psychic" to 0xF56EA5, "bug" to 0xA5C846,
    "rock" to 0xB9A06E, "ghost" to 0x7864AF, "dragon" to 0x6E6EEB, "dark" to 0x6E5F5A,
    "steel" to 0xB4BECD)
val FLAME = intArrayOf(0xF5C842, 0xF08020, 0xE04010)
val LEAF = 0x4CA83C; val DARKEYE = 0x141014; val WHITE = 0xF4F4F4

class Frame {
    val px = IntArray(W * W)
    fun set(x: Int, y: Int, rgb: Int) { if (x in 0 until W && y in 0 until W) px[y * W + x] = rgb }
    fun get(x: Int, y: Int) = if (x in 0 until W && y in 0 until W) px[y * W + x] else 0
    fun on(x: Int, y: Int) = get(x, y) != 0
}
fun blankFrame() = Frame()
fun shade(rgb: Int, f: Double): Int {
    val r = ((rgb shr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
    val g = ((rgb shr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
    val b = ((rgb and 0xFF) * f).toInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or b
}

// per-species signature features (the hero tier). Others fall back to type-auto.
val FEATURES = mapOf(
    "charizard" to listOf("wings", "flame-tail"), "charmander" to listOf("flame-tail"),
    "charmeleon" to listOf("flame-tail", "horns"),
    "pikachu" to listOf("ears", "cheeks", "bolt-tail"), "raichu" to listOf("ears", "cheeks"),
    "gengar" to listOf("grin", "back-spikes"), "haunter" to listOf("grin"),
    "bulbasaur" to listOf("head-leaf"), "ivysaur" to listOf("head-leaf"), "venusaur" to listOf("big-leaf"),
    "squirtle" to listOf("shell"), "wartortle" to listOf("shell"), "blastoise" to listOf("shell", "cannons"),
    "gyarados" to listOf("fins", "whiskers"), "dragonite" to listOf("wings"), "dragonair" to listOf("gem"),
    "rayquaza" to listOf("back-spikes"), "salamence" to listOf("wings"), "flygon" to listOf("wings"),
    "alakazam" to listOf("gem", "whiskers"), "kadabra" to listOf("whiskers"),
    "gardevoir" to listOf("gem"), "mewtwo" to listOf("tail", "gem"), "mew" to listOf("tail"),
    "machamp" to listOf("four-arms"), "machoke" to listOf("belt"),
    "snorlax" to listOf("belly"), "lapras" to listOf("shell", "fins"), "kyogre" to listOf("fins"),
    "tyranitar" to listOf("back-spikes", "horns"), "groudon" to listOf("back-spikes", "horns"),
    "scizor" to listOf("pincers"), "metagross" to listOf("back-spikes"),
    "torchic" to listOf("crest"), "treecko" to listOf("tail"), "mudkip" to listOf("fins", "crest"),
    "loudred" to listOf("ears"), "scyther" to listOf("pincers", "wings"))

fun autoFeatures(types: List<String>): List<String> {
    val f = ArrayList<String>()
    for (t in types) when (t) {
        "flying" -> f.add("wings"); "fire" -> f.add("flame-tail"); "electric" -> f.add("cheeks")
        "grass" -> f.add("head-leaf"); "dragon" -> f.add("back-spikes"); "psychic" -> f.add("gem")
        "ghost" -> f.add("grin"); "bug" -> f.add("antennae"); "ice" -> f.add("horns")
        "water" -> f.add("fins"); "rock", "ground" -> f.add("back-spikes")
    }
    return f.distinct().take(2)
}

object Renderer {
    private fun ellipse(fr: Frame, cx: Double, cy: Double, rx: Double, ry: Double, c: Int) {
        for (y in 0 until W) for (x in 0 until W) {
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            if (dx * dx + dy * dy <= 1.0) fr.set(x, y, c)
        }
    }
    private fun eyes(fr: Frame, ex: Int, ey: Int, spread: Int = 2) {
        val l = ex - spread / 2 - 1; val r = ex + spread / 2
        fr.set(l, ey - 1, WHITE); fr.set(r, ey - 1, WHITE); fr.set(l, ey, DARKEYE); fr.set(r, ey, DARKEYE)
    }
    private fun headPos(shape: String) = when (shape) {
        "quadruped", "legs" -> 11 to 5; "fish" -> 5 to 6; "squiggle" -> 7 to 2
        "ball", "blob" -> 7 to 6; "tentacles" -> 7 to 5; else -> 7 to 4
    }

    // features that only read from the front (skipped in a back/first-person view)
    private val FACE_FEATURES = setOf("grin", "cheeks", "whiskers", "gem", "antennae", "crest", "ears")

    fun render(shape: String, types: List<String>, features: List<String>, t: Int = -1, back: Boolean = false, species: String? = null): Frame {
        // hand-authored hero art (front view only) beats the procedural archetype
        if (species != null && !back && HeroArt.has(species)) {
            val fr = Frame()
            System.arraycopy(HeroArt.px(species)!!, 0, fr.px, 0, W * W)
            outline(fr)
            if (t >= 0 && (t % 8) >= 4) shiftV(fr, -1)   // breathing bob
            return fr
        }
        val fr = Frame()
        val body = TYPE_COLOR[types[0]] ?: 0xBEB4A0
        val accent = TYPE_COLOR[types.getOrElse(1) { types[0] }] ?: body
        val dk = shade(body, 0.7)
        val (hx, hy) = headPos(shape)
        when (shape) {
            "ball", "blob" -> ellipse(fr, 7.0, 7.5, 5.2, 5.0, body)
            "squiggle" -> for (y in 1..13) { val x = 7 + (Math.sin(y * 0.7) * 3).toInt(); for (dx in -1..1) fr.set(x + dx, y, body) }
            "fish" -> { ellipse(fr, 6.0, 7.5, 4.6, 3.0, body); for (y in 5..10) for (x in 10..13) if (Math.abs(y - 7.5) < (x - 9) * 0.9) fr.set(x, y, accent) }
            "wings", "bug-wings" -> ellipse(fr, 7.0, 8.0, 2.6, 4.2, body)
            "tentacles" -> { ellipse(fr, 7.0, 5.5, 4.6, 4.0, body); for (x in intArrayOf(3, 5, 7, 9, 11)) for (y in 9..13) fr.set(x, y, if (y % 2 == 0) accent else body) }
            "quadruped", "legs" -> { ellipse(fr, 7.0, 7.0, 5.0, 3.4, body); ellipse(fr, 11.0, 5.5, 2.4, 2.4, body); for (lx in intArrayOf(4, 6, 9, 11)) for (y in 10..12) fr.set(lx, y, dk) }
            "armor" -> for (y in 3..12) for (x in 3..11) fr.set(x, y, if ((x + y) % 4 == 0) shade(body, 1.15) else body)
            else -> { ellipse(fr, 7.0, 4.5, 2.6, 2.6, body); for (y in 6..11) for (x in 5..9) fr.set(x, y, body); for (s in intArrayOf(-1, 1)) for (y in 6..8) fr.set(7 + s * 4, y, shade(accent, 0.95)); for (lx in intArrayOf(6, 8)) for (y in 12..13) fr.set(lx, y, dk) }
        }
        val blink = t >= 0 && (t % 16) == 8
        for (f in features) { if (back && f in FACE_FEATURES) continue; feature(fr, f, hx, hy, body, accent, t) }
        if (back) {
            // seen from behind: no face, a shaded spine down the back
            for (yy in hy - 1..hy + 4) if (fr.on(hx, yy)) fr.set(hx, yy, shade(body, 0.62))
        } else if (blink) { fr.set(hx - 1, hy, DARKEYE); fr.set(hx, hy, DARKEYE); fr.set(hx + 1, hy, DARKEYE) }
        else eyes(fr, hx, hy, if (shape == "quadruped" || shape == "legs") 1 else 2)
        outline(fr)
        if (t >= 0 && (t % 8) >= 4) shiftV(fr, -1)   // gentle breathing bob
        return fr
    }

    private fun shiftV(fr: Frame, dy: Int) {   // move the whole creature by dy rows
        val out = IntArray(W * W)
        for (y in 0 until W) for (x in 0 until W) { val sy = y - dy; if (sy in 0 until W) out[y * W + x] = fr.px[sy * W + x] }
        System.arraycopy(out, 0, fr.px, 0, out.size)
    }

    private fun feature(fr: Frame, f: String, hx: Int, hy: Int, body: Int, accent: Int, t: Int) {
        val flick = t < 0 || (t % 4) < 2   // flame/spark "tall" phase
        when (f) {
            "head-leaf" -> { fr.set(hx, hy - 3, LEAF); fr.set(hx, hy - 4, LEAF); fr.set(hx - 1, hy - 3, shade(LEAF, 0.8)); fr.set(hx + 1, hy - 3, shade(LEAF, 0.8)) }
            "big-leaf" -> for (dx in -2..2) for (dy in -5..-3) if (Math.abs(dx) + Math.abs(dy + 4) <= 2) fr.set(hx + dx, hy + dy, if ((dx + dy) % 2 == 0) LEAF else shade(LEAF, 0.8))
            "flame-tail" -> { fr.set(12, 11, FLAME[2]); fr.set(12, 10, FLAME[1]); fr.set(13, 10, FLAME[1]); fr.set(12, 9, FLAME[0]); fr.set(13, 9, FLAME[0]); if (flick) { fr.set(12, 8, FLAME[0]); fr.set(13, 8, FLAME[1]) } }
            "crest" -> { fr.set(hx, hy - 3, FLAME[1]); fr.set(hx - 1, hy - 2, FLAME[1]); fr.set(hx + 1, hy - 2, FLAME[0]) }
            "ears" -> { fr.set(hx - 2, hy - 2, body); fr.set(hx - 2, hy - 3, DARKEYE); fr.set(hx + 2, hy - 2, body); fr.set(hx + 2, hy - 3, DARKEYE) }
            "cheeks" -> { fr.set(hx - 3, hy + 1, 0xE85050); fr.set(hx + 3, hy + 1, 0xE85050) }
            "bolt-tail" -> { fr.set(13, 8, 0xF5D246); fr.set(12, 9, 0xF5D246); fr.set(13, 10, 0xF5D246) }
            "grin" -> { for (dx in -2..2) fr.set(hx + dx, hy + 2, WHITE); fr.set(hx - 1, hy + 2, DARKEYE); fr.set(hx + 1, hy + 2, DARKEYE) }
            "shell" -> for (dx in -3..3) for (dy in 0..2) if (dx * dx + dy * dy * 3 <= 9) fr.set(7 + dx, 8 + dy, shade(body, 1.25))
            "cannons" -> { fr.set(3, 7, shade(body, 0.6)); fr.set(11, 7, shade(body, 0.6)) }
            "fins" -> { for (s in intArrayOf(-1, 1)) { fr.set(7 + s * 5, 8, shade(accent, 1.1)); fr.set(7 + s * 5, 9, shade(accent, 1.1)) }; fr.set(7, 3, shade(accent, 1.1)) }
            "whiskers" -> { fr.set(hx - 4, hy + 1, WHITE); fr.set(hx + 4, hy + 1, WHITE) }
            "back-spikes" -> for (x in intArrayOf(4, 6, 8, 10)) { fr.set(x, 3, shade(accent, 1.2)); fr.set(x, 2, shade(accent, 0.9)) }
            "horns" -> { fr.set(hx - 2, hy - 2, WHITE); fr.set(hx + 2, hy - 2, WHITE) }
            "gem" -> { fr.set(hx, hy - 1, 0xE04868); fr.set(hx, hy, shade(0xE04868, 1.3)) }
            "four-arms" -> for (s in intArrayOf(-1, 1)) for (y in 9..10) fr.set(7 + s * 4, y, shade(accent, 0.85))
            "belly" -> ellipse(fr, 7.0, 9.0, 3.0, 2.2, shade(body, 1.2))
            "tail" -> { fr.set(2, 10, accent); fr.set(1, 11, accent); fr.set(3, 9, accent) }
            "pincers" -> for (s in intArrayOf(-1, 1)) { fr.set(7 + s * 5, 6, shade(accent, 1.1)); fr.set(7 + s * 6, 6, shade(accent, 1.1)); fr.set(7 + s * 6, 5, shade(accent, 1.1)) }
            "antennae" -> { fr.set(hx - 1, hy - 3, DARKEYE); fr.set(hx + 1, hy - 3, DARKEYE) }
            "belt" -> for (x in 5..9) fr.set(x, 9, shade(body, 0.5))
            "wings" -> for (s in intArrayOf(-1, 1)) for (y in 4..8) for (x in 1..3) if (y - 4 <= x) fr.set(7 + s * (x + 3), y, shade(accent, 0.85))
        }
    }

    private fun outline(fr: Frame) {   // 1px dark rim where a lit pixel borders empty space (pops on the LEDs)
        val out = fr.px.copyOf()
        for (y in 0 until W) for (x in 0 until W) if (!fr.on(x, y)) {
            if (fr.on(x - 1, y) || fr.on(x + 1, y) || fr.on(x, y - 1) || fr.on(x, y + 1)) out[y * W + x] = 0x0A0A0F
        }
        System.arraycopy(out, 0, fr.px, 0, out.size)
    }
}
