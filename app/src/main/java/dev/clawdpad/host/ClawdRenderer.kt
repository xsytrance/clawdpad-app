package dev.clawdpad.host

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Clawd himself, computed live — a Kotlin port of the daemon's sprite
 * engine (clawdpadd.py): the official Claude Code icon geometry, chibi
 * variant, the 3x5 pixel FONT, and mood composition. Emits 15x15 RGB888
 * frames; LiveHost converts to RGB565 heap bytes and diff-streams them.
 *
 * New here (the music wave): dance(energy, bounce) — his groove pose,
 * driven by real audio analysis — and lyric word cards with kinetic
 * pop-in, for Lyric Sparks.
 */
object ClawdRenderer {
    const val W = 15
    const val H = 15
    val CORAL = intArrayOf(217, 119, 87)

    /** current outfit (Clawdrobe id); "none" = just clawd */
    @Volatile var costume: String = "none"

    // official icon geometry (see clawdpadd.py CLAWD_*)
    private val BODY = intArrayOf(2, 3, 13, 11)
    private val ARMS = arrayOf(intArrayOf(0, 7, 2, 9), intArrayOf(13, 7, 15, 9))
    private val LEGS = arrayOf(
        intArrayOf(3, 11, 4, 13), intArrayOf(5, 11, 6, 13),
        intArrayOf(9, 11, 10, 13), intArrayOf(11, 11, 12, 13))
    private val EYES = arrayOf(intArrayOf(4, 5), intArrayOf(10, 5))

    val FONT = mapOf(
        '0' to arrayOf("111","101","101","101","111"),
        '1' to arrayOf("010","110","010","010","111"),
        '2' to arrayOf("111","001","111","100","111"),
        '3' to arrayOf("111","001","011","001","111"),
        '4' to arrayOf("101","101","111","001","001"),
        '5' to arrayOf("111","100","111","001","111"),
        '6' to arrayOf("111","100","111","101","111"),
        '7' to arrayOf("111","001","001","010","010"),
        '8' to arrayOf("111","101","111","101","111"),
        '9' to arrayOf("111","101","111","001","111"),
        'A' to arrayOf("010","101","111","101","101"),
        'B' to arrayOf("110","101","110","101","110"),
        'C' to arrayOf("011","100","100","100","011"),
        'D' to arrayOf("110","101","101","101","110"),
        'E' to arrayOf("111","100","110","100","111"),
        'F' to arrayOf("111","100","110","100","100"),
        'G' to arrayOf("011","100","101","101","011"),
        'H' to arrayOf("101","101","111","101","101"),
        'I' to arrayOf("111","010","010","010","111"),
        'J' to arrayOf("001","001","001","101","010"),
        'K' to arrayOf("101","110","100","110","101"),
        'L' to arrayOf("100","100","100","100","111"),
        'M' to arrayOf("101","111","111","101","101"),
        'N' to arrayOf("101","111","111","111","101"),
        'O' to arrayOf("010","101","101","101","010"),
        'P' to arrayOf("110","101","110","100","100"),
        'Q' to arrayOf("010","101","101","011","001"),
        'R' to arrayOf("110","101","110","110","101"),
        'S' to arrayOf("011","100","010","001","110"),
        'T' to arrayOf("111","010","010","010","010"),
        'U' to arrayOf("101","101","101","101","111"),
        'V' to arrayOf("101","101","101","101","010"),
        'W' to arrayOf("101","101","111","111","101"),
        'X' to arrayOf("101","101","010","101","101"),
        'Y' to arrayOf("101","101","010","010","010"),
        'Z' to arrayOf("111","001","010","100","111"),
        '!' to arrayOf("010","010","010","000","010"),
        '?' to arrayOf("111","001","011","000","010"),
        '\'' to arrayOf("010","010","000","000","000"),
        '-' to arrayOf("000","000","111","000","000"),
        '.' to arrayOf("000","000","000","000","010"),
        '♥' to arrayOf("000","101","111","111","010"),
        ' ' to arrayOf("000","000","000","000","000"),
    )

    private fun blit(buf: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x in 0 until W && y in 0 until H) {
            val i = (y * W + x) * 3
            buf[i] = r.coerceIn(0, 255).toByte()
            buf[i + 1] = g.coerceIn(0, 255).toByte()
            buf[i + 2] = b.coerceIn(0, 255).toByte()
        }
    }

    /** Full-size Clawd. Port of _clawd(). */
    fun clawd(brightness: Double, dx: Int = 0, dy: Int = 0,
              eyesOpen: Boolean = true, look: Int = 0,
              armLdy: Int = 0, armRdy: Int = 0,
              tint: IntArray = CORAL): ByteArray {
        val buf = ByteArray(W * H * 3)
        val r = (tint[0] * brightness).toInt()
        val g = (tint[1] * brightness).toInt()
        val b = (tint[2] * brightness).toInt()
        fun rect(c: IntArray, oy: Int = 0) {
            for (y in c[1] + dy + oy until c[3] + dy + oy)
                for (x in c[0] + dx until c[2] + dx)
                    blit(buf, x, y, r, g, b)
        }
        rect(BODY)
        rect(ARMS[0], armLdy)
        rect(ARMS[1], armRdy)
        for (leg in LEGS) rect(leg)
        for (e in EYES) {
            val x = e[0] + dx + look
            blit(buf, x, e[1] + 1 + dy, 0, 0, 0)
            if (eyesOpen) blit(buf, x, e[1] + dy, 0, 0, 0)
        }
        return buf
    }

    /** Compose a pose in the current costume: skins replace the body,
     *  props ride on top with the same offsets. */
    fun dressed(brightness: Double, dx: Int, dy: Int, eyesOpen: Boolean,
                look: Int, t: Double, armL: Int = 0, armR: Int = 0,
                tint: IntArray = CORAL): ByteArray {
        val c = costume
        val buf = when (c) {
            "ghost" -> Clawdrobe.ghost(brightness, dx, dy, eyesOpen, look, t)
            "puff" -> Clawdrobe.puff(brightness, dx, dy, eyesOpen, look)
            "chomper" -> Clawdrobe.chomper(brightness, dx, dy, t,
                facingRight = sin(t * 0.13) >= 0)
            else -> clawd(brightness, dx, dy, eyesOpen, look, armL, armR, tint)
        }
        if (c != "none" && Clawdrobe.byId(c)?.isSkin == false)
            Clawdrobe.applyProp(c, buf, dx, dy, look, t)
        return buf
    }

    /** Awake idle: breathe, bob, pace, blink, glance. Port of frame_awake. */
    fun awake(t: Double): ByteArray {
        val breath = 0.72 + 0.28 * sin(t * 2 * PI / 6.5)
        val dx = (1.5 * sin(t * 0.13)).toInt()
        val dy = (0.5 * sin(t * 2 * PI / 6.5)).toInt()
        val look = (0.9 * sin(t * 0.31)).toInt()
        val blink = (t % 4.3) < 0.13
        return dressed(breath, dx, dy, !blink, look, t)
    }

    /** THE GROOVE: audio-driven. energy 0..1 scales brightness; bounce 0..1
     *  (beat envelope) lifts him; strong beats throw the arms up. */
    fun dance(t: Double, energy: Double, bounce: Double,
              tint: IntArray = CORAL): ByteArray {
        val dy = -(bounce * 2.5).toInt()
        val armUp = if (bounce > 0.55) -2 else if (bounce > 0.25) -1 else 0
        val sway = (1.8 * sin(t * 2.2)).toInt()
        val brightness = (0.55 + 0.45 * min(1.0, energy + bounce * 0.5))
        val blink = (t % 3.1) < 0.1
        // headphones auto-equip while he's vibing (unless already dressed)
        val prev = costume
        if (prev == "none") costume = "phones"
        val f = dressed(brightness, sway, dy, !blink, 0, t, armUp, armUp, tint)
        costume = prev
        return f
    }

    /** Lyric Sparks word card: kinetic pop-in (grow = brightness ramp),
     *  scrolls when wider than the glass. Call per-frame with age since
     *  the word appeared. */
    fun lyric(word: String, age: Double, color: IntArray,
              energy: Double): ByteArray {
        val buf = ByteArray(W * H * 3)
        val text = word.uppercase().filter { FONT.containsKey(it) }
        if (text.isEmpty()) return buf
        val pop = min(1.0, age * 6.0)                    // 0→1 in ~170ms
        val glow = (0.45 + 0.55 * min(1.0, energy)) * pop
        val width = text.length * 4 - 1
        val x0 = if (width <= W) (W - width) / 2
                 else (2 - (age * 9.0).toInt()).coerceAtLeast(W - width - 2)
                     .let { min(2, it) }.let {
                         // scroll: start right, drift left, clamp at end
                         val scrolled = 2 - (age * 9.0).toInt()
                         scrolled.coerceIn(W - width - 2, 2)
                     }
        val y0 = 5
        var x = x0
        for (ch in text) {
            val rows = FONT[ch]!!
            for (dy in 0 until 5)
                for (dxc in 0 until 3)
                    if (rows[dy][dxc] == '1')
                        blit(buf, x + dxc, y0 + dy,
                            (color[0] * glow).toInt(),
                            (color[1] * glow).toInt(),
                            (color[2] * glow).toInt())
            x += 4
        }
        // tiny Clawd feet at the bottom: the words stand on him, he owns them
        for (fx in intArrayOf(5, 9)) {
            blit(buf, fx, 13, (CORAL[0] * 0.5 * pop).toInt(),
                (CORAL[1] * 0.5 * pop).toInt(), (CORAL[2] * 0.5 * pop).toInt())
        }
        return buf
    }

    /** RGB888 frame -> BitmapLEDProgram RGB565 heap bytes. */
    fun rgb565(frame: ByteArray): ByteArray {
        val out = ByteArray(W * H * 2)
        for (p in 0 until W * H) {
            val r = frame[p * 3].toInt() and 0xFF
            val g = frame[p * 3 + 1].toInt() and 0xFF
            val b = frame[p * 3 + 2].toInt() and 0xFF
            val r5 = (r shr 3) and 0x1F
            val g6 = (g shr 2) and 0x3F
            val b5 = (b shr 3) and 0x1F
            out[p * 2] = (r5 or ((g6 and 0x07) shl 5)).toByte()
            out[p * 2 + 1] = ((g6 shr 3) or (b5 shl 3)).toByte()
        }
        return out
    }
}
