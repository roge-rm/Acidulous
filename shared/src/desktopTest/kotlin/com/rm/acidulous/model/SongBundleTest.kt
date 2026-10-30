package com.rm.acidulous.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SongBundleTest {

    private fun song(vararg settings: Pair<String, String>) = Song(
        name = "bundled",
        tracks = listOf(Track(id = "t", name = "Pads", machine = Machine("Forage", settings = mapOf(*settings)))),
        scenes = listOf(Scene(id = "s", name = "s")),
    )

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("bundle").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test
    fun aSongAndItsSamplesComeBackOnAnotherPhone() = withRoot { root ->
        File(root, "samples").mkdirs()
        File(root, "samples/kick.wav").writeBytes(byteArrayOf(1, 2, 3))
        val out = File(root, "song.zip")
        assertEquals(1, SongBundle.write(song("pad0" to "samples/kick.wav"), root, out))
        withRoot { other ->
            val back = runBlocking { SongBundle.read(out, other) }
            assertNotNull(back)
            assertEquals("samples/kick.wav", back!!.tracks[0].machine.settings["pad0"])
            assertArrayEquals(byteArrayOf(1, 2, 3), File(other, "samples/kick.wav").readBytes())
        }
    }

    /** A Venue in every kind of effect slot, each naming its own impulse, and one on a factory room. */
    private fun venues(): Song {
        fun venue(impulse: String) = UnitSlot(VenueRooms.TYPE, settings = mapOf(VenueRooms.SETTING to impulse))
        val base = song()
        return base.copy(
            tracks = base.tracks.map { it.copy(effects = listOf(UnitSlot(), venue("impulses/track.wav"), venue(VenueRooms.DEFAULT))) },
            master = base.master.copy(
                sends = listOf(venue("impulses/send.wav"), UnitSlot("Delay")),
                inserts = listOf(venue("impulses/master.wav")),
                groups = listOf(MixGroup(inserts = listOf(venue("impulses/group.wav")))),
            ),
            input = listOf(venue("impulses/input.wav")),
        )
    }

    @Test
    fun impulsesInEveryEffectSlotTravelAndFactoryRoomsDoNot() = withRoot { root ->
        val names = listOf("track", "send", "master", "group", "input")
        File(root, "impulses").mkdirs()
        for ((i, n) in names.withIndex()) File(root, "impulses/$n.wav").writeBytes(byteArrayOf(i.toByte()))
        val song = venues()
        assertEquals(names.map { "impulses/$it.wav" }.toSet(), SongBundle.referenced(song, root).toSet())
        val out = File(root, "rooms.zip")
        assertEquals(5, SongBundle.write(song, root, out))
        withRoot { other ->
            // Their own send impulse differs, so the song's is renamed and followed.
            File(other, "impulses").mkdirs()
            File(other, "impulses/send.wav").writeBytes(byteArrayOf(42))
            val back = runBlocking { SongBundle.read(out, other) }!!
            for ((i, n) in names.withIndex()) {
                val at = if (n == "send") "impulses/send (2).wav" else "impulses/$n.wav"
                assertArrayEquals(byteArrayOf(i.toByte()), File(other, at).readBytes())
            }
            assertEquals("impulses/track.wav", back.tracks[0].effects[1].settings[VenueRooms.SETTING])
            assertEquals(VenueRooms.DEFAULT, back.tracks[0].effects[2].settings[VenueRooms.SETTING])
            assertEquals("impulses/send (2).wav", back.master.sends[0].settings[VenueRooms.SETTING])
            assertEquals("impulses/master.wav", back.master.inserts[0].settings[VenueRooms.SETTING])
            assertEquals("impulses/group.wav", back.master.groups[0].inserts[0].settings[VenueRooms.SETTING])
            assertEquals("impulses/input.wav", back.input[0].settings[VenueRooms.SETTING])
            assertArrayEquals("theirs is untouched", byteArrayOf(42), File(other, "impulses/send.wav").readBytes())
        }
    }

    @Test
    fun yourOwnFileOfTheSameNameIsNotWrittenOver() = withRoot { root ->
        File(root, "samples").mkdirs()
        val mine = File(root, "samples/take 1.wav")
        mine.writeBytes(byteArrayOf(9, 9, 9))
        val out = File(root, "theirs.zip")
        withRoot { theirs ->
            File(theirs, "samples").mkdirs()
            File(theirs, "samples/take 1.wav").writeBytes(byteArrayOf(4, 5, 6))
            SongBundle.write(song("pad0" to "samples/take 1.wav", "zones" to "samples/take 1.wav|36|60"), theirs, out)
        }
        val back = runBlocking { SongBundle.read(out, root) }!!
        assertArrayEquals("mine is untouched", byteArrayOf(9, 9, 9), mine.readBytes())
        assertEquals("samples/take 1 (2).wav", back.tracks[0].machine.settings["pad0"])
        assertEquals("samples/take 1 (2).wav|36|60", back.tracks[0].machine.settings["zones"])
        assertArrayEquals(byteArrayOf(4, 5, 6), File(root, "samples/take 1 (2).wav").readBytes())
    }

    @Test
    fun theSameFileAlreadyThereIsSimplyUsed() = withRoot { root ->
        File(root, "samples").mkdirs()
        File(root, "samples/hat.wav").writeBytes(byteArrayOf(7, 7))
        val out = File(root, "same.zip")
        SongBundle.write(song("pad0" to "samples/hat.wav"), root, out)
        val back = runBlocking { SongBundle.read(out, root) }!!
        assertEquals("samples/hat.wav", back.tracks[0].machine.settings["pad0"])
        assertFalse(File(root, "samples/hat (2).wav").exists())
    }

    @Test
    fun aRenameDoesNotReachIntoALongerName() {
        val s = SongBundle.repoint(song("a" to "samples/a.wav,samples/a.wav2"), mapOf("samples/a.wav" to "samples/a (2).wav"))
        assertEquals("samples/a (2).wav,samples/a.wav2", s.tracks[0].machine.settings["a"])
    }

    private fun tape(file: String) = Song(
        name = "taped",
        tracks = listOf(Track(
            id = "t", name = "Tape", machine = Machine(BIAS_MACHINE),
            clips = mapOf("s" to Clip(bars = 1, audio = ClipAudio(listOf(null, TakeRef(file, 0, 4, 120f, 4 * PPQN))))),
        )),
        scenes = listOf(Scene(id = "s", name = "s")),
    )

    @Test
    fun audioTrackTakesTravelAndARenamedOneIsFollowed() = withRoot { root ->
        File(root, "samples").mkdirs()
        File(root, "samples/take 1.wav").writeBytes(byteArrayOf(9, 9))
        val out = File(root, "tape.zip")
        withRoot { theirs ->
            File(theirs, "samples").mkdirs()
            File(theirs, "samples/take 1.wav").writeBytes(byteArrayOf(1, 2))
            assertEquals(1, SongBundle.write(tape("samples/take 1.wav"), theirs, out, "acidulous-engine 1\nSetTempo 120.0\n"))
        }
        val back = runBlocking { SongBundle.read(out, root) }!!
        assertEquals("samples/take 1 (2).wav", back.tracks[0].clips["s"]!!.audio!!.lane(1)!!.file)
        assertArrayEquals(byteArrayOf(1, 2), File(root, "samples/take 1 (2).wav").readBytes())
        assertFalse("engine.txt is not unpacked", File(root, "engine.txt").exists())
    }

    @Test
    fun aZipWithoutEngineLinesStillOpens() = withRoot { root ->
        File(root, "samples").mkdirs()
        File(root, "samples/take.wav").writeBytes(byteArrayOf(3))
        val out = File(root, "old.zip")
        SongBundle.write(tape("samples/take.wav"), root, out)
        java.util.zip.ZipFile(out).use { z -> assertEquals(null, z.getEntry(SongBundle.ENGINE_ENTRY)) }
        withRoot { other ->
            val back = runBlocking { SongBundle.read(out, other) }!!
            assertEquals("samples/take.wav", back.tracks[0].clips["s"]!!.audio!!.lane(1)!!.file)
            assertArrayEquals(byteArrayOf(3), File(other, "samples/take.wav").readBytes())
        }
    }

    private fun frozenSong(): Song {
        val f = Frozen(file = "t1__s2.wav", bpm = 120f, ticks = 96, frames = 48000, voice = 5, tail = 10)
        val notes = listOf(Note(tick = 0, length = 24, pitch = 60, velocity = 100))
        return Song(
            name = "frozen",
            tracks = listOf(
                Track(id = "t1", name = "Bass", machine = Machine("Reflux"), clips = mapOf(
                    "s1" to Clip(notes = notes),
                    "s2" to Clip(notes = notes, frozen = f),
                )),
                Track(id = "t2", name = "Pads", machine = Machine("Trinity"), clips = mapOf(
                    "s1" to Clip(notes = notes, frozen = f.copy(file = "t2__s1.wav")),
                )),
            ),
            scenes = listOf(Scene(id = "s1", name = "a"), Scene(id = "s2", name = "b")),
        )
    }

    @Test
    fun frozenClipsArriveThawedAndListedToFreezeAgain() {
        val song = frozenSong()
        val arrival = SongBundle.arrive(song)
        assertEquals(listOf(Freeze.Target(0, "s2"), Freeze.Target(1, "s1")), arrival.refreeze)
        assertTrue(arrival.song.tracks.all { t -> t.clips.values.all { it.frozen == null } })
        // Everything else about the clips comes through, and the list can be frozen.
        assertEquals(song.tracks[0].clips["s2"]!!.notes, arrival.song.tracks[0].clips["s2"]!!.notes)
        assertTrue(arrival.refreeze.all { Freeze.freezable(arrival.song, it.track, it.sceneId) })
    }

    @Test
    fun anArrivalNeverSharesTrackIdsWithTheSongItCameFrom() {
        val song = frozenSong()
        val arrival = SongBundle.arrive(song)
        val before = song.tracks.map { it.id }.toSet()
        val after = arrival.song.tracks.map { it.id }
        assertTrue(after.none { it in before })
        assertEquals(after.size, after.toSet().size)
        assertEquals(song.scenes, arrival.song.scenes)
        assertEquals(song.tracks.map { it.name }, arrival.song.tracks.map { it.name })
    }

    @Test
    fun aSongWithNothingFrozenHasNothingToFreeze() {
        assertTrue(SongBundle.arrive(tape("samples/take.wav")).refreeze.isEmpty())
    }

    @Test
    fun theFreezeListSurvivesTheZip() = withRoot { root ->
        val out = File(root, "frozen.zip")
        SongBundle.write(frozenSong(), root, out)
        withRoot { other ->
            val back = runBlocking { SongBundle.read(out, other) }!!
            assertEquals(2, SongBundle.arrive(back).refreeze.size)
        }
    }

    @Test
    fun aCopyPointsItsFreezesAtNewFilesToCopy() {
        val song = frozenSong()
        val plan = Freeze.copyPlan(song)
        val copy = Freeze.applied(song, plan)
        val t1 = copy.tracks[0]
        val t2 = copy.tracks[1]
        assertEquals(mapOf("t1" to t1.id, "t2" to t2.id), plan.ids)
        assertTrue(copy.tracks.none { it.id == "t1" || it.id == "t2" })
        assertEquals(mapOf("t1__s2.wav" to "${t1.id}__s2.wav", "t2__s1.wav" to "${t2.id}__s1.wav"), plan.files)
        assertEquals("${t1.id}__s2.wav", t1.clips["s2"]!!.frozen!!.file)
        // The freeze is otherwise exact, so it isn't stale.
        assertEquals(song.tracks[0].clips["s2"]!!.frozen!!.copy(file = ""), t1.clips["s2"]!!.frozen!!.copy(file = ""))
        assertEquals(null, t1.clips["s1"]!!.frozen)
    }

    @Test
    fun aRenderThatFailedToCopyIsDropped() {
        val song = frozenSong()
        val plan = Freeze.copyPlan(song)
        val copy = Freeze.applied(song, plan, failed = setOf(plan.files.getValue("t2__s1.wav")))
        assertEquals(null, copy.tracks[1].clips["s1"]!!.frozen)
        assertNotNull(copy.tracks[0].clips["s2"]!!.frozen)
    }

    @Test
    fun aCopyAppliesToTheSongAsItIsNow() {
        val song = frozenSong()
        val plan = Freeze.copyPlan(song)
        val f = song.tracks[0].clips["s2"]!!.frozen!!
        val notes = listOf(Note(tick = 12, length = 12, pitch = 64, velocity = 90))
        // Edited during the copy: a clip's notes changed, a clip frozen under its
        // old id, one under a name of its own, and a track added.
        val now = song.copy(tracks = listOf(
            song.tracks[0].copy(clips = song.tracks[0].clips +
                ("s1" to Clip(notes = notes, frozen = f.copy(file = "t1__s1.wav")))),
            song.tracks[1].copy(clips = song.tracks[1].clips +
                ("s2" to Clip(notes = notes, frozen = f.copy(file = "elsewhere.wav")))),
            Track(id = "t3", name = "New", machine = Machine("Hexbeat")),
        ))
        val copy = Freeze.applied(now, plan)
        assertEquals(notes, copy.tracks[0].clips["s1"]!!.notes)
        assertEquals("old-id name is dropped", null, copy.tracks[0].clips["s1"]!!.frozen)
        assertEquals("elsewhere.wav", copy.tracks[1].clips["s2"]!!.frozen!!.file)
        assertEquals("${copy.tracks[0].id}__s2.wav", copy.tracks[0].clips["s2"]!!.frozen!!.file)
        assertTrue(copy.tracks[2].id != "t3")
    }

    @Test
    fun anArrivalMapsItsOldTrackIds() {
        val arrival = SongBundle.arrive(frozenSong())
        assertEquals(mapOf("t1" to arrival.song.tracks[0].id, "t2" to arrival.song.tracks[1].id), arrival.ids)
    }

    @Test
    fun onlyRendersNoSongUsesAreUnused() {
        val song = frozenSong()
        val other = song.copy(tracks = listOf(song.tracks[1]))
        val files = listOf("t1__s2.wav", "t2__s1.wav", "gone__s1.wav", "notes.txt")
        assertEquals(listOf("gone__s1.wav"), Freeze.unused(files, listOf(song)))
        assertEquals(listOf("t1__s2.wav", "gone__s1.wav"), Freeze.unused(files, listOf(other)))
        assertEquals(listOf("t1__s2.wav", "t2__s1.wav", "gone__s1.wav"), Freeze.unused(files, emptyList()))
    }

    @Test
    fun aFreezeInTheUndoHistoryIsStillInUse() {
        val song = frozenSong()
        val editor = SongEditor(song) { _, _ -> }
        editor.editClip(0, "s2") { it.copy(frozen = null) }
        val files = listOf("t1__s2.wav", "t2__s1.wav")
        assertTrue(Freeze.unused(files, editor.withHistory()).isEmpty())
        assertEquals(listOf("t1__s2.wav"), Freeze.unused(files, listOf(editor.song)))
    }

    @Test
    fun finishedRendersGoIntoTheSavedSongByTrackId() {
        val song = frozenSong()
        val f = song.tracks[0].clips["s2"]!!.frozen!!.copy(file = "new.wav")
        val back = Freeze.withRenders(song, listOf(
            Freeze.Rendered("t1", "s1", f),
            Freeze.Rendered("t1", "s2", f),
            Freeze.Rendered("gone", "s1", f),
        ))
        assertEquals("new.wav", back.tracks[0].clips["s1"]!!.frozen!!.file)
        assertEquals("a frozen clip keeps its freeze", "t1__s2.wav", back.tracks[0].clips["s2"]!!.frozen!!.file)
        assertEquals(song.tracks[1], back.tracks[1])
    }

    /** [frozenSong] with track 1's freeze current and track 2's out of date (its voice is 5). */
    private fun currentlyFrozen(): Song {
        val song = frozenSong().copy(tempo = 120f)
        val t1 = song.tracks[0]
        val clip = t1.clips["s2"]!!
        val f = clip.frozen!!.copy(ticks = clip.bars * song.signature.ticksPerBar, voice = Freeze.voiceOf(t1), content = Freeze.contentOf(song, t1, clip))
        return song.copy(tracks = listOf(t1.copy(clips = t1.clips + ("s2" to clip.copy(frozen = f))), song.tracks[1]))
    }

    @Test
    fun frozenAudioGoesInOnlyWhenAskedAndOnlyIfCurrent() = withRoot { root ->
        val freeze = File(root, "freeze").apply { mkdirs() }
        File(freeze, "t1__s2.wav").writeBytes(byteArrayOf(7, 7))
        File(freeze, "t2__s1.wav").writeBytes(byteArrayOf(8))
        val song = currentlyFrozen()
        assertEquals(listOf("t1__s2.wav"), SongBundle.frozenFiles(song, freeze))
        assertEquals(2L, SongBundle.frozenBytes(song, freeze))
        val without = File(root, "without.zip")
        SongBundle.write(song, root, without)
        java.util.zip.ZipFile(without).use { z -> assertTrue(z.entries().toList().none { it.name.startsWith("freeze/") }) }
        val with = File(root, "with.zip")
        SongBundle.write(song, root, with, freezeRoot = freeze)
        java.util.zip.ZipFile(with).use { z ->
            assertEquals(listOf("freeze/t1__s2.wav"), z.entries().toList().map { it.name }.filter { it.startsWith("freeze/") })
            assertArrayEquals(byteArrayOf(7, 7), z.getInputStream(z.getEntry("freeze/t1__s2.wav")).readBytes())
        }
        // The app freezes again on import, so the WAVs aren't unpacked.
        withRoot { other ->
            val back = runBlocking { SongBundle.read(with, other) }!!
            assertFalse(File(other, "freeze").exists())
            assertEquals(2, SongBundle.arrive(back).refreeze.size)
        }
    }
}
