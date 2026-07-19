package dev.clawdpad.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touch cases in Blocks.decode, against synthetic packets built with the
 * golden-tested BitWriter/checksum. Layout is the PROVISIONAL juce one
 * (tsOff5 idx5 x12 y12 z8 [vel8]); once real touch traffic is captured
 * (long-press status → clawd-capture) these vectors get replaced/joined
 * by real ones in decoder-golden.json.
 */
class TouchDecodeTest {

    private fun packet(devIdx: Int = 9, build: BitWriter.() -> Unit): ByteArray {
        val w = BitWriter(256)
        w.build()
        val payload = w.getData()
        return byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x10, 0x77,
            devIdx.toByte()) + payload +
            byteArrayOf(Protocol.checksum(payload).toByte(), 0xF7.toByte())
    }

    private fun BitWriter.touch(type: Int, tsOff: Int, idx: Int,
                                x: Int, y: Int, z: Int, vel: Int = 0) {
        writeBits(type, 7)
        writeBits(tsOff, 5)
        writeBits(idx, 5)
        writeBits(x, 12)
        writeBits(y, 12)
        writeBits(z, 8)
        if (type >= 0x13) writeBits(vel, 8)
    }

    @Test
    fun touchStartWithVelocity() {
        val events = mutableListOf<TouchEvent>()
        val sysex = packet {
            writeBitsLong(100_000L, 32)
            touch(0x13, tsOff = 3, idx = 1, x = 2048, y = 1024, z = 200, vel = 250)
        }
        val got = Blocks.decode(sysex, Blocks.State()) { events.add(it) }
        assertEquals(listOf("touch:start:1:2048:1024:200:250"), got)
        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals(TouchPhase.START, ev.phase)
        assertEquals(1, ev.touchIndex)
        assertEquals(2048 * 15f / 4096f, ev.x, 1e-4f)
        assertEquals(1024 * 15f / 4096f, ev.y, 1e-4f)
        assertEquals(200 / 255f, ev.z, 1e-4f)
        assertEquals(250 / 255f, ev.velocity, 1e-4f)
        assertEquals(100_003L, ev.deviceTimeMs)
    }

    @Test
    fun plainMoveAndEndHaveNoVelocity() {
        val events = mutableListOf<TouchEvent>()
        val sysex = packet {
            writeBitsLong(7L, 32)
            touch(0x11, tsOff = 0, idx = 0, x = 100, y = 4095, z = 30)
            touch(0x12, tsOff = 1, idx = 0, x = 100, y = 4095, z = 0)
        }
        val got = Blocks.decode(sysex, Blocks.State()) { events.add(it) }
        assertEquals(listOf("touch:move:0:100:4095:30:0",
                            "touch:end:0:100:4095:0:0"), got)
        assertEquals(TouchPhase.MOVE, events[0].phase)
        assertEquals(TouchPhase.END, events[1].phase)
        assertEquals(0f, events[0].velocity, 0f)
    }

    @Test
    fun mixedTouchThenAckBothDecode() {
        val st = Blocks.State()
        val sysex = packet {
            writeBitsLong(0L, 32)
            touch(0x10, tsOff = 0, idx = 2, x = 1, y = 2, z = 3)
            writeBits(0x02, 7)      // PACKET_ACK
            writeBits(321, 10)
        }
        val got = Blocks.decode(sysex, st) { }
        assertEquals(listOf("touch:start:2:1:2:3:0", "ack:9:321"), got)
        assertEquals(321, st.lastAck)
    }

    @Test
    fun truncatedTouchProducesNothingAndNoCrash() {
        val events = mutableListOf<TouchEvent>()
        val sysex = packet {
            writeBitsLong(0L, 32)
            writeBits(0x10, 7)
            writeBits(3, 5)         // then nothing — payload too short
        }
        val got = Blocks.decode(sysex, Blocks.State()) { events.add(it) }
        assertTrue(got.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun nullCallbackStillEmitsEventStrings() {
        val sysex = packet {
            writeBitsLong(0L, 32)
            touch(0x10, tsOff = 0, idx = 0, x = 0, y = 0, z = 255)
        }
        assertEquals(listOf("touch:start:0:0:0:255:0"),
            Blocks.decode(sysex, Blocks.State()))
    }
}
