package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A loop dropped into a Bias cell: the take is the loop's bars, so it repeats
 * at the tempo its bars give and its first hit lands on the bar.
 */
class LoopTakeTest {

    private val song = Song(name = "t", tempo = 120f, scenes = listOf(Scene(id = "s", name = "s")))
    private val clip = Clip(bars = 4)

    @Test
    fun aTrimmedLoopTakesJustItsBars() {
        // Two bars at 92 bpm, starting 150 ms in, with a second of ringing after.
        val seconds = 2 * 4 * 60f / 92f
        val shape = LoopShape(bars = 2f, seconds = seconds, start = 0.15f)
        val file = Math.round((0.15f + seconds + 1f) * ENGINE_RATE)
        val take = song.takeForFile("s", clip, "samples/loop.wav", file, shape)

        assertTrue(take.loop)
        assertEquals(Math.round(0.15f * ENGINE_RATE), take.offset)
        assertEquals(Math.round(seconds * ENGINE_RATE).toFloat(), take.frames.toFloat(), 1f)
        assertEquals(92f, take.bpm, 0.01f)
    }

    @Test
    fun theLoopNeverRunsPastTheFile() {
        val shape = LoopShape(bars = 1f, seconds = 3f, start = 0.5f)
        val take = song.takeForFile("s", clip, "samples/loop.wav", 2 * ENGINE_RATE, shape)
        assertEquals(ENGINE_RATE / 2, take.offset)
        assertEquals(2 * ENGINE_RATE - ENGINE_RATE / 2, take.frames)
    }

    @Test
    fun aFileThatIsNotALoopIsTheWholeFile() {
        val take = song.takeForFile("s", clip, "samples/hit.wav", 12345, LoopShape(bars = 0f, seconds = 1f, start = 0.2f))
        assertFalse(take.loop)
        assertEquals(0, take.offset)
        assertEquals(12345, take.frames)
        assertEquals(120f, take.bpm, 0f)
    }
}
