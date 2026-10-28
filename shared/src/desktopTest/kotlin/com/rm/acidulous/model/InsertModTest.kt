package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertModTest {
    private fun modded(): Song = Fixtures.song().updateTrack(0) { t ->
        t.withEffect(2, "Filter")
            .withEffectParam(2, "cutoff", 0.4f)
            .withEffectParam(2, InsertMod.SOURCE, 0.25f)
            .withEffectParam(2, InsertMod.target(1), InsertMod.targetValue(0))
            .withEffectParam(2, InsertMod.depth(1), 0.8f)
    }

    @Test
    fun `a target names an effect parameter by index and back`() {
        for (i in -1 until InsertMod.TARGETS) assertEquals(i, InsertMod.targetIndex(InsertMod.targetValue(i)))
    }

    @Test
    fun `the mod saves with the song`() {
        val song = modded()
        val back = SongStore.decode(SongStore.encode(song))
        assertEquals(song, back)
        assertTrue(InsertMod.isOn(back.tracks[0].effectAt(2)))
    }

    @Test
    fun `a song from before has its mods off`() {
        val back = SongStore.decode(SongStore.encode(Fixtures.song().updateTrack(0) { it.withEffect(0, "Delay") }))
        assertFalse(InsertMod.isOn(back.tracks[0].effectAt(0)))
    }

    @Test
    fun `an effect preset keeps the slot's mod`() {
        val t = modded().tracks[0].withEffectPatch(2, mapOf("cutoff" to 0.9f, "reso" to 0.2f))
        val fx = t.effectAt(2)
        assertEquals(0.9f, fx.params["cutoff"])
        assertEquals(0.25f, fx.params[InsertMod.SOURCE])
        assertEquals(0.8f, fx.params[InsertMod.depth(1)])
    }

    @Test
    fun `choosing another effect drops the mod`() {
        val t = modded().tracks[0].withEffect(2, "Delay")
        assertFalse(InsertMod.isOn(t.effectAt(2)))
    }

    @Test
    fun `a patch carries the mod on its own inserts`() {
        val track = modded().tracks[0].withEffect(0, "Filter").withEffectParam(0, InsertMod.SOURCE, 0.5f)
        val patch = PatchChain.withEffectsOf(Patch("machine:Reflux", "p", emptyMap()), track)
        val applied = PatchChain.apply(Fixtures.song().tracks[0], patch, { true }, { emptyMap() }).track
        assertEquals(0.5f, applied.effectAt(0).params[InsertMod.SOURCE])
    }

    @Test
    fun `an engine index names a mod setting`() {
        val names = listOf("mod_source", "mod_shape")
        assertEquals("mod_shape", InsertMod.nameOfIndex(InsertMod.INDEX_BASE - 1, names))
        assertEquals(null, InsertMod.nameOfIndex(-2, names))
    }

    private fun key(track: Int) = InsertMod.keyValue(track)
    private fun keyedFrom(song: Song, on: Int, from: Int): Song = song.updateTrack(on) { t ->
        val withLane = t.clips.mapValues { (_, c) ->
            c.copy(automation = c.automation + (laneKey("effect1", InsertMod.KEY) to Lane(listOf(LanePoint(0, key(from)), LanePoint(10, LANE_BASE)))))
        }
        t.withEffect(0, "Filter")
            .withEffectParam(0, InsertMod.SOURCE, InsertMod.sourceValue(InsertMod.KEYED))
            .withEffectParam(0, InsertMod.KEY, key(from))
            .copy(clips = withLane)
    }

    @Test
    fun `a keyed mod follows its track when tracks move`() {
        // Track 2 keyed from itself; a copy of track 1 goes in between.
        val song = keyedFrom(Fixtures.song(), on = 1, from = 1).duplicateTrack(0)
        val fx = song.tracks[2].effectAt(0)
        assertEquals(key(2), fx.params[InsertMod.KEY])
        assertEquals(InsertMod.sourceValue(InsertMod.KEYED), fx.params[InsertMod.SOURCE])
        val lane = song.tracks[2].clips.values.first().automation.getValue(laneKey("effect1", InsertMod.KEY))
        assertEquals(key(2), lane.points[0].value)
        assertEquals(LANE_BASE, lane.points[1].value)
    }

    @Test
    fun `a key names a track or none, and back`() {
        for (t in 0..InsertMod.NO_KEY) assertEquals(t, InsertMod.keyTrack(InsertMod.keyValue(t)))
        assertEquals(1f, InsertMod.keyValue(InsertMod.NO_KEY))
    }

    @Test
    fun `a mod keyed from a deleted track hears nothing, keeping its source`() {
        val none = key(InsertMod.NO_KEY)
        val keyed = keyedFrom(Fixtures.song(), on = 1, from = 0).deleteTrack(0).tracks[0]
        assertEquals(none, keyed.effectAt(0).params[InsertMod.KEY])
        assertEquals(InsertMod.sourceValue(InsertMod.KEYED), keyed.effectAt(0).params[InsertMod.SOURCE])
        // And one keyed from a track after the deleted one moves up with it.
        val other = keyedFrom(Fixtures.song(), on = 1, from = 1).deleteTrack(0)
        assertEquals(key(0), other.tracks[0].effectAt(0).params[InsertMod.KEY])
    }

    @Test
    fun `a deleted track's key goes to none, keyed or not`() {
        val none = key(InsertMod.NO_KEY)
        val keyed = keyedFrom(Fixtures.song(), on = 1, from = 0).deleteTrack(0).tracks[0]
        // On an LFO, with a key a lane on its source may switch to later.
        val lfo = keyedFrom(Fixtures.song(), on = 1, from = 0).updateTrack(1) {
            it.withEffectParam(0, InsertMod.SOURCE, InsertMod.sourceValue(InsertMod.LFO))
        }.deleteTrack(0).tracks[0]
        assertEquals(none, lfo.effectAt(0).params[InsertMod.KEY])
        assertEquals(InsertMod.sourceValue(InsertMod.LFO), lfo.effectAt(0).params[InsertMod.SOURCE])
        // The same with the key left at its default, track 1.
        val implicit = Fixtures.song().updateTrack(1) {
            it.withEffect(0, "Filter").withEffectParam(0, InsertMod.SOURCE, InsertMod.sourceValue(InsertMod.LFO))
        }.deleteTrack(0).tracks[0]
        assertEquals(none, implicit.effectAt(0).params[InsertMod.KEY])
        // A lane's points that named it go to none too; a lock's "back to the knob" stays.
        for (t in listOf(keyed, lfo)) {
            val lane = t.clips.values.first().automation.getValue(laneKey("effect1", InsertMod.KEY))
            assertEquals(none, lane.points[0].value)
            assertEquals(LANE_BASE, lane.points[1].value)
        }
        // A key of none stays none as tracks move.
        val kept = keyedFrom(Fixtures.song(), on = 1, from = InsertMod.NO_KEY).deleteTrack(0).duplicateTrack(0).tracks[1]
        assertEquals(none, kept.effectAt(0).params[InsertMod.KEY])
    }

    @Test
    fun `a slot whose mod is only a lane loses its key too`() {
        // Nothing in the slot's params but a lane on its source: its key is the default, track 1.
        val song = Fixtures.song().updateTrack(1) { t ->
            t.withEffect(0, "Filter").copy(clips = t.clips.mapValues { (_, c) ->
                c.copy(automation = c.automation + (laneKey("effect1", InsertMod.SOURCE) to
                    Lane(listOf(LanePoint(0, InsertMod.sourceValue(InsertMod.KEYED))))))
            })
        }
        assertEquals(1, song.modsKeyedFrom(0))
        assertEquals(key(InsertMod.NO_KEY), song.deleteTrack(0).tracks[0].effectAt(0).params[InsertMod.KEY])
    }

    @Test
    fun `counting the mods a delete leaves without a key`() {
        val song = keyedFrom(Fixtures.song(), on = 1, from = 0)
        assertEquals(1, song.modsKeyedFrom(0))
        assertEquals(0, song.modsKeyedFrom(1))
        // An LFO with an unused key on the track doesn't count; one with a lane naming it does.
        val lfoNoLane = song.updateTrack(1) { t ->
            t.withEffectParam(0, InsertMod.SOURCE, InsertMod.sourceValue(InsertMod.LFO))
                .copy(clips = t.clips.mapValues { (_, c) -> c.copy(automation = c.automation - laneKey("effect1", InsertMod.KEY)) })
        }
        assertEquals(0, lfoNoLane.modsKeyedFrom(0))
        val lfoLane = song.updateTrack(1) { it.withEffectParam(0, InsertMod.SOURCE, InsertMod.sourceValue(InsertMod.LFO)) }
        assertEquals(1, lfoLane.modsKeyedFrom(0))
        // The master's too.
        val master = Fixtures.song().let { it.copy(master = it.master.copy(inserts = listOf(UnitSlot("Filter",
            mapOf(InsertMod.SOURCE to InsertMod.sourceValue(InsertMod.KEYED), InsertMod.KEY to key(0)))))) }
        assertEquals(1, master.modsKeyedFrom(0))
    }

    @Test
    fun `undo of the delete puts the keys back`() {
        val song = keyedFrom(Fixtures.song(), on = 1, from = 0)
        val editor = SongEditor(song) { _, _ -> }
        editor.editSong { it.deleteTrack(0) }
        assertEquals(key(InsertMod.NO_KEY), editor.song.tracks[0].effectAt(0).params[InsertMod.KEY])
        editor.undoSong()
        assertEquals(key(0), editor.song.tracks[1].effectAt(0).params[InsertMod.KEY])
        assertEquals(song, editor.song)
    }

    @Test
    fun `a slot without a mod is left alone when a track goes`() {
        val song = Fixtures.song().updateTrack(1) { it.withEffect(0, "Filter").withEffectParam(0, "cutoff", 0.3f) }
        val fx = song.deleteTrack(0).tracks[0].effectAt(0)
        assertEquals(null, fx.params[InsertMod.KEY])
    }

    @Test
    fun `the input's and the master's keyed mods move too`() {
        val keyed = mapOf(InsertMod.SOURCE to InsertMod.sourceValue(InsertMod.KEYED), InsertMod.KEY to key(1))
        val base = Fixtures.song()
        val song = base.copy(
            input = listOf(UnitSlot("Filter", keyed)),
            master = base.master.copy(inserts = listOf(UnitSlot("Filter", keyed))),
        ).duplicateTrack(0)
        assertEquals(key(2), song.input[0].params[InsertMod.KEY])
        assertEquals(key(2), song.master.inserts[0].params[InsertMod.KEY])
    }
}
