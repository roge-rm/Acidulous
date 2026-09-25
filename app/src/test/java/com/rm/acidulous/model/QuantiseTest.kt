package com.rm.acidulous.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Quantise, as the editor, the recorder and a Launchpad all mean it. */
class QuantiseTest {
    private val g = PPQN / 4 // a sixteenth: 60 ticks
    private val bar = PPQN * 4
    private fun note(tick: Int, length: Int = 50, raw: Int? = null, nudge: Int = 0) =
        Note(tick = tick, length = length, pitch = 60, velocity = 100, rawTick = raw, nudge = nudge)

    @Test
    fun `full strength snaps, half goes halfway, none stays`() {
        val notes = listOf(note(70), note(100))
        assertEquals(listOf(60, 120), Quantise.apply(notes, null, QuantiseSpec(g), bar).map { it.tick })
        assertEquals(listOf(65, 110), Quantise.apply(notes, null, QuantiseSpec(g, strength = 0.5f), bar).map { it.tick })
        assertEquals(notes, Quantise.apply(notes, null, QuantiseSpec(g, strength = 0f), bar))
    }

    @Test
    fun `only the chosen notes move`() {
        val notes = listOf(note(70), note(100))
        assertEquals(listOf(70, 120), Quantise.apply(notes, setOf(1), QuantiseSpec(g), bar).map { it.tick })
    }

    @Test
    fun `ends move when asked, a step after the start at least`() {
        val notes = listOf(note(70, length = 100)) // 70..170
        assertEquals(100, Quantise.apply(notes, null, QuantiseSpec(g), bar).single().length)
        val ended = Quantise.apply(notes, null, QuantiseSpec(g, ends = true), bar).single()
        assertEquals(60, ended.tick)
        assertEquals(120, ended.length) // 60..180
        val tiny = Quantise.apply(listOf(note(62, length = 5)), null, QuantiseSpec(g, ends = true), bar).single()
        assertEquals(g, tiny.length)
    }

    @Test
    fun `where it was played is kept once, and never overwritten`() {
        val once = Quantise.apply(listOf(note(70)), null, QuantiseSpec(g), bar).single()
        assertEquals(70, once.rawTick)
        val twice = Quantise.apply(listOf(once), null, QuantiseSpec(g * 2), bar).single()
        assertEquals(120, twice.tick) // exactly halfway rounds up
        assertEquals(70, twice.rawTick)
        // A note already on the line is not touched at all.
        assertNull(Quantise.apply(listOf(note(120)), null, QuantiseSpec(g), bar).single().rawTick)
    }

    @Test
    fun `a nudge survives a quantise`() {
        assertEquals(12, Quantise.apply(listOf(note(70, nudge = 12)), null, QuantiseSpec(g), bar).single().nudge)
    }

    @Test
    fun `as played puts them back`() {
        val q = Quantise.apply(listOf(note(70), note(185)), null, QuantiseSpec(g), bar)
        assertEquals(listOf(60, 180), q.map { it.tick })
        assertEquals(listOf(70, 185), Quantise.asPlayed(q, null, bar).map { it.tick })
        assertEquals(listOf(70, 180), Quantise.asPlayed(q, setOf(0), bar).map { it.tick })
    }

    @Test
    fun `triplet grids`() {
        val t = PPQN / 6 // a sixteenth triplet: 40
        assertEquals(listOf(40, 80), Quantise.apply(listOf(note(45), note(75)), null, QuantiseSpec(t), bar).map { it.tick })
    }

    @Test
    fun `nothing lands past the end of the clip`() {
        val q = Quantise.apply(listOf(note(bar - 5)), null, QuantiseSpec(g), bar).single()
        assertEquals(bar - g, q.tick)
    }

    @Test
    fun `a groove is a clip's timing, and lands others the same way`() {
        // A drummer on eighths: every second sixteenth played 20 ticks late.
        val take = (0 until 16).map { i -> note(i * g + if (i % 2 == 1) 20 else 0) }
        val groove = Quantise.grooveFrom(take, g, bar)
        assertEquals(16, groove.size)
        assertArrayEquals(IntArray(16) { if (it % 2 == 1) 20 else 0 }, groove)
        val straight = listOf(note(g), note(2 * g + 3), note(bar + g))
        assertEquals(listOf(g + 20, 2 * g, bar + g + 20),
            Quantise.apply(straight, null, QuantiseSpec(g, groove = groove), bar * 2).map { it.tick })
        // Steps the groove clip is silent on stay on the grid.
        assertArrayEquals(IntArray(4), Quantise.grooveFrom(emptyList(), PPQN, bar))
    }
}
