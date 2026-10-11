package com.rm.acidulous.desktop

import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_HAT0X
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_HAT0Y
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_HAT1X
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_HAT2Y
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_RX
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_RY
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_RZ
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_X
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_Y
import com.rm.acidulous.desktop.PadTranslator.Companion.ABS_Z
import com.rm.acidulous.desktop.PadTranslator.Companion.EV_ABS
import com.rm.acidulous.desktop.PadTranslator.Companion.EV_KEY
import com.rm.acidulous.desktop.PadTranslator.Companion.EV_SYN
import com.rm.acidulous.ui.KeyCodes
import com.rm.acidulous.ui.Pad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvdevTest {
    private class Recorder : PadSink {
        val buttons = ArrayList<Pair<Int, Boolean>>()
        var axes = emptyList<Pad.Axis>()
        override fun button(device: String, keyCode: Int, down: Boolean) { buttons += keyCode to down }
        override fun moved(device: String, axes: List<Pad.Axis>) { this.axes = axes }
        fun axis(name: String) = axes.first { it.name == name }.value
    }

    private val xbox = listOf(
        AxisInfo(ABS_X, -32768, 32767, 128), AxisInfo(ABS_Y, -32768, 32767, 128),
        AxisInfo(ABS_RX, -32768, 32767, 128), AxisInfo(ABS_RY, -32768, 32767, 128),
        AxisInfo(ABS_Z, 0, 255, 0), AxisInfo(ABS_RZ, 0, 255, 0),
        AxisInfo(ABS_HAT0X, -1, 1, 0), AxisInfo(ABS_HAT0Y, -1, 1, 0),
    )

    @Test fun `an Xbox-style pad's buttons are Android's`() {
        val r = Recorder()
        val t = PadTranslator("Microsoft X-Box 360 pad", xbox, r)
        t.event(EV_KEY, 0x130, 1) // A down
        t.event(EV_KEY, 0x130, 2) // auto-repeat, ignored
        t.event(EV_KEY, 0x130, 0)
        t.event(EV_KEY, 0x13b, 1) // Start
        assertEquals(
            listOf(KeyCodes.KEYCODE_BUTTON_A to true, KeyCodes.KEYCODE_BUTTON_A to false, KeyCodes.KEYCODE_BUTTON_START to true),
            r.buttons,
        )
    }

    @Test fun `its hat is the d-pad's keys`() {
        val r = Recorder()
        val t = PadTranslator("pad", xbox, r)
        t.event(EV_ABS, ABS_HAT0X, -1)
        t.event(EV_ABS, ABS_HAT0X, 1)
        t.event(EV_ABS, ABS_HAT0X, 0)
        t.event(EV_ABS, ABS_HAT0Y, -1)
        assertEquals(
            listOf(
                KeyCodes.KEYCODE_DPAD_LEFT to true, KeyCodes.KEYCODE_DPAD_LEFT to false,
                KeyCodes.KEYCODE_DPAD_RIGHT to true, KeyCodes.KEYCODE_DPAD_RIGHT to false,
                KeyCodes.KEYCODE_DPAD_UP to true,
            ),
            r.buttons,
        )
    }

    @Test fun `sticks are -1 to 1 and its Z and RZ are triggers`() {
        val r = Recorder()
        val t = PadTranslator("pad", xbox, r)
        t.event(EV_ABS, ABS_X, 32767)
        t.event(EV_ABS, ABS_RY, -32768)
        t.event(EV_ABS, ABS_Z, 255)
        t.event(EV_SYN, 0, 0)
        assertEquals(1f, r.axis("X"), 0.001f)
        assertEquals(-1f, r.axis("RY"), 0.001f)
        assertEquals(1f, r.axis("LTRIGGER"), 0.001f)
        assertEquals(0f, r.axis("RTRIGGER"), 0.001f)
        assertTrue(r.axes.none { it.name == "Z" })
    }

    @Test fun `a stick of 0 to 255 rests at 0`() {
        val r = Recorder()
        val t = PadTranslator("old pad", listOf(AxisInfo(ABS_X, 0, 255, 15), AxisInfo(ABS_Z, 0, 255, 15)), r)
        t.event(EV_ABS, ABS_X, 128)
        t.event(EV_ABS, ABS_Z, 0)
        t.event(EV_SYN, 0, 0)
        assertEquals(0f, r.axis("X"), 0.01f)
        // No RX, so Z is the right stick, as on Android.
        assertEquals(-1f, r.axis("Z"), 0.01f)
    }

    @Test fun `the Deck's hats are its touchpads and its triggers`() {
        val deck = listOf(
            AxisInfo(ABS_X, -32767, 32767, 0), AxisInfo(ABS_Y, -32767, 32767, 0),
            AxisInfo(ABS_HAT0X, -32767, 32767, 0), AxisInfo(ABS_HAT0Y, -32767, 32767, 0),
            AxisInfo(ABS_HAT1X, -32767, 32767, 0), AxisInfo(ABS_HAT2Y, 0, 32767, 0),
        )
        val r = Recorder()
        val t = PadTranslator("Steam Deck", deck, r)
        t.event(EV_ABS, ABS_HAT0X, 32767)
        t.event(EV_ABS, ABS_HAT1X, -32767)
        t.event(EV_ABS, ABS_HAT2Y, 32767)
        t.event(EV_SYN, 0, 0)
        assertTrue(r.buttons.isEmpty())
        assertEquals(1f, r.axis("LPAD_X"), 0.001f)
        assertEquals(-1f, r.axis("RPAD_X"), 0.001f)
        assertEquals(1f, r.axis("LTRIGGER"), 0.001f)
    }

    @Test fun `the reader takes Linux's own event layout`() {
        // Three input_events as the kernel writes them on 64-bit Linux:
        // a 16 byte time, then type, code (16 bits each) and value (32).
        val file = File.createTempFile("events", ".bin")
        try {
            val b = ByteBuffer.allocate(24 * 3).order(ByteOrder.nativeOrder())
            for ((type, code, value) in listOf(Triple(1, 0x130, 1), Triple(3, 0, -12345), Triple(0, 0, 0))) {
                b.putLong(1L).putLong(2L).putShort(type.toShort()).putShort(code.toShort()).putInt(value)
            }
            file.writeBytes(b.array())
            val fd = Evdev.nativeOpen(file.path)
            assertTrue(fd >= 0)
            val out = IntArray(64 * 3)
            val n = Evdev.nativeRead(fd, out, 100)
            Evdev.nativeClose(fd)
            assertEquals(3, n)
            assertEquals(listOf(1, 0x130, 1, 3, 0, -12345, 0, 0, 0), out.take(9))
        } finally {
            file.delete()
        }
    }

    private fun deckReport(build: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 1); b.put(2, 9)
        b.build()
        return b.array()
    }

    @Test fun `the Deck's report gives its buttons, sticks, triggers and touchpads`() {
        val r = Recorder()
        val deck = DeckDecoder(r)
        deck.report(deckReport {
            put(8, 0x80.toByte()) // A
            put(13, 0x02) // L4
            put(10, 0x10) // the right pad touched
            putShort(48, 32767) // left stick right
            putShort(50, 32767) // and up
            putShort(46, 16384) // right trigger half way
            putShort(20, -32767) // right pad at its left edge
        })
        assertEquals(listOf(KeyCodes.KEYCODE_BUTTON_A to true, KeyCodes.KEYCODE_BUTTON_1 to true), r.buttons)
        assertEquals(1f, r.axis("X"), 0.001f)
        assertEquals(-1f, r.axis("Y"), 0.001f)
        assertEquals(0.5f, r.axis("RTRIGGER"), 0.01f)
        assertEquals(-1f, r.axis("RPAD_X"), 0.001f)
        // The left pad isn't touched, so it reads 0 whatever the report says.
        assertEquals(0f, r.axis("LPAD_X"), 0f)
        r.buttons.clear()
        deck.report(deckReport { })
        assertEquals(listOf(KeyCodes.KEYCODE_BUTTON_A to false, KeyCodes.KEYCODE_BUTTON_1 to false), r.buttons)
        // Other reports, like the Deck's settings, are left alone.
        r.buttons.clear()
        deck.report(ByteArray(64).also { it[0] = 1; it[2] = 3; it[8] = 0x80.toByte() })
        assertTrue(r.buttons.isEmpty())
    }
}
