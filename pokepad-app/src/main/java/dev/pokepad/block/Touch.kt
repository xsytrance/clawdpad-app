package dev.pokepad.block

/** Reassembles fragmented BLE/USB MIDI chunks into complete SysEx messages.
 *  Non-SysEx bytes (running-status notes/pressure/CC — MPE?) go to onStray
 *  when provided; they may become a fallback touch source if capture shows
 *  the block speaks MPE rather than BLOCKS touch SysEx. */
class SysexAssembler(
    private val onMessage: (ByteArray) -> Unit,
    private val onStray: ((Byte) -> Unit)? = null,
) {
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
                else -> onStray?.invoke(b) ?: android.util.Log.i("pokepad-midi",
                    "%02X".format(b))
            }
        }
    }
}

enum class TouchPhase { START, MOVE, END }

/** One decoded touch sample from the block.
 *  x/y in pad coordinates 0..15 (LED cells), z pressure 0..1,
 *  velocity 0..1 (0 unless the message carried one).
 *  deviceTimeMs is the block's own clock (packet timestamp + offset) —
 *  use it for speed/velocity math; BLE delivers samples in bursts, so
 *  hostTimeMs only orders events across packets. */
data class TouchEvent(
    val touchIndex: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val velocity: Float,
    val phase: TouchPhase,
    val deviceTimeMs: Long,
    val hostTimeMs: Long,
)

/** LEGACY: probabilistic tap detection, kept only as a fallback until the
 *  real touch decode (Blocks.decode 0x10..0x15) is confirmed against
 *  captured traffic. sysex[6] is actually the first 7 bits of the packet
 *  TIMESTAMP, not the message type — this fires when the clock happens to
 *  pass 0x10/0x13. HostService stops consulting it as soon as a genuine
 *  TouchEvent has been decoded. */
object Touch {
    private val HEADER = byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x10, 0x77)

    fun isTouchStart(sysex: ByteArray): Boolean {
        if (sysex.size < 9) return false
        for (k in HEADER.indices)
            if (sysex[k] != HEADER[k]) return false
        val type = sysex[6].toInt() and 0x7F
        return type == 0x10 || type == 0x13
    }
}
