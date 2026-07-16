package dev.clawdpad.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** Blocks.kt vs events produced by the reference decoder on REAL
 *  captured block traffic (topology, version, hundreds of ACKs). */
class DecoderTest {
    @Test
    fun realCaptures() {
        val golden = JSONObject(
            javaClass.classLoader!!.getResourceAsStream("decoder-golden.json")!!
                .readBytes().decodeToString())
        val cases = golden.getJSONArray("cases")
        for (c in 0 until cases.length()) {
            val case_ = cases.getJSONObject(c)
            val sysex = Base64.getDecoder().decode(case_.getString("sysex"))
            val expected = case_.getJSONArray("events")
            val got = Blocks.decode(sysex, Blocks.State())
            assertEquals("case $c count", expected.length(), got.size)
            for (i in got.indices)
                assertEquals("case $c ev $i", expected.getString(i), got[i])
        }
    }
}
