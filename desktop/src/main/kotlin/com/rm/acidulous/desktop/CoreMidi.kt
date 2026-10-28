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

/** CoreMIDI, opened by coremidi.cpp. macOS only. */
internal object CoreMidiNative {
    init {
        // The engine's library. NativeEngine has already loaded it, so this does nothing.
        System.loadLibrary("acidulous")
    }

    @JvmStatic external fun nativeOpen(): Boolean
    @JvmStatic external fun nativeEndpoints(): Array<String>
    @JvmStatic external fun nativeListen(id: Int, on: Boolean): Boolean
    @JvmStatic external fun nativeSend(id: Int, bytes: ByteArray, offset: Int, count: Int): Boolean
    @JvmStatic external fun nativeRead(out: ByteArray, from: IntArray, timeoutMs: Int): Int
    /** For the tests: a virtual source and destination called [name] that echo into each other. */
    @JvmStatic external fun nativeLoopback(name: String): Boolean

    const val SOURCE = 1
    /** Any hardware but Bluetooth: USB, or a maker's own driver. Not the IAC bus or a network session. */
    const val USB = 1
    const val BLUETOOTH = 2
}

/** One CoreMIDI source or destination, as listed by nativeEndpoints. */
internal data class MacEndpoint(val id: Int, val source: Boolean, val deviceKey: String, val deviceName: String, val maker: String, val flags: Int)

/** A CoreMIDI device: the endpoints it sends from and the ones it receives on, by unique id. */
internal class MacDevice(val desc: MidiDeviceDesc, val sources: List<Int>, val destinations: List<Int>)

/**
 * The endpoints grouped into devices: by the device they belong to, or by
 * name for a program's own ports. A device's id comes from its CoreMIDI
 * unique id, which stays the same when it's plugged in again.
 */
internal fun macDevices(endpoints: List<MacEndpoint>): List<MacDevice> =
    endpoints.groupBy { it.deviceKey }.map { (key, own) ->
        val first = own.first()
        val sources = own.filter { it.source }.map { it.id }
        val destinations = own.filter { !it.source }.map { it.id }
        MacDevice(
            MidiDeviceDesc(
                id = key.hashCode() and 0x7fffffff,
                name = first.deviceName,
                product = first.deviceName,
                maker = first.maker.ifEmpty { null },
                inputPortCount = destinations.size,
                outputPortCount = sources.size,
                usb = first.flags and CoreMidiNative.USB != 0,
                bluetooth = first.flags and CoreMidiNative.BLUETOOTH != 0,
            ),
            sources, destinations,
        )
    }

/**
 * MIDI on macOS through CoreMIDI. It sees USB devices, Bluetooth MIDI once
 * it's paired in Audio MIDI Setup, network sessions and other programs'
 * ports, and shares them with other apps.
 *
 * Like AlsaSeqMidi, the device list is re-read every two seconds, timed
 * sends wait on the MIDI thread, and incoming messages are read on their own
 * thread and routed by the source they came from.
 */
class CoreMidi private constructor(private val pollMs: Long) : MidiSystem {
    companion object {
        /** CoreMIDI, or null if it won't start, so Java Sound is used instead. */
        fun open(pollMs: Long = 2000): CoreMidi? {
            val ok = runCatching { CoreMidiNative.nativeOpen() }.onFailure { Log.w(TAG, "no CoreMIDI", it) }.getOrDefault(false)
            return if (ok) CoreMidi(pollMs) else null
        }
    }

    private val thread = MidiThread()
    override val supported: Boolean = true
    override val bluetooth: MidiBluetooth? = null
    override val worker: MidiWorker = thread

    @Volatile private var known: List<MacDevice> = emptyList()

    private fun scan(): List<MacDevice> {
        val endpoints = runCatching { CoreMidiNative.nativeEndpoints() }.getOrDefault(emptyArray()).toList().chunked(6).mapNotNull { f ->
            runCatching { MacEndpoint(f[0].toInt(), f[1].toInt() == CoreMidiNative.SOURCE, f[2], f[3], f[4], f[5].toInt()) }.getOrNull()
        }
        return macDevices(endpoints).also { known = it }
    }

    override val devices: List<MidiDeviceDesc> get() = scan().map { it.desc }

    private fun describe(d: MacDevice) =
        "${d.desc.name} (in ${d.sources}, out ${d.destinations}${if (d.desc.usb) ", usb" else ""}${if (d.desc.bluetooth) ", bluetooth" else ""})"

    /** Listener for each source, by its unique id. */
    private val listeners = ConcurrentHashMap<Int, (ByteArray, Int, Int, Long) -> Unit>()

    init {
        Thread({
            val buffer = ByteArray(65536)
            val from = IntArray(1)
            while (true) {
                val n = CoreMidiNative.nativeRead(buffer, from, 250)
                if (n < 0) break
                if (n == 0) continue
                // Timestamped on arrival, using System.nanoTime like the hub expects.
                val at = System.nanoTime()
                // Keep reading whatever the hub does. An exception here would
                // end this thread and stop every input.
                listeners[from[0]]?.let { heard ->
                    runCatching { heard(buffer.copyOf(n), 0, n, at) }
                        .onFailure { Log.w(TAG, "a message from ${from[0]} could not be handled", it) }
                }
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
        // Compare the endpoints too: a device whose ports changed is a new
        // device to the hub, and its connections to the old ones are dead.
        var before = known.associateBy { it.desc.id }
        before.values.forEach { Log.i(TAG, "device ${describe(it)}") }
        fun same(a: MacDevice?, b: MacDevice) = a != null && a.sources == b.sources && a.destinations == b.destinations
        thread.every(pollMs) {
            val now = scan().associateBy { it.desc.id }
            for ((id, d) in before) if (!same(now[id], d)) { Log.i(TAG, "gone ${describe(d)}"); removed(d.desc) }
            for ((id, d) in now) if (!same(before[id], d)) { Log.i(TAG, "device ${describe(d)}"); added(d.desc) }
            before = now
        }
    }

    private inner class Opened(private val device: MacDevice) : MidiOpenDevice {
        override val desc = device.desc
        private val heard = mutableListOf<Int>()

        override fun openInputPort(index: Int): MidiSendPort? {
            val destination = device.destinations.getOrNull(index) ?: return null
            return object : MidiSendPort {
                override fun send(bytes: ByteArray, offset: Int, count: Int) {
                    CoreMidiNative.nativeSend(destination, bytes, offset, count)
                }
                override fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) =
                    thread.sendAt(bytes, offset, count, timestamp, ::send)
                override fun close() {}
            }
        }

        override fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit) {
            val source = device.sources.getOrNull(index) ?: return
            listeners[source] = onSend
            if (CoreMidiNative.nativeListen(source, true)) {
                synchronized(heard) { heard += source }
            } else {
                listeners.remove(source)
            }
        }

        override fun close() {
            val sources = synchronized(heard) { heard.toList().also { heard.clear() } }
            for (source in sources) {
                listeners.remove(source)
                CoreMidiNative.nativeListen(source, false)
            }
        }
    }
}
