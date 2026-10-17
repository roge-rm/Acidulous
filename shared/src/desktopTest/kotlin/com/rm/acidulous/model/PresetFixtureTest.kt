package com.rm.acidulous.model

import com.rm.acidulous.engine.EngineAssets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The app's .acpreset and the engine's own reader and writer
 * (engine/core/PresetFile.h) against the same two files in
 * resources/acpreset/: app-bank.acpreset, written here, and
 * native-bank.acpreset, written by tools/preset_file_test. Both hold
 * [patches]; the C++ test has the same list.
 *
 * If the app's format changes on purpose, run this with
 * ACIDULOUS_WRITE_FIXTURES=1 to write app-bank.acpreset again, then
 * tools/preset_file_test.sh to check the engine still reads it.
 */
class PresetFixtureTest {

    private val fixtures = File("src/desktopTest/resources/acpreset")
    private val json = Json

    /** A long document with quotes, tabs and letters past ASCII, so the zip is deflated with codes of its own. */
    private fun document(): String =
        (1..60).joinToString("") { "block osc$it saw \"quoted\"\tpatch osc$it.out -> mix.in$it\n" } + "café ✓ 🎹"

    private fun patches(): List<Patch> = listOf(
        Patch(
            "Trinity", "Fixture Glass",
            mapOf("cutoff" to 0.25f, "res" to 0.1f, "drive" to 1f, "level" to 0f, "tiny" to 3.0e-5f, "odd" to 0.123456789f),
            settings = mapOf("macro1" to "Bright", "nexus" to document()),
            low = 48, high = 72, family = "lead",
            version = RateMigration.PATCH_VERSION,
            inserts = listOf(
                UnitSlot("Chorus", mapOf("mix" to 0.4f, "rate" to 0.7f), bypass = true, settings = mapOf("formula" to "x*2")),
                UnitSlot("Delay", mapOf("time" to 0.5f)),
            ),
            sendReverb = 0.3f, sendDelay = 0f,
        ),
        Patch("Trinity", "Fixture Init", mapOf("cutoff" to 0.5f), version = RateMigration.PATCH_VERSION),
    )

    private fun <T> phone(block: (File) -> T): T {
        val files = Files.createTempDirectory("phone").toFile()
        return try {
            EngineAssets.install(File(files, "files"), File(files, "cache").apply { mkdirs() })
            block(EngineAssets.userRoot())
        } finally {
            files.deleteRecursively()
        }
    }

    private fun entries(zip: File): Map<String, JsonElement> = ZipFile(zip).use { z ->
        z.entries().toList().associate { it.name to json.parseToJsonElement(z.getInputStream(it).readBytes().decodeToString()) }
    }

    @Test
    fun theAppFixtureIsWhatTheAppWrites() = phone { root ->
        val out = File(root, "app-bank.acpreset")
        runBlocking { PresetFile.write("Trinity", patches(), root, out) }
        if (System.getenv("ACIDULOUS_WRITE_FIXTURES") == "1") out.copyTo(File(fixtures, "app-bank.acpreset"), overwrite = true)
        assertEquals(
            "PresetFile writes something else now; see this test's comment",
            entries(File(fixtures, "app-bank.acpreset")), entries(out),
        )
    }

    @Test
    fun theEngineFixtureImportsAsTheSamePatches() = phone { root ->
        val got = runBlocking { PresetFile.read(File(fixtures, "native-bank.acpreset"), root) }
        assertNotNull(got)
        assertEquals("machine", got!!.kind)
        assertEquals("Trinity", got.unit)
        assertEquals(patches().map { it.name }, got.names)
        for (want in patches()) assertEquals(want, PatchStore.loadUser("Trinity", want.name))
    }
}
