package com.rm.acidulous.model

import com.rm.acidulous.model.lyrics.Accent
import com.rm.acidulous.model.lyrics.Dictionary
import com.rm.acidulous.model.lyrics.Lyrics
import com.rm.acidulous.model.lyrics.Pronounce
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class LyricsTest {
    private val dictionary = Dictionary(File("src/commonMain/files/dictionary.bin").readBytes())

    private fun say(word: String, accent: Accent) =
        Pronounce.forSinger(Pronounce.word(word, dictionary, accent)).joinToString(" ")

    private fun sing(vararg lyrics: String, accent: Accent = Accent.Prairie): List<String>? =
        Lyrics.forNotes(lyrics.mapIndexed { i, l -> Note(tick = i * 96, length = 96, pitch = 60, velocity = 100, lyric = l) },
            accent, dictionary)

    @Test
    fun aChordSingsOneSyllableOnItsTopNote() {
        // "hel-" on a chord of three, "lo" on one note: the word carries across
        // the chord, and its other notes sing nothing of their own.
        val notes = listOf(
            Note(tick = 0, length = 96, pitch = 60, velocity = 100),
            Note(tick = 0, length = 96, pitch = 67, velocity = 100, lyric = "hel-"),
            Note(tick = 0, length = 96, pitch = 64, velocity = 100),
            Note(tick = 96, length = 96, pitch = 65, velocity = 100, lyric = "lo"),
        )
        assertEquals(listOf(1, 3), Lyrics.sungOrder(notes))
        val sounds = Lyrics.forNotes(notes, Accent.Prairie, dictionary)!!
        assertEquals("", sounds[0])
        assertEquals("", sounds[2])
        assertEquals(say("hello", Accent.Prairie), (sounds[1] + " " + sounds[3]).trim())
        // Words already on a lower note are still sung.
        val lower = notes.mapIndexed { i, n -> if (i == 0) n.copy(lyric = "hel-") else if (i == 1) n.copy(lyric = "") else n }
        assertEquals(listOf(0, 3), Lyrics.sungOrder(lower, byWords = true))
    }

    @Test
    fun theDictionaryIsSearched() {
        assertTrue(dictionary.size > 100_000)
        assertEquals(listOf("AH0", "B", "AW1", "T"), dictionary.lookup("about"))
        assertEquals(listOf("Z", "UW1"), dictionary.lookup("zoo"))
        assertEquals(listOf("AH0"), dictionary.lookup("a"))
        assertNull(dictionary.lookup("zzxq"))
        assertNull(dictionary.lookup(""))
    }

    @Test
    fun rightAndOutRiseBeforeAVoicelessSound() {
        assertEquals("AX B AWP T", say("about", Accent.Prairie))
        assertEquals("AX B AWC T", say("about", Accent.Central))
        assertEquals("AX B AW T", say("about", Accent.American))
        assertEquals("L AY D", say("lied", Accent.Prairie))
        assertEquals("L AYC T", say("light", Accent.Prairie))
        // A tapped T still raises the vowel before it; a tapped D doesn't.
        assertEquals("R AYC DX ER", say("writer", Accent.Prairie))
        assertEquals("R AY DX ER", say("rider", Accent.Prairie))
    }

    @Test
    fun cotAndCaughtAreOneVowelInCanada() {
        assertEquals("D OC N", say("dawn", Accent.Prairie))
        assertEquals("D OC N", say("don", Accent.Prairie))
        assertEquals("D AO N", say("dawn", Accent.American))
        assertEquals("D AA N", say("don", Accent.American))
        // Before an R the two stay apart: car and core.
        assertEquals("K AA R", say("car", Accent.Prairie))
    }

    @Test
    fun thePrairiesRaiseBagAndEgg() {
        assertEquals("B EG G", say("bag", Accent.Prairie))
        assertEquals("EG G", say("egg", Accent.Prairie))
        assertEquals("B AEC G", say("bag", Accent.Central))
        assertEquals("B AE G", say("bag", Accent.American))
        assertEquals("G OWP", say("go", Accent.Prairie))
        assertEquals("G OW", say("go", Accent.Central))
    }

    @Test
    fun americanManRisesAndCanadianDoesnt() {
        assertEquals("M AEN N", say("man", Accent.American))
        assertEquals("M AE N", say("man", Accent.Prairie))
    }

    @Test
    fun aFewWordsAreCanadiansOwn() {
        assertEquals("S AO R IY", say("sorry", Accent.Prairie))
        assertEquals("S AA R IY", say("sorry", Accent.American))
        assertEquals("P AEC S T AX", say("pasta", Accent.Prairie))
    }

    @Test
    fun wordsTheDictionaryDoesntHaveAreSpelledOut() {
        assertEquals("Z OC B", say("zobb", Accent.Prairie))
        assertEquals("L OC", say("la", Accent.Prairie))
        assertTrue(say("glorpish", Accent.Prairie).isNotEmpty())
    }

    @Test
    fun syllablesAreJoinedIntoWordsAndSharedBack() {
        assertEquals(listOf("HH AX", "L OWP"), sing("hel-", "lo"))
        assertEquals(listOf("T W IHC NG", "K AX L"), sing("twin-", "kle"))
        assertEquals(listOf("HH AEC", "P IY"), sing("hap-", "py"))
        // Whole words on one note each.
        assertEquals(listOf("DH AX", "K AEC T"), sing("the", "cat"))
        // Several words on one note sing one after another.
        assertEquals(listOf("DH AX K AEC T"), sing("the cat"))
    }

    @Test
    fun aDashHoldsTheVowelAndMovesTheEnding() {
        assertEquals(listOf("Y EHC", "EHC S"), sing("yes", "-"))
        assertEquals(listOf("G OWP", "OWP", "OWP"), sing("go", "-", "-"))
    }

    @Test
    fun bracketsAreSoundsAsWritten() {
        assertEquals(listOf("HH AX L OW"), sing("[hh ax l ow]"))
    }

    @Test
    fun plainNotesSingNothingAndAClipWithoutWordsIsNull() {
        assertEquals(listOf("", "L OC"), sing("", "la"))
        assertNull(sing("", ""))
    }

    @Test
    fun aTypedLineIsSharedOutAndGatheredBack() {
        assertEquals(listOf("hel-", "lo", "world"), Lyrics.spread("hel-lo world"))
        assertEquals(listOf("a-", "ma-", "zing"), Lyrics.spread("  a-ma-zing "))
        assertEquals(listOf("go", "-", "", "[hh ax]"), Lyrics.spread("go - _ [hh ax]"))
        assertEquals("hel-lo world", Lyrics.gather(listOf("hel-", "lo", "world", "")))
        assertEquals("go - _ [hh ax]", Lyrics.gather(listOf("go", "-", "", "[hh ax]")))
        assertEquals("", Lyrics.gather(listOf("", "")))
    }

    @Test
    fun theAccentControlsValuesPickTheAccent() {
        assertEquals(Accent.Prairie, Accent.of(0f))
        assertEquals(Accent.Central, Accent.of(0.5f))
        assertEquals(Accent.American, Accent.of(1f))
    }
}
