package com.rm.acidulous.desktop

import com.rm.acidulous.midi.MidiOpenDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter
import javax.sound.midi.spi.MidiDeviceProvider

/**
 * The desktop's MIDI adapter against a loopback: what is sent to "Loop" comes
 * back from it. Registered as a Java Sound provider (see
 * META-INF/services), the way a real driver is, and listed - as Java Sound
 * lists every device - as two entries of one name, one each way.
 */
class JavaSoundMidiTest {
    private fun open(midi: JavaSoundMidi): MidiOpenDevice {
        val desc = midi.devices.first { it.name == "Loop" }
        val latch = CountDownLatch(1)
        var opened: MidiOpenDevice? = null
        midi.openDevice(desc) { opened = it; latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        return opened!!
    }

    @Test fun `a device's two entries are one device, with a port each way`() {
        val loop = JavaSoundMidi().devices.single { it.name == "Loop" }
        assertEquals(1, loop.inputPortCount)
        assertEquals(1, loop.outputPortCount)
    }

    @Test fun `what is sent comes back, byte for byte`() {
        val device = open(JavaSoundMidi())
        val heard = LinkedBlockingQueue<ByteArray>()
        device.connectOutputPort(0) { b, off, n, _ -> heard += b.copyOfRange(off, off + n) }
        val port = device.openInputPort(0)
        assertNotNull(port)
        port!!
        port.send(byteArrayOf(0x90.toByte(), 60, 100), 0, 3)
        port.send(byteArrayOf(0xc3.toByte(), 5), 0, 2)
        port.send(byteArrayOf(0xf8.toByte()), 0, 1)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 100), heard.poll(1, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 5), heard.poll(1, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(0xf8.toByte()), heard.poll(1, TimeUnit.SECONDS))
        device.close()
    }

    @Test fun `a timestamped send goes at its time, not before`() {
        val device = open(JavaSoundMidi())
        val arrived = LinkedBlockingQueue<Long>()
        device.connectOutputPort(0) { _, _, _, _ -> arrived += System.nanoTime() }
        val at = System.nanoTime() + 80_000_000L
        device.openInputPort(0)!!.send(byteArrayOf(0x80.toByte(), 60, 0), 0, 3, at)
        val got = arrived.poll(2, TimeUnit.SECONDS)!!
        assertTrue("arrived ${(at - got) / 1_000_000} ms early", got >= at - 2_000_000L)
        device.close()
    }

    @Test fun `a sysex goes whole`() {
        val device = open(JavaSoundMidi())
        val heard = LinkedBlockingQueue<ByteArray>()
        device.connectOutputPort(0) { b, off, n, _ -> heard += b.copyOfRange(off, off + n) }
        val sysex = byteArrayOf(0xf0.toByte(), 0x00, 0x20, 0x29, 0x02, 0x0e, 0x0e, 0x01, 0xf7.toByte())
        device.openInputPort(0)!!.send(sysex, 0, sysex.size)
        assertArrayEquals(sysex, heard.poll(1, TimeUnit.SECONDS))
        device.close()
    }
}

/**
 * Two Java Sound devices named "Loop": one to send to, one that hears what was
 * sent. Java Sound makes a provider more than once and matches devices by the
 * identity of their Info, so everything here is one shared set, as a real
 * driver's is.
 */
class LoopbackProvider : MidiDeviceProvider() {
    override fun getDeviceInfo(): Array<MidiDevice.Info> = arrayOf(Loopback.inInfo, Loopback.outInfo)
    override fun getDevice(info: MidiDevice.Info): MidiDevice = if (info === Loopback.inInfo) Loopback.inDevice else Loopback.outDevice
}

private object Loopback {
    class Info(description: String) : MidiDevice.Info("Loop", "Test", description, "1")

    val inInfo = Info("loopback in")
    val outInfo = Info("loopback out")
    val listeners = mutableListOf<Receiver>()

    class Loop(private val info: Info, private val sends: Boolean) : MidiDevice {
        private var open = false
        override fun getDeviceInfo() = info
        override fun open() { open = true }
        override fun close() { open = false }
        override fun isOpen() = open
        override fun getMicrosecondPosition() = -1L
        override fun getMaxReceivers() = if (sends) 0 else -1
        override fun getMaxTransmitters() = if (sends) -1 else 0
        override fun getReceiver(): Receiver = object : Receiver {
            override fun send(message: MidiMessage, timeStamp: Long) = synchronized(listeners) { listeners.toList() }.forEach { it.send(message, -1) }
            override fun close() {}
        }
        override fun getReceivers(): List<Receiver> = emptyList()
        override fun getTransmitter(): Transmitter = object : Transmitter {
            private var to: Receiver? = null
            override fun setReceiver(receiver: Receiver?) {
                synchronized(listeners) { to?.let { listeners -= it }; to = receiver; receiver?.let { listeners += it } }
            }
            override fun getReceiver() = to
            override fun close() { synchronized(listeners) { to?.let { listeners -= it } } }
        }
        override fun getTransmitters(): List<Transmitter> = emptyList()
    }

    val inDevice = Loop(inInfo, sends = true)
    val outDevice = Loop(outInfo, sends = false)
}
