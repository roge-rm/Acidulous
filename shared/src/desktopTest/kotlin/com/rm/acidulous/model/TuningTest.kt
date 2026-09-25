package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

class TuningTest {

    private fun cents(ratio: Float) = 1200.0 * log2(ratio.toDouble())

    @Test
    fun equalTemperamentMovesNothing() {
        assertTrue(Tunings.EQUAL.isEqual)
        Tunings.ratios(Tunings.EQUAL, 0).forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test
    fun theRootKeepsItsPitchAndTheRestAreCountedFromIt() {
        val just = Tunings.builtIn.first { it.name == "just" }
        val inA = Tunings.ratios(just, 9) // A
        assertEquals(1f, inA[69], 1e-6f) // A4 stays 440
        assertEquals(1f, inA[57], 1e-6f) // and the octave below
        assertEquals(1.96, cents(inA[69 + 7]), 0.01) // a just fifth is 1.96 cents wide
        assertEquals(-13.69, cents(inA[69 + 4]), 0.01) // a just third, 13.69 narrow
        assertEquals(-13.69, cents(inA[69 + 4 - 12]), 0.01) // in every octave
    }

    @Test
    fun nineteenStepsPutTheOctaveNineteenKeysUp() {
        val t = Tunings.builtIn.first { it.name == "19 equal" }
        val r = Tunings.ratios(t, 0)
        // Key 60 is C and stays; key 79 is the octave, which in equal
        // temperament would be G - seven hundred cents below where it is now.
        assertEquals(1f, r[60], 1e-6f)
        assertEquals(1200.0 - 1900.0, cents(r[79]), 0.01)
        assertEquals(1200.0 / 19 - 100.0, cents(r[61]), 0.01)
        assertTrue(r.all { it.isFinite() && it > 0f })
    }

    @Test
    fun aScalaFileReads() {
        val scl = """
            ! meantone.scl
            !
            Quarter-comma meantone, a few notes
             4
            !
             193.157
             5/4
             696.578 fifth
             2
        """.trimIndent()
        val t = Tunings.parseScl(scl, "fallback")
        assertEquals("Quarter-comma meantone, a few notes", t.name)
        assertEquals(4, t.cents.size)
        assertEquals(193.157f, t.cents[0], 0.001f)
        assertEquals(386.3137f, t.cents[1], 0.001f)
        assertEquals(696.578f, t.cents[2], 0.001f)
        assertEquals(1200f, t.cents[3], 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFileThatSaysMoreNotesThanItHasIsRefused() {
        Tunings.parseScl("short\n5\n100.0\n200.0\n", "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun somethingElseIsRefused() {
        Tunings.parseScl("MThd this is a MIDI file", "x")
    }

    @Test
    fun aTuningSurvivesBeingSavedAndOpened() {
        val just = Tunings.builtIn.first { it.name == "just" }
        val song = Song(
            name = "tuned", tuning = just,
            tracks = listOf(Track(id = "t", name = "t", machine = Machine("Trinity"), tuning = Tunings.EQUAL)),
            scenes = listOf(Scene(id = "s", name = "s")),
        )
        assertEquals(song, SongStore.decode(SongStore.encode(song)))
    }
}
