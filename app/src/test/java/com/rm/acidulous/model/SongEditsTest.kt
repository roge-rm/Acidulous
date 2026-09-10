package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongEditsTest {

    private val demo = DemoSong.build()

    @Test
    fun addSceneAppendsOrInsertsAfter() {
        assertEquals(listOf("Intro", "Verse", "Scene 3"), demo.addScene().scenes.map { it.name })
        assertEquals(listOf("Intro", "Scene 3", "Verse"), demo.addScene(afterIndex = 0).scenes.map { it.name })
        assertTrue(demo.addScene().scenes.map { it.id }.toSet().size == 3)
    }

    @Test
    fun duplicateSceneCopiesSettingsAndEveryClip() {
        val s = demo.duplicateScene(1)
        assertEquals(listOf("Intro", "Verse", "Verse copy"), s.scenes.map { it.name })
        val copy = s.scenes[2]
        assertNotEquals(demo.scenes[1].id, copy.id)
        assertEquals(demo.scenes[1].tempo, copy.tempo)
        val original = s.tracks[0].clips[demo.scenes[1].id]!!
        val copied = s.tracks[0].clips[copy.id]!!
        assertEquals(original, copied)               // same content
        assertNotEquals(original.rev, copied.rev)    // different instance, so the engine marshals it
    }

    @Test
    fun deleteSceneDropsItsClipsAndKeepsTheLastScene() {
        val s = demo.deleteScene(0)
        assertEquals(listOf("Verse"), s.scenes.map { it.name })
        assertFalse(demo.scenes[0].id in s.tracks[0].clips)
        assertSame(s, s.deleteScene(0)) // last scene stays
    }

    @Test
    fun moveSceneReorders() {
        assertEquals(listOf("Verse", "Intro"), demo.moveScene(1, 0).scenes.map { it.name })
        assertSame(demo, demo.moveScene(0, 0))
    }

    @Test
    fun tracksAddDuplicateDeleteChange() {
        val two = demo.addTrack("SubVert")
        assertEquals(2, two.tracks.size)
        assertEquals("SubVert", two.tracks[1].machine.type)
        assertTrue(two.tracks[1].clips.isEmpty())

        val dup = demo.duplicateTrack(0)
        assertEquals(demo.tracks[0].clips.keys, dup.tracks[1].clips.keys)
        assertNotEquals(demo.tracks[0].id, dup.tracks[1].id)

        assertEquals(0, demo.deleteTrack(0).tracks.size)
        assertEquals("Other", demo.changeMachine(0, "Other").tracks[0].machine.type)
        assertEquals("Lead", demo.renameTrack(0, "Lead").tracks[0].name)
    }

    @Test
    fun songLevelUndoIsSeparateFromTrackUndo() {
        val pushes = ArrayList<Boolean>()
        val editor = SongEditor(demo) { _, p -> pushes += p }
        editor.editSong { it.addScene() }
        editor.editClip(0, "s-verse") { it.copy(mute = true) }
        assertTrue(editor.canUndoSong())
        assertTrue(editor.canUndo(0))

        editor.undoSong()
        assertEquals(2, editor.song.scenes.size)
        assertTrue(editor.canRedoSong())
        // the song-level undo restored the document as it was before the scene add,
        // which is before the mute too - so the track history still applies on top
        editor.redoSong()
        assertEquals(3, editor.song.scenes.size)
        editor.undo(0)
        assertFalse(editor.song.tracks[0].clips["s-verse"]!!.mute)
    }
}
