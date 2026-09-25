package com.rm.acidulous.midi

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How hard a controller's notes arrive, bent to suit the hands on it.
 *
 * Controllers disagree about what a tap is worth: one gives an ordinary tap
 * 110, another 60. A curve bends every note-on the same way before anything
 * hears it, so a player who finds everything loud can turn it down without
 * losing the difference between soft and hard. The ends stay put - the
 * softest note is still the softest, the hardest still 127 - and the middle
 * moves: softer lowers it, harder raises it.
 */
object VelocityCurve {
    /** Steps each way from as sent. */
    const val STEPS = 3

    /** [v], 1..127, bent [step] steps: below 0 softer, above harder, 0 as sent. */
    fun apply(v: Int, step: Int): Int {
        if (step == 0 || v <= 0) return v
        // Each step is half an octave of exponent: softer 2 squares it.
        val exponent = 2.0.pow(-step.coerceIn(-STEPS, STEPS) * 0.5)
        return (127.0 * (v / 127.0).pow(exponent)).roundToInt().coerceIn(1, 127)
    }
}
