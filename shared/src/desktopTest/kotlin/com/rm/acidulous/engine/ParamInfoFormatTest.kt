package com.rm.acidulous.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a knob's value reads, by unit. A linear 0..max range, so v01 = value / max. */
class ParamInfoFormatTest {

    private fun read(unit: String, value: Float, max: Float): String =
        ParamInfo("p", 0f, max, 0f, 0, 0, unit).format(value / max)

    @Test
    fun hertz() {
        assertEquals("0.12Hz", read("Hz", 0.12f, 20000f))
        assertEquals("2.5Hz", read("Hz", 2.5f, 20000f))
        assertEquals("440Hz", read("Hz", 440f, 20000f))
        assertEquals("1.2k", read("Hz", 1200f, 20000f))
    }

    @Test
    fun seconds() {
        assertEquals("250ms", read("s", 0.25f, 10f))
        assertEquals("2.50s", read("s", 2.5f, 10f))
        assertEquals("12.0s", read("s", 12f, 20f))
    }

    @Test
    fun milliseconds() {
        assertEquals("40ms", read("ms", 40f, 2000f))
        assertEquals("1.50s", read("ms", 1500f, 2000f))
    }

    @Test
    fun ratio() {
        assertEquals("1.50x", read("x", 1.5f, 4f))
    }

    @Test
    fun volumeDial() {
        val p = ParamInfo("volume", 0f, 1.5f, 0.29f, 0, 0, "")
        assertEquals("-inf", p.formatDb(0f))
        assertEquals("0.0 dB", p.formatDb(p.defaultNormalized))
        assertEquals("+14.3 dB", p.formatDb(1f))
        assertEquals(0f, p.toDial(0f))
        assertEquals(1f, p.toDial(1f))
        val at = p.toDial(p.defaultNormalized)
        assertEquals(0.808f, at, 0.005f)
        assertEquals(p.defaultNormalized, p.fromDial(at), 1e-4f)
        assertEquals(0f, p.fromDial(0f))
    }
}
