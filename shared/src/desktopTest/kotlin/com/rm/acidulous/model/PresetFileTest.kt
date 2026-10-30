package com.rm.acidulous.model

import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.model.voice.VoiceBank
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class PresetFileTest {

    /** A phone: its own files folder and cache, installed as the app's. */
    private class Phone(val files: File = Files.createTempDirectory("phone").toFile()) {
        val cache = File(files, "cache").apply { mkdirs() }
        val user get() = File(files, "user")
        fun install() = EngineAssets.install(File(files, "files"), cache).also { }
        val root: File get() { install(); return EngineAssets.userRoot() }
    }

    private fun <T> phone(block: (Phone) -> T): T {
        val p = Phone()
        return try { block(p) } finally { p.files.deleteRecursively() }
    }

    private fun put(root: File, path: String, vararg bytes: Byte) {
        File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    private fun voice(root: File, name: String) {
        val dir = VoiceBank.folderOf(root, name)
        VoiceBank.save(dir, VoiceBank(name = name, takes = mapOf("ah" to "ah.wav")))
        File(dir, "ah.wav").writeBytes(byteArrayOf(7, 7, 7))
    }

    private val params = mapOf("cutoff" to 0.25f, "res" to 0.5f)

    private fun write(p: Phone, key: String, patches: List<Patch>, name: String = "out.acpreset"): PresetFile.Written =
        runBlocking { PresetFile.write(key, patches, p.root, File(p.cache, name)) }

    private fun read(p: Phone, zip: File): PresetFile.Imported? = runBlocking { PresetFile.read(zip, p.root) }

    @Test
    fun aPatchWithItsFilesAndVoiceComesBackOnAnotherPhone() = phone { mine ->
        val root = mine.root
        put(root, "samples/hit.wav", 1, 2, 3)
        put(root, "samples/pad.wav", 4)
        put(root, "samples/gm.sf2", 5)
        voice(root, "Ana")
        val patch = Patch(
            "Forage", "Hits", params,
            settings = mapOf(
                "p00_sample" to "samples/hit.wav", "sf2" to "samples/gm.sf2",
                "zones" to "samples/pad.wav|0|127|60|1|127|0.0|1.0|0.0|0",
                "voice" to "voices/Ana/bank.json", "formula" to "x*2",
            ),
            low = 36, high = 60,
        )
        val written = write(mine, "Forage", listOf(patch))
        assertEquals(emptyList<String>(), written.missing)
        assertEquals(5, written.media)
        ZipFile(written.file).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue(names.toString(), names.containsAll(listOf(
                "preset.json", "patches/1.json", "media/samples/hit.wav", "media/samples/pad.wav", "media/samples/gm.sf2", "media/voices/Ana/bank.json", "media/voices/Ana/ah.wav",
            )))
            val text = z.getInputStream(z.getEntry("patches/1.json")).readBytes().decodeToString()
            assertTrue(text, "media/voices/Ana/bank.json" in text && "media/samples/hit.wav" in text)
        }
        phone { theirs ->
            val got = read(theirs, written.file)!!
            assertEquals("machine", got.kind)
            assertEquals("Forage", got.unit)
            assertEquals(listOf("Hits"), got.names)
            val back = PatchStore.loadUser("Forage", "Hits")!!
            assertEquals(params, back.params)
            assertEquals(36, back.low)
            assertEquals("x*2", back.settings["formula"])
            assertEquals("samples/hit.wav", back.settings["p00_sample"])
            assertEquals("samples/pad.wav|0|127|60|1|127|0.0|1.0|0.0|0", back.settings["zones"])
            assertEquals("voices/Ana/bank.json", back.settings["voice"])
            assertArrayEquals(byteArrayOf(1, 2, 3), File(theirs.root, "samples/hit.wav").readBytes())
            assertArrayEquals(byteArrayOf(5), File(theirs.root, "samples/gm.sf2").readBytes())
            assertArrayEquals(byteArrayOf(7, 7, 7), File(VoiceBank.folderOf(theirs.root, "Ana"), "ah.wav").readBytes())
        }
    }

    @Test
    fun anIdenticalVoiceIsReusedAndADifferentOneIsNumbered() = phone { mine ->
        voice(mine.root, "Ana")
        val patch = Patch("Diction", "Sing", params, mapOf("voice" to "voices/Ana/bank.json"))
        val written = write(mine, "Diction", listOf(patch))
        phone { theirs ->
            read(theirs, written.file)!!
            read(theirs, written.file)!!
            assertEquals(listOf("Ana"), VoiceBank.all(theirs.root).map { it.name })
            assertEquals("voices/Ana/bank.json", PatchStore.loadUser("Diction", "Sing 2")!!.settings["voice"])
            // Their own Ana sounds different: the shared one is kept beside it.
            File(VoiceBank.folderOf(theirs.root, "Ana"), "ah.wav").writeBytes(byteArrayOf(1))
            read(theirs, written.file)!!
            assertEquals(listOf("Ana", "Ana (2)"), VoiceBank.all(theirs.root).map { it.name })
            assertEquals("voices/Ana (2)/bank.json", PatchStore.loadUser("Diction", "Sing 3")!!.settings["voice"])
            assertArrayEquals(byteArrayOf(7, 7, 7), File(VoiceBank.folderOf(theirs.root, "Ana (2)"), "ah.wav").readBytes())
            // And importing again finds that copy.
            read(theirs, written.file)!!
            assertEquals(2, VoiceBank.all(theirs.root).size)
            assertEquals("voices/Ana (2)/bank.json", PatchStore.loadUser("Diction", "Sing 4")!!.settings["voice"])
        }
    }

    @Test
    fun aBankOfEffectPatchesLandsInTheEffectsFolder() = phone { mine ->
        val patches = listOf(Patch("fx.Delay", "Slap", params), Patch("fx.Delay", "Long", params + ("mix" to 1f)))
        val written = write(mine, "fx.Delay", patches)
        assertEquals(2, written.count)
        phone { theirs ->
            val got = read(theirs, written.file)!!
            assertEquals("effect", got.kind)
            assertEquals("Delay", got.unit)
            assertEquals(listOf("Long", "Slap"), PatchStore.userList("fx.Delay"))
            assertEquals(1f, PatchStore.loadUser("fx.Delay", "Long")!!.params["mix"])
            assertTrue(File(theirs.root, "patches/fx/Delay/Slap.json").isFile)
        }
    }

    @Test
    fun venueImpulsesTravelInEffectPatchesAndMachinePatchInserts() = phone { mine ->
        put(mine.root, "impulses/hall.wav", 1, 2)
        put(mine.root, "impulses/plate.wav", 3)
        val fx = Patch("fx.Venue", "Hall", params, settings = mapOf(VenueRooms.SETTING to "impulses/hall.wav"))
        val room = Patch("fx.Venue", "Room", params, settings = mapOf(VenueRooms.SETTING to VenueRooms.DEFAULT))
        val fxWritten = write(mine, "fx.Venue", listOf(fx, room), "venue.acpreset")
        assertEquals("a factory room isn't missing", emptyList<String>(), fxWritten.missing)
        assertEquals(1, fxWritten.media)
        val synth = Patch(
            "Trinity", "Plated", params,
            inserts = listOf(UnitSlot(VenueRooms.TYPE, settings = mapOf(VenueRooms.SETTING to "impulses/plate.wav"))),
        )
        val synthWritten = write(mine, "Trinity", listOf(synth), "synth.acpreset")
        assertEquals(1, synthWritten.media)
        phone { theirs ->
            read(theirs, fxWritten.file)!!
            read(theirs, synthWritten.file)!!
            assertEquals("impulses/hall.wav", PatchStore.loadUser("fx.Venue", "Hall")!!.settings[VenueRooms.SETTING])
            assertEquals(VenueRooms.DEFAULT, PatchStore.loadUser("fx.Venue", "Room")!!.settings[VenueRooms.SETTING])
            assertEquals("impulses/plate.wav", PatchStore.loadUser("Trinity", "Plated")!!.inserts[0].settings[VenueRooms.SETTING])
            assertArrayEquals(byteArrayOf(1, 2), File(theirs.root, "impulses/hall.wav").readBytes())
            assertArrayEquals(byteArrayOf(3), File(theirs.root, "impulses/plate.wav").readBytes())
        }
    }

    @Test
    fun clashingNamesAndFilesGetANumber() = phone { mine ->
        val root = mine.root
        put(root, "samples/hit.wav", 1, 2, 3)
        put(root, "samples/same.wav", 9)
        val written = write(mine, "Forage", listOf(
            Patch("Forage", "Hits", params, mapOf("sample" to "samples/hit.wav")),
            Patch("Forage", "Same", params, mapOf("sample" to "samples/same.wav")),
        ))
        phone { theirs ->
            val root2 = theirs.root
            put(root2, "samples/hit.wav", 8, 8)
            put(root2, "samples/same.wav", 9)
            PatchStore.save(Patch("Forage", "Hits", mapOf("cutoff" to 1f)))
            PatchStore.save(Patch("Forage", "Hits 2", mapOf("cutoff" to 1f)))
            val got = read(theirs, written.file)!!
            assertEquals(listOf("Hits 3", "Same"), got.names)
            // Their own patch and file are untouched.
            assertEquals(mapOf("cutoff" to 1f), PatchStore.loadUser("Forage", "Hits")!!.params)
            assertArrayEquals(byteArrayOf(8, 8), File(root2, "samples/hit.wav").readBytes())
            assertEquals("samples/hit (2).wav", PatchStore.loadUser("Forage", "Hits 3")!!.settings["sample"])
            assertArrayEquals(byteArrayOf(1, 2, 3), File(root2, "samples/hit (2).wav").readBytes())
            // The same bytes are shared, not copied again.
            assertEquals("samples/same.wav", PatchStore.loadUser("Forage", "Same")!!.settings["sample"])
            assertFalse(File(root2, "samples/same (2).wav").exists())
            // Importing again numbers the patches again.
            assertEquals(listOf("Hits 4", "Same 2"), read(theirs, written.file)!!.names)
        }
    }

    @Test
    fun missingFilesAreListedAndTheFileIsStillWritten() = phone { mine ->
        val written = write(mine, "Forage", listOf(Patch("Forage", "Gone", params, mapOf("sample" to "samples/gone.wav", "voice" to "voices/Nobody/bank.json"))))
        assertEquals(2, written.missing.size)
        assertTrue(written.missing.toString(), written.missing.all { it.startsWith("Gone: ") })
        assertTrue(written.file.isFile)
        phone { theirs ->
            read(theirs, written.file)!!
            assertEquals("samples/gone.wav", PatchStore.loadUser("Forage", "Gone")!!.settings["sample"])
        }
    }

    @Test
    fun somethingThatIsNotAPresetIsRefusedAndAddsNothing() = phone { p ->
        val junk = File(p.cache, "junk.acpreset").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        assertNull(read(p, junk))
        val song = File(p.cache, "song.zip")
        val w = com.rm.acidulous.io.ZipWriter(song)
        w.add("song.json", "{}".encodeToByteArray()); w.add("media/samples/x.wav", byteArrayOf(1))
        w.close()
        assertNull(read(p, song))
        assertFalse(runBlocking { PresetFile.isPreset(junk) })
        assertFalse(runBlocking { PresetFile.isPreset(song) })
        // A manifest naming a patch that isn't there, and a unit that would leave the patches folder.
        val bad = File(p.cache, "bad.acpreset")
        val b = com.rm.acidulous.io.ZipWriter(bad)
        b.add("preset.json", """{"version":1,"kind":"machine","unit":"../x","patches":["patches/1.json"]}""".encodeToByteArray())
        b.add("patches/1.json", """{"machine":"x","name":"n","params":{}}""".encodeToByteArray())
        b.close()
        assertNull(read(p, bad))
        assertEquals(emptyList<String>(), PatchStore.userList("Forage"))
        assertFalse(File(p.root, "samples/x.wav").exists())
        assertFalse(File(p.root, "patches/../x").exists())
    }

    @Test
    fun aNewerPresetVersionIsRefused() = phone { p ->
        val f = File(p.cache, "new.acpreset")
        val w = com.rm.acidulous.io.ZipWriter(f)
        w.add("preset.json", """{"version":9,"kind":"machine","unit":"Reflux","patches":["patches/1.json"]}""".encodeToByteArray())
        w.add("patches/1.json", """{"machine":"Reflux","name":"n","params":{}}""".encodeToByteArray())
        w.close()
        assertNull(read(p, f))
    }

    @Test
    fun engineLinesGoInPerPatch() = phone { mine ->
        val patches = listOf(Patch("Reflux", "A", params), Patch("Reflux", "B", params))
        val written = runBlocking {
            PresetFile.write("Reflux", patches, mine.root, File(mine.cache, "e.acpreset")) { p ->
                PresetEngine.Recording("acidulous-engine 1\n# ${p.name}\n", emptyList())
            }
        }
        assertTrue(written.engineLines)
        ZipFile(written.file).use { z ->
            assertEquals("acidulous-engine 1\n# B\n", z.getInputStream(z.getEntry("engine/2.txt")).readBytes().decodeToString())
            assertNotNull(z.getEntry("engine/1.txt"))
        }
        val none = write(mine, "Reflux", patches, "n.acpreset")
        assertFalse(none.engineLines)
        ZipFile(none.file).use { z -> assertNull(z.getEntry("engine/1.txt")) }
    }

    @Test
    fun unsafeOrAbsentMediaIsClearedAndListed() = phone { p ->
        val f = File(p.cache, "odd.acpreset")
        val w = com.rm.acidulous.io.ZipWriter(f)
        w.add("preset.json", """{"version":1,"kind":"machine","unit":"Forage","patches":["patches/1.json"]}""".encodeToByteArray())
        w.add("patches/1.json", """{"machine":"Forage","name":"odd","params":{},"settings":{"sample":"/etc/passwd","slice_sample":"../../x.wav","p00_sample":"media/samples/absent.wav","p01_sample":"samples/plain.wav"}}""".encodeToByteArray())
        w.add("media/voices/Bad/bank.json", """{"name":"Bad","takes":{"a":"../evil.wav","b":"ok.wav"}}""".encodeToByteArray())
        w.add("media/voices/Bad/ok.wav", byteArrayOf(1))
        w.close()
        val got = read(p, f)!!
        assertEquals(3, got.missing.size)
        val back = PatchStore.loadUser("Forage", "odd")!!
        assertEquals("", back.settings["sample"])
        assertEquals("", back.settings["slice_sample"])
        assertEquals("", back.settings["p00_sample"])
        assertEquals("samples/plain.wav", back.settings["p01_sample"])
        // A voice with a take named outside its folder keeps only the plain one.
        val g = File(p.cache, "voice.acpreset")
        val z = com.rm.acidulous.io.ZipWriter(g)
        z.add("preset.json", """{"version":1,"kind":"machine","unit":"Diction","patches":["patches/1.json"]}""".encodeToByteArray())
        z.add("patches/1.json", """{"machine":"Diction","name":"v","params":{},"settings":{"voice":"media/voices/Bad/bank.json"}}""".encodeToByteArray())
        z.add("media/voices/Bad/bank.json", """{"name":"Bad","takes":{"a":"../evil.wav","b":"ok.wav"}}""".encodeToByteArray())
        z.add("media/voices/Bad/ok.wav", byteArrayOf(1))
        z.close()
        read(p, g)!!
        assertEquals(mapOf("b" to "ok.wav"), VoiceBank.load(VoiceBank.folderOf(p.root, "Bad"))!!.takes)
    }
}
