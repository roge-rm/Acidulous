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
}
