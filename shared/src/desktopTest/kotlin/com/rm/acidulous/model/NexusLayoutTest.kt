package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fit button's arranging: that it follows the signal, that it takes the
 * window's shape, and that nothing lands on anything else.
 */
class NexusLayoutTest {
    private val w = 150f
    private val h = 92f

    /** [n] modules in a line, each feeding the next. */
    private fun chain(n: Int) = NexusPatch(
        modules = (0 until n).map { NexusModule(it, "filter", x = it * 400f, y = 0f) },
        cables = (0 until n - 1).map { NexusCable(it, 0, it + 1, 0) },
    )

    private fun bounds(p: NexusPatch): Pair<Float, Float> =
        (p.modules.maxOf { it.x } + w - p.modules.minOf { it.x }) to (p.modules.maxOf { it.y } + h - p.modules.minOf { it.y })

    private fun assertNoOverlaps(p: NexusPatch) {
        for (a in p.modules) for (b in p.modules) {
            if (a.slot >= b.slot) continue
            val apart = a.x + w <= b.x || b.x + w <= a.x || a.y + h <= b.y || b.y + h <= a.y
            assertTrue("${a.slot} and ${b.slot} overlap", apart)
        }
    }

    @Test
    fun aLongChainWrapsOnAnUprightScreenAndNotOnATurnedOne() {
        val upright = chain(8).arranged(0.6f, w, h)
        val turned = chain(8).arranged(2.2f, w, h)
        val (uw, uh) = bounds(upright)
        val (tw, th) = bounds(turned)
        assertTrue("upright should be taller than wide: $uw x $uh", uh > uw)
        assertTrue("turned should be wider than tall: $tw x $th", tw > th)
        assertNoOverlaps(upright)
        assertNoOverlaps(turned)
    }

    @Test
    fun theSignalReadsLeftToRightWithinABand() {
        val p = chain(4).arranged(3f, w, h)
        val xs = (0 until 4).map { s -> p.moduleAt(s)!!.x }
        assertEquals(xs.sorted(), xs)
        assertEquals("one band", 1, p.modules.map { it.y }.distinct().size)
    }

    @Test
    fun theOutputIsInTheLastColumnAndAModulatorBeforeWhatItMoves() {
        // osc -> filter -> out, lfo modulating the osc->filter cable, env on a spare path to out.
        val p = NexusPatch(
            modules = listOf(
                NexusModule(0, "osc"), NexusModule(1, "filter"), NexusModule(2, "out"),
                NexusModule(3, "lfo"), NexusModule(4, "env"),
            ),
            cables = listOf(
                NexusCable(0, 0, 1, 0, modSlot = 3, modPort = 0, modAmount = 0.5f),
                NexusCable(1, 0, 2, 0),
                NexusCable(4, 0, 2, 1),
            ),
        )
        val cols = p.flowColumns()
        assertEquals(listOf(2), cols.last())
        val colOf = { s: Int -> cols.indexOfFirst { s in it } }
        assertTrue(colOf(3) < colOf(1))
        assertNoOverlaps(p.arranged(1f, w, h))
    }

    @Test
    fun feedbackDoesNotSendTheLoopOffForever() {
        val p = NexusPatch(
            modules = (0 until 3).map { NexusModule(it, "delay") },
            cables = listOf(NexusCable(0, 0, 1, 0), NexusCable(1, 0, 2, 0), NexusCable(2, 0, 0, 0)),
        )
        val cols = p.flowColumns()
        assertEquals(3, cols.flatten().size)
        assertTrue(cols.size <= 3)
        assertNoOverlaps(p.arranged(1f, w, h))
    }

    @Test
    fun aFullPatchFitsEveryShape() {
        val p = NexusPatch(modules = (0 until NEXUS_SLOTS).map { NexusModule(it, "osc") })
        for (aspect in listOf(0.4f, 0.8f, 1f, 1.6f, 3f)) {
            val a = p.arranged(aspect, w, h)
            assertNoOverlaps(a)
            val (bw, bh) = bounds(a)
            // Near the window's shape: within a factor of two either way.
            val shape = bw / bh / aspect
            assertTrue("aspect $aspect gave $bw x $bh", shape in 0.5f..2f)
        }
    }

    @Test
    fun positionsAreOnTheDragGridAndTheCablesUntouched() {
        val p = chain(5)
        val a = p.arranged(1f, w, h)
        assertEquals(p.cables, a.cables)
        assertEquals(p.topology(), a.topology())
        for (m in a.modules) {
            assertEquals(0f, m.x % 10f, 0f)
            assertEquals(0f, m.y % 10f, 0f)
        }
    }

    @Test
    fun aPatchWithNoPlacesIsLaidOutAlongItsSignal() {
        // Out is slot 0 here, so slot order would have put it first.
        val p = NexusPatch.decode("v|1\nm|0|out|mono\nm|1|voice\nm|2|osc\nm|3|vca\nc|1.0|2.0|1.0\nc|2.0|3.0|1.0\nc|3.0|0.0|1.0")
        assertNoOverlaps(p)
        val x = { s: Int -> p.moduleAt(s)!!.x }
        assertTrue("out should be right of the vca", x(0) > x(3) || p.moduleAt(0)!!.y > p.moduleAt(3)!!.y)
        assertEquals(p.flowColumns().last(), listOf(0))
        // Places given are kept.
        val kept = NexusPatch.decode("v|1\nm|0|out|mono\np|00|500|40")
        assertEquals(500f, kept.moduleAt(0)!!.x, 0f)
    }
}
