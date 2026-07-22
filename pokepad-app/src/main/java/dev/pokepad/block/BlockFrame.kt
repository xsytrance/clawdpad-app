package dev.pokepad.block

/** 15x15 LED frame helpers for the block: RGB888 → RGB565 packing (the heap
 *  format) and a resting Poké Ball shown after connect, before a battle. */
object BlockFrame {
    const val W = 15
    const val H = 15

    /** RGB888 (W*H*3) → RGB565 (W*H*2), the on-heap frame format. */
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

    private fun set(buf: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x < 0 || x > 14 || y < 0 || y > 14) return
        val p = (y * W + x) * 3; buf[p] = r.toByte(); buf[p + 1] = g.toByte(); buf[p + 2] = b.toByte()
    }

    /** a resting Poké Ball (idle when connected but no battle running). */
    fun idle(): ByteArray {
        val buf = ByteArray(W * H * 3)
        val cx = 7.0; val cy = 7.0; val rad = 6.2
        for (y in 0 until H) for (x in 0 until W) {
            val dx = x - cx; val dy = y - cy; val d = Math.sqrt(dx * dx + dy * dy)
            if (d > rad) continue
            when {
                Math.abs(dy) < 0.6 -> set(buf, x, y, 20, 20, 24)                 // band
                dx * dx + dy * dy <= 2.2 -> set(buf, x, y, 235, 235, 240)        // button
                dx * dx + dy * dy <= 4.0 -> set(buf, x, y, 20, 20, 24)           // button ring
                dy < 0 -> set(buf, x, y, 226, 59, 59)                            // red top
                else -> set(buf, x, y, 235, 235, 238)                            // white bottom
            }
        }
        return buf
    }
}
