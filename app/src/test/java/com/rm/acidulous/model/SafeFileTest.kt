package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeFileTest {

    @Test
    fun aSafeWriteReplacesTheFileAndLeavesNothingBehind() {
        val dir = Files.createTempDirectory("safe").toFile()
        try {
            val f = File(dir, "song.json")
            f.writeTextSafely("first")
            f.writeTextSafely("second")
            assertEquals("second", f.readText())
            assertEquals(listOf("song.json"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWriteThatCannotLandLeavesTheOldFileWhole() {
        val dir = Files.createTempDirectory("safe").toFile()
        try {
            val f = File(dir, "song.json")
            f.writeTextSafely("kept")
            // A directory where the temporary file would go: the write fails
            // before anything touches the song.
            File(dir, ".song.json.tmp").mkdir()
            File(dir, ".song.json.tmp/x").writeText("x")
            runCatching { f.writeTextSafely("lost") }
            assertEquals("kept", f.readText())
            assertFalse(File(dir, "song.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
