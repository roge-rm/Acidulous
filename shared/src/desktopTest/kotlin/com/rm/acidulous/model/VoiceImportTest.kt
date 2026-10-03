package com.rm.acidulous.model

import com.rm.acidulous.io.ZipWriter
import com.rm.acidulous.model.voice.VoiceBank
import com.rm.acidulous.model.voice.VoiceImport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceImportTest {

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("voices").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    /** A voice called [name] with two takes, zipped as the voice page's share does, plus any [extra] entries. */
    private fun sharedVoice(root: File, name: String, vararg extra: Pair<String, ByteArray>): File {
        val dir = VoiceBank.folderOf(root, name)
        VoiceBank.save(dir, VoiceBank(name = name, takes = mapOf("ah" to "ah.wav", "sah" to "sah.wav")))
        File(dir, "ah.wav").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "sah.wav").writeBytes(byteArrayOf(4, 5, 6))
        val zip = File(root, "$name.zip")
        val w = ZipWriter(zip)
        w.addFile("$name/${VoiceBank.INDEX}", File(dir, VoiceBank.INDEX))
        w.addFile("$name/ah.wav", File(dir, "ah.wav"))
        w.addFile("$name/sah.wav", File(dir, "sah.wav"))
        for ((entry, bytes) in extra) w.add(entry, bytes)
        w.close()
        return zip
    }

    @Test
    fun aSharedVoiceComesBackOnAnotherPhone() = withRoot { theirs ->
        val zip = sharedVoice(theirs, "Ana")
        withRoot { mine ->
            assertEquals(VoiceImport.Kind.Voice, runBlocking { VoiceImport.kindOf(zip) })
            assertEquals("Ana", runBlocking { VoiceImport.read(zip, mine) })
            val back = VoiceBank.load(VoiceBank.folderOf(mine, "Ana"))!!
            assertEquals("Ana", back.name)
            assertEquals(mapOf("ah" to "ah.wav", "sah" to "sah.wav"), back.takes)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(VoiceBank.folderOf(mine, "Ana"), "ah.wav").readBytes())
            assertArrayEquals(byteArrayOf(4, 5, 6), File(VoiceBank.folderOf(mine, "Ana"), "sah.wav").readBytes())
            assertEquals(listOf("Ana"), VoiceBank.all(mine).map { it.name })
        }
    }

    @Test
    fun yourOwnVoiceOfTheSameNameIsKept() = withRoot { theirs ->
        val zip = sharedVoice(theirs, "Ana")
        withRoot { mine ->
            val own = VoiceBank.folderOf(mine, "Ana")
            VoiceBank.save(own, VoiceBank(name = "Ana", takes = mapOf("ah" to "ah.wav")))
            File(own, "ah.wav").writeBytes(byteArrayOf(9, 9, 9))
            assertEquals("Ana (2)", runBlocking { VoiceImport.read(zip, mine) })
            assertArrayEquals("mine is untouched", byteArrayOf(9, 9, 9), File(own, "ah.wav").readBytes())
            assertEquals("Ana (2)", VoiceBank.load(VoiceBank.folderOf(mine, "Ana (2)"))!!.name)
            assertEquals(setOf("Ana", "Ana (2)"), VoiceBank.all(mine).map { it.name }.toSet())
        }
    }

    @Test
    fun onlyTheTakesItsIndexNamesComeOut() = withRoot { theirs ->
        val zip = sharedVoice(theirs, "Ana", "Ana/notes.txt" to byteArrayOf(7), "Ana/../evil.wav" to byteArrayOf(8), "other/x.wav" to byteArrayOf(9))
        withRoot { mine ->
            assertEquals("Ana", runBlocking { VoiceImport.read(zip, mine) })
            assertEquals(setOf(VoiceBank.INDEX, "ah.wav", "sah.wav"), VoiceBank.folderOf(mine, "Ana").list()!!.toSet())
            assertFalse(File(mine, "voices/evil.wav").exists())
            assertFalse(File(mine, "other").exists())
        }
    }

    @Test
    fun aVoiceIsNotASongAndLeavesNothingBehind() = withRoot { theirs ->
        val zip = sharedVoice(theirs, "Ana")
        withRoot { mine ->
            assertNull(runBlocking { SongBundle.read(zip, mine) })
            assertTrue("nothing unpacked", mine.list()!!.isEmpty())
        }
    }

    @Test
    fun aSongBundleIsStillASong() = withRoot { root ->
        val out = File(root, "song.zip")
        SongBundle.write(Song(name = "s", tracks = emptyList(), scenes = listOf(Scene(id = "s", name = "s"))), root, out)
        assertEquals(VoiceImport.Kind.Song, runBlocking { VoiceImport.kindOf(out) })
        assertNull(runBlocking { VoiceImport.read(out, root) })
    }
}
