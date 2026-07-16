package dev.clawdpad.host

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The Clawdrobe: props Clawd wears (they ride his bob, lean and jumps)
 * and full character skins that inherit his behavior engine — breathing,
 * blinking, glancing, dancing — with a different body.
 *
 * Everything here is original clawdpad pixel art. The costume-maker doc
 * ships idea prompts, not IP: "four spooky arcade roommates", "a shy pink
 * puffball", "a hungry yellow circle"… what you draw at home is your
 * business.
 */
data class Costume(val id: String, val emoji: String, val label: String,
                   val isSkin: Boolean)

object Clawdrobe {
    val ALL = listOf(
        Costume("none", "🐾", "just clawd", false),
        Costume("tophat", "🎩", "dapper", false),
        Costume("shades", "😎", "too cool", false),
        Costume("party", "🥳", "party", false),
        Costume("crown", "👑", "royalty", false),
        Costume("phones", "🎧", "vibin'", false),
        Costume("scarf", "🧣", "cozy", false),
        Costume("ghost", "👻", "spooky pal", true),
        Costume("puff", "🌸", "pink puff", true),
        Costume("chomper", "🟡", "chomper", true),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    // ── props: painted over Clawd, offset by his dx/dy (and look for eyewear)
    private fun px(buf: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x in 0..14 && y in 0..14) {
            val i = (y * 15 + x) * 3
            buf[i] = r.toByte(); buf[i + 1] = g.toByte(); buf[i + 2] = b.toByte()
        }
    }

    fun applyProp(id: String, buf: ByteArray, dx: Int, dy: Int, look: Int,
                  t: Double) {
        when (id) {
            "tophat" -> {
                for (x in 3..11) px(buf, x + dx, 2 + dy, 40, 36, 44)
                for (y in 0..1) for (x in 5..9) px(buf, x + dx, y + dy, 52, 46, 58)
                for (x in 5..9) px(buf, x + dx, 1 + dy, 180, 60, 70) // band
            }
            "shades" -> {
                val yy = 5 + dy
                for (x in 3..5) px(buf, x + dx + look, yy, 18, 16, 20)
                for (x in 9..11) px(buf, x + dx + look, yy, 18, 16, 20)
                for (x in 6..8) px(buf, x + dx + look, yy, 30, 26, 32)
                px(buf, 4 + dx + look, yy, 90, 90, 110)  // glint
                px(buf, 10 + dx + look, yy, 90, 90, 110)
            }
            "party" -> {
                px(buf, 7 + dx, 0 + dy, 255, 210, 60)
                for (x in 6..8) px(buf, x + dx, 1 + dy, 235, 90, 160)
                for (x in 5..9) px(buf, x + dx, 2 + dy,
                    if ((x + dx) % 2 == 0) 90 else 235,
                    if ((x + dx) % 2 == 0) 170 else 90,
                    if ((x + dx) % 2 == 0) 235 else 160)
            }
            "crown" -> {
                for (x in intArrayOf(4, 7, 10)) px(buf, x + dx, 1 + dy, 255, 205, 40)
                for (x in 4..10) px(buf, x + dx, 2 + dy, 255, 205, 40)
                px(buf, 7 + dx, 2 + dy, 220, 60, 90)  // jewel
            }
            "phones" -> {
                for (x in 4..10) px(buf, x + dx, 1 + dy, 46, 42, 50)
                for (side in intArrayOf(-1, 1)) {
                    val cx = 7 + side * 6
                    for (y in 5..7) {
                        px(buf, cx + dx, y + dy, 46, 42, 50)
                        px(buf, cx - side + dx, y + dy, 217, 119, 87)
                    }
                    px(buf, cx + dx, 4 + dy, 46, 42, 50)
                    px(buf, cx + dx, 3 + dy, 46, 42, 50)
                }
            }
            "scarf" -> {
                for (x in 3..11) px(buf, x + dx, 10 + dy, 200, 60, 60)
                for (x in 4..10) px(buf, x + dx, 11 + dy, 170, 45, 45)
                px(buf, 10 + dx, 12 + dy, 200, 60, 60)  // tail, flutters
                if (sin(t * 2.3) > 0.3) px(buf, 11 + dx, 12 + dy, 200, 60, 60)
            }
        }
    }

    // ── skins: whole other bodies, same soul ───────────────────────────

    /** A spooky roommate: dome head, wavy skirt, googly eyes that track. */
    fun ghost(brightness: Double, dx: Int, dy: Int, eyesOpen: Boolean,
              look: Int, t: Double): ByteArray {
        val buf = ByteArray(15 * 15 * 3)
        val r = (150 * brightness).toInt()
        val g = (190 * brightness).toInt()
        val b = (255 * brightness).toInt()
        for (y in 2..12) for (x in 2..12) {
            val cx = x - 7.0 - dx
            val topDist = hypot(cx, (y - 6.0 - dy) * 1.1)
            val inDome = y <= 6 + dy && topDist < 5.4
            val inBody = y in (6 + dy)..(11 + dy) && abs(cx) < 5.2
            val skirtRow = y == 12 + dy && abs(cx) < 5.2 &&
                    ((x + (t * 3).toInt()) % 2 == 0)
            if (inDome || inBody || skirtRow) px(buf, x, y, r, g, b)
        }
        for (side in intArrayOf(-1, 1)) {                 // googly eyes
            val ex = 7 + side * 2 + dx
            val ey = 5 + dy
            px(buf, ex, ey, 245, 245, 250); px(buf, ex, ey + 1, 245, 245, 250)
            if (eyesOpen) px(buf, ex + look.coerceIn(-1, 1), ey + 1, 30, 30, 60)
        }
        return buf
    }

    /** A shy pink puffball. Round. Extremely round. */
    fun puff(brightness: Double, dx: Int, dy: Int, eyesOpen: Boolean,
             look: Int): ByteArray {
        val buf = ByteArray(15 * 15 * 3)
        val r = (255 * brightness).toInt()
        val g = (150 * brightness).toInt()
        val b = (185 * brightness).toInt()
        for (y in 0..14) for (x in 0..14) {
            if (hypot(x - 7.0 - dx, y - 7.0 - dy) < 5.2) px(buf, x, y, r, g, b)
        }
        for (side in intArrayOf(-1, 1)) {                 // feet
            for (fx in 0..1) px(buf, 7 + side * 3 - fx * side + dx, 12 + dy,
                (200 * brightness).toInt(), (40 * brightness).toInt(),
                (70 * brightness).toInt())
        }
        for (side in intArrayOf(-1, 1)) {                 // blush
            px(buf, 7 + side * 3 + dx, 8 + dy, (255 * brightness).toInt(),
                (110 * brightness).toInt(), (150 * brightness).toInt())
        }
        for (side in intArrayOf(-1, 1)) {                 // tall shiny eyes
            val ex = 7 + side * 2 + dx + look.coerceIn(-1, 1)
            px(buf, ex, 6 + dy, 25, 20, 35)
            if (eyesOpen) {
                px(buf, ex, 5 + dy, 25, 20, 35)
                px(buf, ex, 4 + dy, 90, 85, 120)          // glint
            }
        }
        return buf
    }

    /** A hungry yellow circle. Chomps. Flips to face his walking direction. */
    fun chomper(brightness: Double, dx: Int, dy: Int, t: Double,
                facingRight: Boolean): ByteArray {
        val buf = ByteArray(15 * 15 * 3)
        val r = (255 * brightness).toInt()
        val g = (215 * brightness).toInt()
        val mouth = 0.18 + 0.42 * abs(sin(t * 5.0))       // chomp chomp
        for (y in 0..14) for (x in 0..14) {
            val cx = x - 7.0 - dx
            val cy = y - 7.0 - dy
            if (hypot(cx, cy) < 5.4) {
                val ang = atan2(cy, if (facingRight) cx else -cx)
                if (abs(ang) > mouth) px(buf, x, y, r, g, 0)
            }
        }
        px(buf, 7 + dx + (if (facingRight) 1 else -1), 4 + dy, 25, 22, 18)
        return buf
    }
}
