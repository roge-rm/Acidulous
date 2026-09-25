package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * What a finger's two hundred messages a second come out as.
 *
 * The interesting property is not how few points survive but whether the
 * curve still passes through where it was: a thinning that loses the shape
 * has recorded something else.
 */
class CurveBuilderTest {

    private fun build(neutral: Float = 0f, block: CurveBuilder.() -> Unit): Lane? =
        CurveBuilder(neutral = neutral).apply(block).build()

    @Test
    fun `a finger that never moved records nothing`() {
        val lane = build(neutral = 0.5f) { repeat(200) { add(it * 2, 0.5f) } }
        assertNull(lane)
    }

    @Test
    fun `a finger held at one pressure records one point`() {
        val lane = build { repeat(200) { add(it * 2, 0.75f) } }
        assertNotNull(lane)
        assertEquals(1, lane!!.points.size)
        assertEquals(0.75f, lane.points[0].value, 1e-4f)
    }

    @Test
    fun `an even glide comes out as its two ends`() {
        val lane = build { repeat(201) { add(it, it / 200f) } }
        assertNotNull(lane)
        assertEquals(2, lane!!.points.size)
        assertEquals(0f, lane.points.first().value, 1e-4f)
        assertEquals(1f, lane.points.last().value, 1e-4f)
    }

    @Test
    fun `a corner is kept`() {
        // Up for a hundred ticks, then flat: the turn is the whole shape, and
        // the flat that follows is free, because a lane holds its last value.
        val lane = build {
            for (t in 0..100) add(t, t / 100f)
            for (t in 101..200) add(t, 1f)
        }
        assertNotNull(lane)
        assertEquals(2, lane!!.points.size)
        assertEquals(100, lane.points[1].tick)
        assertEquals(0.5f, lane.valueAt(50), 1e-3f)
        assertEquals(1f, lane.valueAt(200), 1e-3f)
    }

    @Test
    fun `a corner in the middle is kept`() {
        // Up and back down: nothing about this can be one straight line.
        val lane = build {
            for (t in 0..100) add(t, t / 100f)
            for (t in 101..200) add(t, (200 - t) / 100f)
        }
        assertNotNull(lane)
        assertEquals(3, lane!!.points.size)
        assertEquals(100, lane.points[1].tick)
        assertEquals(1f, lane.points[1].value, 1e-3f)
        assertEquals(0f, lane.points[2].value, 1e-3f)
    }

    @Test
    fun `the shape survives the thinning`() {
        // Two cycles of a vibrato, sampled the way a controller sends it.
        val samples = (0..480).map { t -> 0.5f + 0.25f * sin(t / 480f * 4f * Math.PI).toFloat() }
        val lane = build(neutral = 0.5f) { samples.forEachIndexed { t, v -> add(t, v) } }
        assertNotNull(lane)
        assertTrue("kept ${lane!!.points.size} of ${samples.size}", lane.points.size < samples.size / 4)
        val worst = samples.indices.maxOf { t -> abs(lane.valueAt(t) - samples[t]) }
        // A whole tone of full-scale bend is 1/48 here; a fiftieth of that is
        // under a cent, which is the point of thinning rather than resampling.
        assertTrue("worst error $worst", worst < 0.01f)
    }

    @Test
    fun `the point cap is honoured`() {
        // Noise: nothing a line predicts, so only the cap can stop it.
        val rng = java.util.Random(1)
        val lane = CurveBuilder(neutral = 0f, limit = 64)
            .apply { repeat(4000) { add(it, rng.nextFloat()) } }.build()
        assertNotNull(lane)
        assertTrue("kept ${lane!!.points.size}", lane.points.size <= 65)
    }

    @Test
    fun `values outside the domain are brought back into it`() {
        val lane = build { add(0, -3f); add(10, 4f) }
        assertNotNull(lane)
        assertTrue(lane!!.points.all { it.value in 0f..1f })
    }

    @Test
    fun `a curve is trimmed to its note`() {
        // The finger went on moving after the key came up.
        val lane = Lane(points = listOf(LanePoint(0, 0f), LanePoint(100, 1f)))
        val trimmed = lane.trimmedTo(50)
        assertNotNull(trimmed)
        assertEquals(50, trimmed!!.points.last().tick)
        assertEquals(0.5f, trimmed.points.last().value, 1e-4f)
    }

    @Test
    fun `a curve inside its note is left alone`() {
        val lane = Lane(points = listOf(LanePoint(0, 0f), LanePoint(40, 1f)))
        assertEquals(lane, lane.trimmedTo(50))
    }

    @Test
    fun `bend converts to semitones and back`() {
        for (semis in listOf(-48f, -12f, -2f, 0f, 0.5f, 7f, 48f)) {
            assertEquals(semis, Note.bendFrom01(Note.bendTo01(semis)), 1e-3f)
        }
        assertEquals(0.5f, Note.bendTo01(0f), 1e-6f)
    }
}
