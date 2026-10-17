package com.rm.acidulous.desktop

import com.rm.acidulous.engine.EngineLines
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Frozen
import com.rm.acidulous.model.Machine
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.SongBundle
import com.rm.acidulous.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * What a song zip's dry recording holds with the real engine: media as zip
 * paths, frozen clips as their notes, and audio track takes. tools/engine_replay.sh renders the result.
 */
class EngineLinesToolTest {

    companion object {
        private val out = File("build/engine-lines-test").absoluteFile
        private val user = File(out, "user")

        @BeforeClass @JvmStatic
        fun startEngine() {
            out.deleteRecursively()
            user.mkdirs()
            EngineLinesTool.start(out, user)
        }

        @AfterClass @JvmStatic
        fun stopEngine() = NativeEngine.stop()
    }

    @Test
    fun aUserSampleTravelsAsAZipPath() {
        val song = EngineLinesTool.sampledSong(user)
        val recording = EngineLinesTool.record(song, user)
        val lines = recording.text.lines()
        assertTrue(lines.toString(), "LoadSample 0 0 \"zip:samples/hit.wav\" 30" in lines)
        assertEquals(emptyList<String>(), recording.unportable)
        val zip = File(out, "sampled.zip")
        SongBundle.write(song, user, zip, recording.text)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue(names.toString(), names.containsAll(listOf("song.json", "engine.txt", "samples/hit.wav")))
        }
    }

    @Test
    fun aFrozenClipIsRecordedAsItsNotes() {
        val base = EngineLinesTool.sampledSong(user)
        val scene = base.scenes.single()
        EngineLinesTool.writeHit(File(out, "freeze/bass.wav"))
        val notes = listOf(Note(0, PPQN, 36, 100), Note(2 * PPQN, PPQN, 43, 100))
        val frozen = Frozen(file = "bass.wav", bpm = 120f, ticks = 4 * PPQN, frames = 12000)
        val song = base.copy(
            tracks = base.tracks + Track(
                id = "t-bass", name = "Bass", machine = Machine("Reflux"),
                clips = mapOf(scene.id to Clip(bars = 1, notes = notes, frozen = frozen)),
            ),
        )
        val lines = EngineLinesTool.record(song, user).text.lines()
        assertFalse(lines.any { it.startsWith("LoadFrozen") })
        // Rack 1, scene 0: tick, length, pitch, velocity, curves, trig word.
        val clip = lines.single { it.startsWith("SnapshotSetClip @0 1 0 ") }
        assertTrue(clip, " i[12: 0 $PPQN 36 100 0 100 ${2 * PPQN} $PPQN 43 100 0 100] " in clip)
    }

    @Test
    fun frozenAudioIsMountedOnlyWhenAskedAndTheNotesAreAlwaysThere() {
        val song = runBlocking { EngineLinesTool.frozenSong(user) }
        val scene = song.scenes.single()
        val f = song.tracks[1].clips[scene.id]!!.frozen!!
        val freezeRoot = EngineSync.freezeRoot!!
        // Rack 1's notes, written either way so a host can pick.
        val notes = " i[18: 0 $PPQN 36 100 0 100 ${2 * PPQN} $PPQN 43 120 0 100 ${5 * PPQN} ${2 * PPQN} 31 90 0 100] "

        val plain = EngineLinesTool.record(song, user).text.lines()
        assertFalse(plain.toString(), plain.any { it.startsWith("MountFrozen") })
        assertTrue(notes in plain.single { it.startsWith("SnapshotSetClip @0 1 0 ") })

        val recording = EngineLinesTool.record(song, user, frozenAudio = true)
        assertEquals(emptyList<String>(), recording.unportable)
        val lines = recording.text.lines()
        val mount = "MountFrozen 1 ${scene.engineId} \"zip:freeze/${f.file}\" ${f.bpm} ${f.ticks} ${f.frames} ${f.tail} ${f.peak}"
        assertEquals(listOf(mount), lines.filter { it.startsWith("MountFrozen") })
        assertTrue(notes in lines.single { it.startsWith("SnapshotSetClip @0 1 0 ") })

        val zip = File(out, "frozen.zip")
        SongBundle.write(song, user, zip, recording.text, freezeRoot = freezeRoot)
        ZipFile(zip).use { z ->
            val wav = z.getEntry("freeze/${f.file}")
            assertTrue(z.getInputStream(wav).readBytes().contentEquals(File(freezeRoot, f.file).readBytes()))
        }
        val plainZip = File(out, "frozen-plain.zip")
        SongBundle.write(song, user, plainZip, plain.joinToString("\n"))
        ZipFile(plainZip).use { z -> assertTrue(z.entries().toList().none { it.name.startsWith("freeze/") }) }

        // Edited after the freeze: it plays the machine, so no mount.
        val edited = song.copy(tracks = song.tracks.mapIndexed { i, t ->
            if (i != 1) t else t.copy(clips = t.clips.mapValues { (_, c) -> c.copy(notes = c.notes.drop(1)) })
        })
        assertFalse(EngineLinesTool.record(edited, user, frozenAudio = true).text.lines().any { it.startsWith("MountFrozen") })
    }

    @Test
    fun theEngineOnlyGetsFreezesThatAreUpToDate() {
        val song = runBlocking { EngineLinesTool.frozenSong(user) }
        val f = song.tracks[1].clips.values.single().frozen!!
        EngineSync.sync(song)
        runBlocking { EngineSync.loadsDone() }
        val freezes = { EngineSync.memoryText().lines().single { it.startsWith("freezes[") } }
        assertTrue(freezes(), f.file in freezes())
        val edited = song.copy(tracks = song.tracks.mapIndexed { i, t ->
            if (i != 1) t else t.copy(clips = t.clips.mapValues { (_, c) -> c.copy(notes = c.notes.drop(1)) })
        })
        EngineSync.sync(edited)
        runBlocking { EngineSync.loadsDone() }
        assertFalse(freezes(), f.file in freezes())
        // Undone: the same notes again, so it's current and mounted again.
        EngineSync.sync(song)
        runBlocking { EngineSync.loadsDone() }
        assertTrue(freezes(), f.file in freezes())
    }

    @Test
    fun aSongZipCarriesItsEngineLinesAndTakes() {
        val song = EngineLinesTool.tapeSong(user)
        val sent = EngineLines.sent
        val recording = EngineLinesTool.record(song, user)
        assertEquals("a dry recording reached the engine", sent, EngineLines.sent)
        assertEquals(emptyList<String>(), recording.unportable)
        val lines = recording.text.lines()
        assertEquals(EngineLines.HEADER, lines.first())
        // A song starts with no freezes, whatever a host played before it.
        assertEquals("ClearFrozen", lines[1])
        assertTrue(lines.toString(), "MountMachine 1 \"Bias\"" in lines)
        val reel = lines.single { it.startsWith("LoadReel 1 ") }
        assertTrue(reel, "|zip:samples/take.wav|" in reel)
        // A fresh engine has no clips cached, so every clip is written out.
        assertTrue(lines.toString(), lines.any { it.startsWith("SnapshotSetClip @0 0 0 ") })
        assertTrue(lines.toString(), lines.any { it.startsWith("SnapshotCommit @0") })

        val zip = File(out, "tape.zip")
        assertEquals(2, SongBundle.write(song, user, zip, recording.text))
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("song.json", "engine.txt", "samples/hit.wav", "samples/take.wav"), names)
            assertTrue(z.getInputStream(z.getEntry("engine.txt")).readBytes().decodeToString().startsWith("acidulous-engine 2\n"))
        }
        val other = File(out, "other").apply { deleteRecursively(); mkdirs() }
        val back = runBlocking { SongBundle.read(zip, other) }!!
        assertEquals(song.tracks[1].clips, back.tracks[1].clips)
        assertTrue(File(other, "samples/take.wav").readBytes().contentEquals(File(user, "samples/take.wav").readBytes()))
        assertFalse(File(other, "engine.txt").exists())
    }
}
