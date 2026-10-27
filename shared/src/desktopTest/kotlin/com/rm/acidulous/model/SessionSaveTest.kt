package com.rm.acidulous.model

import com.rm.acidulous.engine.EngineAssets
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionSaveTest {
    private fun <T> inTempFolder(block: () -> T): T {
        val dir = Files.createTempDirectory("session").toFile()
        return try {
            EngineAssets.install(File(dir, "files"), File(dir, "cache").apply { mkdirs() })
            block()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun named(name: String) = Song(name = name)

    @Test
    fun laterWritesOffThread() = inTempFolder {
        saveSessionLater(named("later"))
        val until = System.currentTimeMillis() + 10_000
        while (SongStore.loadSession()?.name != "later" && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("later", SongStore.loadSession()?.name)
    }

    @Test
    fun nowWinsOverAnEarlierLater() = inTempFolder {
        // Many queued saves, then the one made on quitting: that one is in the file at once and stays.
        repeat(50) { saveSessionLater(named("old $it")) }
        saveSessionNow(named("quit"))
        assertEquals("quit", SongStore.loadSession()?.name)
        Thread.sleep(300)
        assertEquals("quit", SongStore.loadSession()?.name)
    }

    @Test
    fun theNewestLaterWins() = inTempFolder {
        repeat(50) { saveSessionLater(named("edit $it")) }
        val until = System.currentTimeMillis() + 10_000
        while (SongStore.loadSession()?.name != "edit 49" && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals("edit 49", SongStore.loadSession()?.name)
    }
}
