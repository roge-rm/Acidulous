package com.rm.acidulous.desktop

import com.rm.acidulous.midi.MidiBluetooth
import com.rm.acidulous.midi.MidiDeviceDesc
import com.rm.acidulous.midi.MidiOpenDevice
import com.rm.acidulous.midi.MidiSendPort
import com.rm.acidulous.midi.MidiSystem
import com.rm.acidulous.midi.MidiWorker
import com.rm.acidulous.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
 * MIDI on the desktop, through Java Sound: on Linux, ALSA's raw MIDI devices,
 * which is where anything plugged in over USB appears.
 *
 * Java Sound lists a device's input and its output as two separate entries
 * with one name; they are put back together here, because the hub thinks of
 * a controller as one thing with ports both ways, as Android does. It says
 * nothing when a device comes or goes, so the list is read again every two
 * seconds. And it sends at once rather than at a time, so a timestamped send
 * waits on the MIDI thread until its moment - the job Android's port does
 * itself.
 *
 * Not here yet: ports that exist only on ALSA's sequencer - another
 * program's, or a Bluetooth instrument's.
 */
class JavaSoundMidi(private val pollMs: Long = 2000) : MidiSystem {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "midi").apply { isDaemon = true } }

    override val supported: Boolean = true
    override val bluetooth: MidiBluetooth? = null

    override val worker: MidiWorker = object : MidiWorker {
        private val pending = HashMap<Runnable, MutableList<ScheduledFuture<*>>>()
        override fun post(task: Runnable) = postDelayed(task, 0)
        override fun postDelayed(task: Runnable, delayMs: Long) {
            lateinit var future: ScheduledFuture<*>
            future = executor.schedule({
                synchronized(pending) { pending[task]?.remove(future) }
                task.run()
            }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            synchronized(pending) { pending.getOrPut(task) { mutableListOf() } += future }
        }
        override fun removeCallbacks(task: Runnable) {
            val futures = synchronized(pending) { pending.remove(task) } ?: return
            futures.forEach { it.cancel(false) }
        }
    }

    /** A device's Java Sound entries: the one that sends to us, the one we send to, or both. */
    private class Entry(val desc: MidiDeviceDesc, val from: MidiDevice.Info?, val to: MidiDevice.Info?)

    @Volatile private var entries: List<Entry> = emptyList()

    private fun scan(): List<Entry> {
        val byName = LinkedHashMap<String, Pair<MidiDevice.Info?, MidiDevice.Info?>>()
        for (info in runCatching { JavaMidi.getMidiDeviceInfo() }.getOrDefault(emptyArray())) {
            val device = runCatching { JavaMidi.getMidiDevice(info) }.getOrNull() ?: continue
            // The JVM's own software synth and sequencer are not instruments.
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
                    // Stable across rescans, so the hub's open ports keep their ids.
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
        executor.scheduleWithFixedDelay({
            val now = scan().also { entries = it }.map { it.desc }.associateBy { it.id }
            for ((id, d) in now) if (id !in known) added(d)
            for ((id, d) in known) if (id !in now) removed(d)
            known = now
        }, pollMs, pollMs, TimeUnit.MILLISECONDS)
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
                override fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    val copy = bytes.copyOfRange(offset, offset + count)
                    val waitMs = (timestamp - System.nanoTime()) / 1_000_000
                    if (waitMs <= 0) send(copy, 0, copy.size) else worker.postDelayed({ send(copy, 0, copy.size) }, waitMs)
                }
                override fun close() = receiver.close()
            }
        }

        override fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit) {
            val transmitter = from?.transmitter ?: return
            transmitter.receiver = object : Receiver {
                // Java Sound's own stamps count from when the device opened; the
                // hub wants System.nanoTime's base, so the moment of arrival it is.
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
