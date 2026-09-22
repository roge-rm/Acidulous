package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongEditsTest {

    private val demo = Fixtures.song()

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
        val n = demo.tracks.size
        val two = demo.addTrack("Reflux")
        assertEquals(n + 1, two.tracks.size)
        assertEquals("Reflux", two.tracks[n].machine.type)
        assertTrue(two.tracks[n].clips.isEmpty())

        val dup = demo.duplicateTrack(0)
        assertEquals(n + 1, dup.tracks.size)
        assertEquals(demo.tracks[0].clips.keys, dup.tracks[1].clips.keys)
        assertNotEquals(demo.tracks[0].id, dup.tracks[1].id)

        assertEquals(n - 1, demo.deleteTrack(0).tracks.size)
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

    @Test
    fun effectSlotsFillChangeAndClear() {
        val t = demo.tracks[0]
        assertTrue(t.effects.isEmpty())
        assertEquals(EffectSlot(), t.effectAt(1)) // an absent slot reads as empty

        val withDelay = t.withEffect(1, "Delay")
        assertEquals(EFFECT_SLOTS, withDelay.effects.size)
        assertEquals("Delay", withDelay.effectAt(1).type)
        assertTrue(withDelay.effectAt(0).isEmpty)

        val tweaked = withDelay.withEffectParam(1, "duck", 0.7f).withEffectBypass(1, true)
        assertEquals(0.7f, tweaked.effectAt(1).params["duck"])
        assertTrue(tweaked.effectAt(1).bypass)

        // a new type starts clean; clearing empties the slot; out-of-range is a no-op
        assertTrue(tweaked.withEffect(1, "Reverb").effectAt(1).params.isEmpty())
        assertTrue(tweaked.withEffect(1, "").effectAt(1).isEmpty)
        assertSame(tweaked, tweaked.withEffect(5, "Delay"))

        assertEquals("effect2", effectUnit(1))
        assertEquals(0, effectSlotOf("effect1"))
        assertEquals(null, effectSlotOf("channel"))
    }

    @Test
    fun durationFollowsScenesRepeatsAndTempo() {
        // demo: Intro 1 bar x2 at 120 (4 s) + Verse 2 bars x1 at 140 (8 beats = 3.43 s)
        assertEquals(4f + 8f * 60f / 140f, demo.durationSeconds(), 1e-3f)
        val blank = SongStore.blank("New")
        assertEquals(2f, blank.durationSeconds(), 1e-3f) // one empty bar at 120
        assertEquals(1, blank.tracks.size)
        // Hexbeat, not Reflux: a new song is almost always a beat before it is
        // anything else. `SongStore.blank`'s default moved when the settings
        // window gained a machine to start with, and this line did not.
        assertEquals("Hexbeat", blank.tracks[0].machine.type)
    }

    @Test
    fun newTracksAreNumberedOnlyWhenTheNameIsTaken() {
        val one = demo.addTrack("Mosaic")
        assertEquals("Mosaic", one.tracks.last().name)
        val two = one.addTrack("Mosaic")
        assertEquals("Mosaic 2", two.tracks.last().name)
        val three = two.addTrack("Mosaic")
        assertEquals("Mosaic 3", three.tracks.last().name)
        // a gap left by a rename or delete is filled rather than skipped
        val renamed = three.renameTrack(three.tracks.size - 2, "Keys")
        assertEquals("Mosaic 2", renamed.addTrack("Mosaic").tracks.last().name)
    }

    /**
     * A sidechain names its source by position, so moving tracks must move it.
     * Four tracks: a kick at 1, a bass at 2 whose compressor listens to it, a
     * pad at 3 listening to it too, and a hat at 4 that the delay send ducks
     * under. Values are the parameter's own normalisation, k / 16.
     */
    @Test
    fun sidechainsFollowTheirSourceWhenTracksMove() {
        fun key(k: Int) = k / 16f
        fun comp(k: Int) = UnitSlot("Compressor", mapOf(SIDECHAIN_PARAM to key(k)))
        val lane = Lane(listOf(LanePoint(0, key(1)), LanePoint(100, key(4))))
        var song = demo.copy(tracks = emptyList())
        for (m in listOf("Genesis", "Trinity", "Cumulus", "Hexbeat")) song = song.addTrack(m)
        song = song.updateTrack(1) { it.copy(effects = listOf(comp(1))) }
        song = song.updateTrack(2) {
            it.copy(effects = listOf(comp(1)), clips = mapOf("s" to Clip(automation = mapOf("effect1:sidechain" to lane))))
        }
        song = song.copy(master = song.master.copy(sends = listOf(UnitSlot("Reverb"), comp(4))))
        fun sc(s: Song, t: Int) = s.tracks[t].effects[0].params[SIDECHAIN_PARAM]

        // A copy of the kick goes in at 2, so everything after it moves down.
        val dup = song.duplicateTrack(0)
        assertEquals(key(1), sc(dup, 2)) // the kick itself did not move
        assertEquals(key(5), dup.master.sends[1].params[SIDECHAIN_PARAM]) // the hat did
        assertEquals(listOf(key(1), key(5)), dup.tracks[3].clips["s"]!!.automation["effect1:sidechain"]!!.points.map { it.value })

        // Delete the bass: the pad moves up, and still hears the kick.
        val lost = song.deleteTrack(1)
        assertEquals(key(1), sc(lost, 1))
        assertEquals(key(3), lost.master.sends[1].params[SIDECHAIN_PARAM])

        // Delete the kick: its listeners go back to their own input.
        val gone = song.deleteTrack(0)
        assertEquals(0f, sc(gone, 0))
        assertEquals(key(3), gone.master.sends[1].params[SIDECHAIN_PARAM])
        assertEquals(listOf(0f, key(3)), gone.tracks[1].clips["s"]!!.automation["effect1:sidechain"]!!.points.map { it.value })

        // A track's output names a group by position the same way.
        val grouped = song.addTrack("Bus").updateTrack(3) { it.copy(mixer = it.mixer.copy(output = 5)) }
        assertEquals(4, grouped.deleteTrack(0).tracks[2].mixer.output)
        assertEquals(6, grouped.duplicateTrack(1).tracks[4].mixer.output)
        assertEquals(0, grouped.deleteTrack(4).tracks[3].mixer.output)
    }
}
