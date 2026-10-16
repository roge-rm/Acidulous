package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A freeze is a render of its clip too, so editing the notes (or a lane the
 * render baked in) marks it stale, and putting them back makes it current.
 */
class FreezeContentTest {

    private val song = Fixtures.song()
    private val sceneId = song.scenes[0].id
    private val notes = listOf(Note(0, PPQN, 36, 100), Note(2 * PPQN, PPQN, 43, 120))

    private fun withClip(clip: Clip): Song {
        val t = song.tracks[0]
        return song.copy(tracks = song.tracks.toMutableList().also { it[0] = t.copy(clips = t.clips + (sceneId to clip)) })
    }

    /** The clip frozen as it is: content and voice both current. */
    private fun frozen(clip: Clip, content: Int = hash(clip)): Song {
        val ticks = clip.bars * song.signatureOf(song.scenes[0]).ticksPerBar
        val f = Frozen("f.wav", song.tempo, ticks, 48000, 0.5f, Freeze.voiceOf(song.tracks[0]), 48000, content)
        return withClip(clip.copy(frozen = f))
    }

    private fun hash(clip: Clip) = Freeze.contentOf(song, song.tracks[0], clip)

    private fun stale(s: Song) = Freeze.stale(s, sceneId, s.tracks[0].clips[sceneId]!!)

    /** [s] with the clip changed by [edit], keeping its freeze the way the editor does. */
    private fun edited(s: Song, edit: (Clip) -> Clip): Song {
        val clip = s.tracks[0].clips[sceneId]!!
        return withClip(edit(clip).copy(frozen = clip.frozen))
    }

    @Test
    fun theHashIsTheClipsNotItsInstance() {
        // Saved in the song, so a clip read back (a new instance) hashes the same.
        val a = Clip(bars = 2, notes = notes)
        val b = Clip(bars = 2, notes = notes.map { it.copy() })
        assertEquals(hash(a), hash(b))
        val read = SongStore.decode(SongStore.encode(frozen(a)))
        assertFalse(stale(read))
    }

    @Test
    fun editingTheNotesMakesItStaleAndUndoingMakesItCurrent() {
        val s = frozen(Clip(bars = 2, notes = notes))
        assertFalse(stale(s))
        val moved = edited(s) { it.copy(notes = it.notes.map { n -> if (n.pitch == 43) n.copy(pitch = 44) else n }) }
        assertTrue(stale(moved))
        assertTrue(Freeze.outdated(moved, moved.tracks[0], moved.tracks[0].clips[sceneId]!!, moved.scenes[0]))
        val back = edited(moved) { it.copy(notes = notes) }
        assertFalse(stale(back))
    }

    @Test
    fun lengthDiceTrigsAndExpressionCount() {
        val base = Clip(bars = 2, notes = notes)
        val h = hash(base)
        assertNotEquals(h, hash(base.copy(bars = 4)))
        assertNotEquals(h, hash(base.copy(seed = 3)))
        assertNotEquals(h, hash(base.copy(playMode = PlayMode.OneShot)))
        assertNotEquals(h, hash(base.copy(notes = notes.map { it.copy(trig = Trig.Fill) })))
        assertNotEquals(h, hash(base.copy(notes = notes.map { it.copy(bend = Lane(listOf(LanePoint(0, 0.7f)))) })))
    }

    @Test
    fun aMachineLaneCountsButTheMixersDoesNot() {
        val s = frozen(Clip(bars = 2, notes = notes))
        val lane = Lane(listOf(LanePoint(0, 0.2f), LanePoint(PPQN, 0.8f)))
        assertTrue(stale(edited(s) { it.copy(automation = mapOf(laneKey("machine", "cutoff") to lane)) }))
        assertFalse(stale(edited(s) { it.copy(automation = mapOf(laneKey("channel", "volume") to lane)) }))
        // Mute and grid aren't rendered either.
        assertFalse(stale(edited(s) { it.copy(mute = true, grid = PPQN / 8) }))
    }

    @Test
    fun aFreezeFromBeforeThisFieldIsLeftAlone() {
        val s = frozen(Clip(bars = 2, notes = notes), content = 0)
        assertFalse(stale(edited(s) { it.copy(notes = emptyList()) }))
    }

    @Test
    fun onlyCurrentFreezesAreListed() {
        val s = frozen(Clip(bars = 2, notes = notes))
        assertEquals(listOf(Freeze.Target(0, sceneId)), Freeze.current(s).map { it.first })
        assertEquals(emptyList<Freeze.Target>(), Freeze.current(edited(s) { it.copy(notes = notes.drop(1)) }).map { it.first })
        // At another tempo too.
        assertEquals(emptyList<Freeze.Target>(), Freeze.current(s.copy(tempo = s.tempo + 1f)).map { it.first })
    }

    @Test
    fun howTheTrackPlaysTheClipCounts() {
        val s = frozen(Clip(bars = 2, notes = notes))
        fun track(edit: (Track) -> Track) = s.copy(tracks = s.tracks.toMutableList().also { it[0] = edit(it[0]) })
        assertTrue(stale(track { it.copy(transpose = 2) }))
        assertTrue(stale(track { it.copy(velocity = 90) }))
        assertTrue(stale(track { it.copy(swing = 0.62f) }))
        assertTrue(stale(s.copy(swing = 0.62f)))
        // The swing unit only counts once there's swing.
        assertFalse(stale(s.copy(swingUnit = 1)))
        val swung = song.copy(swing = 60f)
        val clip = s.tracks[0].clips[sceneId]!!
        val f = clip.frozen!!.copy(content = Freeze.contentOf(swung, swung.tracks[0], clip))
        val swungFrozen = swung.copy(tracks = swung.tracks.toMutableList().also { it[0] = it[0].copy(clips = it[0].clips + (sceneId to clip.copy(frozen = f))) })
        assertFalse(stale(swungFrozen))
        assertTrue(stale(swungFrozen.copy(swingUnit = 1)))
        val tuning = Tuning("odd", listOf(90f, 210f, 300f, 400f, 500f, 600f, 700f, 800f, 900f, 1000f, 1100f, 1200f))
        assertTrue(stale(s.copy(tuning = tuning)))
        assertTrue(stale(track { it.copy(tuning = tuning) }))
    }

    @Test
    fun anotherTimeSignatureMakesItStale() {
        // The render is bars at the scene's bar length, so 3/4 makes it the wrong length.
        val s = frozen(Clip(bars = 2, notes = notes))
        assertTrue(stale(s.copy(signature = Signature(3, 4))))
        assertTrue(stale(s.copy(scenes = s.scenes.mapIndexed { i, sc -> if (i == 0) sc.copy(signature = Signature(7, 8)) else sc })))
        assertFalse(stale(s.copy(signature = s.signature)))
    }

    @Test
    fun theHashIsPinned() {
        // Saved in songs: if this number changes, every freeze goes stale on
        // opening. Change it only on purpose.
        val track = Track(id = "t", name = "Bass", machine = Machine("Reflux"))
        val g = Song(name = "g", tempo = 120f, tracks = listOf(track), scenes = listOf(Scene(id = "s", name = "s")))
        val clip = Clip(
            bars = 2, seed = 4,
            notes = listOf(Note(0, PPQN, 36, 100, trig = Trig.N2of4, lyric = "la"), Note(PPQN, PPQN / 2, 48, 64, pressure = Lane(listOf(LanePoint(0, 0.25f))))),
            automation = mapOf(laneKey("machine", "cutoff") to Lane(listOf(LanePoint(0, 0.5f)), linear = false)),
        )
        assertEquals(GOLDEN, Freeze.contentOf(g, track, clip))
    }

    private companion object {
        const val GOLDEN = 1854314899
    }
}
