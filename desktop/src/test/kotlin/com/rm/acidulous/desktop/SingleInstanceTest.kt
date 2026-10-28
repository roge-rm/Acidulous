package com.rm.acidulous.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    @Test
    fun aSecondLaunchHandsOverItsFiles() {
        val dir = Files.createTempDirectory("single").toFile()
        try {
            val preset = java.io.File(dir, "a bank.acpreset").apply { writeText("x") }
            val song = java.io.File(dir, "song.zip").apply { writeText("x") }
            val got = CountDownLatch(2)
            var files: List<java.io.File> = emptyList()
            assertTrue(SingleInstance.claim(dir) { files = it; got.countDown() })
            SingleInstance.onRaise = { got.countDown() }
            // The lock is this process's own, so a second claim here fails like a second launch's.
            assertFalse(SingleInstance.claim(dir))
            SingleInstance.handOver(dir, listOf(preset, song))
            assertTrue(got.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(preset.absolutePath, song.absolutePath), files.map { it.path })
        } finally {
            SingleInstance.onRaise = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun onlyFilesComeFromTheCommandLine() {
        val dir = Files.createTempDirectory("args").toFile()
        try {
            val preset = java.io.File(dir, "p.acpreset").apply { writeText("x") }
            val files = OpenFiles.fromArgs(arrayOf("-steamdeck", preset.path, java.io.File(dir, "missing.zip").path, dir.path))
            assertEquals(listOf(preset.absoluteFile), files)
        } finally {
            dir.deleteRecursively()
        }
    }
}
