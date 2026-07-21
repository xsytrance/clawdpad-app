package dev.clawdpad.host

/**
 * The CLAWD COMBAT roster: parody fighting-game archetypes, each a dressed-up
 * Clawd with a look and a feel. Pure data — the brawler renderer in
 * FightScene turns a kind + a live body state into pixels. Tints echo the
 * Clawdrobe cast so a fighter reads as "Clawd wearing that character".
 */
data class FighterKind(
    val id: String,
    val name: String,
    val title: String,
    val emoji: String,
    val tint: IntArray,       // body color
    val accent: IntArray,     // gib / spark burst color (the "gore", tinted silly)
    val power: Float,         // hit damage multiplier
    val speed: Float,         // recovery / dash speed multiplier
    val guard: Float,         // block strength multiplier
    val special: String,      // signature finisher name
)

object Fighters {
    val ROSTER: List<FighterKind> = listOf(
        FighterKind("clawd", "CLAWD", "all-rounder", "🐾",
            intArrayOf(217, 119, 87), intArrayOf(255, 205, 130),
            power = 1.0f, speed = 1.0f, guard = 1.0f, special = "CLAW STORM"),
        FighterKind("tinbot", "TINBOT", "iron wall", "🤖",
            intArrayOf(150, 162, 178), intArrayOf(120, 225, 255),
            power = 1.35f, speed = 0.7f, guard = 1.6f, special = "OVERLOAD"),
        FighterKind("prowl", "PROWL", "alley cat", "🐱",
            intArrayOf(232, 152, 88), intArrayOf(255, 225, 120),
            power = 0.8f, speed = 1.55f, guard = 0.75f, special = "NINE LIVES"),
        FighterKind("hopps", "HOPPS", "springheel", "🐸",
            intArrayOf(122, 206, 110), intArrayOf(205, 255, 150),
            power = 1.1f, speed = 1.25f, guard = 0.85f, special = "MOON KICK"),
        FighterKind("boo", "BOO", "trickster", "👻",
            intArrayOf(178, 192, 236), intArrayOf(232, 242, 255),
            power = 0.9f, speed = 1.35f, guard = 0.9f, special = "HAUNT"),
        FighterKind("puff", "PUFF", "pink peril", "🌸",
            intArrayOf(255, 152, 188), intArrayOf(255, 195, 222),
            power = 1.05f, speed = 1.0f, guard = 1.15f, special = "MEGA PUFF"),
    )

    fun byId(id: String): FighterKind = ROSTER.firstOrNull { it.id == id } ?: ROSTER[0]
    fun indexOf(id: String): Int = ROSTER.indexOfFirst { it.id == id }.coerceAtLeast(0)
}
