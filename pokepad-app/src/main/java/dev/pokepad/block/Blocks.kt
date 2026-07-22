package dev.pokepad.block

/**
 * Device-message decoder (subset): packet ACKs, topology, version — the
 * pieces that kill the power-cycle ritual and un-hardcode the device
 * index. Byte-layout ported from the reference decoder and held to
 * vectors generated from REAL captured block traffic (DecoderTest).
 */
object Blocks {
    private const val PACKET_TIMESTAMP = 32
    private const val MESSAGE_TYPE = 7
    private const val PACKET_COUNTER = 10
    private const val PROTOCOL_VERSION_BITS = 8
    private const val DEVICE_COUNT = 7
    private const val CONNECTION_COUNT = 8
    private const val SERIAL_CHAR = 7
    private const val SERIAL_LENGTH = 16
    private const val TOPOLOGY_INDEX = 7
    private const val BATTERY_LEVEL = 5
    private const val BATTERY_CHARGING = 1
    private const val CONNECTOR_PORT = 5

    // Touch message layout — VERIFIED against real Lightpad Block XC5G
    // traffic captured 2026-07-20 (see TouchDecodeTest golden vectors):
    //   header(10)  x(12)  y(12)  [velocity(8) on start/end]
    // header>>5 is (touchIndex+1): 0x20→finger0, 0x40→finger1, 0x60→finger2.
    // There is NO continuous-pressure (z) field in this mode; velocity is
    // present only on start/end. Left→right = x 0..4095, top→bottom = y.
    private const val TOUCH_HEADER = 10
    private const val TOUCH_XY = 12
    private const val TOUCH_VELOCITY = 8
    private const val TOUCH_BITS = TOUCH_HEADER + 2 * TOUCH_XY

    class State {
        @Volatile var lastAck = -1
        @Volatile var lastAckAt = 0L
        @Volatile var topologyIndex = -1
        @Volatile var serial = ""
        @Volatile var battery = -1
        @Volatile var deviceCount = 0
        val devices = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, String, Int>>()
    }

    /** Decode one device→host SysEx; update state; return events (tests).
     *  onTouch (optional) receives scaled TouchEvents for 0x10..0x15. */
    fun decode(sysex: ByteArray, st: State,
               onTouch: ((TouchEvent) -> Unit)? = null): List<String> {
        val events = mutableListOf<String>()
        if (sysex.size < 8) return events
        val hdr = Protocol.SYSEX_HEADER
        for (k in hdr.indices) if (sysex[k] != hdr[k]) return events
        if (sysex[sysex.size - 1] != 0xF7.toByte()) return events
        val deviceIndex = sysex[5].toInt() and 0x3F
        val payloadFrom = 6
        val payloadTo = sysex.size - 2
        val payload = sysex.copyOfRange(payloadFrom, payloadTo)
        if (Protocol.checksum(payload) != (sysex[sysex.size - 2].toInt() and 0xFF))
            return events
        val r = BitReader(sysex, payloadFrom, payloadTo)
        val totalBits = payload.size * 7
        var bitsRead = 0
        fun read(n: Int): Int { bitsRead += n; return r.readBits(n) }
        if (totalBits < PACKET_TIMESTAMP) return events
        val timestamp = read(PACKET_TIMESTAMP).toLong() and 0xFFFFFFFFL
        loop@ while (totalBits - bitsRead >= MESSAGE_TYPE) {
            when (val type = read(MESSAGE_TYPE)) {
                0 -> break@loop
                0x02 -> {                                    // PACKET_ACK
                    if (totalBits - bitsRead < PACKET_COUNTER) break@loop
                    val c = read(PACKET_COUNTER)
                    st.lastAck = c
                    st.lastAckAt = System.currentTimeMillis()
                    events.add("ack:$deviceIndex:$c")
                }
                0x01, 0x04 -> {                              // TOPOLOGY (+extend)
                    val ver = read(PROTOCOL_VERSION_BITS)
                    if (ver > 1) break@loop
                    val nDev = read(DEVICE_COUNT)
                    val nCon = read(CONNECTION_COUNT)
                    events.add("topo_begin:$nDev:$nCon")
                    st.devices.clear()
                    for (d in 0 until nDev) {
                        val sb = StringBuilder()
                        for (i in 0 until SERIAL_LENGTH) {
                            val ch = read(SERIAL_CHAR)
                            if (ch != 0) sb.append(ch.toChar())
                        }
                        val idx = read(TOPOLOGY_INDEX)
                        val bat = read(BATTERY_LEVEL)
                        val chg = read(BATTERY_CHARGING)
                        events.add("device:$idx:$sb:$bat:$chg")
                        st.devices.add(Triple(idx, sb.toString(), bat))
                        if (d == 0) {                        // master = ours
                            st.topologyIndex = idx
                            st.serial = sb.toString()
                            st.battery = bat
                        }
                    }
                    st.deviceCount = nDev
                    for (c in 0 until nCon) {
                        read(TOPOLOGY_INDEX); read(CONNECTOR_PORT)
                        read(TOPOLOGY_INDEX); read(CONNECTOR_PORT)
                    }
                    if (nDev < 6 && nCon < 24) events.add("topo_end")
                }
                0x05 -> {                                    // TOPOLOGY_END
                    read(PROTOCOL_VERSION_BITS)
                    events.add("topo_end")
                }
                in 0x10..0x15 -> {                           // TOUCH
                    // start/end (0x13/0x15) carry a trailing velocity byte;
                    // plain move (0x11) does not.
                    val withVel = type >= 0x13
                    val need = TOUCH_BITS + if (withVel) TOUCH_VELOCITY else 0
                    if (totalBits - bitsRead < need) break@loop
                    val header = read(TOUCH_HEADER)
                    val idx = ((header ushr 5) - 1).coerceAtLeast(0)
                    val rx = read(TOUCH_XY)
                    val ry = read(TOUCH_XY)
                    val rv = if (withVel) read(TOUCH_VELOCITY) else 0
                    val phase = when (type % 3) {
                        1 -> TouchPhase.START      // 0x10, 0x13
                        2 -> TouchPhase.MOVE       // 0x11, 0x14
                        else -> TouchPhase.END     // 0x12, 0x15
                    }
                    val name = when (phase) {
                        TouchPhase.START -> "start"
                        TouchPhase.MOVE -> "move"
                        TouchPhase.END -> "end"
                    }
                    events.add("touch:$name:$idx:$rx:$ry:$rv")
                    onTouch?.invoke(TouchEvent(
                        touchIndex = idx,
                        x = rx * 15f / 4096f,
                        y = ry * 15f / 4096f,
                        // no continuous pressure in this mode: expose the
                        // start/end strike velocity as z so gameplay still
                        // gets a "how hard" signal (0 on plain moves).
                        z = rv / 255f,
                        velocity = rv / 255f,
                        phase = phase,
                        deviceTimeMs = timestamp,
                        hostTimeMs = System.currentTimeMillis()))
                }
                0x06 -> {                                    // DEVICE_VERSION
                    val idx = read(TOPOLOGY_INDEX)
                    val len = read(7)
                    val sb = StringBuilder()
                    for (i in 0 until len) {
                        val ch = read(SERIAL_CHAR)
                        if (ch != 0) sb.append(ch.toChar())
                    }
                    events.add("version:$idx:$sb")
                }
                else -> break@loop                           // unknown: stop
            }
        }
        return events
    }
}
