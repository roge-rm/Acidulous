package com.rm.acidulous.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityCurveTest {
    @Test
    fun `as sent is untouched`() {
        for (v in 0..127) assertEquals(v, VelocityCurve.apply(v, 0))
    }

    @Test
    fun `the ends stay put and the middle moves`() {
        for (step in -3..3) {
            assertEquals(127, VelocityCurve.apply(127, step))
            assertEquals(0, VelocityCurve.apply(0, step)) // a note-off stays a note-off
        }
        // A controller that gives an ordinary tap 110: softer 2 brings it to 95, softer 3 to 85.
        assertEquals(95, VelocityCurve.apply(110, -2))
        assertEquals(85, VelocityCurve.apply(110, -3))
        assertTrue(VelocityCurve.apply(64, 2) > 64)
    }

    @Test
    fun `order is kept, and nothing reaches zero`() {
        for (step in -3..3) {
            val out = (1..127).map { VelocityCurve.apply(it, step) }
            assertTrue(out.zipWithNext().all { (a, b) -> a <= b })
            assertTrue(out.all { it in 1..127 })
        }
    }
}
