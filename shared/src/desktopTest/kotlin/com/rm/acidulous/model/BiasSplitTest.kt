package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting one recording into cells.
 *
 * The engine's half of this is `tools/marks_test`, which proves the boundaries
 * land where they should. This is the other half: given boundaries, the right
 * windows into the file, and nothing invented.
 */
class BiasSplitTest {

    private val bar = 4 * PPQN
    private val barFrames = 2 * ENGINE_RATE // a bar at 120bpm

    private fun mark(frame: Int, scene: Long, tick: Int = 0, cycle: Int = 8 * 960, bpm: Float = 120f) =
        Mark(frame.toLong(), scene, tick, cycle, bpm)

    private val names = mapOf(11L to "s-intro", 22L to "s-verse", 33L to "s-chorus")
    private val nameOf: (Long) -> String? = { names[it] }

    @Test
    fun consecutiveMarksPairIntoWindows() {
        val marks = listOf(
            mark(0, 11), mark(8 * barFrames, 22), mark(16 * barFrames, 33),
        )
        val out = splitTake(marks, 24L * barFrames, "samples/take.wav", nameOf)

        assertEquals(listOf("s-intro", "s-verse", "s-chorus"), out.keys.toList())
        assertEquals(0, out["s-intro"]!!.offset)
        assertEquals(8 * barFrames, out["s-intro"]!!.frames)
        assertEquals(8 * barFrames, out["s-verse"]!!.offset)
        // The last one runs to wherever the recording stopped.
        assertEquals(8 * barFrames, out["s-chorus"]!!.frames)
        assertTrue(out.values.all { it.file == "samples/take.wav" })
    }

    @Test
    fun aPunchInKeepsItsStartTick() {
        val out = splitTake(
            listOf(mark(0, 22, tick = 2 * bar)), 4L * barFrames, "samples/take.wav", nameOf,
        )
        assertEquals(2 * bar, out["s-verse"]!!.startTick)
        assertEquals(0, out["s-verse"]!!.offset)
    }

    @Test
    fun eachCellCarriesTheTempoItWasSungAt() {
        val out = splitTake(
            listOf(mark(0, 11, bpm = 120f), mark(4 * barFrames, 22, bpm = 90f)),
            8L * barFrames, "samples/take.wav", nameOf,
        )
        assertEquals(120f, out["s-intro"]!!.bpm, 0.01f)
        assertEquals(90f, out["s-verse"]!!.bpm, 0.01f)
    }

    /** A boundary crossed in the last moments leaves a sliver, not a take. */
    @Test
    fun sliversAreDropped() {
        val out = splitTake(
            listOf(mark(0, 11), mark(4 * barFrames, 22)),
            4L * barFrames + 500, "samples/take.wav", nameOf,
        )
        assertEquals(listOf("s-intro"), out.keys.toList())
        assertNull(out["s-verse"])
    }

    /** A scene deleted between recording and stopping is dropped, not guessed. */
    @Test
    fun aVanishedSceneIsLeftOut() {
        val out = splitTake(
            listOf(mark(0, 11), mark(4 * barFrames, 99)),
            8L * barFrames, "samples/take.wav", nameOf,
        )
        assertEquals(listOf("s-intro"), out.keys.toList())
    }

    /** Round the song twice and the second pass is the keeper. */
    @Test
    fun goingRoundAgainReplacesTheFirstPass() {
        val out = splitTake(
            listOf(mark(0, 11), mark(4 * barFrames, 22), mark(8 * barFrames, 11)),
            12L * barFrames, "samples/take.wav", nameOf,
        )
        assertEquals(8 * barFrames, out["s-intro"]!!.offset)
    }

    @Test
    fun theEnginesFlatArrayUnpacks() {
        val raw = longArrayOf(1000, 11, 480, 7680, 90500, 2000, 22, 0, 3840, 120000)
        val marks = marksFrom(raw, 2)
        assertEquals(2, marks.size)
        assertEquals(1000L, marks[0].frame)
        assertEquals(480, marks[0].tick)
        assertEquals(90.5f, marks[0].bpm, 0.001f)
        assertEquals(3840, marks[1].cycleTicks)
    }
}
