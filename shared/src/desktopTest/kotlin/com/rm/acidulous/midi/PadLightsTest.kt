package com.rm.acidulous.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The song's scale on an Exquis's pads: which notes, and what to send. */
class PadLightsTest {
    private val major = listOf(0, 2, 4, 5, 7, 9, 11)

    @Test
    fun `a scale is every octave of its notes`() {
        val a = PadLights.notes(9, major) // A major
        assertTrue(69 in a && 57 in a && 71 in a && 73 in a)
        assertFalse(70 in a)
        assertTrue(a.size in 74..75)
        assertTrue(a.all { it in 0..127 })
    }

    @Test
    fun `no key lights nothing`() {
        assertTrue(PadLights.notes(null, major).isEmpty())
        assertTrue(PadLights.notes(0, null).isEmpty())
    }

    @Test
    fun `a change sends only the difference, offs first, the root brighter`() {
        val c = PadLights.notes(0, major)
        val g = PadLights.notes(7, major) // one sharp more: F becomes F#
        val msgs = PadLights.changes(c, g, 7)
        assertTrue(msgs.all { it.first == 0x80 && it.second % 12 == 5 || it.first == 0x90 && it.second % 12 == 6 })
        val firstOn = msgs.indexOfFirst { it.first == 0x90 }
        assertTrue(msgs.take(firstOn).all { it.first == 0x80 })
        val fresh = PadLights.changes(emptySet(), g, 7)
        assertEquals(PadLights.ROOT_VELOCITY, fresh.first { it.second == 67 }.third)
        assertEquals(PadLights.NOTE_VELOCITY, fresh.first { it.second == 69 }.third)
    }

    @Test
    fun `turning it off clears what is lit`() {
        val c = PadLights.notes(0, major)
        val off = PadLights.changes(c, emptySet(), null)
        assertEquals(c.size, off.size)
        assertTrue(off.all { it.first == 0x80 })
    }

    @Test
    fun `an Exquis is known by its name or its maker`() {
        assertTrue(PadLights.isExquis("Exquis", null, null))
        assertTrue(PadLights.isExquis(null, "EXQUIS MIDI", "Dualo"))
        assertTrue(PadLights.isExquis("MIDI device", null, "Intuitive Instruments"))
        assertFalse(PadLights.isExquis("nanoKEY Studio", null, "KORG INC."))
    }

    @Test
    fun `the Exquis's own scale, same root first`() {
        assertEquals(0 to 0, PadLights.exquisScale(0, setOf(0, 2, 4, 5, 7, 9, 11))) // C major
        assertEquals(9 to 1, PadLights.exquisScale(9, setOf(9, 11, 0, 2, 4, 5, 7))) // A minor, not C major
        assertEquals(2 to 4, PadLights.exquisScale(2, setOf(2, 4, 5, 7, 9, 11, 0))) // D dorian
    }

    @Test
    fun `the same notes from another root, then the smallest that holds them, then chromatic`() {
        // C Egyptian is C D F G B-flat: G minor pentatonic, the first root that has it.
        assertEquals(7 to 11, PadLights.exquisScale(0, setOf(0, 2, 5, 7, 10)))
        // C major blues (C D E-flat E G A) is in none of them but chromatic.
        assertEquals(0 to PadLights.EXQUIS_CHROMATIC, PadLights.exquisScale(0, setOf(0, 2, 3, 4, 7, 9)))
        // C In Sen (C D-flat F G B-flat) sits inside C phrygian, the smallest that holds it.
        assertEquals(0 to 5, PadLights.exquisScale(0, setOf(0, 1, 5, 7, 10)))
    }

    @Test
    fun `setting it is developer mode in and out around the tonic and scale`() {
        val m = PadLights.exquisScaleMessages(9, 1).map { b -> b.map { it.toInt() and 0xff } }
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x00, 0x04, 0xF7), m[0])
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x06, 9, 0xF7), m[1])
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x07, 1, 0xF7), m[2])
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x00, 0x00, 0xF7), m[3])
    }

    @Test
    fun `holding the buttons, the scale is set without leaving developer mode`() {
        val m = PadLights.exquisScaleMessages(2, 4, inDeveloperMode = true).map { b -> b.map { it.toInt() and 0xff } }
        assertEquals(2, m.size)
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x06, 2, 0xF7), m[0])
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x07, 4, 0xF7), m[1])
    }

    @Test
    fun `the buttons zone, a button's light, and a press`() {
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x00, 0x20, 0xF7), PadLights.exquisSetup(PadLights.ZONE_BUTTONS).map { it.toInt() and 0xff })
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x04, 105, 0, 127, 0, 0, 0xF7), PadLights.exquisLed(105, 0, 200, -3).map { it.toInt() and 0xff })
        assertEquals(PadLights.BUTTON_PLAY, PadLights.exquisButton(0xBF, 105, 127))
        assertEquals(null, PadLights.exquisButton(0xBF, 105, 0)) // the release
        assertEquals(null, PadLights.exquisButton(0xBF, 110, 65)) // an encoder, not taken
        assertEquals(null, PadLights.exquisButton(0xB0, 105, 127)) // channel 1: somebody's CC
    }
}
