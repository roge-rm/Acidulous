package com.rm.acidulous.model

import com.rm.acidulous.model.lyrics.Lyrics
import com.rm.acidulous.ui.wordBoxes
import com.rm.acidulous.ui.wordTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordsLaneTest {
    private fun notes(vararg words: String) =
        words.mapIndexed { i, w -> Note(tick = i * 96, length = 96, pitch = 60, velocity = 100, lyric = w) }

    private fun words(n: List<Note>) = n.map { it.lyric }

    private fun edit(n: List<Note>, at: Int, text: String) =
        Lyrics.place(n, Lyrics.editOrder(n, at, text), text)

    @Test
    fun oneSyllableChangesOneNote() {
        assertEquals(listOf("a", "x", "c"), words(edit(notes("a", "b", "c"), 1, "x")))
    }

    @Test
    fun moreSyllablesCarryOnOverTheNextNotes() {
        val n = notes("a", "b", "c", "d")
        assertEquals(listOf("a", "hel-", "lo", "d"), words(edit(n, 1, "hel-lo")))
        // Past the last note, the extra syllables are dropped.
        assertEquals(listOf("a", "b", "x", "y"), words(edit(n, 2, "x y z")))
    }

    @Test
    fun clearingEmptiesOnlyThatNote() {
        val n = notes("a", "b", "c")
        assertEquals(listOf("a", "", "c"), words(edit(n, 1, "")))
        assertEquals(listOf("a", "", "c"), words(edit(n, 1, "_")))
    }

    @Test
    fun aHoldStaysAHold() {
        assertEquals(listOf("a", "-", "c"), words(edit(notes("a", "b", "c"), 1, "-")))
    }

    @Test
    fun aSelectionTakesTheWholeLine() {
        val n = notes("a", "b", "c", "d")
        val order = Lyrics.lineOrder(n, 1, setOf(1, 3))
        assertEquals(listOf(1, 3), order)
        assertEquals(listOf("a", "x", "c", "y"), words(Lyrics.place(n, order, "x y")))
        // A line shorter than the selection clears the rest of it.
        assertEquals(listOf("a", "x", "c", ""), words(Lyrics.place(n, order, "x")))
    }

    @Test
    fun aChordSingsOnItsTopNoteOnly() {
        val n = listOf(
            Note(tick = 0, length = 96, pitch = 60, velocity = 100, lyric = "q"),
            Note(tick = 0, length = 96, pitch = 67, velocity = 100),
        )
        assertEquals(listOf("", "hi"), words(edit(n, 0, "hi")))
    }

    @Test
    fun boxesRunToTheNextNoteAndATapPicksTheNearest() {
        val n = listOf(
            Note(tick = 0, length = 400, pitch = 60, velocity = 100),
            Note(tick = 96, length = 24, pitch = 62, velocity = 100),
            Note(tick = 480, length = 96, pitch = 64, velocity = 100),
        )
        val boxes = wordBoxes(n) { true }
        assertEquals(listOf(0 to 96, 96 to 120, 480 to 576), boxes.map { it.start to it.end })
        assertEquals(0, wordTarget(boxes, 50f, 24))
        assertEquals(1, wordTarget(boxes, 100f, 24))
        assertEquals(2, wordTarget(boxes, 500f, 24))
        assertEquals(1, wordTarget(boxes, 125f, 24))
        assertNull(wordTarget(boxes, 300f, 24))
    }
}
