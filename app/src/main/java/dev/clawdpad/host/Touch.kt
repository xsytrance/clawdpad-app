package dev.clawdpad.host

/** Reassembles fragmented BLE/USB MIDI chunks into complete SysEx messages. */
class SysexAssembler(private val onMessage: (ByteArray) -> Unit) {
    private val buf = ArrayList<Byte>(256)
    private var inside = false

    fun feed(msg: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val b = msg[i]
            when {
                b == 0xF0.toByte() -> { buf.clear(); buf.add(b); inside = true }
                b == 0xF7.toByte() && inside -> {
                    buf.add(b); onMessage(buf.toByteArray()); inside = false
                }
                inside -> buf.add(b)
            }
        }
    }
}

/** Minimal ROLI touch parsing: is this SysEx a touchStart event?
 *  Message types 0x10/0x13 = touchStart (plain / with velocity), read as
 *  the first 7 bits of the 7-bit-packed payload (LSB-first). */
object Touch {
    private val HEADER = byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x10, 0x77)

    fun isTouchStart(sysex: ByteArray): Boolean {
        if (sysex.size < 9) return false
        for (k in HEADER.indices)
            if (sysex[k] != HEADER[k]) return false
        val type = sysex[6].toInt() and 0x7F   // payload byte 0, bits 0-6
        return type == 0x10 || type == 0x13
    }
}
