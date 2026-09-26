package com.rm.acidulous.desktop

import com.rm.acidulous.midi.MidiBluetooth
import com.rm.acidulous.midi.MidiDeviceDesc
import com.rm.acidulous.midi.MidiOpenDevice
import com.rm.acidulous.midi.MidiSendPort
import com.rm.acidulous.midi.MidiSystem
import com.rm.acidulous.midi.MidiWorker
import com.rm.acidulous.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Acidulous.MIDI"

/** ALSA's sequencer, as alsa_seq.cpp opens it. */
internal object AlsaSeq {
    init {
        // The engine's library, loaded already by NativeEngine; again is nothing.
        System.loadLibrary("acidulous")
    }

    @JvmStatic external fun nativeOpen(): Int
    @JvmStatic external fun nativePorts(): Array<String>
    @JvmStatic external fun nativeListen(client: Int, port: Int, on: Boolean): Boolean
    @JvmStatic external fun nativeSpeak(client: Int, port: Int, on: Boolean): Boolean
    @JvmStatic external fun nativeSend(client: Int, port: Int, bytes: ByteArray, offset: Int, count: Int): Boolean
    @JvmStatic external fun nativeRead(out: ByteArray, from: IntArray, timeoutMs: Int): Int

    // A port's capabilities, as <alsa/seq.h> numbers them.
    const val CAP_READ = 0x01
    const val CAP_WRITE = 0x02
    const val CAP_SUBS_READ = 0x20
    const val CAP_SUBS_WRITE = 0x40
    const val CAP_NO_EXPORT = 0x80
    const val KERNEL_CLIENT = 2
}

/** One of the sequencer's ports, as nativePorts lists it. */
internal data class SeqPort(
    val client: Int,
    val port: Int,
    val caps: Int,
    val clientType: Int,
    val card: Int,
    val clientName: String,
    val portName: String,
)

/** A sequencer client as the hub's device: the ports it sends from and those it takes. */
internal class SeqDevice(val desc: MidiDeviceDesc, val client: Int, val sources: List<Int>, val destinations: List<Int>)

/**
 * Which of the sequencer's clients are instruments, as devices.
 *
 * Not the system's own client (0), nor this app's, nor "Midi Through" - a
 * loop back to itself that every Linux has and nobody plugged in - nor
 * PipeWire's bridge clients, nor a port its owner keeps to itself.
 *
 * A device's id is its name, so a controller plugged back in is the same one:
 * the sequencer gives it a new client number each time. Two with one name are
 * told apart by the order they are listed in.
 */
internal fun seqDevices(ports: List<SeqPort>, ownClient: Int): List<SeqDevice> {
    val usable = ports.filter {
        it.client != 0 && it.client != ownClient &&
            it.clientName != "Midi Through" && !it.clientName.startsWith("PipeWire-") &&
            it.caps and AlsaSeq.CAP_NO_EXPORT == 0
    }
    val seen = HashMap<String, Int>()
    return usable.groupBy { it.client }.mapNotNull { (client, own) ->
        val readable = AlsaSeq.CAP_READ or AlsaSeq.CAP_SUBS_READ
        val writable = AlsaSeq.CAP_WRITE or AlsaSeq.CAP_SUBS_WRITE
        val sources = own.filter { it.caps and readable == readable }.map { it.port }
        val destinations = own.filter { it.caps and writable == writable }.map { it.port }
        if (sources.isEmpty() && destinations.isEmpty()) return@mapNotNull null
        val first = own.first()
        val name = first.clientName
        val nth = seen.merge(name, 1, Int::plus)!!
        val key = if (nth == 1) name else "$name#$nth"
        SeqDevice(
            MidiDeviceDesc(
                id = key.hashCode() and 0x7fffffff,
                name = name,
                product = name,
                maker = null,
                inputPortCount = destinations.size,
                outputPortCount = sources.size,
                // A card behind it is hardware: USB, as good as always.
                usb = first.clientType == AlsaSeq.KERNEL_CLIENT && first.card >= 0,
                bluetooth = false,
            ),
            client, sources, destinations,
        )
    }
}

/**
 * MIDI on Linux through ALSA's sequencer, which is what Linux's music
 * programs use: the USB devices Java Sound's raw MIDI sees, shared rather than
 * held, and besides them every other program's ports and BlueZ's Bluetooth
 * MIDI instruments.
 *
 * As JavaSoundMidi: the list is read again every two seconds, since word of a
 * change would mean a subscription to the system's announcements for no
 * gain, and a timestamped send waits on the MIDI thread. What comes in is read
 * on a thread of its own and sorted by the port it came from.
 */
class AlsaSeqMidi private constructor(private val ownClient: Int, private val pollMs: Long) : MidiSystem {
    companion object {
        /** The sequencer, or null where there is none - no /dev/snd/seq, no libasound - for Java Sound instead. */
        fun open(pollMs: Long = 2000): AlsaSeqMidi? {
            val own = runCatching { AlsaSeq.nativeOpen() }.onFailure { Log.w(TAG, "no sequencer", it) }.getOrDefault(-1)
            return if (own >= 0) AlsaSeqMidi(own, pollMs) else null
        }
    }

    private val thread = MidiThread()
    override val supported: Boolean = true
    override val bluetooth: MidiBluetooth? = null
    override val worker: MidiWorker = thread

    @Volatile private var known: List<SeqDevice> = emptyList()

    private fun scan(): List<SeqDevice> {
        val ports = runCatching { AlsaSeq.nativePorts() }.getOrDefault(emptyArray()).toList().chunked(7).mapNotNull { f ->
            runCatching { SeqPort(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5], f[6]) }.getOrNull()
        }
        return seqDevices(ports, ownClient).also { known = it }
    }

    override val devices: List<MidiDeviceDesc> get() = scan().map { it.desc }

    private fun describe(d: SeqDevice) =
        "${d.desc.name} (client ${d.client}, in ${d.sources}, out ${d.destinations}${if (d.desc.usb) ", usb" else ""})"

    /** Who hears each port: client and port, as one number. */
    private val listeners = ConcurrentHashMap<Long, (ByteArray, Int, Int, Long) -> Unit>()
    private fun keyOf(client: Int, port: Int) = (client.toLong() shl 32) or port.toLong()

    private var said = 0

    init {
        Thread({
            val buffer = ByteArray(65536)
            val from = IntArray(2)
            while (true) {
                val n = AlsaSeq.nativeRead(buffer, from, 250)
                if (n < 0) break
                if (n == 0) continue
                // Stamped on arrival: the hub wants System.nanoTime's base.
                val at = System.nanoTime()
                val heard = listeners[keyOf(from[0], from[1])]
                // The first few, heard or not, so a log says whether anything
                // arrives at all and from where.
                if (said < 8) {
                    said++
                    Log.i(TAG, "in from ${from[0]}:${from[1]}: ${buffer.take(n.coerceAtMost(6)).joinToString(" ") { "%02X".format(it) }}" +
                        if (heard == null) " (nothing connected to it)" else "")
                }
                // Whatever the hub does with it, the reading goes on: an
                // exception here would end this thread, and every input with it.
                if (heard != null) runCatching { heard(buffer.copyOf(n), 0, n, at) }
                    .onFailure { Log.w(TAG, "a message from ${from[0]}:${from[1]} could not be handled", it) }
            }
        }, "midi-in").apply { isDaemon = true }.start()
    }

    override fun openDevice(device: MidiDeviceDesc, done: (MidiOpenDevice?) -> Unit) {
        worker.post {
            val found = known.firstOrNull { it.desc.id == device.id } ?: scan().firstOrNull { it.desc.id == device.id }
            done(found?.let { Opened(it) })
        }
    }

    override fun watch(added: (MidiDeviceDesc) -> Unit, removed: (MidiDeviceDesc) -> Unit) {
        // By id and client both: plugged back in, a device keeps its id and
        // gets a new client, and the hub's hold on the old one is dead.
        var before = known.associateBy { it.desc.id }
        before.values.forEach { Log.i(TAG, "device ${describe(it)}") }
        thread.every(pollMs) {
            val now = scan().associateBy { it.desc.id }
            for ((id, d) in before) if (now[id]?.client != d.client) { Log.i(TAG, "gone ${describe(d)}"); removed(d.desc) }
            for ((id, d) in now) if (before[id]?.client != d.client) { Log.i(TAG, "device ${describe(d)}"); added(d.desc) }
            before = now
        }
    }

    private inner class Opened(private val device: SeqDevice) : MidiOpenDevice {
        override val desc = device.desc
        private val heard = mutableListOf<Int>()
        private val spoken = mutableListOf<Int>()

        override fun openInputPort(index: Int): MidiSendPort? {
            val port = device.destinations.getOrNull(index) ?: return null
            // Connected first: a hardware port does not open its device's
            // output for a message sent to it unasked. See nativeSpeak.
            if (!AlsaSeq.nativeSpeak(device.client, port, true)) return null
            synchronized(spoken) { spoken += port }
            return object : MidiSendPort {
                override fun send(bytes: ByteArray, offset: Int, count: Int) {
                    AlsaSeq.nativeSend(device.client, port, bytes, offset, count)
                }
                override fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) =
                    thread.sendAt(bytes, offset, count, timestamp, ::send)
                override fun close() {
                    if (synchronized(spoken) { spoken.remove(port) }) AlsaSeq.nativeSpeak(device.client, port, false)
                }
            }
        }

        override fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit) {
            val port = device.sources.getOrNull(index) ?: return
            listeners[keyOf(device.client, port)] = onSend
            if (AlsaSeq.nativeListen(device.client, port, true)) {
                synchronized(heard) { heard += port }
            } else {
                listeners.remove(keyOf(device.client, port))
            }
        }

        override fun close() {
            val ports = synchronized(heard) { heard.toList().also { heard.clear() } }
            for (port in ports) {
                listeners.remove(keyOf(device.client, port))
                AlsaSeq.nativeListen(device.client, port, false)
            }
            val sent = synchronized(spoken) { spoken.toList().also { spoken.clear() } }
            for (port in sent) AlsaSeq.nativeSpeak(device.client, port, false)
        }
    }
}
