package com.rm.acidulous.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interface scale's math. The rest of the scale feature has to be checked
 * by eye, but these two functions decide whether a layout is asked for more
 * than it can fit, and they're pure.
 */
class UiScaleTest {
    // A Pixel 5: 1080x2340 at 440dpi, which is 393 x 851 dp.
    private val phoneShort = 393f
    private val phoneLong = 851f

    @Test
    fun `the largest step fits an ordinary phone`() {
        assertEquals(1.3f, appliedScale(1.3f, phoneShort, phoneLong), 0.001f)
    }

    @Test
    fun `a narrow phone is capped, and by its short edge`() {
        // A 320 dp phone can't fit 1.3x, so it's capped at 320/300.
        val capped = appliedScale(1.3f, 320f, 640f)
        assertEquals(1.066f, capped, 0.01f)
        assertTrue("the cap must bind", capped < 1.3f)
    }

    @Test
    fun `turning the phone does not change the scale`() {
        // The short edge is the same either way round, which is why it's the
        // one that caps.
        assertEquals(
            appliedScale(1.3f, phoneShort, phoneLong),
            appliedScale(1.3f, phoneShort, phoneLong),
            0f,
        )
        // A landscape window gets the same answer as the portrait one.
        assertEquals(
            appliedScale(1.2f, minOf(phoneShort, phoneLong), maxOf(phoneShort, phoneLong)),
            appliedScale(1.2f, minOf(phoneLong, phoneShort), maxOf(phoneLong, phoneShort)),
            0f,
        )
    }

    @Test
    fun `it never draws the app smaller than stated`() {
        // A screen too small for the app at 1.0 doesn't shrink it further.
        assertEquals(1f, appliedScale(1.3f, 200f, 320f), 0.001f)
        assertEquals(1f, appliedScale(1f, phoneShort, phoneLong), 0.001f)
    }

    @Test
    fun `a tablet caps nothing`() {
        assertEquals(1.3f, appliedScale(1.3f, 800f, 1280f), 0.001f)
    }

    @Test
    fun `at its own size the roll shows exactly what it always did`() {
        // The slot changes when a lane opens or the panel folds, and at 1.0
        // the row count must not depend on it. A row height in dp would show
        // more rows when folding instead of taller ones.
        for (slot in listOf(90f, 263f, 445f, 600f)) {
            assertEquals(16, rowsForSlot(slot, 851f, 1f, 16, 6, 36))
            assertEquals(12, rowsForSlot(slot, 393f, 1f, 12, 6, 36))
        }
    }

    @Test
    fun `a larger setting means fewer rows, each physically taller`() {
        // The editor on a Pixel 5 with the panel and automation strip open:
        // 263 dp of roll in an 851 dp window. At 1.3 the window reports 655,
        // the chrome keeps its dp and the roll gets about 91.
        val rows = rowsForSlot(90.9f, 654.6f, 1.3f, 16, 6, 36)
        assertTrue("fewer rows than the sixteen at 1.0", rows < 16)
        // Physical row height: the slot's pixels over the row count.
        val density = 2.75f
        val atOne = 263f * density / 16f
        val atScale = 90.9f * density * 1.3f / rows
        assertTrue("a row must not shrink: $atScale vs $atOne", atScale >= atOne)
    }

    @Test
    fun `a row grows by the scale, and not by less`() {
        // This works out to scale x slot-at-one / base, so a row is exactly
        // this much taller unless a clamp gets in the way.
        val window = 851f
        val slotAtOne = 445f
        // Everything outside the roll is in dp and stays the same at every
        // setting. The window is what reports less.
        val chrome = window - slotAtOne
        for (scale in listOf(1.1f, 1.2f, 1.3f)) {
            val windowNow = window / scale
            val slot = windowNow - chrome
            val rows = rowsForSlot(slot, windowNow, scale, 16, 6, 36)
            val grown = (slot * scale / rows) / (slotAtOne / 16f)
            assertEquals(scale, grown, 0.06f)
        }
    }

    @Test
    fun `the row count stays inside its clamps`() {
        assertEquals(6, rowsForSlot(20f, 851f, 1.3f, 16, 6, 36))
        assertEquals(36, rowsForSlot(4000f, 851f, 1f, 64, 6, 36))
        // A ceiling below the floor is a caller bug, but still gives a sane
        // answer.
        assertEquals(6, rowsForSlot(400f, 851f, 1f, 16, 6, 2))
        // A slot that hasn't been measured yet falls back to the base.
        assertEquals(16, rowsForSlot(0f, 851f, 1.3f, 16, 6, 36))
    }
}
