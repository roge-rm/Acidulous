package com.rm.acidulous.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlsaSeqMidiTest {
    private val both = AlsaSeq.CAP_READ or AlsaSeq.CAP_SUBS_READ or AlsaSeq.CAP_WRITE or AlsaSeq.CAP_SUBS_WRITE
    private val readOnly = AlsaSeq.CAP_READ or AlsaSeq.CAP_SUBS_READ
    private val user = 1
    private val kernel = AlsaSeq.KERNEL_CLIENT

    /** Roughly what `aconnect -l` shows with a pad controller, a software synth, an on-screen keyboard and this app. */
    private val ports = listOf(
        SeqPort(0, 0, readOnly, kernel, -1, "System", "Timer"),
        SeqPort(0, 1, readOnly, kernel, -1, "System", "Announce"),
        SeqPort(14, 0, both, kernel, -1, "Midi Through", "Midi Through Port-0"),
        SeqPort(24, 0, both, kernel, 2, "Pad Controller", "Pad Controller MIDI 1"),
        SeqPort(24, 1, both, kernel, 2, "Pad Controller", "Pad Controller MIDI 2"),
        SeqPort(24, 2, both, kernel, 2, "Pad Controller", "Pad Controller MIDI 3"),
        SeqPort(128, 0, AlsaSeq.CAP_WRITE or AlsaSeq.CAP_SUBS_WRITE, user, -1, "Soft Synth", "input"),
        SeqPort(129, 0, readOnly, user, -1, "Screen Keyboard", "out"),
        SeqPort(130, 0, both or AlsaSeq.CAP_NO_EXPORT, user, -1, "Acidulous", "in"),
        SeqPort(131, 0, both, user, -1, "PipeWire-System", "input"),
        SeqPort(132, 0, AlsaSeq.CAP_READ, user, -1, "Private", "not subscribable"),
    )

    @Test
    fun instrumentsOnly() {
        val devices = seqDevices(ports, ownClient = 130)
        assertEquals(listOf("Pad Controller", "Soft Synth", "Screen Keyboard"), devices.map { it.desc.name })
    }

    @Test
    fun portsBothWays() {
        val (pads, synth, keyboard) = seqDevices(ports, ownClient = 130)
        assertEquals(listOf(0, 1, 2), pads.sources)
        assertEquals(listOf(0, 1, 2), pads.destinations)
        assertEquals(3, pads.desc.inputPortCount)
        assertEquals(3, pads.desc.outputPortCount)
        // A synth only receives and a keyboard only sends.
        assertEquals(1 to 0, synth.desc.inputPortCount to synth.desc.outputPortCount)
        assertEquals(0 to 1, keyboard.desc.inputPortCount to keyboard.desc.outputPortCount)
    }

    @Test
    fun hardwareIsUsb() {
        val (pads, synth) = seqDevices(ports, ownClient = 130)
        assertTrue(pads.desc.usb)
        assertFalse(synth.desc.usb)
    }

    @Test
    fun idsFollowTheNameNotTheClientNumber() {
        val before = seqDevices(ports, ownClient = 130).first()
        val replugged = seqDevices(ports.map { if (it.client == 24) it.copy(client = 28) else it }, ownClient = 130).first()
        assertEquals(before.desc.id, replugged.desc.id)
        assertEquals(28, replugged.client)
    }

    @Test
    fun twoOfOneNameAreTwo() {
        val twins = listOf(
            SeqPort(24, 0, both, kernel, 2, "Keys 25", "Keys 25 MIDI 1"),
            SeqPort(28, 0, both, kernel, 3, "Keys 25", "Keys 25 MIDI 1"),
        )
        val (a, b) = seqDevices(twins, ownClient = 130)
        assertNotEquals(a.desc.id, b.desc.id)
    }
}
