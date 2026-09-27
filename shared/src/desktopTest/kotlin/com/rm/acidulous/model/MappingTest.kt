package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which mapping is used when two could apply, and which mappings can be
 * recorded as automation.
 */
class MappingTest {

    private val cutoff = Mapping(cc = 74, unit = "machine", name = "cutoff")
    private val play = Mapping(note = 36, action = Action.PlayStop.name)

    private fun songWith(vararg m: Mapping) = Fixtures.song().copy(mappings = m.toList())

    @Test
    fun `the song wins over the device for the same controller`() {
        val device = listOf(Mapping(cc = 74, unit = "channel", name = "gain"))
        val song = songWith(cutoff)
        val found = Mappings.find(song, device, cc = 74)
        assertEquals("machine", found?.unit)
        assertEquals("cutoff", found?.name)
    }

    @Test
    fun `the device answers where the song is silent`() {
        val device = listOf(Mapping(cc = 7, unit = "channel", name = "gain"))
        val found = Mappings.find(songWith(cutoff), device, cc = 7)
        assertEquals("gain", found?.name)
    }

    @Test
    fun `an unmapped controller is nobody's`() {
        assertNull(Mappings.find(songWith(cutoff), emptyList(), cc = 99))
        assertNull(Mappings.find(songWith(cutoff), emptyList(), note = 60))
    }

    @Test
    fun `notes and controllers do not answer for each other`() {
        val song = songWith(Mapping(cc = 36, unit = "machine", name = "cutoff"), play)
        assertEquals("cutoff", Mappings.find(song, emptyList(), cc = 36)?.name)
        assertEquals(Action.PlayStop.name, Mappings.find(song, emptyList(), note = 36)?.action)
    }

    @Test
    fun `a parameter mapping names the very lane it will write`() {
        // A mapping and its automation lane use the same key, so recording
        // needs nothing extra.
        assertEquals(laneKey("machine", "cutoff"), cutoff.laneKey)
        assertTrue(cutoff.laneKey in listOf(laneKey("machine", "cutoff")))
    }

    @Test
    fun `an action has no lane, so nothing can record it`() {
        assertNull(play.laneKey)
        assertTrue(play.isAction)
    }

    @Test
    fun `a pinned mapping names its track and a following one does not`() {
        assertNull(cutoff.rack)
        assertEquals(3, cutoff.copy(rack = 3).rack)
    }

    @Test
    fun `the press threshold is the midpoint, and a release is not a press`() {
        assertTrue(127 >= Mappings.PRESS)
        assertTrue(64 >= Mappings.PRESS)
        assertTrue(63 < Mappings.PRESS)
        assertTrue(0 < Mappings.PRESS)
    }

    @Test
    fun `one source drives one thing`() {
        val first = listOf(cutoff)
        val second = Mappings.set(first, Mapping(cc = 74, unit = "channel", name = "pan"))
        assertEquals(1, second.size)
        assertEquals("pan", second.single().name)
    }

    @Test
    fun `clearing a target forgets whatever drove it`() {
        val all = listOf(cutoff, play)
        assertEquals(listOf(play), Mappings.clearTarget(all, "machine", "cutoff", null))
        assertEquals(listOf(cutoff), Mappings.clearTarget(all, null, null, Action.PlayStop.name))
    }

    @Test
    fun `the notes a mapping has claimed are listed once, in order`() {
        val song = songWith(Mapping(note = 38, action = Action.Panic.name), play)
        val device = listOf(Mapping(note = 36, action = Action.Stop.name)) // same note as the song's
        assertEquals(listOf(36, 38), Mappings.claimedNotes(song, device))
    }

    @Test
    fun `mappings survive being saved and loaded`() {
        val song = songWith(cutoff, play, Mapping(cc = 7, unit = "channel", name = "gain", rack = 2))
        val back = SongStore.decode(SongStore.encode(song))
        assertEquals(song.mappings, back.mappings)
        assertNotNull(Mappings.find(back, emptyList(), note = 36))
    }

    @Test
    fun `a song written before mappings existed still loads`() {
        // Checks that a file with no mappings key at all still opens. The
        // field is last in the object, so its comma has to go too or the
        // JSON is broken.
        val old = SongStore.encode(Fixtures.song())
            .replace(Regex(",\\s*\"mappings\":\\s*\\[[^\\]]*\\]"), "")
        assertTrue("the key should be gone from the fixture", "\"mappings\"" !in old)
        assertEquals(emptyList<Mapping>(), SongStore.decode(old).mappings)
    }

    @Test
    fun `the master answers by name, and round trips through its own value`() {
        // A controller writes the master through withMasterParam and the
        // screen reads it back through currentMaster. If they disagree a
        // mapped fader jumps as soon as it's touched.
        val song = Fixtures.song()
        // Reverb and delay are on the sends and are tested below.
        for (name in listOf("volume", "limiterdrive")) {
            val next = song.withMasterParam(name, 0.25f)
            assertEquals(name, 0.25f, currentMaster(next.master, name), 1e-3f)
        }
        for (name in listOf("limiteron")) {
            assertEquals(name, 1f, currentMaster(song.withMasterParam(name, 1f).master, name), 0f)
            assertEquals(name, 0f, currentMaster(song.withMasterParam(name, 0f).master, name), 0f)
        }
    }

    @Test
    fun `a send parameter goes in and comes back out`() {
        val song = Fixtures.song()
        val next = song.withSendParam(0, "size", 0.25f)
        assertEquals(0.25f, currentSend(next.master, 0, "size"), 1e-3f)
        // A name the song has never set reads as the effect's default. That
        // lets an effect gain a parameter without rewriting saved songs.
        assertEquals(0f, currentSend(next.master, 0, "shimmer"), 0f)
    }

    @Test
    fun `changing a send's effect does not keep the last one's settings`() {
        val song = Fixtures.song().withSendParam(0, "size", 0.9f)
        assertEquals(0.9f, currentSend(song.master, 0, "size"), 1e-3f)
        val swapped = song.withSend(0, "Chorus")
        assertEquals("Chorus", swapped.master.sendAt(0).type)
        assertTrue(swapped.master.sendAt(0).params.isEmpty())
        // Choosing the type it already has leaves it alone.
        val same = song.withSend(0, "Reverb")
        assertEquals(0.9f, currentSend(same.master, 0, "size"), 1e-3f)
    }

    @Test
    fun `an old song's two fixed boxes become the two slots`() {
        // What an older song has, from before the sends were slots.
        val old = Master(
            reverb = ReverbSettings(on = false, size = 0.7f, damp = 0.3f, tone = 0.25f),
            delay = DelaySettings(on = true, time = 6, feedback = 0.5f, tone = 0.4f, pingPong = false),
        )
        val now = old.migrated()
        assertEquals("Reverb", now.sendAt(0).type)
        assertEquals("Delay", now.sendAt(1).type)
        // Switched off becomes bypassed, and every knob keeps its value.
        assertTrue(now.sendAt(0).bypass)
        assertEquals(false, now.sendAt(1).bypass)
        assertEquals(0.7f, now.sendAt(0).params["size"]!!, 1e-3f)
        assertEquals(0.3f, now.sendAt(0).params["damp"]!!, 1e-3f)
        assertEquals(0.5f, now.sendAt(1).params["feedback"]!!, 1e-3f)
        assertEquals(0f, now.sendAt(1).params["pingpong"]!!, 0f)
        // The old fields are cleared, so migrating twice does nothing more.
        assertEquals(now, now.migrated())
    }

    @Test
    fun `an unknown master name changes nothing`() {
        val song = Fixtures.song()
        assertEquals(song.master, song.withMasterParam("nosuchthing", 1f).master)
    }

    @Test
    fun `a note toggles the mixer switches and sets everything else`() {
        // The engine's channel and master tables aren't passed over the
        // bridge, so this list is the only place that knows mute is a switch.
        val track = Fixtures.song().tracks.first()
        assertTrue(mappedIsSwitch(track, "channel", "mute"))
        assertTrue(mappedIsSwitch(track, "channel", "solo"))
        assertTrue(mappedIsSwitch(null, "master", "limiteron"))
        assertTrue(!mappedIsSwitch(track, "channel", "gain"))
        assertTrue(!mappedIsSwitch(null, "master", "volume"))
    }

    @Test
    fun `a source label reads the way a musician would say it`() {
        assertEquals("CC 74", cutoff.sourceLabel())
        assertEquals("note C2", play.sourceLabel()) // MIDI 36
    }
}
