package com.rm.acidulous.desktop

import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.EngineLineWriter
import com.rm.acidulous.engine.EngineLines
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.recordDry
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.PresetEngine
import com.rm.acidulous.model.PresetFile
import com.rm.acidulous.model.voice.VoiceBank
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * The engine lines of a preset file, recorded dry beside a song that is
 * synced, and replayed onto other racks by the C++ replayer.
 */
class PresetEngineTest {

    companion object {
        private val out = File("build/preset-engine-test").absoluteFile
        private val root get() = EngineAssets.userRoot()

        @BeforeClass @JvmStatic
        fun startEngine() {
            out.deleteRecursively()
            EngineAssets.install(File(out, "files"), File(out, "cache").apply { mkdirs() })
            EngineLinesTool.start(out, root)
            val song = EngineLinesTool.sampledSong(root)
            EngineSync.sync(song)
            runBlocking { EngineSync.loadsDone() }
        }

        @AfterClass @JvmStatic
        fun stopEngine() = NativeEngine.stop()
    }

    private fun voice(name: String) {
        val dir = VoiceBank.folderOf(root, name)
        val prompt = com.rm.acidulous.model.voice.VoicePrompts.all.first { it.held && !it.glides }
        VoiceBank.save(dir, VoiceBank(
            name = name, takes = mapOf(prompt.id to "ah.wav"),
            cuts = mapOf(prompt.id to com.rm.acidulous.model.voice.TakeCut(holdFrom = 100, holdTo = 4000)),
        ))
        EngineLinesTool.writeHit(File(dir, "ah.wav"))
    }

    @Test
    fun aMachinePatchIsOnlyItsMountLoadsAndParams() {
        val sentBefore = EngineLines.sent
        val memory = EngineSync.memoryText()
        val rec = PresetEngine.record(Patch("Reflux", "A", mapOf("cutoff" to 0.3f)), root)
        assertEquals(sentBefore, EngineLines.sent)
        assertEquals(memory, EngineSync.memoryText())
        val lines = rec.text.lines().filter { it.isNotEmpty() }
        assertEquals("acidulous-engine 1", lines[0])
        assertEquals("# target rack 0", lines[1])
        assertEquals("MountMachine 0 \"Reflux\"", lines[2])
        val rest = lines.drop(3)
        assertTrue(rest.isNotEmpty() && rest.all { it.startsWith("SetParam 0 \"machine\" ") })
        assertTrue(rest.toString(), rest.any { it.startsWith("SetParam 0 \"machine\" \"cutoff\" 0.3 ") })
        assertEquals(emptyList<String>(), rec.unportable)
    }

    @Test
    fun aMachinePatchWithEffectsMountsItsInsertsAndSetsItsSends() {
        val memory = EngineSync.memoryText()
        val rec = PresetEngine.record(
            Patch(
                "Reflux", "B", emptyMap(),
                inserts = listOf(com.rm.acidulous.model.UnitSlot("Chorus", mapOf("mix" to 0.4f)), com.rm.acidulous.model.UnitSlot("Delay")),
                sendReverb = 0.25f, sendDelay = 0f,
            ),
            root,
        )
        assertEquals(memory, EngineSync.memoryText())
        val lines = rec.text.lines().filter { it.isNotEmpty() }
        assertTrue(lines.toString(), "MountEffect 0 0 \"Chorus\"" in lines)
        assertTrue(lines.toString(), "MountEffect 0 1 \"Delay\"" in lines)
        assertTrue(lines.filter { !it.contains("\"machine\"") }.toString(), lines.any { it.startsWith("SetParam 0 \"effect1\" \"mix\" 0.4 ") })
        assertTrue(lines.filter { !it.contains("\"machine\"") }.toString(), lines.any { it.startsWith("SetParam 0 \"effect2\" \"feedback\" ") })
        assertTrue(lines.filter { !it.contains("\"machine\"") }.toString(), lines.any { it.startsWith("SetParam 0 \"channel\" \"sendreverb\" 0.25 ") })
        assertTrue(lines.filter { !it.contains("\"machine\"") }.toString(), lines.any { it.startsWith("SetParam 0 \"channel\" \"senddelay\" 0.0 ") })
    }

    @Test
    fun aPatchInsertThisBuildDoesNotHaveIsLeftOut() {
        val inserts = listOf(com.rm.acidulous.model.UnitSlot("FutureVerb"), com.rm.acidulous.model.UnitSlot("Chorus"))
        val song = PresetEngine.songFor(Patch("Reflux", "C", emptyMap(), inserts = inserts))
        assertEquals(listOf("Chorus"), song.tracks[0].effects.map { it.type })
    }

    @Test
    fun aRemountedSlotGetsAFreshEffectOfTheSameType() {
        val song = PresetEngine.songFor(Patch("Reflux", "C", emptyMap(), inserts = listOf(com.rm.acidulous.model.UnitSlot("Chorus"))))
        val w = EngineLineWriter()
        recordDry(w) {
            EngineSync.scratch { m ->
                EngineSync.ensureEffects(song, m)
                EngineSync.ensureEffects(song, m)
                EngineSync.remountEffects(0, listOf(0), m)
                EngineSync.ensureEffects(song, m)
            }
        }
        assertEquals(2, w.text().lines().count { it == "MountEffect 0 0 \"Chorus\"" })
    }

    @Test
    fun anEffectPatchIsOnlyItsMountLoadsAndParams() {
        val sentBefore = EngineLines.sent
        val memory = EngineSync.memoryText()
        val rec = PresetEngine.record(Patch("fx.Delay", "D", mapOf("mix" to 0.5f)), root)
        assertEquals(sentBefore, EngineLines.sent)
        assertEquals(memory, EngineSync.memoryText())
        val lines = rec.text.lines().filter { it.isNotEmpty() }
        assertEquals("# target rack 0 slot 0", lines[1])
        assertEquals("MountEffect 0 0 \"Delay\"", lines[2])
        assertTrue(lines.drop(3).all { it.startsWith("SetParam 0 \"effect1\" ") })
        assertTrue(lines.toString(), lines.any { it.startsWith("SetParam 0 \"effect1\" \"mix\" 0.5 ") })
    }

    @Test
    fun aLoadFinishingDuringARecordingStillLands() {
        val zones = "samples/nope.wav|0|127|60|1|127|0.0|1.0|0.0|0"
        val base = EngineLinesTool.sampledSong(root)
        val song = base.copy(tracks = listOf(base.tracks[0].copy(machine = com.rm.acidulous.model.Machine("Mosaic", settings = mapOf("zones" to zones)))))
        EngineSync.sync(song)
        // The worker is loading a map that isn't there while these record.
        repeat(30) { PresetEngine.record(Patch("Reflux", "A", mapOf("cutoff" to 0.3f)), root) }
        runBlocking { EngineSync.loadsDone() }
        assertTrue("the load's result was lost", EngineSync.mapStatus.isNotEmpty())
        EngineSync.sync(EngineLinesTool.sampledSong(root))
        runBlocking { EngineSync.loadsDone() }
    }

    @Test
    fun onlyTheRecordingThreadIsDry() {
        val writer = EngineLineWriter()
        val sentBefore = EngineLines.sent
        recordDry(writer) {
            Thread { NativeEngine.setParam(15, "machine", "x", 0f, record = false) }.apply { start(); join() }
            NativeEngine.mountMachine(5, "Reflux")
        }
        // The other thread's call went to the engine and isn't in the recording; this thread's didn't.
        assertEquals(sentBefore + 1, EngineLines.sent)
        assertEquals(listOf("MountMachine 5 \"Reflux\""), writer.text().lines().drop(1).filter { it.isNotEmpty() })
        // Back to normal afterwards.
        NativeEngine.setParam(15, "machine", "x", 0f, record = false)
        assertEquals(sentBefore + 2, EngineLines.sent)
    }

    @Test
    fun aUserSampleAndVoiceAreMediaPathsInTheZip() {
        EngineLinesTool.writeHit(File(root.absolutePath, "samples/hit.wav"))
        voice("Ana")
        val forage = Patch("Forage", "Hit", mapOf(), mapOf("p00_sample" to "samples/hit.wav"))
        val diction = Patch("Diction", "Sing", mapOf(), mapOf("voice" to "voices/Ana/bank.json"))
        val file = File(out, "hit.acpreset")
        val written = runBlocking {
            PresetFile.write("Forage", listOf(forage), root, file) { PresetEngine.record(it, root) }
        }
        assertTrue(written.engineLines)
        assertEquals(emptyList<String>(), written.unportable)
        ZipFile(file).use { z ->
            val text = z.getInputStream(z.getEntry("engine/1.txt")).readBytes().decodeToString()
            assertTrue(text, "LoadSample 0 0 \"zip:media/samples/hit.wav\" 30" in text)
            assertTrue(z.getEntry("media/samples/hit.wav") != null)
        }
        val sung = runBlocking {
            PresetFile.write("Diction", listOf(diction), root, File(out, "sing.acpreset")) { PresetEngine.record(it, root) }
        }
        assertEquals(emptyList<String>(), sung.unportable)
        ZipFile(sung.file).use { z ->
            val text = z.getInputStream(z.getEntry("engine/1.txt")).readBytes().decodeToString()
            // The lines name the takes where the zip has them.
            assertTrue(text, "zip:media/voices/Ana/ah.wav" in text)
            assertTrue(z.getEntry("media/voices/Ana/ah.wav") != null)
        }
        val fx = runBlocking {
            PresetFile.write("fx.Delay", listOf(Patch("fx.Delay", "D", mapOf("mix" to 0.5f))), root, File(out, "delay.acpreset")) { PresetEngine.record(it, root) }
        }
        assertTrue(fx.engineLines)
    }

    /** Replays a preset's lines with the C++ replayer and returns what the engine logged. */
    private fun replay(zip: File, vararg flags: String): String {
        val script = File("../tools/engine_replay.sh").absoluteFile
        val p = ProcessBuilder(listOf("bash", script.path, "--lines", "engine/1.txt") + flags + listOf(zip.path, File(out, "r.wav").path, "1", "0"))
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        assertTrue(text, p.waitFor(9, TimeUnit.MINUTES))
        assertEquals(text, 0, p.exitValue())
        return text
    }

    @Test
    fun aPresetReplaysOntoAnotherRackAndSlot() {
        assumeTrue(ProcessBuilder("which", "g++", "unzip").start().waitFor() == 0)
        // Written by the test above, or here.
        val machine = File(out, "reflux.acpreset")
        runBlocking { PresetFile.write("Reflux", listOf(Patch("Reflux", "A", mapOf("cutoff" to 0.3f))), root, machine) { PresetEngine.record(it, root) } }
        val fx = File(out, "delay2.acpreset")
        runBlocking { PresetFile.write("fx.Delay", listOf(Patch("fx.Delay", "D", mapOf("mix" to 0.5f))), root, fx) { PresetEngine.record(it, root) } }

        val onRack3 = replay(machine, "--rack", "3")
        assertTrue(onRack3, "queued Reflux for rack 3" in onRack3)
        assertFalse(onRack3, "for rack 0" in onRack3)
        val onSlot = replay(fx, "--rack", "2", "--slot", "1")
        assertTrue(onSlot, "queued effect 'Delay' for rack 2 slot 1" in onSlot)
        val asWritten = replay(fx)
        assertTrue(asWritten, "queued effect 'Delay' for rack 0 slot 0" in asWritten)
    }
}
