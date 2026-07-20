package dev.clawdpad.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touch cases in Blocks.decode.
 *
 * The REAL vectors below are verbatim SysEx captured from a Lightpad Block
 * XC5G on 2026-07-20 (long-press status → "capture ON" → clawd-capture),
 * during a scripted gesture pass (centre tap + four corner taps). They pin
 * the verified wire layout: per touch group  header(10) x(12) y(12)
 * [velocity(8) on start/end], 7-bit LSB-first, after the 32-bit packet
 * timestamp — decoded left→right x, top→bottom y, both 0..4095.
 *
 * The synthetic cases (built with the golden-tested BitWriter/checksum)
 * cover multitouch, ack-interleave and truncation.
 */
class TouchDecodeTest {

    private fun hexPacket(s: String): ByteArray {
        val toks = s.trim().split(Regex("\\s+"))
        return ByteArray(toks.size) { toks[it].toInt(16).toByte() }
    }

    private fun packet(devIdx: Int = 9, build: BitWriter.() -> Unit): ByteArray {
        val w = BitWriter(256)
        w.build()
        val payload = w.getData()
        return byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x10, 0x77,
            devIdx.toByte()) + payload +
            byteArrayOf(Protocol.checksum(payload).toByte(), 0xF7.toByte())
    }

    /** real device layout: header = (touchIndex+1) shl 5 */
    private fun BitWriter.touch(type: Int, idx: Int, x: Int, y: Int,
                                vel: Int = 0) {
        writeBits(type, 7)
        writeBits((idx + 1) shl 5, 10)
        writeBits(x, 12)
        writeBits(y, 12)
        if (type >= 0x13) writeBits(vel, 8)
    }

    // ── REAL captured traffic: centre + four corners ────────────────────
    // Each is a touch-start; the decoded x/y must land in the right quadrant
    // of the 0..4095 pad (left→right x, top→bottom y).

    @Test
    fun realCentreTapDecodesToMiddle() {
        val sysex = hexPacket(
            "F0 00 21 10 77 49 00 00 00 00 30 02 04 15 10 1B 54 03 00 40 32 2E F7")
        val ev = mutableListOf<TouchEvent>()
        val got = Blocks.decode(sysex, Blocks.State()) { ev.add(it) }
        assertEquals(listOf("touch:start:0:2069:2156:58"), got)
        assertEquals(TouchPhase.START, ev[0].phase)
        // ~centre of the pad
        assertTrue(ev[0].x in 7f..8.5f)
        assertTrue(ev[0].y in 7f..8.5f)
    }

    @Test
    fun realCornersLandInCorrectQuadrants() {
        data class C(val hex: String, val expect: String,
                     val leftHalf: Boolean, val topHalf: Boolean)
        val corners = listOf(
            C("F0 00 21 10 77 49 00 00 00 00 30 02 04 53 03 61 18 03 00 40 2A 41 F7",
              "touch:start:0:467:388:51", true, true),      // top-left
            C("F0 00 21 10 77 49 00 00 00 00 30 02 04 03 1D 3B 10 08 00 40 3F 5D F7",
              "touch:start:0:3715:236:130", false, true),   // top-right
            C("F0 00 21 10 77 49 00 00 00 00 30 02 04 7A 02 6E 46 0B 00 40 3F 67 F7",
              "touch:start:0:378:3512:184", true, false),   // bottom-left
            C("F0 00 21 10 77 49 00 00 00 00 30 02 04 06 7D 60 7E 0F 00 40 3F 08 F7",
              "touch:start:0:3718:3459:255", false, false), // bottom-right
        )
        for (c in corners) {
            val ev = mutableListOf<TouchEvent>()
            val got = Blocks.decode(hexPacket(c.hex), Blocks.State()) { ev.add(it) }
            assertEquals(c.expect, got.single())
            assertEquals("x half for ${c.expect}",
                c.leftHalf, ev[0].x < 7.5f)
            assertEquals("y half for ${c.expect}",
                c.topHalf, ev[0].y < 7.5f)
        }
    }

    // ── synthetic coverage against the same layout ──────────────────────

    @Test
    fun startCarriesVelocityMoveDoesNot() {
        val ev = mutableListOf<TouchEvent>()
        val sysex = packet {
            writeBitsLong(0L, 32)
            touch(0x13, idx = 1, x = 2048, y = 1024, vel = 250)
            touch(0x11, idx = 1, x = 2050, y = 1030)          // move, no vel
        }
        val got = Blocks.decode(sysex, Blocks.State()) { ev.add(it) }
        assertEquals(listOf("touch:start:1:2048:1024:250",
                            "touch:move:1:2050:1030:0"), got)
        assertEquals(1, ev[0].touchIndex)
        assertEquals(2048 * 15f / 4096f, ev[0].x, 1e-4f)
        assertEquals(250 / 255f, ev[0].velocity, 1e-4f)
        assertEquals(0f, ev[1].velocity, 0f)
    }

    @Test
    fun multitouchTwoFingersInOnePacket() {
        val ev = mutableListOf<TouchEvent>()
        val sysex = packet {
            writeBitsLong(0L, 32)
            touch(0x11, idx = 0, x = 100, y = 200)
            touch(0x11, idx = 1, x = 4000, y = 3000)
        }
        Blocks.decode(sysex, Blocks.State()) { ev.add(it) }
        assertEquals(2, ev.size)
        assertEquals(0, ev[0].touchIndex)
        assertEquals(1, ev[1].touchIndex)
    }

    @Test
    fun realConcatenatedMultitouchDecodesFirstFingerSafely() {
        // Real two-finger MOVE captured from hardware: extra fingers pack as
        // consecutive `02 <finger>` groups WITHOUT repeating the type byte.
        // Full multi-finger parsing needs a dedicated capture (see
        // docs/lightpad-touch-protocol.md §2); today we decode finger 0 and
        // stop cleanly rather than emit garbage for the trailing groups.
        val sysex = hexPacket(
            "F0 00 21 10 77 49 00 00 00 00 10 02 04 08 31 48 7A 1F " +
            "02 08 4B 70 5B 43 07 2B F7")
        val ev = mutableListOf<TouchEvent>()
        val got = Blocks.decode(sysex, Blocks.State()) { ev.add(it) }
        assertEquals(listOf("touch:move:0:2184:1313:0"), got)
        assertEquals(0, ev.single().touchIndex)
    }

    @Test
    fun touchThenAckBothDecode() {
        val st = Blocks.State()
        val sysex = packet {
            writeBitsLong(0L, 32)
            touch(0x15, idx = 0, x = 1, y = 2, vel = 9)       // end
            writeBits(0x02, 7)                                 // PACKET_ACK
            writeBits(321, 10)
        }
        val got = Blocks.decode(sysex, st) { }
        assertEquals(listOf("touch:end:0:1:2:9", "ack:9:321"), got)
        assertEquals(321, st.lastAck)
    }

    @Test
    fun truncatedTouchProducesNothingAndNoCrash() {
        val ev = mutableListOf<TouchEvent>()
        val sysex = packet {
            writeBitsLong(0L, 32)
            writeBits(0x11, 7)
            writeBits(0x20, 10)                                // header, then nothing
        }
        val got = Blocks.decode(sysex, Blocks.State()) { ev.add(it) }
        assertTrue(got.isEmpty())
        assertTrue(ev.isEmpty())
    }
}
