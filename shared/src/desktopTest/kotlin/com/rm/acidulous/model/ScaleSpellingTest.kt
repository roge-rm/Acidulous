package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that scales are spelled the usual way, e.g. C Dorian has E♭ and B♭,
 * not D♯ and A♯. Every case here has a settled spelling, so a failure means
 * the code picked the wrong enharmonic name.
 */
class ScaleSpellingTest {

    private fun notes(key: Int, scale: Int): String {
        val spelling = Scales.spelling(key, scale)
        val iv = Scales.intervals[scale]
        return iv.joinToString(" ") { spelling[(key + it) % 12] ?: "?" }
    }

    private fun scaleNamed(name: String): Int {
        val i = Scales.names.indexOf(name)
        assertTrue("no scale called $name", i >= 0)
        return i
    }

    private val ionian get() = scaleNamed("Ionian (Major)")
    private val dorian get() = scaleNamed("Dorian")
    private val aeolian get() = scaleNamed("Aeolian (Minor)")

    @Test
    fun `the white notes stay white`() {
        assertEquals("C D E F G A B", notes(0, ionian))
        assertEquals("A B C D E F G", notes(9, aeolian))
    }

    @Test
    fun `one sharp and one flat`() {
        assertEquals("G A B C D E F♯", notes(7, ionian))
        assertEquals("F G A B♭ C D E", notes(5, ionian))
    }

    @Test
    fun `the flat keys are flat, not their sharp twins`() {
        // B♭ major. A♯ major would need 5 sharps and a double sharp.
        assertEquals("B♭ C D E♭ F G A", notes(10, ionian))
        assertEquals("E♭ F G A♭ B♭ C D", notes(3, ionian))
        assertEquals("A♭ B♭ C D♭ E♭ F G", notes(8, ionian))
        // D♭ major (5 flats) rather than C♯ major (7 sharps).
        assertEquals("D♭ E♭ F G♭ A♭ B♭ C", notes(1, ionian))
    }

    @Test
    fun `the case that started this`() {
        // C Dorian.
        assertEquals("C D E♭ F G A B♭", notes(0, dorian))
    }

    @Test
    fun `every letter is used exactly once in a seven note scale`() {
        for (scale in Scales.intervals.indices) {
            if (Scales.intervals[scale].size != 7) continue
            for (key in 0 until 12) {
                val letters = notes(key, scale).split(" ").map { it.first() }
                assertEquals(
                    "scale ${Scales.names[scale]} in key $key repeats or skips a letter: ${notes(key, scale)}",
                    7, letters.toSet().size,
                )
            }
        }
    }

    @Test
    fun `no note is left unspelled, whatever the scale`() {
        for (scale in Scales.intervals.indices) {
            for (key in 0 until 12) {
                assertTrue(
                    "${Scales.names[scale]} in key $key has an unnamed note",
                    '?' !in notes(key, scale),
                )
            }
        }
    }

    @Test
    fun `a scale with an unconventional shape still gets single accidentals`() {
        // Pentatonic and eight-note scales don't have one letter per degree,
        // so each note gets a single accidental, never a double.
        for (scale in Scales.intervals.indices) {
            if (Scales.intervals[scale].size == 7) continue
            for (key in 0 until 12) {
                for (note in notes(key, scale).split(" ")) {
                    assertTrue(
                        "${Scales.names[scale]} in key $key produced $note",
                        note.length <= 2,
                    )
                }
            }
        }
    }

    @Test
    fun `the root is named as its own scale names it`() {
        assertEquals("D♭", Scales.rootName(1, ionian))
        assertEquals("C", Scales.rootName(0, dorian))
        assertEquals("E♭", Scales.rootName(3, ionian))
    }

    @Test
    fun `a note keeps its octave, and one outside the scale keeps a plain name`() {
        // MIDI 60 is C4 here, as the rest of the app has it, so 63 is E♭4.
        assertEquals("E♭4", Scales.noteName(63, 0, dorian)) // in C Dorian
        assertEquals("C4", Scales.noteName(60, 0, dorian))
        // F♯ isn't in C Dorian, so it keeps its plain sharp name.
        assertEquals("F♯4", Scales.noteName(66, 0, dorian))
    }
}
