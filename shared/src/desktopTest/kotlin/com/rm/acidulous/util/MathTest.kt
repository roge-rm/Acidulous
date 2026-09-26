package com.rm.acidulous.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MathTest {
    @Test
    fun javasAnswers() {
        val ints = listOf(-17, -8, -7, -1, 0, 1, 6, 7, 8, 17, 95, -96, Int.MAX_VALUE, Int.MIN_VALUE + 1)
        for (x in ints) for (y in listOf(1, 3, 7, 8, 12, 96, -5)) {
            assertEquals(java.lang.Math.floorMod(x, y), Math.floorMod(x, y))
            assertEquals(java.lang.Math.floorDiv(x, y), Math.floorDiv(x, y))
            assertEquals(java.lang.Math.floorMod(x.toLong(), y.toLong()), Math.floorMod(x.toLong(), y.toLong()))
            assertEquals(java.lang.Math.floorMod(x.toLong() * 3, y), Math.floorMod(x.toLong() * 3, y))
            assertEquals(java.lang.Math.floorDiv(x.toLong(), y.toLong()), Math.floorDiv(x.toLong(), y.toLong()))
        }
        for (a in listOf(0.5f, 1.5f, 2.5f, -0.5f, -1.5f, -2.5f, 0.49999997f, 3.7f, -3.7f, 1e9f, Float.NaN, 1e12f, -1e12f)) {
            assertEquals("round $a", java.lang.Math.round(a), Math.round(a))
        }
        for (a in listOf(0.5, 1.5, 2.5, -0.5, -1.5, -2.5, 0.49999999999999994, 3.7, -3.7, 1e15 + 0.5, Double.NaN, 1e20, -1e20)) {
            assertEquals("round $a", java.lang.Math.round(a), Math.round(a))
        }
        assertEquals(java.lang.Math.toRadians(33.0), Math.toRadians(33.0), 0.0)
        assertEquals(java.lang.Math.pow(2.0, 0.5), Math.pow(2.0, 0.5), 0.0)
    }
}
