package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongStoreTest {

    @Test
    fun demoSongSurvivesJsonRoundTrip() {
        val song = DemoSong.build()
        val text = SongStore.encode(song)
        val back = SongStore.decode(text)
        assertEquals(song, back)
    }

    @Test
    fun derivedSceneLengthIsLongestClip() {
        val song = DemoSong.build()
        assertEquals(1, song.barsOf(song.scenes[0]))
        assertEquals(2, song.barsOf(song.scenes[1]))
    }

    @Test
    fun unknownKeysAreTolerated() {
        val text = SongStore.encode(DemoSong.build()).replaceFirst("\"name\"", "\"futureField\": 42, \"name\"")
        assertTrue(text.contains("futureField"))
        assertEquals("Demo", SongStore.decode(text).name)
    }

    @Test
    fun clipRevIsInstanceIdentityNotContent() {
        val a = Clip(notes = listOf(Note(0, 50, 36, 100)))
        val same = a                       // carried along: same rev
        val edited = a.copy(bars = 2)      // an edit: new rev
        val loaded = SongStore.decode(SongStore.encode(DemoSong.build())).tracks[0].clips.values.first()
        assertEquals(a.rev, same.rev)
        assertTrue(edited.rev != a.rev)
        assertTrue(loaded.rev != a.rev)
        // and rev never leaks into equality or the file
        assertEquals(a, a.copy())
        assertTrue(!SongStore.encode(DemoSong.build()).contains("\"rev\""))
    }

    @Test
    fun sceneEngineIdIsStable() {
        assertEquals(fnv1a64("s-intro"), Scene("s-intro", "Intro").engineId)
        assertTrue(fnv1a64("s-intro") != fnv1a64("s-verse"))
    }

    @Test
    fun mixerAndMasterRoundTripAndDefault() {
        val song = DemoSong.build()
        assertEquals(0.25f, song.tracks[0].mixer.sendReverb)
        assertEquals(song, SongStore.decode(SongStore.encode(song)))
        // a file from before M5 has no mixer or master: defaults apply
        val old = """{"name":"Old","tracks":[{"id":"t","name":"T","machine":{"type":"Subvert"}}],"scenes":[{"id":"s","name":"S"}]}"""
        val decoded = SongStore.decode(old)
        assertEquals(Mixer(), decoded.tracks[0].mixer)
        assertEquals(Master(), decoded.master)
    }

    @Test
    fun engineParamMappingsAreInvertible() {
        assertEquals(1f / 1.5f, EngineParams.volume01(1f), 1e-6f)
        assertEquals(0.5f, EngineParams.pan01(0f), 1e-6f)
        assertEquals(-1f, EngineParams.panFrom01(0f), 1e-6f)
        assertEquals(1f, EngineParams.delayTime01(6), 1e-6f)
        assertEquals(0.5f, EngineParams.delayTime01(3), 1e-6f)
        assertEquals(EngineParams.DELAY_TIMES, EngineParams.DELAY_TIME_NAMES.size)
    }

    @Test
    fun lanesInterpolateAndReplaceByTick() {
        val lane = Lane().withPoint(0, 0f).withPoint(480, 1f)
        assertEquals(0.5f, lane.valueAt(240), 1e-6f)
        assertEquals(1f, lane.valueAt(9999), 1e-6f)
        assertEquals(0f, lane.copy(linear = false).valueAt(240), 1e-6f)
        val replaced = lane.withPoint(480, 0.25f)
        assertEquals(2, replaced.points.size)
        assertEquals(0.25f, replaced.valueAt(480), 1e-6f)
        val clip = Clip(automation = mapOf(laneKey("machine", "cutoff") to lane))
        assertEquals(clip, SongStore.decode(SongStore.encode(DemoSong.build().let { d ->
            d.copy(tracks = listOf(d.tracks[0].copy(clips = mapOf("s-intro" to clip))))
        })).tracks[0].clips["s-intro"])
    }

    @Test
    fun signatureTicks() {
        assertEquals(960, Signature(4, 4).ticksPerBar)
        assertEquals(720, Signature(3, 4).ticksPerBar)
        assertEquals(720, Signature(6, 8).ticksPerBar)
        assertEquals(840, Signature(7, 8).ticksPerBar)
    }

    @Test
    fun effectsRoundTripAndOldFilesHaveNone() {
        val song = DemoSong.build()
        val withFx = song.copy(tracks = song.tracks.mapIndexed { i, t ->
            if (i == 0) t.withEffect(0, "Filter").withEffectParam(0, "cutoff", 0.4f).withEffect(1, "Delay").withEffectBypass(1, true) else t
        })
        val back = SongStore.decode(SongStore.encode(withFx))
        assertEquals(withFx, back)
        assertEquals("Filter", back.tracks[0].effectAt(0).type)
        assertEquals(0.4f, back.tracks[0].effectAt(0).params["cutoff"])
        assertTrue(back.tracks[0].effectAt(1).bypass)
        val old = """{"name":"Old","tracks":[{"id":"t","name":"T","machine":{"type":"Subvert"}}],"scenes":[{"id":"s","name":"S"}]}"""
        assertTrue(SongStore.decode(old).tracks[0].effects.isEmpty())
    }

    @Test
    fun eventorsRoundTrip() {
        // In their home slots already - chord 0, scale 1, arp 2 - so loading
        // gives back exactly what was saved.
        val song = DemoSong.build()
        val withEv = song.copy(tracks = song.tracks.mapIndexed { i, t ->
            if (i == 0) {
                t.withEventor(1, "Scale").withEventorParam(1, "scale", 0.5f)
                    .withEventor(2, "Arp").withEventorBypass(2, true)
            } else {
                t
            }
        })
        val back = SongStore.decode(SongStore.encode(withEv))
        assertEquals(withEv, back)
        assertEquals("Arp", back.tracks[0].eventorAt(2).type)
        assertTrue(back.tracks[0].eventorAt(2).bypass)
        assertEquals(0.5f, back.tracks[0].eventorAt(1).params["scale"])
        assertTrue(back.tracks[0].effects.isEmpty()) // the other kind untouched
        assertEquals("eventor2", eventorUnit(1))
        assertEquals(1, eventorSlotOf("eventor2"))
    }

    @Test
    fun oldSongsHaveTheirEventorsMovedHome() {
        // Before M34 an eventor went wherever there was room, because only
        // two of the three could run at once. Each has its own chip and its
        // own slot now, so a song written earlier is migrated as it loads -
        // otherwise the chord chip reads the scale's slot as empty and the
        // first tap quietly replaces it.
        val song = DemoSong.build()
        val old = song.copy(tracks = song.tracks.mapIndexed { i, t ->
            if (i == 0) t.withEventor(0, "Scale").withEventor(1, "Arp") else t
        })
        val back = SongStore.decode(SongStore.encode(old))
        assertEquals("", back.tracks[0].eventorAt(0).type) // the chord's, left free
        assertEquals("Scale", back.tracks[0].eventorAt(1).type)
        assertEquals("Arp", back.tracks[0].eventorAt(2).type)
    }

}
