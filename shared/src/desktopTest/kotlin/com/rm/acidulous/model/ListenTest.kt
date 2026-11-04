package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listening through a phrase: which take plays on which pass, and keeping one. */
class ListenTest {

    private val frames = 8 * ENGINE_RATE

    /** Three takes; take 2 only has audio from 3 s on. Take 1 is heard up to 3 s, take 2 after. */
    private fun lane() = TakeRef(
        file = "samples/a.wav", offset = 0, frames = frames, bpm = 120f, ticks = 16 * PPQN,
        takes = listOf(
            StackTake("samples/a.wav", 0, 0, frames),
            StackTake("samples/b.wav", 0, 0, frames),
            StackTake("samples/c.wav", -3 * ENGINE_RATE, 3 * ENGINE_RATE, frames),
        ),
        pieces = listOf(Piece(0, 3 * ENGINE_RATE, 1), Piece(3 * ENGINE_RATE, frames, 2)),
    )

    @Test
    fun eachPassPlaysTheNextTakeAndGoesRound() {
        val plan = lane().listenPlan(0, 4 * ENGINE_RATE, 5 * ENGINE_RATE)!!
        assertEquals(listOf(0, 1, 2), plan.takes)
        assertEquals(3 * ENGINE_RATE, plan.from)
        val pass = plan.length.toLong()
        assertEquals(2 * ENGINE_RATE, plan.length)
        assertEquals(-1, plan.takeAt(-1))
        assertEquals(0, plan.takeAt(0))
        assertEquals(0, plan.takeAt(pass - 1))
        assertEquals(1, plan.takeAt(pass))
        assertEquals(2, plan.takeAt(2 * pass + 10))
        assertEquals(0, plan.takeAt(3 * pass))
        assertEquals(plan.from + 10, plan.frameAt(pass + 10))
    }

    @Test
    fun onlyTakesWithAudioInThePhraseAreHeard() {
        val plan = lane().listenPlan(1, ENGINE_RATE, 2 * ENGINE_RATE)!!
        assertEquals(listOf(0, 1), plan.takes)
        assertEquals("the run-up stops at the lane's start", 0, plan.from)
        assertEquals(1, plan.lane)
        val one = lane().copy(takes = lane().takes.take(1) + lane().takes[2], pieces = listOf(Piece(0, frames, 0)))
        assertNull("one take there is nothing to choose between", one.listenPlan(0, ENGINE_RATE, 2 * ENGINE_RATE))
    }

    @Test
    fun theSpecIsTheLaneWithEachTakeKept() {
        val root = kotlin.io.path.createTempDirectory("listen").toFile()
        try {
            java.io.File(root, "samples").mkdirs()
            for (f in listOf("a", "b", "c")) java.io.File(root, "samples/$f.wav").writeBytes(ByteArray(8))
            val lane = lane()
            val plan = lane.listenPlan(2, 4 * ENGINE_RATE, 5 * ENGINE_RATE)!!
            val lines = lane.listenSpec(plan, com.rm.acidulous.io.File(root.path)).trim().lines()
            assertEquals("listen|2|${3 * ENGINE_RATE}|${5 * ENGINE_RATE}", lines[0])
            // Each pass is a cell of its own, lane 0, with that take kept.
            for ((i, k) in plan.takes.withIndex()) {
                val cell = i + 1
                val pieces = lines.filter { it.startsWith("piece|$cell|") }.map {
                    val f = it.split('|')
                    Piece(f[3].toInt(), f[4].toInt(), f[5].toInt())
                }
                assertEquals("pass $cell", lane.keeping(plan, k).pieces, pieces)
                assertTrue(lines.any { it.startsWith("$cell|0|") })
            }
            assertTrue(Piece(4 * ENGINE_RATE, 5 * ENGINE_RATE, 0) in lane.keeping(plan, 0).pieces)
            java.io.File(root, "samples/a.wav").delete()
            assertEquals("no file, nothing to listen to", "", lane.listenSpec(plan, com.rm.acidulous.io.File(root.path)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepingIsOneUndoStepAndListeningAloneChangesNothing() {
        val scene = Scene(id = "s", name = "s")
        val track = Track(id = "t", name = "t", machine = Machine(BIAS_MACHINE), clips = mapOf("s" to Clip(bars = 4, audio = ClipAudio(listOf(lane())))))
        val song = Song(name = "x", scenes = listOf(scene), tracks = listOf(track))
        val editor = SongEditor(song) { _, _ -> }
        fun now() = editor.song.tracks[0].clips["s"]!!.audio!!.lane(0)!!
        val plan = now().listenPlan(0, 4 * ENGINE_RATE, 5 * ENGINE_RATE)!!
        // Listening writes nothing: the plan and the spec are built from the lane.
        assertEquals(song, editor.song)
        editor.editClip(0, "s") { it.withTake(0, it.audio!!.lane(0)!!.keeping(plan, 1)) }
        assertEquals(1, now().heardIn(plan.start, plan.end))
        assertEquals("and nowhere else", 2, now().heardIn(5 * ENGINE_RATE, frames))
        editor.undo(0)
        assertEquals(lane(), now())
    }

    @Test
    fun theTakeHeardInAPhrase() {
        assertEquals(1, lane().heardIn(0, ENGINE_RATE))
        assertEquals(2, lane().heardIn(4 * ENGINE_RATE, 5 * ENGINE_RATE))
        assertEquals("the most of it", 2, lane().heardIn(2 * ENGINE_RATE, 6 * ENGINE_RATE))
    }
}
