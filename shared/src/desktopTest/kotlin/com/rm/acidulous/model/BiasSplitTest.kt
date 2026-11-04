package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting one recording into cells. `tools/marks_test` checks the engine
 * puts the boundaries in the right place; this checks that given the
 * boundaries, each cell gets the right window into the file.
 */
class BiasSplitTest {

    private val bar = 4 * PPQN
    private val barFrames = 2 * ENGINE_RATE // a bar at 120bpm

    private fun mark(frame: Int, scene: Long, tick: Int = 0, cycle: Int = 8 * 960, bpm: Float = 120f) =
        Mark(frame.toLong(), scene, tick, cycle, bpm)

    private val names = mapOf(11L to "s-intro", 22L to "s-verse", 33L to "s-chorus")
    private val nameOf: (Long) -> String? = { names[it] }

    private val List<Pair<String, TakeRef>>.keys get() = map { it.first }
    private val List<Pair<String, TakeRef>>.values get() = map { it.second }
    private operator fun List<Pair<String, TakeRef>>.get(scene: String): TakeRef? = lastOrNull { it.first == scene }?.second

    @Test
    fun consecutiveMarksPairIntoWindows() {
        val marks = listOf(
            mark(0, 11), mark(8 * barFrames, 22), mark(16 * barFrames, 33),
        )
        val out = splitTake(marks, 24L * barFrames, "samples/take.wav", nameOf)

        assertEquals(listOf("s-intro", "s-verse", "s-chorus"), out.keys)
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
        assertEquals(listOf("s-intro"), out.keys)
        assertNull(out["s-verse"])
    }

    /** A scene deleted between recording and stopping is dropped, not guessed. */
    @Test
    fun aVanishedSceneIsLeftOut() {
        val out = splitTake(
            listOf(mark(0, 11), mark(4 * barFrames, 99)),
            8L * barFrames, "samples/take.wav", nameOf,
        )
        assertEquals(listOf("s-intro"), out.keys)
    }

    /** Go round the song twice and both passes are kept, in order. */
    @Test
    fun goingRoundAgainKeepsBothPasses() {
        val out = splitTake(
            listOf(mark(0, 11), mark(4 * barFrames, 22), mark(8 * barFrames, 11)),
            12L * barFrames, "samples/take.wav", nameOf,
        )
        assertEquals(listOf("s-intro", "s-verse", "s-intro"), out.keys)
        assertEquals(listOf(0, 8 * barFrames), out.filter { it.first == "s-intro" }.map { it.second.offset })
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

    /**
     * Marks left from an earlier recording, ahead of this one's. The engine
     * now clears them when a capture starts; the split ignores them anyway.
     */
    @Test
    fun marksFromAnEarlierRecordingAreIgnored() {
        val cell = 2 * barFrames
        val stale = listOf(mark(0, 11), mark(cell, 11), mark(2 * cell, 11), mark(5 * cell, 11))
        val fresh = listOf(mark(0, 11), mark(cell, 11))
        val out = splitTake(stale + fresh, cell + cell / 2L, "samples/take.wav", nameOf)
        assertEquals(listOf(0, cell), out.values.map { it.offset })
        assertEquals(listOf(cell, cell / 2), out.values.map { it.frames })
    }

    @Test
    fun aMarkPastTheEndOfTheFileMakesNoTake() {
        val cell = 2 * barFrames
        val out = splitTake(listOf(mark(0, 11), mark(cell, 11), mark(3 * cell, 11)), cell * 2L, "samples/take.wav", nameOf)
        assertEquals(listOf(0, cell), out.values.map { it.offset })
        assertTrue(out.values.all { it.offset + it.frames <= cell * 2 })
    }

    /** The song stopped while still armed: the take ends there, not at disarm. */
    @Test
    fun aStopEndsTheLastPass() {
        val cell = 2 * barFrames
        val stop = Mark(cell + cell / 2L, 0, 0, 0, 0f)
        val out = splitTake(listOf(mark(0, 11), mark(cell, 11), stop), 11L * cell, "samples/take.wav", nameOf)
        assertEquals(listOf(0, cell), out.values.map { it.offset })
        assertEquals(listOf(cell, cell / 2), out.values.map { it.frames })
    }

    /** Playing again after a stop, still armed, starts a new pass after the gap. */
    @Test
    fun playingAgainAfterAStopStartsANewPass() {
        val cell = 2 * barFrames
        val stop = Mark(cell.toLong(), 0, 0, 0, 0f)
        val out = splitTake(listOf(mark(0, 11), stop, mark(cell + cell / 2, 11)), 3L * cell, "samples/take.wav", nameOf)
        assertEquals(listOf(0, cell + cell / 2), out.values.map { it.offset })
        assertEquals(listOf(cell, 3 * cell - (cell + cell / 2)), out.values.map { it.frames })
    }
}
