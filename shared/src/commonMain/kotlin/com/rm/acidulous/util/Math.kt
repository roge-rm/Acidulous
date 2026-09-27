package com.rm.acidulous.util

import kotlin.math.pow
import kotlin.math.PI
import kotlin.math.floor

/**
 * The parts of java.lang.Math the shared code uses, with Java's results on
 * every platform. Only the import changes. kotlin.math's round rounds a half
 * to the even neighbour while Java's rounds up, which would shift grid
 * positions and knob values by one in a browser.
 */
object Math {
    fun floorMod(x: Int, y: Int): Int = x - floorDiv(x, y) * y
    fun floorMod(x: Long, y: Long): Long = x - floorDiv(x, y) * y
    fun floorMod(x: Long, y: Int): Int = floorMod(x, y.toLong()).toInt()
    fun floorDiv(x: Int, y: Int): Int {
        val q = x / y
        return if ((x % y != 0) && ((x xor y) < 0)) q - 1 else q
    }
    fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x % y != 0L) && ((x xor y) < 0L)) q - 1 else q
    }
    fun floorDiv(x: Long, y: Int): Long = floorDiv(x, y.toLong())

    /** A half rounds up, toward positive infinity, like Java's; NaN is 0. */
    fun round(a: Float): Int {
        if (a.isNaN()) return 0
        val f = kotlin.math.floor(a)
        return (if (a - f >= 0.5f) f + 1f else f).toInt()
    }
    fun round(a: Double): Long {
        if (a.isNaN()) return 0L
        val f = floor(a)
        return (if (a - f >= 0.5) f + 1.0 else f).toLong()
    }

    fun pow(a: Double, b: Double): Double = a.pow(b)
    fun toRadians(angdeg: Double): Double = angdeg * (PI / 180.0)
}
