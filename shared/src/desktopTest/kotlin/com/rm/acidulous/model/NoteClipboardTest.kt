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
        // Same place, so the copies replace the originals instead of stacking.
        val (c, added) = clip.withPasted(clip.notes.subList(1, 3), 48, 48, len)
        assertEquals(3, c.notes.size)
        assertEquals(setOf(1, 2), added)
        assertEquals(listOf(48, 96), c.notes.drop(1).map { it.tick })
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

    @Test fun pasteAnchorsInPitchToo() {
        // The first note (60) lands on 72 and the others keep their spacing: 64 -> 76.
        val (c, _) = clip.withPasted(clip.notes.subList(0, 2), 0, 192, len, originPitch = 60, atPitch = 72)
        assertEquals(listOf(72, 76), c.notes.drop(3).map { it.pitch })
        assertEquals(listOf(192, 240), c.notes.drop(3).map { it.tick })
    }

    @Test fun pasteDropsNotesOffThePitchRange() {
        val (c, added) = clip.withPasted(clip.notes, 0, 0, len, originPitch = 60, atPitch = 125)
        // 60 -> 125 fits, 64 -> 129 and 67 -> 132 don't.
        assertEquals(setOf(3), added)
        assertEquals(125, c.notes[3].pitch)
    }

    @Test fun pasteReplacesNotesOfTheSamePitchItOverlaps() {
        // Lands on the 64 note at 48 (length 48): that one goes, the 60 and 67 stay.
        val one = listOf(Note(0, 24, 99, 100))
        val (c, added) = clip.withPasted(one, 0, 60, len, originPitch = 99, atPitch = 64)
        assertEquals(listOf(60, 67, 64), c.notes.map { it.pitch })
        assertEquals(setOf(2), added)
        // A different pitch at the same time stays.
        val (d, _) = clip.withPasted(one, 0, 60, len, originPitch = 99, atPitch = 65)
        assertEquals(4, d.notes.size)
    }

    @Test fun clipboardRemembersTheFirstNotesPitch() {
        NoteClipboard.copy(clip, setOf(1, 2))
        assertEquals(64, NoteClipboard.originPitch)
        NoteClipboard.clear()
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
