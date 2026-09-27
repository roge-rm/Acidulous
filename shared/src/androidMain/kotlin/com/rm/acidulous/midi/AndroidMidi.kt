package com.rm.acidulous.midi

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import com.rm.acidulous.res.*
import com.rm.acidulous.util.Log

/**
 * Android's MIDI for [MidiHub]: MidiManager, a HandlerThread for everything
 * to run on, and the Bluetooth scan that turns a BLE MIDI peripheral into a
 * MIDI device. Kept here so the hub itself has no Android code.
 */
fun androidMidi(context: Context): MidiSystem? {
    val app = context.applicationContext
    val manager = app.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return null
    return AndroidMidi(app, manager)
}

private const val TAG = "Acidulous.MIDI"

private class AndroidMidi(private val context: Context, private val manager: MidiManager) : MidiSystem {
    private val thread = HandlerThread("midi-in").apply { start() }
    private val handler = Handler(thread.looper)

    /** The real MidiDeviceInfo behind each id handed out, needed to open it. */
    private val infos = HashMap<Int, MidiDeviceInfo>()

    override val supported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    override val devices: List<MidiDeviceDesc>
        get() = @Suppress("DEPRECATION") manager.devices.map { desc(it) }

    override val worker: MidiWorker = object : MidiWorker {
        override fun post(task: Runnable) { handler.post(task) }
        override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
        override fun removeCallbacks(task: Runnable) { handler.removeCallbacks(task) }
    }

    override val bluetooth: MidiBluetooth = Bluetooth()

    override fun openDevice(device: MidiDeviceDesc, done: (MidiOpenDevice?) -> Unit) {
        val info = infos[device.id] ?: run { done(null); return }
        manager.openDevice(info, { opened -> done(opened?.let { wrap(it) }) }, handler)
    }

    override fun watch(added: (MidiDeviceDesc) -> Unit, removed: (MidiDeviceDesc) -> Unit) {
        manager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = added(desc(device))
            override fun onDeviceRemoved(device: MidiDeviceInfo) = removed(desc(device))
        }, handler)
    }

    private fun desc(info: MidiDeviceInfo): MidiDeviceDesc {
        infos[info.id] = info
        val props = info.properties
        return MidiDeviceDesc(
            id = info.id,
            name = props.getString(MidiDeviceInfo.PROPERTY_NAME),
            product = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            maker = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
            inputPortCount = info.inputPortCount,
            outputPortCount = info.outputPortCount,
            usb = info.type == MidiDeviceInfo.TYPE_USB,
            bluetooth = info.type == MidiDeviceInfo.TYPE_BLUETOOTH,
        )
    }

    private fun wrap(device: MidiDevice): MidiOpenDevice = object : MidiOpenDevice {
        override val desc = desc(device.info)
        override fun openInputPort(index: Int): MidiSendPort? = device.openInputPort(index)?.let { port(it) }
        override fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit) {
            device.openOutputPort(index)?.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) =
                    onSend(msg, offset, count, timestamp)
            })
        }
        override fun close() = device.close()
    }

    private fun port(p: MidiInputPort): MidiSendPort = object : MidiSendPort {
        override fun send(bytes: ByteArray, offset: Int, count: Int) = p.send(bytes, offset, count)
        override fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) = p.send(bytes, offset, count, timestamp)
        override fun close() = p.close()
    }

    // --- Bluetooth ------------------------------------------------------------
    //
    // A BLE MIDI device only becomes a MIDI device once it's found and
    // opened. Scan for the MIDI service, hand the result to MidiManager, and
    // from then on it works like anything plugged in.

    private inner class Bluetooth : MidiBluetooth {
        /**
         * The GATT service every BLE MIDI device advertises, from the BLE-MIDI
         * spec.
         *
         * This is the scan filter, so a single wrong digit means the scan
         * never matches anything and just reports "nothing found". Check it
         * against the spec if you touch it.
         */
        private val BLE_MIDI_SERVICE = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")

        override fun ready(): Boolean {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            return adapter?.isEnabled == true
        }

        /** The permissions a scan needs, which differ before and after Android 12. */
        override fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }

        private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        private var wide = false
        private var widenTask: Runnable? = null
        private var endTask: Runnable? = null

        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Reading a device's name needs BLUETOOTH_CONNECT on Android 12+,
                // and without it throws instead of returning null, which would
                // kill the scan from inside a system callback.
                val name = try {
                    result.device.name ?: result.scanRecord?.deviceName
                } catch (e: SecurityException) {
                    null
                }
                val isMidi = result.scanRecord?.serviceUuids?.contains(BLE_MIDI_SERVICE) == true
                if (wide && !isMidi && name == null) return // nothing to show and nothing to pick
                val discovered = MidiHub.discovered
                val at = discovered.indexOfFirst { it.address == result.device.address }
                val found = MidiHub.Found(result.device.address, name ?: MidiHub.say(Res.string.midi_unnamed), isMidi)
                if (at < 0) {
                    discovered += found
                } else if (isMidi && !discovered[at].midi) {
                    discovered[at] = found // the service turned up in the scan response
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                MidiHub.scanStatus = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> MidiHub.say(Res.string.midi_scan_running)
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> MidiHub.say(Res.string.midi_scan_refused_restart)
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> MidiHub.say(Res.string.midi_scan_unsupported)
                    else -> MidiHub.say(Res.string.midi_scan_failed, errorCode)
                }
                MidiHub.scanning = false
            }
        }

        private fun beginScan(filtered: Boolean) {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val filters = if (filtered) listOf(ScanFilter.Builder().setServiceUuid(BLE_MIDI_SERVICE).build()) else null
            try {
                scanner?.startScan(filters, settings, scanCallback)
                MidiHub.scanning = true
            } catch (e: SecurityException) {
                Log.w(TAG, "scan refused: ${e.message}")
                MidiHub.scanStatus = MidiHub.say(Res.string.midi_scan_refused)
                MidiHub.scanning = false
            }
        }

        /**
         * Scan for the MIDI service first, and if nothing answers within a few
         * seconds, widen to every device with a name.
         *
         * Not every device puts its 128-bit service UUID in the advertising
         * packet (there's only room for one). Some put it in the scan
         * response, where Android's offloaded filter can miss it. So a
         * filtered scan that finds nothing doesn't prove there's nothing, and
         * a list to pick from is better than an empty one.
         */
        override fun scan() {
            if (MidiHub.scanning) return
            val adapter: BluetoothAdapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
            MidiHub.discovered.clear()
            MidiHub.scanStatus = ""
            wide = false
            scanner = adapter.bluetoothLeScanner ?: return
            beginScan(filtered = true)
            if (!MidiHub.scanning) return

            widenTask = Runnable {
                if (!MidiHub.scanning || MidiHub.discovered.isNotEmpty()) return@Runnable
                try {
                    scanner?.stopScan(scanCallback)
                } catch (e: SecurityException) {
                    Log.w(TAG, "stop refused: ${e.message}")
                }
                wide = true
                MidiHub.scanStatus = MidiHub.say(Res.string.midi_scan_widened)
                beginScan(filtered = false)
            }.also { handler.postDelayed(it, 6_000) }
            endTask = Runnable {
                val none = MidiHub.discovered.isEmpty()
                stop()
                if (none) {
                    MidiHub.scanStatus = MidiHub.say(Res.string.midi_scan_nothing)
                }
            }.also { handler.postDelayed(it, 16_000) }
        }

        override fun stop() {
            widenTask?.let { handler.removeCallbacks(it) }
            endTask?.let { handler.removeCallbacks(it) }
            widenTask = null
            endTask = null
            if (!MidiHub.scanning) return
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Log.w(TAG, "stop refused: ${e.message}")
            }
            MidiHub.scanning = false
        }

        override fun connect(address: String) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
            val device: BluetoothDevice = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "bad address $address"); return
            }
            stop()
            MidiHub.scanStatus = MidiHub.say(Res.string.midi_opening)
            try {
                manager.openBluetoothDevice(device, { opened ->
                    if (opened == null) {
                        // Android returns nothing and gives no reason. Usually
                        // it's not a MIDI device, or it's already paired in the
                        // system's Bluetooth settings and so isn't listening.
                        Log.w(TAG, "openBluetoothDevice gave nothing for $address")
                        MidiHub.scanStatus = MidiHub.say(Res.string.midi_open_failed)
                    } else {
                        MidiHub.scanStatus = ""
                        MidiHub.attachFound(wrap(opened))
                    }
                }, handler)
            } catch (e: SecurityException) {
                Log.w(TAG, "connect refused: ${e.message}")
                MidiHub.scanStatus = MidiHub.say(Res.string.midi_connect_refused)
            }
        }
    }
}
