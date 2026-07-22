package dev.pokepad.core

/*
 * Poképad — Action animations (the battle beats).
 *
 * Renderer.render() gives a still creature Frame; Anim turns that still into the
 * sequences the block actually plays during a fight:
 *
 *   summon  — a Poké Ball drops in, snaps open in a white burst, the creature
 *             materialises out of the flash and settles with a bounce.
 *   attack  — wind-up, lunge forward with a leading impact streak, recover.
 *   hurt    — white/red flash + recoil shove backward.
 *   faint   — desaturate to grey, tip over, and sink off the bottom.
 *
 * Every beat is a pure function of (base frame, frame index t, length T) so the
 * on-device Scene can drive it from the battle loop with zero state. All frame
 * math stays on the 15x15 grid. No ripped assets (the covenant): the Poké Ball
 * is drawn from primitives here.
 */

// ---- frame ops (all return a NEW Frame; base is never mutated) ------------
private fun copy(base: Frame): Frame { val f = Frame(); System.arraycopy(base.px, 0, f.px, 0, base.px.size); return f }
private fun blank() = Frame()

private fun shiftH(base: Frame, dx: Int): Frame {
    val f = blank()
    for (y in 0 until W) for (x in 0 until W) { val sx = x - dx; if (sx in 0 until W) f.px[y * W + x] = base.px[y * W + sx] }
    return f
}
private fun shiftDown(base: Frame, dy: Int): Frame {
    val f = blank()
    for (y in 0 until W) for (x in 0 until W) { val sy = y - dy; if (sy in 0 until W) f.px[y * W + x] = base.px[sy * W + x] }
    return f
}
// horizontal shear by `slope` rows→cols (tip-over): row y shifts right by (7-y)*slope
private fun shear(base: Frame, slope: Double): Frame {
    val f = blank()
    for (y in 0 until W) for (x in 0 until W) {
        if (base.px[y * W + x] == 0) continue
        val nx = x + ((7 - y) * slope).toInt()
        if (nx in 0 until W) f.px[y * W + nx] = base.px[y * W + x]
    }
    return f
}
private fun mix(a: Int, b: Int, t: Double): Int {
    val ar = a shr 16 and 0xFF; val ag = a shr 8 and 0xFF; val ab = a and 0xFF
    val br = b shr 16 and 0xFF; val bg = b shr 8 and 0xFF; val bb = b and 0xFF
    val r = (ar + (br - ar) * t).toInt(); val g = (ag + (bg - ag) * t).toInt(); val bl = (ab + (bb - ab) * t).toInt()
    return (r shl 16) or (g shl 8) or bl
}
private fun tint(base: Frame, color: Int, amt: Double): Frame {   // pull every lit pixel toward color
    val f = copy(base)
    for (i in f.px.indices) if (f.px[i] != 0 && f.px[i] != 0x0A0A0F) f.px[i] = mix(f.px[i], color, amt)
    return f
}
private fun grey(base: Frame, amt: Double): Frame {   // desaturate toward luma-grey
    val f = copy(base)
    for (i in f.px.indices) { val c = f.px[i]; if (c == 0 || c == 0x0A0A0F) continue
        val l = ((c shr 16 and 0xFF) * 0.3 + (c shr 8 and 0xFF) * 0.59 + (c and 0xFF) * 0.11).toInt()
        f.px[i] = mix(c, (l shl 16) or (l shl 8) or l, amt) }
    return f
}
private fun dim(base: Frame, keep: Double): Frame { return tint(base, 0x000000, 1.0 - keep) }
// keep only pixels within radius p*maxR of center (materialise reveal)
private fun revealDisk(base: Frame, p: Double): Frame {
    val f = blank(); val maxR = 11.0 * p
    for (y in 0 until W) for (x in 0 until W) {
        val dx = x - 7.0; val dy = y - 7.5
        if (Math.sqrt(dx * dx + dy * dy) <= maxR) f.px[y * W + x] = base.px[y * W + x]
    }
    return f
}
private fun over(dst: Frame, src: Frame) { for (i in dst.px.indices) if (src.px[i] != 0) dst.px[i] = src.px[i] }

// ---- the Poké Ball (drawn from primitives) --------------------------------
private const val PB_RED = 0xE23B3B; private const val PB_WHT = 0xEFEFEF
private const val PB_BLK = 0x1A1A1E; private const val BURST = 0xFFF6C8
fun drawBall(cx: Int, cy: Int, r: Int, openAmt: Double): Frame {
    val f = blank(); val split = (openAmt * r).toInt()
    for (y in -r..r) for (x in -r..r) {
        if (x * x + y * y > r * r) continue
        val px = cx + x; val py = cy + y + (if (y < 0) -split else split)   // halves separate as it opens
        if (px !in 0 until W || py !in 0 until W) continue
        val c = when {
            Math.abs(y) <= 0 && openAmt < 0.5 -> PB_BLK               // center band (closed)
            x * x + y * y <= 2 && openAmt < 0.5 -> PB_WHT             // button
            y < 0 -> PB_RED
            else -> PB_WHT
        }
        f.px[py * W + px] = c
    }
    if (openAmt in 0.15..0.85) for (a in 0 until 8) {   // burst rays while snapping open
        val ang = a * Math.PI / 4; val rr = r + 2
        val bx = cx + (Math.cos(ang) * rr).toInt(); val by = cy + (Math.sin(ang) * rr).toInt()
        if (bx in 0 until W && by in 0 until W) f.px[by * W + bx] = BURST
    }
    return f
}

// ---- the four beats -------------------------------------------------------
object Anim {
    // durations chosen so each beat reads at ~90ms/frame on the block
    const val SUMMON = 18; const val ATTACK = 12; const val HURT = 6; const val FAINT = 14

    fun summon(base: Frame, t: Int): Frame {
        val p = t.toDouble() / (SUMMON - 1)
        return when {
            p < 0.35 -> { val q = p / 0.35; drawBall(7, (-2 + q * 9.5).toInt(), 3, 0.0) }         // ball falls in
            p < 0.55 -> { val q = (p - 0.35) / 0.20; val f = drawBall(7, 7, 3, q); over(f, whiteFlash(q * 0.7)); f } // snap open + flash
            else -> {                                                                              // materialise + bounce settle
                val q = (p - 0.55) / 0.45
                val f = revealDisk(base, Math.min(1.0, q * 1.3))
                val settle = if (q < 0.7) shiftDown(f, -((0.7 - q) * 4).toInt()) else f            // little overshoot
                if (q < 0.4) over(settle, whiteFlash((0.4 - q) / 0.4 * 0.5))
                settle
            }
        }
    }

    fun attack(base: Frame, t: Int): Frame {
        val p = t.toDouble() / (ATTACK - 1)
        return when {
            p < 0.30 -> shiftH(base, -((p / 0.30) * 2).toInt())                                     // wind-up back
            p < 0.55 -> { val f = shiftH(base, ((p - 0.30) / 0.25 * 6).toInt()); over(f, streak(11)); f } // lunge + streak
            else -> shiftH(base, (6 - (p - 0.55) / 0.45 * 6).toInt())                               // recover
        }
    }

    fun hurt(base: Frame, t: Int): Frame {
        val p = t.toDouble() / (HURT - 1)
        val recoil = shiftH(base, -((1 - p) * 3).toInt())                                          // shoved back, eases in
        return if (t % 2 == 0) tint(recoil, 0xFFFFFF, 0.75) else tint(recoil, 0xFF5050, 0.5)       // flash white/red
    }

    fun faint(base: Frame, t: Int): Frame {
        val p = t.toDouble() / (FAINT - 1)
        var f = grey(base, p)                    // color drains
        f = shear(f, p * 1.1)                    // tips over
        f = shiftDown(f, (p * 9).toInt())        // sinks off the bottom
        f = dim(f, 1.0 - p * 0.85)               // fades out
        return f
    }

    private fun whiteFlash(amt: Double): Frame {
        val f = blank(); val r = (amt * 9).toInt()
        for (y in 0 until W) for (x in 0 until W) { val dx = x - 7; val dy = y - 7; if (dx * dx + dy * dy <= r * r) f.px[y * W + x] = BURST }
        return f
    }
    private fun streak(x: Int): Frame {   // vertical impact slash at column x
        val f = blank(); for (y in 4..10) { if (x in 0 until W) f.px[y * W + x] = 0xFFFFFF; if (x + 1 < W) f.px[y * W + x + 1] = BURST }
        return f
    }
}
