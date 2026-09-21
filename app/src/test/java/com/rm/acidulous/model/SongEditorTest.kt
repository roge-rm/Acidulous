package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongEditorTest {

    private class Spy {
        val pushes = ArrayList<Boolean>()
        val editor = SongEditor(Fixtures.song()) { _, pushNow -> pushes += pushNow }
    }

    private val verse get() = "s-verse"

    @Test
    fun editIsUndoableAndRedoablePerTrack() {
        val s = Spy()
        val before = s.editor.song.tracks[0]
        s.editor.editClip(0, verse) { it.copy(notes = it.notes + Note(0, 10, 60, 100)) }
        assertEquals(17, s.editor.song.tracks[0].clips[verse]!!.notes.size)
        assertTrue(s.editor.canUndo(0))
        assertFalse(s.editor.canRedo(0))

        s.editor.undo(0)
        assertSame(before, s.editor.song.tracks[0])
        assertTrue(s.editor.canRedo(0))

        s.editor.redo(0)
        assertEquals(17, s.editor.song.tracks[0].clips[verse]!!.notes.size)
    }

    @Test
    fun gestureCoalescesIntoOneUndoStepAndPushesOnlyAtTheEnd() {
        val s = Spy()
        val before = s.editor.song.tracks[0]
        s.editor.beginGesture(0)
        s.editor.updateGestureClip(verse) { c -> c.copy(notes = c.notes.map { it.copy(tick = it.tick + 60) }) }
        s.editor.updateGestureClip(verse) { c -> c.copy(notes = c.notes.map { it.copy(tick = it.tick + 120) }) }
        s.editor.endGesture()

        // absolute, not cumulative: the second update replaced the first
        assertEquals(120, s.editor.song.tracks[0].clips[verse]!!.notes[0].tick)
        assertEquals(listOf(false, false, true), s.pushes)

        s.editor.undo(0)
        assertSame(before, s.editor.song.tracks[0])
        assertFalse(s.editor.canUndo(0))
    }

    @Test
    fun cancelledGestureRestoresTheBaseWithoutAnUndoStep() {
        val s = Spy()
        val before = s.editor.song.tracks[0]
        s.editor.beginGesture(0)
        s.editor.updateGestureClip(verse) { it.copy(bars = 4) }
        s.editor.cancelGesture()
        assertSame(before, s.editor.song.tracks[0])
        assertFalse(s.editor.canUndo(0))
    }

    @Test
    fun noOpEditWritesNoHistory() {
        val s = Spy()
        s.editor.edit(0) { it }
        assertFalse(s.editor.canUndo(0))
        assertTrue(s.pushes.isEmpty())
    }

    @Test
    fun undoStacksAreIndependentPerTrack() {
        val two = Fixtures.song().let { d -> d.copy(tracks = d.tracks + d.tracks[0].copy(id = "t-two", name = "Two")) }
        val pushes = ArrayList<Boolean>()
        val editor = SongEditor(two) { _, p -> pushes += p }
        editor.editClip(0, verse) { it.copy(mute = true) }
        editor.editClip(1, verse) { it.copy(mute = true) }
        editor.undo(0)
        assertFalse(editor.song.tracks[0].clips[verse]!!.mute)
        assertTrue(editor.song.tracks[1].clips[verse]!!.mute)
    }
}
