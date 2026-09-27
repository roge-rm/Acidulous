package com.rm.acidulous.midi

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A velocity curve for incoming notes.
 *
 * Controllers differ: one gives a normal tap 110, another 60. The curve bends
 * every note-on the same way, so a player can make things softer without
 * losing the range between soft and hard. The ends stay put (the softest
 * note stays softest and the hardest stays 127) and the middle moves: softer
 * lowers it, harder raises it.
 */
object VelocityCurve {
    /** Steps each way from unchanged. */
    const val STEPS = 3

    /** [v] (1..127) bent by [step] steps: below 0 is softer, above is harder, 0 is unchanged. */
    fun apply(v: Int, step: Int): Int {
        if (step == 0 || v <= 0) return v
        // Each step scales the exponent by the square root of 2, so softer 2 squares it.
        val exponent = 2.0.pow(-step.coerceIn(-STEPS, STEPS) * 0.5)
        return (127.0 * (v / 127.0).pow(exponent)).roundToInt().coerceIn(1, 127)
    }
}
