package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateTest {

    private fun pattern(k: Int, n: Int, r: Int = 0) = Generate.euclid(k, n, r).joinToString("") { if (it) "x" else "." }

    /** The same necklace: equal under some rotation. */
    private fun sameNecklace(a: String, b: String) = a.length == b.length && (a + a).contains(b)

    @Test
    fun euclidMatchesTheKnownRhythms() {
        // Toussaint's examples, as necklaces: the tresillo, the cinquillo,
        // and the rest of the usual suspects.
        assertTrue(sameNecklace(pattern(3, 8), "x..x..x."))
        assertTrue(sameNecklace(pattern(5, 8), "x.xx.xx."))
        assertTrue(sameNecklace(pattern(4, 12), "x..x..x..x.."))
        assertTrue(sameNecklace(pattern(7, 16), "x..x.x.x..x.x.x."))
        assertTrue(sameNecklace(pattern(5, 12), "x..x.x..x.x."))
    }

    @Test
    fun euclidHasExactlyItsHitsAndStartsOnOne() {
        for (n in 1..32) for (k in 0..n) {
            val p = Generate.euclid(k, n)
            assertEquals("$k of $n", k, p.count { it })
            if (k > 0) assertTrue("$k of $n starts on a rest", p[0])
        }
    }

    @Test
    fun rotationTurnsThePattern() {
        assertEquals(pattern(3, 8).substring(1) + pattern(3, 8)[0], pattern(3, 8, 1))
        assertEquals(pattern(3, 8), pattern(3, 8, 8))
    }

    @Test
    fun euclidRepeatsAcrossTheClipOnItsOwnCycle() {
        // Three over five against a bar of sixteen sixteenths: it does not
        // start over at the bar line.
        val notes = Generate.euclidNotes(Generate.Euclid(hits = 3, steps = 5, stepTicks = PPQN / 4, pitch = 38), 4 * PPQN)
        val steps = notes.map { it.tick / (PPQN / 4) }
        val p = Generate.euclid(3, 5)
        assertEquals((0 until 16).filter { p[it % 5] }, steps)
        assertTrue(notes.all { it.pitch == 38 && it.length > 0 })
    }

    @Test
    fun aLineStaysInKeyAndInRange() {
        val cMinor = setOf(0, 2, 3, 5, 7, 8, 10)
        for (seed in 1..20) {
            val l = Generate.Line(density = 0.8f, low = 48, octaves = 2, leap = 0.5f, length = 2, seed = seed)
            val notes = Generate.lineNotes(l, cMinor, 8 * PPQN)
            assertTrue(notes.isNotEmpty())
            for (n in notes) {
                assertTrue("${n.pitch} out of key", n.pitch % 12 in cMinor)
                assertTrue("${n.pitch} out of range", n.pitch in 48..72)
                assertTrue(n.tick + n.length <= 8 * PPQN)
                assertTrue(n.velocity in 1..127)
            }
            // Ties never overlap the next note.
            notes.zipWithNext().forEach { (a, b) -> assertTrue(a.tick + a.length <= b.tick) }
        }
    }

    @Test
    fun theSameSettingsGiveTheSameLine() {
        val l = Generate.Line(seed = 5)
        assertEquals(Generate.lineNotes(l, null, 4 * PPQN), Generate.lineNotes(l, null, 4 * PPQN))
        assertNotEquals(Generate.lineNotes(l, null, 4 * PPQN), Generate.lineNotes(l.copy(seed = 6), null, 4 * PPQN))
    }

    @Test
    fun moreDensityAddsNotesWithoutMovingTheOthers() {
        // The dice are drawn for every step whatever the density, so a step
        // that sounded at a lower density sounds at a higher one - and, with
        // no leaps, the only thing that can differ is the walk to it.
        val thin = Generate.lineNotes(Generate.Line(density = 0.3f, length = 1, seed = 9), null, 8 * PPQN).map { it.tick }
        val thick = Generate.lineNotes(Generate.Line(density = 0.7f, length = 1, seed = 9), null, 8 * PPQN).map { it.tick }
        assertTrue(thick.size > thin.size)
        assertTrue("thin $thin not inside thick $thick", thick.containsAll(thin))
    }

    @Test
    fun noKeyMeansAMinorPentatonicOnTheLowestNote() {
        val notes = Generate.lineNotes(Generate.Line(density = 1f, low = 50, octaves = 2, leap = 1f), null, 8 * PPQN)
        val allowed = setOf(0, 3, 5, 7, 10).map { (it + 50) % 12 }.toSet()
        assertTrue(notes.all { it.pitch % 12 in allowed })
    }

    @Test
    fun mutateAtNoughtIsWhatItWas() {
        val notes = Generate.lineNotes(Generate.Line(seed = 3), null, 4 * PPQN)
        assertEquals(notes, Generate.mutate(notes, Generate.Mutation(amount = 0f), null, 4 * PPQN, PPQN / 4, false))
    }

    @Test
    fun mutateChangesAboutAsMuchAsAsked() {
        val notes = (0 until 64).map { Note(it * PPQN / 4, PPQN / 8, 60 + (it % 5) * 2, 100) }
        fun changed(a: Float) = Generate.mutate(notes, Generate.Mutation(amount = a, seed = 2), setOf(0, 2, 4, 5, 7, 9, 11), 16 * PPQN, PPQN / 4, false)
            .let { out -> notes.count { it !in out } }
        val little = changed(0.1f)
        val lot = changed(0.6f)
        assertTrue("little $little lot $lot", little in 1 until lot)
        assertTrue(lot < notes.size)
    }

    @Test
    fun mutatedMelodyStaysInKeyAndDrumsKeepTheirPitches() {
        val key = setOf(0, 2, 4, 5, 7, 9, 11)
        val melody = (0 until 32).map { Note(it * PPQN / 4, PPQN / 8, listOf(60, 62, 64, 67)[it % 4], 100) }
        for (seed in 1..10) {
            val out = Generate.mutate(melody, Generate.Mutation(1f, seed), key, 8 * PPQN, PPQN / 4, false)
            assertTrue(out.all { it.pitch % 12 in key })
        }
        val kit = (0 until 16).map { Note(it * PPQN / 4, PPQN / 8, if (it % 4 == 0) 36 else 42, 100) }
        for (seed in 1..10) {
            val out = Generate.mutate(kit, Generate.Mutation(1f, seed), null, 4 * PPQN, PPQN / 4, true)
            assertTrue(out.all { it.pitch == 36 || it.pitch == 42 })
            assertTrue(out.all { it.tick in 0 until 4 * PPQN })
            assertEquals(out.size, out.distinctBy { it.tick to it.pitch }.size)
        }
    }
}
