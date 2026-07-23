package dev.pokepad.core

/*
 * Poképad — HERO ART: hand-authored 15x15 pixel art for iconic species, so each
 * one is instantly recognizable (Pikachu ≠ Raichu at a glance) instead of a
 * generic type-colored archetype. Renderer uses this when present and falls back
 * to the procedural archetype otherwise. All ORIGINAL art (the covenant).
 *
 * Each entry: a palette (char → 0xRRGGBB) + 15 rows of 15 chars. '.' = empty.
 * Common chars: K=near-black detail, W=white, E=eye. Outline is added by the
 * renderer, so author the FILL only.
 */
object HeroArt {
    private val COMMON = mapOf('.' to 0, 'K' to 0x0A0A0F, 'W' to 0xF4F4F4, 'E' to 0x141014)

    private class Hero(val pal: Map<Char, Int>, val rows: List<String>)

    /** 15x15 pixels for a species, or null if not hand-authored. */
    fun px(species: String): IntArray? {
        val h = ART[species] ?: return null
        val out = IntArray(225)
        for (y in 0 until 15) {
            val row = h.rows.getOrElse(y) { "" }
            for (x in 0 until 15) {
                val c = row.getOrElse(x) { '.' }
                out[y * 15 + x] = h.pal[c] ?: COMMON[c] ?: 0
            }
        }
        return out
    }

    fun has(species: String) = ART.containsKey(species)

    private fun hero(pal: Map<Char, Int>, vararg rows: String) = Hero(pal, rows.toList())

    private val ART: Map<String, Hero> = mapOf(
        // ── PIKACHU ── yellow, long black-tipped ears, red cheeks, bolt tail
        "pikachu" to hero(
            mapOf('Y' to 0xF6D02E, 'y' to 0xD9AE1E, 'r' to 0xE24B3A, 'b' to 0xC79A3A, 'B' to 0x8A6A22),
            "..K.......K....",
            "..KY.....YK....",
            "..KY.....YK....",
            "..YYY...YYY....",
            ".YYYYYYYYYYY...",
            ".YYYYYYYYYYY...",
            ".YWEYYYYYEWY.B.",
            ".YYYYYYYYYYYbB.",
            ".rYYKYYYKYYrbB.",
            "..YYYYYYYYYbb..",
            "..YYYYYYYYYb...",
            "..YYYYYYYYY....",
            "..YY.YYY.YY....",
            "..K...Y...K....",
            "..............."),

        // ── CHARMANDER ── orange, cream belly, flame on tail tip
        "charmander" to hero(
            mapOf('O' to 0xF08838, 'o' to 0xD06A24, 'c' to 0xF4D8A8, 'F' to 0xF5C842, 'f' to 0xF08020, 'g' to 0xE04010),
            ".....OOO.......",
            "....OOOOOO.....",
            "...OOOOOOOO...g",
            "...OEOOOEOO..fF",
            "...OOOOOOOO..Ff",
            "....OOOOO...ffO",
            "...OOOOOOO..OO.",
            "..OOcccccOO....",
            "..OcccccccO....",
            "..OcccccccO....",
            "..OOcccccOO....",
            "...OOcccOO.....",
            "...oO...Oo.....",
            "...o.....o.....",
            "..............."),

        // ── SQUIRTLE ── blue head/limbs, brown shell, cream plastron
        "squirtle" to hero(
            mapOf('B' to 0x5AA0E6, 'b' to 0x3A78C0, 'S' to 0xC08840, 's' to 0x9A6A2E, 'c' to 0xF0E0B0),
            "....BBBBB......",
            "...BBBBBBB.....",
            "..BBEBBBEBB....",
            "..BBBBBBBBB....",
            "..bBBBBBBBb....",
            "...BBBBBBB.....",
            "..SSSSSSSSS....",
            ".SScccccccSS...",
            ".ScscscscscS...",
            ".ScccccccccS...",
            ".SScscscscSS...",
            "..SSSSSSSSS....",
            "..bB.....Bb....",
            "..b.......b....",
            "..............."),

        // ── BULBASAUR ── teal body, dark spots, green bulb on back
        "bulbasaur" to hero(
            mapOf('T' to 0x6FC8B0, 't' to 0x4C9C86, 'G' to 0x4CA83C, 'g' to 0x357A28, 's' to 0x2A5A48),
            "....gGGGg......",
            "...GGGGGGG.....",
            "..GgGGGGGgG....",
            "..TTTTTTTTT....",
            ".TTEsTTTsETT...",
            ".TTTTTTTTTTT...",
            ".sTTTTTTTTTs...",
            ".TTsTTTTTsTT...",
            "..TTTTTTTTT....",
            "..sTTTTTTTs....",
            "..TTsTTTsTT....",
            "..TT.....TT....",
            ".tT.......Tt...",
            ".t.........t...",
            "..............."),

        // ── GENGAR ── purple round body, big toothy grin, back spikes
        "gengar" to hero(
            mapOf('P' to 0x7A5AB0, 'p' to 0x5A3E86, 'W' to 0xF4F4F4, 'r' to 0xC03050),
            "..p.p.p.p.p....",
            "..PPPPPPPPP....",
            ".PPPPPPPPPPP...",
            ".PPEWPPPWEPP...",
            ".PPWKPPPKWPP...",
            ".PPPPPPPPPPP...",
            ".PWWWWWWWWWP...",
            ".PWKWKWKWKWP...",
            ".PPWWWWWWWPP...",
            "..PPPPPPPPP....",
            ".pPPPPPPPPp....",
            ".pPP.....PPp...",
            "..p.......p....",
            "..pp.....pp....",
            "..............."),

        // ── SNORLAX ── big blue-grey body, cream belly, sleepy face
        "snorlax" to hero(
            mapOf('B' to 0x5A6E7A, 'b' to 0x3E4E58, 'c' to 0xE8D8B0, 'C' to 0xD0BE94),
            "....BBBBBBB....",
            "..BBBBBBBBBBB..",
            ".BBBBBBBBBBBBB.",
            ".BBKBBBBBKBBBB.",
            ".BBBBBBBBBBBBB.",
            "BBBcccccccccBB.",
            "BbcccccccccccbB",
            "BcccccccccccccB",
            "BcccccccccccccB",
            "BbcccccccccccbB",
            ".BBcccccccccBB.",
            ".BBCCCCCCCCCBB.",
            "..BB.......BB..",
            "..bb.......bb..",
            "..............."),

        // ── JIGGLYPUFF ── pink balloon, big eyes, tuft of hair
        "jigglypuff" to hero(
            mapOf('P' to 0xF0B8C8, 'p' to 0xD892A8, 'B' to 0x40C0E0, 'W' to 0xF8F4F6),
            ".....ppp.......",
            "....pPPPp......",
            "...PPPPPPP.....",
            "..PPPPPPPPP....",
            ".PPPPPPPPPPP...",
            ".PPBWPPPBWPP...",   // big blue eyes with white glint
            ".PPWBPPPWBPP...",
            ".PPPPPKPPPPP...",
            ".PPPPPPPPPPP...",
            ".pPPPPPPPPPp...",
            "..PPPPPPPPP....",
            "...PPPPPPP.....",
            "....p...p......",
            "...............",
            "..............."),

        // ── MAGIKARP ── red fish, big lips, yellow whiskers, tail fin
        "magikarp" to hero(
            mapOf('R' to 0xE8503A, 'r' to 0xC03A28, 'Y' to 0xF5C842, 'W' to 0xF4F4F4, 't' to 0xF0E0A0),
            "...............",
            "...RRRRR....t..",
            "..RRRRRRR..tt..",
            ".RRRRRRRRRtttR.",
            "RRWERRRRRRRttRR",
            "RRWERRRRRRRttR.",
            "YRRRRRRRRRtttR.",
            "Y.RRRRRRR..tt..",
            "Y..RRRRR....t..",
            "...rrrrr.......",
            "...............",
            "...............",
            "...............",
            "...............",
            "..............."))
}
