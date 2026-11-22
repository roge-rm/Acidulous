package com.rm.acidulous.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBandsTest {
    // A knob cell of 64 x 80 on a 640 x 380 room, as dp.
    private fun plan(vararg counts: Int, w: Int = 640, h: Int = 380, cellW: Int = 64) =
        planBands(counts.map { CardNeed(it, cellW, 80) }, w, h, gap = 6, padW = 12, padH = 26)

    @Test fun `a card takes the lines that fit, then the fewest columns`() {
        assertEquals(1 to 4, cardGrid(4, 80, 354))
        assertEquals(2 to 3, cardGrid(6, 80, 354))
        assertEquals(3 to 1, cardGrid(3, 80, 100))
        assertEquals(1 to 1, cardGrid(0, 80, 354))
        // Not even one line fits: still one line.
        assertEquals(5 to 1, cardGrid(5, 80, 40))
    }

    @Test fun `three cards fit one band and share the width`() {
        val p = plan(4, 6, 3)
        assertEquals(1, p.bands)
        assertEquals(listOf(1 to 4, 2 to 3, 1 to 3), p.cards.map { it.cols to it.lines })
        assertEquals(76 + 140 + 76 + 12, p.widest)
        assertEquals(640, p.contentW)
        assertEquals(640, p.cards.sumOf { it.width } + 12)
    }

    @Test fun `eight cards take the fewest bands that fit`() {
        val p = plan(4, 4, 4, 4, 4, 4, 4, 4)
        assertEquals(2, p.bands)
        assertEquals(187, p.bandH)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1), p.cards.map { it.band })
        assertTrue(p.cards.all { it.cols == 2 && it.lines == 2 })
        assertEquals(640, p.contentW)
    }

    @Test fun `when nothing fits the narrowest widest band scrolls`() {
        val p = plan(6, 6, 6, 6, 6, 6, 6, 6)
        assertEquals(2, p.bands)
        assertEquals(4 * 204 + 18, p.widest)
        assertEquals(p.widest, p.contentW)
        // Bands too short for a line aren't tried.
        assertTrue(p.bandH >= 80 + 26)
    }

    @Test fun `bands keep order and balance the widths`() {
        val p = planBands(
            listOf(CardNeed(1, 300, 80), CardNeed(1, 100, 80), CardNeed(1, 100, 80), CardNeed(1, 300, 80)),
            500, 380, gap = 0, padW = 0, padH = 0,
        )
        assertEquals(2, p.bands)
        assertEquals(listOf(0, 0, 1, 1), p.cards.map { it.band })
        assertEquals(400, p.widest)
        assertEquals(listOf(350, 150, 150, 350), p.cards.map { it.width })
    }
}
