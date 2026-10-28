package com.rm.acidulous.desktop

import com.rm.acidulous.midi.MidiOpenDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * CoreMIDI against a loopback made in CoreMIDI itself: a virtual source and
 * destination called "Acidulous Loop", where what reaches the destination
 * comes back from the source. macOS only; skipped elsewhere.
 */
class CoreMidiTest {
    companion object {
        private var midi: CoreMidi? = null

        @BeforeClass @JvmStatic
        fun openLoop() {
            if (!onMac) return
            midi = CoreMidi.open()
            check(midi != null) { "CoreMIDI did not open" }
            check(CoreMidiNative.nativeLoopback("Acidulous Loop")) { "no loopback" }
        }
    }

    private fun loop(): CoreMidi {
        assumeTrue("macOS only", onMac)
        return midi!!
    }

    private fun open(midi: CoreMidi): MidiOpenDevice {
        // A new endpoint can take a moment to show in the list.
        var desc = midi.devices.firstOrNull { it.name == "Acidulous Loop" }
        for (i in 0 until 20) {
            if (desc != null) break
            Thread.sleep(100)
            desc = midi.devices.firstOrNull { it.name == "Acidulous Loop" }
        }
        assertNotNull("the loopback isn't listed: ${midi.devices}", desc)
        val latch = CountDownLatch(1)
        var opened: MidiOpenDevice? = null
        midi.openDevice(desc!!) { opened = it; latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        return opened!!
    }

    /** Everything heard within [ms], joined, since CoreMIDI may merge packets. */
    private fun drain(heard: LinkedBlockingQueue<ByteArray>, bytes: Int, ms: Long = 2000): ByteArray {
        val out = ByteArrayOutputStream()
        val until = System.currentTimeMillis() + ms
        while (out.size() < bytes && System.currentTimeMillis() < until) {
            heard.poll(50, TimeUnit.MILLISECONDS)?.let { out.write(it) }
        }
        return out.toByteArray()
    }

    @Test fun `the loopback's two ends are one device, with a port each way`() {
        val loop = loop().devices.single { it.name == "Acidulous Loop" }
        assertEquals(1, loop.inputPortCount)
        assertEquals(1, loop.outputPortCount)
    }

    @Test fun `what is sent comes back, byte for byte`() {
        val device = open(loop())
        val heard = LinkedBlockingQueue<ByteArray>()
        device.connectOutputPort(0) { b, off, n, _ -> heard += b.copyOfRange(off, off + n) }
        val port = device.openInputPort(0)!!
        port.send(byteArrayOf(0x90.toByte(), 60, 100), 0, 3)
        port.send(byteArrayOf(0xc3.toByte(), 5), 0, 2)
        port.send(byteArrayOf(0xf8.toByte()), 0, 1)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 100, 0xc3.toByte(), 5, 0xf8.toByte()), drain(heard, 6))
        device.close()
    }

    @Test fun `a timestamped send goes at its time, not before`() {
        val device = open(loop())
        val arrived = LinkedBlockingQueue<Long>()
        device.connectOutputPort(0) { _, _, _, _ -> arrived += System.nanoTime() }
        val at = System.nanoTime() + 80_000_000L
        device.openInputPort(0)!!.send(byteArrayOf(0x80.toByte(), 60, 0), 0, 3, at)
        val got = arrived.poll(2, TimeUnit.SECONDS)!!
        assertTrue("arrived ${(at - got) / 1_000_000} ms early", got >= at - 2_000_000L)
        device.close()
    }

    @Test fun `a long sysex goes whole`() {
        val device = open(loop())
        val heard = LinkedBlockingQueue<ByteArray>()
        device.connectOutputPort(0) { b, off, n, _ -> heard += b.copyOfRange(off, off + n) }
        val sysex = byteArrayOf(0xf0.toByte()) + ByteArray(10000) { (it % 128).toByte() } + byteArrayOf(0xf7.toByte())
        device.openInputPort(0)!!.send(sysex, 0, sysex.size)
        assertArrayEquals(sysex, drain(heard, sysex.size))
        device.close()
    }
}
