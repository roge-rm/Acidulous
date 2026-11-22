package com.rm.acidulous.ui

import org.junit.Test
import org.junit.Assert.assertEquals

class KeyPressureTest {
    @Test fun `starts at zero and rises going up`() {
        assertEquals(0, pressureFor(100f, 100f, 48f))
        assertEquals(63, pressureFor(100f, 76f, 48f))
        assertEquals(127, pressureFor(100f, 52f, 48f))
    }

    @Test fun `clamps and falls going back down`() {
        assertEquals(127, pressureFor(100f, 0f, 48f))
        assertEquals(0, pressureFor(100f, 150f, 48f))
        assertEquals(31, pressureFor(100f, 88f, 48f))
    }
}
