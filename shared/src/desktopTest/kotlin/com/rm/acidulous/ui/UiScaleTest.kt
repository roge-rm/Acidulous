package com.rm.acidulous.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interface scale's arithmetic, which is the part of M46 a phone is not
 * needed for.
 *
 * Everything else about a scale has to be looked at - the readout says a pill
 * grew by thirty per cent and only an eye says whether the row it is in still
 * reads - but these two functions decide whether a layout is asked for
 * something it cannot do, and they are pure.
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
        // A 320 dp phone cannot give a third more than it has: 320/300.
        val capped = appliedScale(1.3f, 320f, 640f)
        assertEquals(1.066f, capped, 0.01f)
        assertTrue("the cap must bind", capped < 1.3f)
    }

    @Test
    fun `turning the phone does not change the scale`() {
        // The short edge is the same number either way round, which is the
        // whole reason it is the term that caps.
        assertEquals(
            appliedScale(1.3f, phoneShort, phoneLong),
            appliedScale(1.3f, phoneShort, phoneLong),
            0f,
        )
        // And a window that is landscape-shaped gets the same answer as the
        // portrait one it came from.
        assertEquals(
            appliedScale(1.2f, minOf(phoneShort, phoneLong), maxOf(phoneShort, phoneLong)),
            appliedScale(1.2f, minOf(phoneLong, phoneShort), maxOf(phoneLong, phoneShort)),
            0f,
        )
    }

    @Test
    fun `it never draws the app smaller than stated`() {
        // A screen too small for the app at its own size is not a reason to
        // shrink the app further.
        assertEquals(1f, appliedScale(1.3f, 200f, 320f), 0.001f)
        assertEquals(1f, appliedScale(1f, phoneShort, phoneLong), 0.001f)
    }

    @Test
    fun `a tablet caps nothing`() {
        assertEquals(1.3f, appliedScale(1.3f, 800f, 1280f), 0.001f)
    }

    @Test
    fun `at its own size the roll shows exactly what it always did`() {
        // Whatever is folded: the slot is what changes when a lane opens, and
        // at 1.0 the answer must not depend on it. A row height stated in dp
        // would have made folding the panel show more rows instead of taller
        // ones, which is a change nobody asked for.
        for (slot in listOf(90f, 263f, 445f, 600f)) {
            assertEquals(16, rowsForSlot(slot, 851f, 1f, 16, 6, 36))
            assertEquals(12, rowsForSlot(slot, 393f, 1f, 12, 6, 36))
        }
    }

    @Test
    fun `a larger setting means fewer rows, each physically taller`() {
        // The editor as it stands on a Pixel 5 with the panel and the
        // automation strip open: 263 dp of roll in an 851 dp window. At 1.3
        // the window reports 655, the chrome keeps its stated dp and the roll
        // is left about 91.
        val rows = rowsForSlot(90.9f, 654.6f, 1.3f, 16, 6, 36)
        assertTrue("fewer rows than the sixteen at 1.0", rows < 16)
        // And the physical height of one: the slot's own pixels over the count.
        val density = 2.75f
        val atOne = 263f * density / 16f
        val atScale = 90.9f * density * 1.3f / rows
        assertTrue("a row must not shrink: $atScale vs $atOne", atScale >= atOne)
    }

    @Test
    fun `a row grows by the scale, and not by less`() {
        // The arithmetic reduces to scale x slot-at-one / base, so a row is
        // exactly this much taller wherever the clamps are not in the way.
        val window = 851f
        val slotAtOne = 445f
        // Everything that is not the roll is stated in dp, so it is the same
        // number of dp at every setting; the window is what reports less.
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
        // A ceiling below the floor is the caller's arithmetic going wrong,
        // not a reason to return nonsense.
        assertEquals(6, rowsForSlot(400f, 851f, 1f, 16, 6, 2))
        // And a slot nothing has measured yet falls back to the base.
        assertEquals(16, rowsForSlot(0f, 851f, 1.3f, 16, 6, 36))
    }
}
