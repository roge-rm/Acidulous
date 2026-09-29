package com.rm.acidulous.model

import com.rm.acidulous.io.File
import com.rm.acidulous.model.voice.TakeCut
import com.rm.acidulous.model.voice.VoiceBank
import com.rm.acidulous.model.voice.VoicePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VoiceBankTest {
    @Test
    fun thePromptsAreWhatARecordingIsCutBy() {
        val ids = VoicePrompts.all.map { it.id }
        assertEquals("every prompt has its own file", ids.size, ids.toSet().size)
        // Every vowel held, and every consonant between two ahs, in the first stage.
        assertEquals(15 + 24, VoicePrompts.inStage(1).size)
        val known = com.rm.acidulous.model.lyrics.Dictionary.VOWELS + com.rm.acidulous.model.lyrics.Dictionary.CONSONANTS
        for (p in VoicePrompts.all) assertTrue("${p.id} names sounds the singer has", p.sounds.all { it in known })
        assertEquals("ah-sah", VoicePrompts.byId("aa-s")?.sung)
        // NG can't start a syllable, so it closes the first vowel.
        assertEquals("ahng-ah", VoicePrompts.byId("aa-ng")?.sung)
    }

    @Test
    fun aVoiceIsSavedAndPicksUpWhereItStopped() {
        val root = File(Files.createTempDirectory("voices").toString())
        val dir = VoiceBank.folderOf(root, "me")
        var bank = VoiceBank("me", note = 50)
        VoiceBank.save(dir, bank)
        assertEquals(VoicePrompts.all.first(), VoiceBank.load(dir)?.nextToSing())
        bank = bank.copy(takes = mapOf("v-iy" to "v-iy.wav"))
        VoiceBank.save(dir, bank)
        val back = VoiceBank.load(dir)!!
        assertEquals(50, back.note)
        assertEquals(1, back.doneIn(1))
        assertEquals("v-ih", back.nextToSing()?.id)
        assertEquals(listOf("me"), VoiceBank.all(root).map { it.name })
        assertNull(VoiceBank.load(VoiceBank.folderOf(root, "nobody")))
    }

    @Test
    fun aTakeThatNeedsSingingAgainIsNextAndNotCounted() {
        val cut = TakeCut.parse("too loud|0|0|0|0|0|0|0|0|0.00|0.0")!!
        assertEquals("too loud", cut.problem)
        val good = TakeCut.parse("|4800|90000|12000|40000|0|0|0|0|110.50|-12.0")!!
        assertEquals(4800, good.start)
        assertEquals(110.5f, good.rootHz)
        assertEquals(TakeCut.CUTTER, good.by)
        assertNull(TakeCut.parse("nonsense"))
        val bank = VoiceBank(
            "me", takes = mapOf("v-iy" to "v-iy.wav", "v-ih" to "v-ih.wav"),
            cuts = mapOf("v-iy" to cut, "v-ih" to good),
        )
        assertEquals(1, bank.doneIn(1))
        assertEquals("v-iy", bank.nextToSing()?.id)
        // Saved with the voice.
        val root = File(Files.createTempDirectory("voices").toString())
        val dir = VoiceBank.folderOf(root, "me")
        VoiceBank.save(dir, bank)
        assertEquals("too loud", VoiceBank.load(dir)!!.cuts["v-iy"]?.problem)
    }
}
