package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Forage pad says it is holding.
 *
 * Written after slicing shipped and did nothing visible. The engine played
 * the slices correctly - a pad with no sample of its own reads the shared
 * file - but the pads are drawn from the document, and the document only
 * mentioned the shared file under a key nothing here looked at. Dan: "I
 * expect it to fill all the pads. Nothing changes visually to indicate that
 * has happened."
 */
class ForagePadsTest {

    private fun pads(settings: Map<String, String>) = MachineUi.voicesOf("Forage", settings)

    @Test fun `a fresh track has thirteen empty pads`() {
        val v = pads(emptyMap())
        assertEquals(13, v.size)
        assertTrue("no pad should claim to be loaded", v.none { it.loaded })
        assertTrue("an empty pad invites a sample", v.all { it.short == "+" })
    }

    @Test fun `a pad with its own sample is named after it`() {
        val v = pads(mapOf("p02_sample" to "samples/Rimshot.wav"))
        assertTrue(v[2].loaded)
        assertEquals("Rimshot", v[2].name)
        assertFalse("the others are untouched", v[3].loaded)
    }

    @Test fun `slicing fills the pads it covers`() {
        val v = pads(mapOf("slice_sample" to "samples/break.wav", "slice_count" to "13"))
        assertTrue("every pad plays a piece of the break", v.all { it.loaded })
        assertEquals("break 1", v[0].name)
        assertEquals("break 13", v[12].name)
        assertEquals("1", v[0].short)
    }

    @Test fun `slicing into eight leaves the rest empty`() {
        val v = pads(mapOf("slice_sample" to "samples/break.wav", "slice_count" to "8"))
        assertTrue("the eight it covers", (0 until 8).all { v[it].loaded })
        assertTrue("and no more", (8 until 13).none { v[it].loaded })
        assertEquals("+", v[8].short)
    }

    @Test fun `a pad keeps its own sample through a slice`() {
        val v = pads(
            mapOf(
                "slice_sample" to "samples/break.wav", "slice_count" to "13",
                "p04_sample" to "samples/MyKick.wav",
            ),
        )
        assertEquals("its own sample wins over the slice", "MyKick", v[4].name)
        assertEquals("break 4", v[3].name)
    }

    @Test fun `a slice source without a count fills nothing`() {
        // The file is chosen before the slice is applied, and between those
        // two the pads are still empty.
        val v = pads(mapOf("slice_sample" to "samples/break.wav"))
        assertTrue(v.none { it.loaded })
    }
}
