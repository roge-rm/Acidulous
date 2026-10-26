package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NoteClipboardTest {
    private val bend = Lane(listOf(LanePoint(0, 0.5f), LanePoint(10, 0.8f)))
    private val clip = Clip(
        bars = 1, grid = 24,
        notes = listOf(
            Note(0, 24, 60, 100),
            Note(48, 48, 64, 90, bend = bend),
            Note(96, 24, 67, 80),
        ),
    )
    private val len = 384

    @Test fun deleteDropsEveryChosenNote() {
        val c = clip.withoutNotes(setOf(0, 2))
        assertEquals(listOf(64), c.notes.map { it.pitch })
        assertSame(clip, clip.withoutNotes(emptySet()))
    }

    @Test fun pasteKeepsTimingAndSelectsTheNewNotes() {
        val (c, added) = clip.withPasted(clip.notes.subList(0, 2), 0, 192, len)
        assertEquals(setOf(3, 4), added)
        assertEquals(listOf(192, 240), c.notes.drop(3).map { it.tick })
        assertEquals(bend, c.notes[4].bend)
        assertEquals(48, c.notes[4].length)
    }

    @Test fun pasteAtTheOriginIsTheSamePlace() {
        val (c, _) = clip.withPasted(clip.notes.subList(1, 3), 48, 48, len)
        assertEquals(listOf(48, 96), c.notes.drop(3).map { it.tick })
    }

    @Test fun pasteIsClippedToTheClip() {
        val (c, added) = clip.withPasted(clip.notes, 0, 330, len)
        // 330 and 378 fit, 426 is past the end of the clip.
        assertEquals(setOf(3, 4), added)
        assertEquals(len - 378, c.notes[4].length)
        assertEquals(24, c.notes[3].length)
        val (none, nothing) = clip.withPasted(clip.notes, 0, len, len)
        assertSame(clip, none)
        assertEquals(emptySet<Int>(), nothing)
    }

    @Test fun duplicateGoesJustAfterTheSelectionOnTheGrid() {
        // Span 0..120 sits on the grid of 24, so the copy starts at 120.
        val (c, added) = clip.withDuplicated(setOf(0, 1, 2), len)
        assertEquals(setOf(3, 4, 5), added)
        assertEquals(listOf(120, 168, 216), c.notes.drop(3).map { it.tick })
        // A span of 30 rounds up to 48.
        val odd = Clip(notes = listOf(Note(10, 30, 60, 100)), grid = 24)
        assertEquals(58, odd.withDuplicated(setOf(0), len).first.notes[1].tick)
    }

    @Test fun clipboardKeepsWhatWasCopied() {
        NoteClipboard.copy(clip, setOf(1, 2))
        assertEquals(48, NoteClipboard.origin)
        assertEquals(2, NoteClipboard.notes.size)
        NoteClipboard.copy(clip, emptySet())
        assertEquals(2, NoteClipboard.notes.size)
        NoteClipboard.clear()
    }
}
