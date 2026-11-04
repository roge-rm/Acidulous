package com.rm.acidulous.ui

import com.rm.acidulous.engine.ParamInfo
import org.junit.Test
import org.junit.Assert.assertEquals

class SwitchIndexTest {
    // The arp's octaves and ratchet run 1..4: a tap on cell i must light cell i
    // and send the engine i + 1.
    @Test
    fun aSwitchOverARangeNotFromZeroLightsTheTappedCell() {
        val octaves = ParamInfo("octaves", 1f, 4f, 1f, 2, 4, "")
        for (i in 0 until 4) {
            val v = i / 3f
            assertEquals(i, switchIndex(v, 4))
            assertEquals(i + 1f, octaves.map(v), 0f)
        }
    }

    @Test
    fun aSwitchFromZeroIsUnchanged() {
        val octmode = ParamInfo("octmode", 0f, 2f, 0f, 2, 3, "")
        for (i in 0 until 3) {
            val v = i / 2f
            assertEquals(i, switchIndex(v, 3))
            assertEquals(octmode.map(v).toInt(), switchIndex(v, 3))
        }
    }

    @Test
    fun oneCellIsAlwaysTheFirst() {
        assertEquals(0, switchIndex(0f, 1))
        assertEquals(0, switchIndex(1f, 1))
    }
}
