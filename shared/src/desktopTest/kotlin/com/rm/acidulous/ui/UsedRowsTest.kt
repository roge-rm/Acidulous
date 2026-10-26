package com.rm.acidulous.ui

import org.junit.Test
import org.junit.Assert.assertArrayEquals

class UsedRowsTest {
    @Test fun fewPitchesAllShowTopFirst() {
        assertArrayEquals(intArrayOf(64, 60, 55), usedRowPitches(listOf(55, 60, 64), 0, 16))
    }

    @Test fun windowStartsAtLowest() {
        val used = (0 until 10).map { 40 + it * 2 }
        assertArrayEquals(intArrayOf(52, 50, 48, 46), usedRowPitches(used, 46, 4))
    }

    @Test fun windowStopsAtTheTop() {
        val used = listOf(40, 42, 44, 46, 48)
        assertArrayEquals(intArrayOf(48, 46, 44), usedRowPitches(used, 100, 3))
    }

    @Test fun emptyIsEmpty() {
        assertArrayEquals(intArrayOf(), usedRowPitches(emptyList(), 0, 16))
    }
}
