package com.rm.acidulous.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which arriving channels are fingers.
 *
 * Off by one here is the whole feature failing quietly: a zone that thinks
 * its master channel is a member will treat the controller's own bends as a
 * note's, and one that stops a channel short will drop the last finger.
 * Channels are 0-based in the code and 1-based everywhere a musician reads
 * them, which is the other way this goes wrong.
 */
class MpeRoutingTest {

    private fun member(zone: Int, members: Int, channel: Int): Boolean =
        MpeZone.member(zone, members, channel)

    @Test
    fun `with no zone nothing is a finger`() {
        for (c in 0..15) assertFalse("channel $c", member(0, 15, c))
    }

    @Test
    fun `the lower zone keeps channel 1 for itself`() {
        // Master channel 1 carries what belongs to the whole zone, so it is
        // never a finger; 2 to 16 are.
        assertFalse("ch 1 is the master", member(1, 15, 0))
        for (c in 1..15) assertTrue("ch ${c + 1}", member(1, 15, c))
    }

    @Test
    fun `the upper zone keeps channel 16 for itself`() {
        assertFalse("ch 16 is the master", member(2, 15, 15))
        for (c in 0..14) assertTrue("ch ${c + 1}", member(2, 15, c))
    }

    @Test
    fun `a smaller zone claims fewer channels`() {
        // Four members of a lower zone are channels 2, 3, 4 and 5.
        for (c in 1..4) assertTrue("ch ${c + 1}", member(1, 4, c))
        for (c in 5..15) assertFalse("ch ${c + 1}", member(1, 4, c))
        // And of an upper zone, 15, 14, 13 and 12.
        for (c in 11..14) assertTrue("ch ${c + 1}", member(2, 4, c))
        for (c in 0..10) assertFalse("ch ${c + 1}", member(2, 4, c))
    }

    @Test
    fun `the settings are clamped to what a zone can mean`() {
        assertEquals(15, MpeZone.clampMembers(99))
        assertEquals(1, MpeZone.clampMembers(0))
        assertEquals(96f, MpeZone.clampBend(999f), 0f)
        assertEquals(1f, MpeZone.clampBend(0f), 0f)
        assertEquals(MpeZone.UPPER, MpeZone.clampZone(7))
    }

    @Test
    fun `the default range is the one the specification asks for`() {
        assertEquals(48f, MpeZone.DEFAULT_BEND_SEMIS, 0f)
    }

    @Test
    fun `an out of range channel is never a finger`() {
        assertFalse(MpeZone.member(MpeZone.LOWER, 15, -1))
        assertFalse(MpeZone.member(MpeZone.LOWER, 15, 16))
    }
}
