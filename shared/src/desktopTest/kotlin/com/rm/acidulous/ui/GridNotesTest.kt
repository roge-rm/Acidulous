package com.rm.acidulous.ui

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class GridNotesTest {
    private val aMinor = setOf(9, 11, 0, 2, 4, 5, 7)

    @Test fun `scale grid starts on the root and rows go up a fourth`() {
        val g = GridNotes(aMinor, 9, 3)
        assertEquals(57, g.noteAt(0, 0)) // A3
        assertEquals(59, g.noteAt(0, 1)) // B3
        assertEquals(62, g.noteAt(1, 0)) // D4, a fourth up
        assertEquals(67, g.noteAt(2, 0)) // G4
        assertEquals(69, g.noteAt(0, 7)) // A4, an octave along the row
    }

    @Test fun `scale grid has only scale notes and lights the root`() {
        val g = GridNotes(aMinor, 9, 3)
        for (row in 0 until 5) for (col in 0 until 8) {
            val n = g.noteAt(row, col)!!
            assertTrue("pad $row,$col plays $n", n % 12 in aMinor)
        }
        assertTrue(g.isRoot(57) && g.isRoot(69) && !g.isRoot(59))
    }

    @Test fun `chromatic grid is five semitones a row from C`() {
        val g = GridNotes(null, null, 3)
        assertEquals(48, g.noteAt(0, 0))
        assertEquals(49, g.noteAt(0, 1))
        assertEquals(53, g.noteAt(1, 0))
        assertEquals(58, g.noteAt(2, 0))
        assertTrue(g.isRoot(48) && g.chromatic)
    }

    @Test fun `a pentatonic row starts on the nearest note to a fourth`() {
        // Minor pentatonic has the fourth: two notes a row.
        assertEquals(5, GridNotes(setOf(0, 3, 5, 7, 10), 0, 3).let { it.noteAt(1, 0)!! - it.noteAt(0, 0)!! })
        // Major pentatonic doesn't: the third is nearer than the fifth.
        assertEquals(4, GridNotes(setOf(0, 2, 4, 7, 9), 0, 3).let { it.noteAt(1, 0)!! - it.noteAt(0, 0)!! })
    }

    @Test fun `all notes with a scale is chromatic from the root and dims the rest`() {
        val g = GridNotes(aMinor, 9, 3, allNotes = true)
        assertEquals(57, g.noteAt(0, 0)) // A3 at the bottom left
        assertEquals(58, g.noteAt(0, 1))
        assertEquals(62, g.noteAt(1, 0)) // five semitones up
        assertEquals(67, g.noteAt(2, 0))
        assertTrue(g.dimsOutOfKey && g.isRoot(57) && g.isRoot(69))
        assertTrue(g.inKey(59) && g.inKey(60))
        assertTrue(!g.inKey(58) && !g.inKey(61))
    }

    @Test fun `all notes without a scale changes nothing`() {
        val g = GridNotes(null, null, 3, allNotes = true)
        assertEquals(48, g.noteAt(0, 0))
        assertEquals(53, g.noteAt(1, 0))
        assertTrue(!g.dimsOutOfKey && g.inKey(49))
    }

    @Test fun `notes past MIDI's range are empty pads`() {
        assertNull(GridNotes(null, null, 9).noteAt(9, 15))
    }

    private val cMajor = setOf(0, 2, 4, 5, 7, 9, 11)

    @Test fun `isomorphic is a semitone a column, blank out of key`() {
        val g = GridNotes(cMajor, 0, 3, layout = GridLayout.Isomorphic)
        assertEquals(48, g.noteAt(0, 0))
        assertEquals(null, g.noteAt(0, 1))
        assertEquals(50, g.noteAt(0, 2))
        assertEquals(53, g.noteAt(1, 0))
        assertTrue(g.dimsOutOfKey && g.isRoot(48) && g.inKey(50) && !g.inKey(49))
        assertEquals(7, GridNotes(cMajor, 0, 3, layout = GridLayout.Isomorphic, rowSemis = 7).let { it.noteAt(1, 0)!! - it.noteAt(0, 0)!! })
    }

    @Test fun `layouts are plain without a scale or with all notes`() {
        val g = GridNotes(null, null, 3, layout = GridLayout.PianoRows)
        assertEquals(49, g.noteAt(0, 1))
        assertEquals(53, g.noteAt(1, 0))
        val all = GridNotes(cMajor, 0, 3, allNotes = true, layout = GridLayout.Isomorphic, rowSemis = 3)
        assertEquals(51, all.noteAt(1, 0))
        assertEquals(49, all.noteAt(0, 1))
    }

    @Test fun `scale layout takes a set row step and auto is a fourth`() {
        val auto = GridNotes(cMajor, 0, 3, layout = GridLayout.Scale)
        assertEquals(53, auto.noteAt(1, 0))
        val two = GridNotes(cMajor, 0, 3, layout = GridLayout.Scale, rowSteps = 2)
        assertEquals(52, two.noteAt(1, 0)) // E3
        assertEquals(55, two.noteAt(2, 0)) // G3
        assertEquals(60, GridNotes(cMajor, 0, 3, layout = GridLayout.Scale, rowSteps = 20).noteAt(1, 0)) // clamped to 7
    }

    @Test fun `piano rows put the scale below and the notes between above`() {
        val g = GridNotes(cMajor, 0, 3, layout = GridLayout.PianoRows)
        for (col in 0 until 7) {
            assertTrue(g.inKey(g.noteAt(0, col)!!))
            val up = g.noteAt(1, col)
            assertTrue(up == null || !g.inKey(up))
        }
        assertEquals(listOf(48, 50, 52, 53, 55, 57, 59), (0 until 7).map { g.noteAt(0, it) })
        assertEquals(setOf(49, 51, 54, 56, 58), (0 until 7).mapNotNull { g.noteAt(1, it) }.toSet())
        assertEquals(60, g.noteAt(2, 0)) // the next pair is an octave up
        val c1 = (0 until 7).first { g.noteAt(1, it) == 49 }
        assertEquals(61, g.noteAt(3, c1))
    }
}
