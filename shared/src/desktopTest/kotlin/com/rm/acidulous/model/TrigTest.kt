package com.rm.acidulous.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin trig rule has to match the engine's.
 *
 * MIDI export walks the passes in Kotlin, so the rule exists in both places.
 * `tools/trig_test --table` prints every decision the C++ gate makes over a
 * grid of chances, conditions, passes, seeds and prev states, and this checks
 * `trigPlays` agrees with all of it. Regenerate the table whenever either side
 * changes.
 */
class TrigTest {

    private fun table(): List<String> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("trig_table.txt")) {
            "trig_table.txt is missing - regenerate with tools/trig_test --table"
        }.bufferedReader().readLines()

    @Test
    fun `agrees with the engine on every decision it was asked about`() {
        var rows = 0
        for (line in table()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.trim().split(" ").map { it.toInt() }
            val (chance, cond, pass, tick, pitch, seed, prev, plays) =
                listOf(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
            val note = Note(tick = tick, length = 100, pitch = pitch, velocity = 100,
                chance = chance, trig = Trig.ofCode(cond))
            val got = trigPlays(note, pass, seed, prevPlayed = prev == 1)
            assertEquals("row: $line", plays == 1, got)
            rows++
        }
        // A nearly empty table would let the checks above pass without
        // testing anything.
        assertTrue("only $rows rows in the table", rows > 1000)
    }

    @Test
    fun `the packed word is what the engine unpacks`() {
        // chance | cond shl 7 | (ratchet - 1) shl 13, with no field overflowing
        // into the next. A chance of 100 fits in seven bits.
        for (m in 2..8) {
            for (n in 1..m) {
                val t = Trig.nth(n, m)
                assertEquals("$n:$m round trips", n, t.n)
                assertEquals("$n:$m round trips", m, t.m)
                val note = Note(0, 100, 60, 100, chance = 73, trig = t, ratchet = 5)
                assertEquals(73, note.trigWord and 0x7f)
                assertEquals(Trig.codeOf(t), (note.trigWord shr 7) and 0x3f)
                assertEquals(4, (note.trigWord shr 13) and 7)
            }
        }
        // 35 codes, none clashing with the five named ones.
        val codes = (2..8).flatMap { m -> (1..m).map { n -> Trig.codeOf(Trig.nth(n, m)) } }
        assertEquals(35, codes.toSet().size)
        assertTrue(codes.min() >= Trig.NTH_BASE)
        assertEquals(Trig.NTH_BASE + 35, Trig.entries.size)
    }

    @Test
    fun `an ordinary note writes none of it down`() {
        // @EncodeDefault(NEVER) keeps plain notes from writing four empty
        // fields each.
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val plain = json.encodeToString(Note.serializer(), Note(0, 60, 60, 100))
        assertFalse(plain, plain.contains("chance"))
        assertFalse(plain, plain.contains("trig"))
        assertFalse(plain, plain.contains("ratchet"))
        assertFalse(plain, plain.contains("nudge"))

        // Trigs are saved by name, so adding new ones can't change what a
        // saved song means.
        val fancy = Note(0, 60, 60, 100, chance = 40, trig = Trig.nth(3, 4), ratchet = 3, nudge = -20)
        val text = json.encodeToString(Note.serializer(), fancy)
        assertTrue(text, text.contains("N3of4"))
        assertEquals(fancy, json.decodeFromString(Note.serializer(), text))
    }

    @Test
    fun `chance nought and a hundred are certainties`() {
        val never = Note(0, 60, 60, 100, chance = 0)
        val always = Note(0, 60, 60, 100, chance = 100)
        for (pass in 0 until 200) {
            assertFalse(trigPlays(never, pass, 0, prevPlayed = false))
            assertTrue(trigPlays(always, pass, 0, prevPlayed = false))
        }
    }

    @Test
    fun `fill is down unless somebody is holding it`() {
        val fill = Note(0, 60, 60, 100, trig = Trig.Fill)
        val notFill = Note(0, 60, 60, 100, trig = Trig.NotFill)
        // The export case, which has to be deterministic.
        assertFalse(trigPlays(fill, 0, 0, prevPlayed = false, fill = false))
        assertTrue(trigPlays(notFill, 0, 0, prevPlayed = false, fill = false))
        assertTrue(trigPlays(fill, 0, 0, prevPlayed = false, fill = true))
        assertFalse(trigPlays(notFill, 0, 0, prevPlayed = false, fill = true))
    }

    private operator fun <T> List<T>.component6(): T = this[5]
    private operator fun <T> List<T>.component7(): T = this[6]
    private operator fun <T> List<T>.component8(): T = this[7]
}
