package com.rm.acidulous.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private val seen = mutableListOf<Triple<Int, Int, Int>>()

    /** Realtime bytes go to their own callback, with the arrival stamp. */
    private val realtime = mutableListOf<Triple<Int, Int, Int>>()
    private val stamps = mutableListOf<Long>()

    private val parser = MidiParser(
        onMessage = { s, a, b -> seen += Triple(s, a, b) },
        onRealtime = { s, a, b, stamp -> realtime += Triple(s, a, b); stamps += stamp },
    )

    private fun feed(vararg bytes: Int, stamp: Long = 0L) =
        parser.parse(bytes.map { it.toByte() }.toByteArray(), 0, bytes.size, stamp)

    @Test fun `a note on and off`() {
        feed(0x90, 60, 100, 0x80, 60, 0)
        assertEquals(listOf(Triple(0x90, 60, 100), Triple(0x80, 60, 0)), seen)
    }

    @Test fun `running status repeats the last one`() {
        feed(0x90, 60, 100, 62, 100, 64, 0)
        assertEquals(listOf(Triple(0x90, 60, 100), Triple(0x90, 62, 100), Triple(0x90, 64, 0)), seen)
    }

    @Test fun `a message split across two reads`() {
        feed(0x90, 60)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), seen)
        feed(100)
        assertEquals(listOf(Triple(0x90, 60, 100)), seen)
    }

    @Test fun `clock in the middle of a message does not break it`() {
        feed(0x90, 0xf8, 60, 0xf8, 100)
        assertEquals(listOf(Triple(0x90, 60, 100)), seen)
        // ...and the clocks are still delivered, which is what M32 needs:
        // the parser used to drop every realtime byte on the floor.
        assertEquals(listOf(Triple(0xf8, 0, 0), Triple(0xf8, 0, 0)), realtime)
    }

    @Test fun `realtime bytes carry the arrival stamp`() {
        feed(0xf8, 0xfa, 0xfc, stamp = 1234L)
        assertEquals(listOf(Triple(0xf8, 0, 0), Triple(0xfa, 0, 0), Triple(0xfc, 0, 0)), realtime)
        assertEquals(listOf(1234L, 1234L, 1234L), stamps)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), seen)
    }

    @Test fun `song position is realtime, and takes its two data bytes`() {
        // 0xf2 is the one non-realtime message the realtime path wants, so
        // it collects both bytes before it goes out.
        feed(0xf2, 0x10, 0x00)
        assertEquals(listOf(Triple(0xf2, 0x10, 0x00)), realtime)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), seen)
    }

    @Test fun `song position does not leave running status behind it`() {
        feed(0x90, 60, 100, 0xf2, 0x10, 0x00, 62, 100)
        assertEquals(listOf(Triple(0x90, 60, 100)), seen)
    }

    @Test fun `one byte messages`() {
        feed(0xd0, 64, 0xc0, 7)
        assertEquals(listOf(Triple(0xd0, 64, 0), Triple(0xc0, 7, 0)), seen)
    }

    @Test fun `pitch bend carries both bytes`() {
        feed(0xe0, 0x00, 0x40)
        assertEquals(listOf(Triple(0xe0, 0x00, 0x40)), seen)
    }

    @Test fun `sysex is skipped whole`() {
        feed(0xf0, 0x7e, 0x7f, 0x06, 0x01, 0xf7, 0x90, 60, 100)
        assertEquals(listOf(Triple(0x90, 60, 100)), seen)
    }

    @Test fun `running status does not survive sysex`() {
        feed(0x90, 60, 100, 0xf0, 0x01, 0xf7, 62, 100)
        assertEquals(listOf(Triple(0x90, 60, 100)), seen)
    }
}
