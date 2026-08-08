package dev.clawdpad.host

/**
 * The Augury's 3x5 font, extracted verbatim from dazzler/firmware/code.py.
 *
 * Each glyph is three COLUMNS; bit i of a column is row i, top down. Copied
 * rather than re-drawn on purpose: a mirror that invents its own letterforms
 * is not a mirror.
 *
 * DELIBERATELY MISSING "/". The firmware source now defines it, but the BOARD
 * has not been reflashed — the running panel still draws nothing where the
 * divider in `6.3 /16GB` goes. Re-extract after the next flash.
 */
object AuguryFont {
    val GLYPHS: Map<String, IntArray> = mapOf(
        " " to intArrayOf(0, 0, 0),
        "!" to intArrayOf(0, 23, 0),
        "-" to intArrayOf(4, 4, 4),
        "." to intArrayOf(0, 16, 0),
        "0" to intArrayOf(31, 17, 31),
        "1" to intArrayOf(0, 31, 0),
        "2" to intArrayOf(29, 21, 23),
        "3" to intArrayOf(21, 21, 31),
        "4" to intArrayOf(7, 4, 31),
        "5" to intArrayOf(23, 21, 29),
        "6" to intArrayOf(31, 21, 29),
        "7" to intArrayOf(1, 1, 31),
        "8" to intArrayOf(31, 21, 31),
        "9" to intArrayOf(7, 21, 31),
        ":" to intArrayOf(0, 10, 0),
        "A" to intArrayOf(30, 5, 30),
        "B" to intArrayOf(31, 21, 10),
        "C" to intArrayOf(14, 17, 17),
        "D" to intArrayOf(31, 17, 14),
        "E" to intArrayOf(31, 21, 17),
        "F" to intArrayOf(31, 5, 1),
        "G" to intArrayOf(14, 17, 29),
        "H" to intArrayOf(31, 4, 31),
        "I" to intArrayOf(17, 31, 17),
        "J" to intArrayOf(8, 16, 15),
        "K" to intArrayOf(31, 4, 27),
        "L" to intArrayOf(31, 16, 16),
        "M" to intArrayOf(31, 2, 31),
        "N" to intArrayOf(31, 6, 31),
        "O" to intArrayOf(14, 17, 14),
        "P" to intArrayOf(31, 5, 2),
        "Q" to intArrayOf(14, 17, 30),
        "R" to intArrayOf(31, 5, 26),
        "S" to intArrayOf(18, 21, 9),
        "T" to intArrayOf(1, 31, 1),
        "U" to intArrayOf(15, 16, 15),
        "V" to intArrayOf(7, 24, 7),
        "W" to intArrayOf(15, 16, 15),
        "X" to intArrayOf(27, 4, 27),
        "Y" to intArrayOf(3, 28, 3),
        "Z" to intArrayOf(25, 21, 19),
    )

    // PAL, verbatim from firmware/code.py.
    const val BLACK = 0xFF000000.toInt()
    const val AMBER = 0xFFC47F10.toInt()
    const val RED = 0xFFC23B3B.toInt()
    const val CYAN = 0xFF1F7F93.toInt()
    const val DIM = 0xFF2B3242.toInt()
    const val WHITE = 0xFF7C8697.toInt()
}
