package com.rm.acidulous.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zone setting called auto: what a controller says about itself, and
 * what it gives away by how it plays. Channels are 0-based here.
 */
class MpeAutoTest {
    /** RPN [msb]/[lsb] set to [value] on [channel], as a controller sends it. */
    private fun MpeAuto.rpn(channel: Int, msb: Int, lsb: Int, value: Int, fine: Int? = null): List<Boolean> =
        listOfNotNull(
            controller(channel, 101, msb),
            controller(channel, 100, lsb),
            controller(channel, 6, value),
            fine?.let { controller(channel, 38, it) },
        )

    @Test
    fun `the configuration message on channel 1 is a lower zone`() {
        val a = MpeAuto()
        assertTrue(a.rpn(0, 0, 6, 10).all { it })
        assertEquals(MpeZone.LOWER, a.zone)
        assertEquals(10, a.members)
        assertTrue(a.fromConfig)
        assertEquals(48f, a.bendSemis, 0f)
    }

    @Test
    fun `on channel 16 it is an upper zone, and nought fingers turns it off`() {
        val a = MpeAuto()
        a.rpn(15, 0, 6, 7)
        assertEquals(MpeZone.UPPER, a.zone)
        assertEquals(7, a.members)
        a.rpn(15, 0, 6, 0)
        assertEquals(MpeZone.OFF, a.zone)
    }

    @Test
    fun `a finger's bend range is taken, with its cents`() {
        val a = MpeAuto()
        a.rpn(0, 0, 6, 15)
        a.rpn(1, 0, 0, 24, fine = 50)
        assertEquals(24.5f, a.bendSemis, 0.001f)
    }

    @Test
    fun `the master channel's bend range is the track's, not the fingers'`() {
        val a = MpeAuto()
        a.rpn(0, 0, 6, 15)
        a.rpn(0, 0, 0, 2)
        assertEquals(48f, a.bendSemis, 0f)
    }

    @Test
    fun `two fingers down on channels of their own switch on a lower zone`() {
        val a = MpeAuto()
        a.noteOn(1, recognise = true)
        assertEquals(MpeZone.OFF, a.zone)
        a.noteOn(2, recognise = true)
        assertTrue(a.changed)
        assertEquals(MpeZone.LOWER, a.zone)
        assertFalse(a.fromConfig)
    }

    @Test
    fun `a keyboard on one channel is never taken for fingers`() {
        val a = MpeAuto()
        // Chords on channel 2, and on channel 1: many notes, one channel.
        repeat(4) { a.noteOn(1, recognise = true) }
        repeat(4) { a.noteOn(0, recognise = true) }
        assertEquals(MpeZone.OFF, a.zone)
        // One after another on different channels is not two at once.
        val b = MpeAuto()
        b.noteOn(3, recognise = true); b.noteOff(3)
        b.noteOn(4, recognise = true)
        assertEquals(MpeZone.OFF, b.zone)
    }

    @Test
    fun `recognising is only for auto`() {
        val a = MpeAuto()
        a.noteOn(1, recognise = false)
        a.noteOn(2, recognise = false)
        assertEquals(MpeZone.OFF, a.zone)
    }

    @Test
    fun `other controllers pass, and an NRPN deselects`() {
        val a = MpeAuto()
        assertFalse(a.controller(1, 74, 64))
        assertFalse(a.controller(1, 1, 100))
        a.controller(0, 101, 0); a.controller(0, 100, 6)
        a.controller(0, 99, 1) // NRPN select
        assertFalse(a.controller(0, 6, 12))
        assertEquals(MpeZone.OFF, a.zone)
    }

    @Test
    fun `forgetting goes back to having heard nothing`() {
        val a = MpeAuto()
        a.rpn(0, 0, 6, 15)
        a.rpn(1, 0, 0, 24)
        a.forget()
        assertEquals(MpeZone.OFF, a.zone)
        assertEquals(48f, a.bendSemis, 0f)
        assertFalse(a.fromConfig)
    }
}
