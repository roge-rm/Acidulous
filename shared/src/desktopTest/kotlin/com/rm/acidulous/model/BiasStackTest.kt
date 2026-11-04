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
        assertEquals(listOf(Piece(0, 2 * barFrames - d, 1)), trimmed.heardPieces())
    }

    /** A trim only hides: dragging an end back out brings back takes, pieces and nudges as they were. */
    @Test
    fun trimmingInThenOutRestoresTheLane() {
        val clip = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(2 * barFrames, frames = barFrames))
        val held = clip.audio!!.lane(0)!!
        val lane = held.copy(takes = held.takes.mapIndexed { k, t -> if (k == 1) t.copy(nudges = listOf(Nudge(1000, 9000, 120))) else t })
        val d = barFrames + 4000

        // The head, past the join and the nudge.
        val headIn = lane.copy(offset = lane.offset + d, frames = lane.frames - d).reframed(lane)
        assertEquals(listOf(Piece(0, barFrames - 4000, 0)), headIn.heardPieces())
        assertEquals(d, headIn.headRoom())
        val headOut = headIn.copy(offset = headIn.offset - d, frames = headIn.frames + d).reframed(headIn)
        assertEquals(lane, headOut)
        assertEquals(0, headOut.headRoom())

        // The tail, back past the join.
        val tailIn = lane.copy(frames = lane.frames - d).reframed(lane)
        assertEquals(listOf(Piece(0, barFrames - 4000, 1)), tailIn.heardPieces())
        assertEquals(lane.frames, tailIn.stackLength())
        val tailOut = tailIn.copy(frames = tailIn.frames + d).reframed(tailIn)
        assertEquals(lane, tailOut)
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

    /** A loop recorded three times is three undo steps, newest first, as the manual says. */
    @Test
    fun eachPassOfALoopRecordingIsItsOwnUndoStep() {
        val track = Track(id = "t", name = "t", machine = Machine(BIAS_MACHINE))
        val song = Song(name = "x", scenes = listOf(Scene(id = "s", name = "s")), tracks = listOf(track))
        val editor = SongEditor(song) { _, _ -> }
        fun lane() = editor.song.tracks[0].clips["s"]?.audio?.lane(0)
        val takes = (0 until 3).map { "s" to pass(offset = it * 2 * barFrames) }
        val steps = editor.song.recordedSteps(0, 0, takes)
        assertEquals(3, steps.size)
        for (step in steps) editor.edit(0) { step }
        assertEquals(3, lane()!!.takeCount)
        editor.undo(0)
        assertEquals(2, lane()!!.takeCount)
        assertEquals(1, lane()!!.heardTake())
        editor.undo(0)
        assertEquals(1, lane()!!.takeCount)
        editor.undo(0)
        assertEquals(null, lane())
    }

    /** One run through the song crosses three scenes in one pass: one undo step. */
    @Test
    fun oneRunThroughTheSongIsOnePass() {
        val takes = listOf("a" to pass(0), "b" to pass(1), "c" to pass(2), "a" to pass(3), "b" to pass(4))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("a", "b")), recordingPasses(takes).map { p -> p.map { it.first } })
    }

    /** The card names the file you hear, or none when the pieces mix files. */
    @Test
    fun theCardNamesTheHeardTakesFile() {
        val lane = Clip(bars = 2).withRecorded(0, pass(0, file = "samples/a.wav"))
            .withRecorded(0, pass(0, file = "samples/b.wav")).audio!!.lane(0)!!
        assertEquals("samples/b.wav", lane.heardFile())
        assertEquals("samples/a.wav", lane.hearing(0).heardFile())
        val mixed = lane.copy(pieces = lane.pieces.overlay(0, barFrames, 0))
        assertEquals(null, mixed.heardFile())
        // Passes of one recording share a file, so mixing them still names it.
        val loop = Clip(bars = 2).withRecorded(0, pass(0)).withRecorded(0, pass(2 * barFrames)).audio!!.lane(0)!!
        assertEquals("samples/take.wav", loop.copy(pieces = loop.pieces.overlay(0, barFrames, 0)).heardFile())
    }
}
