package com.rm.acidulous.desktop

import com.rm.acidulous.midi.MidiBluetooth
import com.rm.acidulous.midi.MidiDeviceDesc
import com.rm.acidulous.midi.MidiOpenDevice
import com.rm.acidulous.midi.MidiSendPort
import com.rm.acidulous.midi.MidiSystem
import com.rm.acidulous.midi.MidiWorker
import com.rm.acidulous.util.Log
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.midi.SysexMessage
import javax.sound.midi.MidiSystem as JavaMidi

private const val TAG = "Acidulous.MIDI"

/**
 * MIDI on desktop through Java Sound. On Linux that's ALSA's raw MIDI
 * devices, which is where USB devices show up.
 *
 * Java Sound lists a device's input and output as two entries with the same
 * name. They're joined back together here, because the hub treats a
 * controller as one device with ports both ways, like Android does. Java
 * Sound doesn't report devices coming and going, so the list is re-read
 * every two seconds. It also sends immediately rather than at a set time, so
 * timestamped sends wait on the MIDI thread (see MidiThread).
 *
 * This is the fallback. When ALSA's sequencer opens, AlsaSeqMidi is used
 * instead, which sees these devices and other programs' ports too.
 */
class JavaSoundMidi(private val pollMs: Long = 2000) : MidiSystem {
    private val thread = MidiThread()

    override val supported: Boolean = true
    override val bluetooth: MidiBluetooth? = null
    override val worker: MidiWorker = thread

    /** A device's Java Sound entries: the one that sends to us, the one we send to, or both. */
    private class Entry(val desc: MidiDeviceDesc, val from: MidiDevice.Info?, val to: MidiDevice.Info?)

    @Volatile private var entries: List<Entry> = emptyList()

    private fun scan(): List<Entry> {
        val byName = LinkedHashMap<String, Pair<MidiDevice.Info?, MidiDevice.Info?>>()
        for (info in runCatching { JavaMidi.getMidiDeviceInfo() }.getOrDefault(emptyArray())) {
            val device = runCatching { JavaMidi.getMidiDevice(info) }.getOrNull() ?: continue
            // The JVM's own software synth and sequencer aren't instruments.
            if (device is Synthesizer || device is Sequencer) continue
            val (from, to) = byName[info.name] ?: (null to null)
            byName[info.name] = when {
                device.maxTransmitters != 0 && from == null -> info to to
                device.maxReceivers != 0 && to == null -> from to info
                else -> from to to
            }
        }
        return byName.map { (name, pair) ->
            val (from, to) = pair
            val info = from ?: to!!
            Entry(
                MidiDeviceDesc(
                    // Stays the same across rescans, so the hub's open ports keep their ids.
                    id = name.hashCode() and 0x7fffffff,
                    name = name.substringBefore(" [hw:").ifBlank { name },
                    product = info.description,
                    maker = info.vendor,
                    inputPortCount = if (to != null) 1 else 0,
                    outputPortCount = if (from != null) 1 else 0,
                    usb = true,
                    bluetooth = false,
                ),
                from, to,
            )
        }
    }

    override val devices: List<MidiDeviceDesc>
        get() {
            entries = scan()
            return entries.map { it.desc }
        }

    override fun openDevice(device: MidiDeviceDesc, done: (MidiOpenDevice?) -> Unit) {
        worker.post {
            val entry = (entries.firstOrNull { it.desc.id == device.id } ?: scan().firstOrNull { it.desc.id == device.id })
            done(entry?.let { runCatching { Opened(it) }.onFailure { e -> Log.w(TAG, "could not open ${device.name}", e) }.getOrNull() })
        }
    }

    override fun watch(added: (MidiDeviceDesc) -> Unit, removed: (MidiDeviceDesc) -> Unit) {
        var known = entries.map { it.desc }.associateBy { it.id }
        thread.every(pollMs) {
            val now = scan().also { entries = it }.map { it.desc }.associateBy { it.id }
            for ((id, d) in now) if (id !in known) added(d)
            for ((id, d) in known) if (id !in now) removed(d)
            known = now
        }
    }

    private inner class Opened(entry: Entry) : MidiOpenDevice {
        override val desc = entry.desc
        private val from: MidiDevice? = entry.from?.let { JavaMidi.getMidiDevice(it).apply { open() } }
        private val to: MidiDevice? = entry.to?.let { JavaMidi.getMidiDevice(it).apply { open() } }

        override fun openInputPort(index: Int): MidiSendPort? {
            val receiver = to?.receiver ?: return null
            return object : MidiSendPort {
                override fun send(bytes: ByteArray, offset: Int, count: Int) {
                    messageOf(bytes, offset, count)?.let { runCatching { receiver.send(it, -1) } }
                }
                override fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) =
                    thread.sendAt(bytes, offset, count, timestamp, ::send)
                override fun close() = receiver.close()
            }
        }

        override fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit) {
            val transmitter = from?.transmitter ?: return
            transmitter.receiver = object : Receiver {
                // Java Sound's timestamps count from when the device opened,
                // and the hub wants System.nanoTime, so use the arrival time.
                override fun send(message: MidiMessage, timeStamp: Long) {
                    onSend(message.message, 0, message.length, System.nanoTime())
                }
                override fun close() {}
            }
        }

        override fun close() {
            runCatching { from?.close() }
            runCatching { to?.close() }
        }
    }
}

/** The hub's bytes as a Java Sound message: a channel or system message, or a whole SysEx. */
internal fun messageOf(bytes: ByteArray, offset: Int, count: Int): MidiMessage? {
    if (count <= 0) return null
    val status = bytes[offset].toInt() and 0xff
    return runCatching {
        if (status == 0xf0 || status == 0xf7) {
            SysexMessage(bytes.copyOfRange(offset, offset + count), count)
        } else {
            val d1 = if (count > 1) bytes[offset + 1].toInt() and 0x7f else 0
            val d2 = if (count > 2) bytes[offset + 2].toInt() and 0x7f else 0
            when (count) {
                1 -> ShortMessage(status)
                else -> ShortMessage().apply { setMessage(status, d1, d2) }
            }
        }
    }.getOrNull()
}
