package com.rm.acidulous.ui

import org.junit.Test
import org.junit.Assert.assertEquals

class PianoRowsTest {
    @Test fun `rows sit at their offsets from the strip`() {
        assertEquals(3, pianoRowOctave(3, PianoOffsetDefaults, 0))
        assertEquals(4, pianoRowOctave(3, PianoOffsetDefaults, 1))
        assertEquals(6, pianoRowOctave(3, PianoOffsetDefaults, 3))
        assertEquals(1, pianoRowOctave(3, listOf(-2, 1, 2, 3), 0))
    }

    @Test fun `row octaves stay on the stepper's range`() {
        assertEquals(0, pianoRowOctave(1, listOf(-3, 0, 0, 0), 0))
        assertEquals(8, pianoRowOctave(7, PianoOffsetDefaults, 3))
    }

    @Test fun `moving a row moves only that row and clamps`() {
        assertEquals(listOf(0, 2, 2, 3), movePianoRow(PianoOffsetDefaults, 1, 1))
        assertEquals(listOf(-3, 1, 2, 3), movePianoRow(listOf(-3, 1, 2, 3), 0, -1))
        assertEquals(listOf(0, 1, 2, 4), movePianoRow(listOf(0, 1, 2, 4), 3, 1))
    }

    @Test fun `a row at the stepper's end doesn't store a move`() {
        // Strip at 6, row 3 at +3 is already octave 8 (the top).
        assertEquals(listOf(0, 1, 2, 3), movePianoRow(PianoOffsetDefaults, 3, 1, 6))
        assertEquals(listOf(0, 1, 2, 4), movePianoRow(PianoOffsetDefaults, 3, 1, 4))
        // Row 0 at 0 on a strip at 0 is the bottom.
        assertEquals(listOf(0, 1, 2, 3), movePianoRow(PianoOffsetDefaults, 0, -1, 0))
        assertEquals(listOf(-1, 1, 2, 3), movePianoRow(PianoOffsetDefaults, 0, -1, 2))
    }

    @Test fun `offsets save and read back, bad values fall back`() {
        assertEquals("0,1,2,3", encodePianoOffsets(PianoOffsetDefaults))
        assertEquals(listOf(-1, 0, 4, 3), decodePianoOffsets("-1,0,9,x"))
        assertEquals(PianoOffsetDefaults, decodePianoOffsets(null))
        assertEquals(listOf(2, 1, 2, 3), decodePianoOffsets("2"))
    }

    @Test fun `a set length is seven white keys an octave`() {
        assertEquals(7, pianoKeyCount(1000f, 23f, 40f, 52f, 1, null))
        assertEquals(21, pianoKeyCount(300f, 23f, 40f, 0f, 3, null))
        // A five-note scale over two octaves.
        assertEquals(10, pianoKeyCount(1000f, 23f, 40f, 52f, 2, 5))
    }

    @Test fun `auto fits keys to the width`() {
        // The second screen: keys about 52 wide as many as fit.
        assertEquals(19, pianoKeyCount(1000f, 23f, 40f, 52f, 0, null))
        // The phone: two octaves at 23, five where 40 fits.
        assertEquals(14, pianoKeyCount(400f, 23f, 40f, 0f, 0, null))
        assertEquals(35, pianoKeyCount(1400f, 23f, 40f, 0f, 0, null))
        assertEquals(17, pianoKeyCount(400f, 23f, 40f, 0f, 0, 7))
    }
}
