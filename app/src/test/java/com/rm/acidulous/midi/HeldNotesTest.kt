package com.rm.acidulous.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A note goes back to where it came from.
 *
 * Every case here is one that was wrong or could be: the target moving under a
 * held note, the same note number down on two MPE channels at once, and a
 * controller vanishing with a finger on it.
 */
class HeldNotesTest {

    @Test fun `an off follows its on, whatever is selected now`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 0, note = 60, rack = 3)
        // The user leaves the machine screen; the hub would now say rack 7.
        assertEquals(3, h.rackForOff(0, 60))
    }

    @Test fun `a note nobody is holding has no home`() {
        assertNull(HeldNotes().rackForOff(0, 60))
    }

    @Test fun `the same note on two fingers is two notes`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 2, note = 60, rack = 1)
        h.onNoteOn(port = 1, channel = 3, note = 60, rack = 4)
        assertEquals(1, h.rackForOff(2, 60))
        assertEquals(4, h.rackForOff(3, 60))
        h.onNoteOff(2, 60)
        assertNull(h.rackForOff(2, 60))
        assertEquals("the other finger is untouched", 4, h.rackForOff(3, 60))
    }

    @Test fun `expression follows the note it is shaping`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 5, note = 64, rack = 2)
        assertEquals(2, h.rackForExpression(5))
        h.onNoteOff(5, 64)
        assertNull("with nothing down the channel means nothing", h.rackForExpression(5))
    }

    @Test fun `a channel keeps its expression while any note on it is down`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 0, note = 60, rack = 6)
        h.onNoteOn(port = 1, channel = 0, note = 64, rack = 6)
        h.onNoteOff(0, 60)
        assertEquals(6, h.rackForExpression(0))
    }

    @Test fun `a vanished device releases its own notes and only those`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 0, note = 60, rack = 2)
        h.onNoteOn(port = 1, channel = 0, note = 64, rack = 2)
        h.onNoteOn(port = 9, channel = 1, note = 67, rack = 5)
        val freed = h.release(1).sortedBy { it.note }
        assertEquals(2, freed.size)
        assertEquals(HeldNotes.Held(2, 0, 60), freed[0])
        assertEquals(HeldNotes.Held(2, 0, 64), freed[1])
        assertNull(h.rackForOff(0, 60))
        assertEquals("the other controller still has its finger down", 5, h.rackForOff(1, 67))
        assertEquals(1, h.count)
    }

    @Test fun `releasing a port that held nothing frees nothing`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 0, note = 60, rack = 2)
        assertEquals(0, h.release(4).size)
        assertEquals(1, h.count)
    }

    @Test fun `panic forgets everything`() {
        val h = HeldNotes()
        h.onNoteOn(port = 1, channel = 0, note = 60, rack = 2)
        h.onNoteOn(port = 2, channel = 1, note = 64, rack = 3)
        h.clear()
        assertEquals(0, h.count)
        assertNull(h.rackForOff(0, 60))
        assertNull(h.rackForExpression(1))
    }
}
