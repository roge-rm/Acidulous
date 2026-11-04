package com.rm.acidulous.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lane's stack of takes: loop recording adds to it, pieces say what plays. */
class BiasStackTest {

    private val bar = 4 * PPQN
    private val barFrames = 2 * ENGINE_RATE // a bar at 120 bpm
    private val cycle = 2 * bar

    private fun pass(offset: Int, frames: Int = 2 * barFrames, startTick: Int = 0, file: String = "samples/take.wav") =
        TakeRef(file = file, offset = offset, frames = frames, bpm = 120f, ticks = cycle, startTick = startTick)

    @Test
    fun eachPassOfALoopGoesOnTheStackAndTheNewestIsHeard() {
        var clip = Clip(bars = 2)
        for (i in 0 until 3) clip = clip.withRecorded(0, pass(offset = i * 2 * barFrames))
        val lane = clip.audio!!.lane(0)!!
        assertEquals(3, lane.takeCount)
        assertEquals(listOf(0, 2 * barFrames, 4 * barFrames), lane.takes.map { it.offset })
        assertEquals(lane.takes[0].offset, lane.offset)
        assertEquals(listOf(Piece(0, 2 * barFrames, 2)), lane.pieces)
        assertEquals(2, lane.heardTake())
    }

    @Test
    fun onePassIsAPlainLaneAsBefore() {
        val clip = Clip(bars = 2).withRecorded(0, pass(offset = 1000))
        val lane = clip.audio!!.lane(0)!!
        assertTrue(lane.takes.isEmpty())
        assertTrue(lane.pieces.isEmpty())
        assertEquals(1, lane.takeCount)
    }

    /** Stopping half way through a pass replaces only the half it has. */
    @Test
    fun aShortLastPassIsHeardOnlyWhereItHasAudio() {
        val clip = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(2 * barFrames, frames = barFrames))
        val lane = clip.audio!!.lane(0)!!
        assertEquals(listOf(Piece(0, barFrames, 1), Piece(barFrames, 2 * barFrames, 0)), lane.pieces)
        assertEquals(2 * barFrames, lane.frames)
    }

    /** A punch-in followed by a whole pass: the region grows back to the bar and take 0 keeps its place in time. */
    @Test
    fun anEarlierPassGrowsTheRegion() {
        val punched = pass(offset = 500, frames = barFrames, startTick = bar)
        val clip = Clip(bars = 2).withRecorded(0, punched).withRecorded(0, pass(offset = 500 + barFrames))
        val lane = clip.audio!!.lane(0)!!
        assertEquals(0, lane.startTick)
        assertEquals(2 * barFrames, lane.frames)
        // Take 0's audio still starts a bar in.
        assertEquals(StackTake("samples/take.wav", 500 - barFrames, barFrames, 2 * barFrames), lane.takes[0])
        assertEquals(StackTake("samples/take.wav", 500 + barFrames, 0, 2 * barFrames), lane.takes[1])
        assertEquals(listOf(Piece(0, 2 * barFrames, 1)), lane.pieces)
    }

    @Test
    fun anotherTempoReplacesInsteadOfStacking() {
        val clip = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(0).copy(bpm = 90f, file = "samples/b.wav"))
        val lane = clip.audio!!.lane(0)!!
        assertTrue(lane.takes.isEmpty())
        assertEquals("samples/b.wav", lane.file)
    }

    @Test
    fun pickingATakeHearsItWhole() {
        val lane = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(2 * barFrames)).audio!!.lane(0)!!
        val first = lane.hearing(0)
        assertEquals(listOf(Piece(0, 2 * barFrames, 0)), first.pieces)
        assertEquals(0, first.heardTake())
    }

    @Test
    fun trimmingTheHeadKeepsTakesOnTheirAudio() {
        val lane = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(2 * barFrames)).audio!!.lane(0)!!
        val d = 1000
        val trimmed = lane.copy(offset = lane.offset + d, frames = lane.frames - d).reframed(lane)
        assertEquals(listOf(d, 2 * barFrames + d), trimmed.takes.map { it.offset })
        assertEquals(trimmed.offset, trimmed.takes[0].offset)
        assertEquals(listOf(Piece(0, 2 * barFrames - d, 1)), trimmed.pieces)
    }

    @Test
    fun theSpecCarriesTakesAndPieces() {
        val root = kotlin.io.path.createTempDirectory("stack").toFile()
        try {
            java.io.File(root, "samples").mkdirs()
            java.io.File(root, "samples/take.wav").writeBytes(ByteArray(8))
            val scene = Scene(id = "s", name = "s")
            val clip = Clip(bars = 2).withRecorded(1, pass(0)).withRecorded(1, pass(2 * barFrames))
            val track = Track(id = "t", name = "t", machine = Machine(BIAS_MACHINE), clips = mapOf("s" to clip))
            val song = Song(name = "x", tempo = 120f, scenes = listOf(scene), tracks = listOf(track))
            val lines = reelSpec(song, track, com.rm.acidulous.io.File(root.path)).trim().lines()
            assertEquals(3, lines.size)
            val path = java.io.File(root, "samples/take.wav").absolutePath
            assertEquals("take|${scene.engineId}|1|$path|${2 * barFrames}", lines[1])
            assertEquals("piece|${scene.engineId}|1|0|${2 * barFrames}|1", lines[2])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anOldLaneLoadsAsAStackOfOne() {
        val json = """{"file":"samples/a.wav","offset":0,"frames":10,"bpm":120.0,"ticks":3840}"""
        val take = Json { ignoreUnknownKeys = true }.decodeFromString(TakeRef.serializer(), json)
        assertEquals(1, take.takeCount)
        assertEquals(listOf(StackTake("samples/a.wav", 0, 0, 10)), take.stack())
    }

    @Test
    fun aStackIsInUseAndRepointed() {
        val clip = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(0, file = "samples/b.wav"))
        val track = Track(id = "t", name = "t", machine = Machine(BIAS_MACHINE), clips = mapOf("s" to clip))
        val song = Song(name = "x", scenes = listOf(Scene(id = "s", name = "s")), tracks = listOf(track))
        assertTrue("samples/b.wav" in song.samplesInUse())
        val moved = SongBundle.repoint(song, mapOf("samples/b.wav" to "samples/b (2).wav"))
        assertEquals("samples/b (2).wav", moved.tracks[0].clips["s"]!!.audio!!.lane(0)!!.takes[1].file)
    }

    @Test
    fun undoTakesBackAPassAndATakePick() {
        val track = Track(id = "t", name = "t", machine = Machine(BIAS_MACHINE), clips = mapOf("s" to Clip(bars = 2).withRecorded(0, pass(0))))
        val editor = SongEditor(Song(name = "x", scenes = listOf(Scene(id = "s", name = "s")), tracks = listOf(track))) { _, _ -> }
        fun lane() = editor.song.tracks[0].clips["s"]!!.audio!!.lane(0)!!
        editor.editClip(0, "s") { it.withRecorded(0, pass(2 * barFrames)) }
        editor.editClip(0, "s") { it.withTake(0, lane().hearing(0)) }
        assertEquals(0, lane().heardTake())
        editor.undo(0)
        assertEquals(1, lane().heardTake())
        editor.undo(0)
        assertEquals(1, lane().takeCount)
    }
}
