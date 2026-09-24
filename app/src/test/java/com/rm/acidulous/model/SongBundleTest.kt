package com.rm.acidulous.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            val back = SongBundle.read(out, other)
            assertNotNull(back)
            assertEquals("samples/kick.wav", back!!.tracks[0].machine.settings["pad0"])
            assertArrayEquals(byteArrayOf(1, 2, 3), File(other, "samples/kick.wav").readBytes())
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
        val back = SongBundle.read(out, root)!!
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
        val back = SongBundle.read(out, root)!!
        assertEquals("samples/hat.wav", back.tracks[0].machine.settings["pad0"])
        assertFalse(File(root, "samples/hat (2).wav").exists())
    }

    @Test
    fun aRenameDoesNotReachIntoALongerName() {
        val s = SongBundle.repoint(song("a" to "samples/a.wav,samples/a.wav2"), mapOf("samples/a.wav" to "samples/a (2).wav"))
        assertEquals("samples/a (2).wav,samples/a.wav2", s.tracks[0].machine.settings["a"])
    }
}
