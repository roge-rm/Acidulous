package com.rm.acidulous.midi

import com.rm.acidulous.util.Runnable

/**
 * The platform's MIDI, as [MidiHub] uses it.
 *
 * Modelled on Android's MidiManager, which the hub was written against: a
 * list of devices, notices when one comes or goes, and devices opened
 * asynchronously with the result delivered on the hub's thread. On Android
 * it's those calls unchanged (AndroidMidi.kt). Other platforms provide the
 * same from their own MIDI systems.
 */
interface MidiSystem {
    /** Whether this device can do MIDI at all. */
    val supported: Boolean
    /** Every device connected now. */
    val devices: List<MidiDeviceDesc>
    /** The thread MIDI work happens on. Callbacks arrive here. */
    val worker: MidiWorker
    /** Bluetooth MIDI, where the app has to find devices itself. Null otherwise. */
    val bluetooth: MidiBluetooth?

    /** Open [device]. [done] gets it, or null, on [worker]. */
    fun openDevice(device: MidiDeviceDesc, done: (MidiOpenDevice?) -> Unit)
    /** Get told, on [worker], when a device is plugged in or removed. */
    fun watch(added: (MidiDeviceDesc) -> Unit, removed: (MidiDeviceDesc) -> Unit)
}

/**
 * A device as the hub needs to know it. Port counts use Android's names: an
 * output port is one the device sends from (the hub's input), and an input
 * port is one it receives on.
 */
data class MidiDeviceDesc(
    val id: Int,
    val name: String?,
    val product: String?,
    val maker: String?,
    val inputPortCount: Int,
    val outputPortCount: Int,
    val usb: Boolean,
    val bluetooth: Boolean,
)

interface MidiOpenDevice {
    val desc: MidiDeviceDesc
    /** A port to send to, or null. */
    fun openInputPort(index: Int): MidiSendPort?
    /** Receive what the device sends on one of its ports: bytes, offset, count, and a System.nanoTime timestamp. */
    fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit)
    fun close()
}

interface MidiSendPort {
    fun send(bytes: ByteArray, offset: Int, count: Int)
    /** Send at [timestamp] (System.nanoTime). The platform does the waiting. */
    fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long)
    fun close()
}

    /** Stands in for Android's Handler, as far as the hub uses one. */
interface MidiWorker {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun removeCallbacks(task: Runnable)
}

/**
 * Finding and opening Bluetooth MIDI devices, on platforms where the app has
 * to do it. Results go into [MidiHub.discovered], [MidiHub.scanning] and
 * [MidiHub.scanStatus], and opened devices go to [MidiHub.attachFound].
 */
interface MidiBluetooth {
    /** Bluetooth is available and switched on. */
    fun ready(): Boolean
    /** The permissions a scan needs. */
    fun permissions(): Array<String>
    fun scan()
    fun stop()
    fun connect(address: String)
}
