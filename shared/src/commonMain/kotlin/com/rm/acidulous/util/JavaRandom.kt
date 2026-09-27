package com.rm.acidulous.util

/**
 * java.util.Random, number for number, on every platform.
 *
 * Generate and Quantise use seeded randoms, so the same seed must give the
 * same notes everywhere, including in a browser. Kotlin's Random(seed) is a
 * different sequence. This is Java's 48-bit linear congruential generator
 * with the same derivations (see the java.util.Random javadoc), checked
 * against it in JavaRandomTest.
 */
class JavaRandom(seed: Long) {
    private var seed: Long = (seed xor MULTIPLIER) and MASK

    private fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + ADDEND) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    fun nextInt(): Int = next(32)

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next(31)
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
        var u = r
        while (true) {
            r = u % bound
            if (u - r + m >= 0) return r
            u = next(31)
        }
    }

    fun nextLong(): Long = (next(32).toLong() shl 32) + next(32)
    fun nextBoolean(): Boolean = next(1) != 0
    fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val MASK = (1L shl 48) - 1
        // 1.0 / (1L shl 53) written out, because the Wasm compiler can't fold that
        // expression into a constant. It's exactly the same double.
        const val DOUBLE_UNIT = 1.1102230246251565E-16
    }
}
