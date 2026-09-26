package com.rm.acidulous.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JavaRandomTest {
    @Test
    fun theSameSequenceAsJavas() {
        for (seed in listOf(0L, 1L, 17L, -5L, 7919L * 3 + 17, 104729L * 99 + 3, Long.MAX_VALUE, Long.MIN_VALUE, 123456789012345L)) {
            val java = java.util.Random(seed)
            val ours = JavaRandom(seed)
            repeat(2000) { i ->
                when (i % 7) {
                    0 -> assertEquals(java.nextInt(), ours.nextInt())
                    1 -> assertEquals(java.nextInt(12), ours.nextInt(12))
                    2 -> assertEquals(java.nextInt(16), ours.nextInt(16))
                    3 -> assertEquals(java.nextFloat(), ours.nextFloat(), 0f)
                    4 -> assertEquals(java.nextBoolean(), ours.nextBoolean())
                    5 -> assertEquals(java.nextLong(), ours.nextLong())
                    else -> assertEquals(java.nextDouble(), ours.nextDouble(), 0.0)
                }
            }
        }
    }
}
