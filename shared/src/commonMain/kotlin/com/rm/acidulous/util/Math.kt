package com.rm.acidulous.util

import kotlin.math.pow
import kotlin.math.PI
import kotlin.math.floor

/**
 * java.lang.Math, as much of it as the shared code uses, with Java's own
 * results on every platform. The calls read as they always did; only the
 * import says which Math. Not kotlin.math's round, which rounds a half to
 * the even neighbour - Java's rounds it up, and a grid position or a knob
 * value that moved by one on a browser would be a bug nobody could see.
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

    /** A half rounds up, toward positive infinity, as Java's does; NaN is nought. */
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
