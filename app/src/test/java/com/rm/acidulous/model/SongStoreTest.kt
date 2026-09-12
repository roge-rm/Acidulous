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
    fun signatureTicks() {
        assertEquals(960, Signature(4, 4).ticksPerBar)
        assertEquals(720, Signature(3, 4).ticksPerBar)
        assertEquals(720, Signature(6, 8).ticksPerBar)
        assertEquals(840, Signature(7, 8).ticksPerBar)
    }
}
