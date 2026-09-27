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

/** ALSA's sequencer, opened by alsa_seq.cpp. */
internal object AlsaSeq {
    init {
        // The engine's library. NativeEngine has already loaded it, so this does nothing.
        System.loadLibrary("acidulous")
    }

    @JvmStatic external fun nativeOpen(): Int
    @JvmStatic external fun nativePorts(): Array<String>
    @JvmStatic external fun nativeListen(client: Int, port: Int, on: Boolean): Boolean
    @JvmStatic external fun nativeSpeak(client: Int, port: Int, on: Boolean): Boolean
    @JvmStatic external fun nativeSend(client: Int, port: Int, bytes: ByteArray, offset: Int, count: Int): Boolean
    @JvmStatic external fun nativeRead(out: ByteArray, from: IntArray, timeoutMs: Int): Int

    // Port capability flags, as numbered in <alsa/seq.h>.
    const val CAP_READ = 0x01
    const val CAP_WRITE = 0x02
    const val CAP_SUBS_READ = 0x20
    const val CAP_SUBS_WRITE = 0x40
    const val CAP_NO_EXPORT = 0x80
    const val KERNEL_CLIENT = 2
}

/** One sequencer port, as listed by nativePorts. */
internal data class SeqPort(
    val client: Int,
    val port: Int,
    val caps: Int,
    val clientType: Int,
    val card: Int,
    val clientName: String,
    val portName: String,
)

/** A sequencer client as a MIDI device: the ports it sends from and the ports it receives on. */
internal class SeqDevice(val desc: MidiDeviceDesc, val client: Int, val sources: List<Int>, val destinations: List<Int>)

/**
 * Which sequencer clients are instruments, as devices.
 *
 * Skips the system client (0), this app, "Midi Through" (a loopback every
 * Linux has), PipeWire's bridge clients, and ports marked as private.
 *
 * A device's id comes from its name, because the sequencer gives it a new
 * client number each time it's plugged in. Devices with the same name are
 * told apart by their order in the list.
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
                // A kernel client with a sound card is hardware, almost always USB.
                usb = first.clientType == AlsaSeq.KERNEL_CLIENT && first.card >= 0,
                bluetooth = false,
            ),
            client, sources, destinations,
        )
    }
}

/**
 * MIDI on Linux through ALSA's sequencer, which is what Linux music apps use.
 * It sees the same USB devices as Java Sound's raw MIDI, but shares them
 * instead of taking them over, and also sees other programs' ports and
 * BlueZ's Bluetooth MIDI devices.
 *
 * Like JavaSoundMidi, the device list is re-read every two seconds rather
 * than subscribing to system announcements, and timestamped sends wait on the
 * MIDI thread. Incoming messages are read on their own thread and routed by
 * the port they came from.
 */
class AlsaSeqMidi private constructor(private val ownClient: Int, private val pollMs: Long) : MidiSystem {
    companion object {
        /** The sequencer, or null if there isn't one (no /dev/snd/seq or no libasound), so Java Sound is used instead. */
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

    /** Listener for each port, keyed by client and port packed into one number. */
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
                // Timestamped on arrival, using System.nanoTime like the hub expects.
                val at = System.nanoTime()
                val heard = listeners[keyOf(from[0], from[1])]
                // Log the first few messages, handled or not, to show whether
                // anything arrives and from where.
                if (said < 8) {
                    said++
                    Log.i(TAG, "in from ${from[0]}:${from[1]}: ${buffer.take(n.coerceAtMost(6)).joinToString(" ") { "%02X".format(it) }}" +
                        if (heard == null) " (nothing connected to it)" else "")
                }
                // Keep reading whatever the hub does. An exception here would
                // end this thread and stop every input.
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
        // Compare both id and client: a replugged device keeps its id but
        // gets a new client, and the hub's connection to the old one is dead.
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
            // Connect first, since a hardware port won't pass on messages sent
            // to it without a connection. See nativeSpeak.
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
