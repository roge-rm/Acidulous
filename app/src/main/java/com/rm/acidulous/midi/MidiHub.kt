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
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import com.rm.acidulous.engine.NativeEngine

/**
 * Playing Acidulous from real keys.
 *
 * Android hands USB and Bluetooth MIDI through the same interface once a
 * device is open, so almost all of this is one path. The difference is
 * getting there: a USB device announces itself and can be opened, while a
 * Bluetooth one has to be found by scanning for the MIDI service and handed
 * to [MidiManager.openBluetoothDevice] before it becomes a MIDI device at
 * all. That scan, and the permissions in front of it, is the whole reason
 * most Android apps make you run a separate bridge app. There is no need.
 *
 * Everything that arrives is re-addressed to a rack and pushed through the
 * same engine entry the on-screen keyboard uses, so recording, eventors and
 * the machine's own handling all behave identically whichever you play.
 */
object MidiHub {
    /**
     * MIDI over Bluetooth Low Energy: the GATT service every such device
     * advertises, from the BLE-MIDI specification.
     *
     * This is the scan filter, so one wrong digit in it is not a bug that
     * degrades anything - it is a scan that can never match, on any device,
     * for ever, and reports "nothing found" perfectly calmly. It had an 8
     * where the spec has a 4 and cost a BLE controller an evening.
     */
    private val BLE_MIDI_SERVICE = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private const val TAG = "Acidulous.MIDI"

    data class Port(val id: Int, val name: String, val maker: String, val bluetooth: Boolean, val open: Boolean)
    /** [midi] is true when the advertisement actually named the MIDI service. */
    data class Found(val address: String, val name: String, val midi: Boolean)

    /**
     * Where incoming notes go. Following the selected track is what you want
     * while writing; a fixed track is what you want when the phone is a
     * sound module and nobody is looking at its screen; channel-to-rack is
     * for a controller that addresses several at once.
     */
    enum class Routing { SelectedTrack, FixedTrack, ChannelToRack }

    private var manager: MidiManager? = null
    private var appContext: Context? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val opened = HashMap<Int, MidiDevice>()
    private val parsers = HashMap<Int, MidiParser>()

    val ports = mutableStateListOf<Port>()
    val discovered = mutableStateListOf<Found>()
    var scanning by mutableStateOf(false)
        private set

    /** Why the list looks the way it does. Empty when there is nothing to say. */
    var scanStatus by mutableStateOf("")
        private set
    var routing by mutableStateOf(Routing.SelectedTrack)
    var lastMessage by mutableStateOf("")
        private set
    var received by mutableStateOf(0)
        private set

    /** Which rack plays when routing is [Routing.SelectedTrack]. */
    var target: () -> Int = { 0 }

    /** Which rack plays when routing is [Routing.FixedTrack]. */
    var fixedRack by mutableStateOf(0)

    val supported: Boolean
        get() = appContext?.packageManager?.hasSystemFeature(PackageManager.FEATURE_MIDI) == true

    fun start(context: Context) {
        if (manager != null) return
        appContext = context.applicationContext
        manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        worker = HandlerThread("midi-in").apply { start() }
        handler = Handler(worker!!.looper)
        refresh()
        manager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                opened.remove(device.id)?.close()
                refresh()
            }
        }, handler)
    }

    fun refresh() {
        val mgr = manager ?: return
        val list = @Suppress("DEPRECATION") mgr.devices.filter { it.outputPortCount > 0 }
        ports.clear()
        list.forEach { info ->
            val props = info.properties
            ports += Port(
                id = info.id,
                name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
                    ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device",
                maker = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty(),
                bluetooth = info.type == MidiDeviceInfo.TYPE_BLUETOOTH,
                open = opened.containsKey(info.id),
            )
        }
    }

    fun toggle(portId: Int) {
        if (opened.containsKey(portId)) {
            close(portId)
            return
        }
        val mgr = manager ?: return
        val info = @Suppress("DEPRECATION") mgr.devices.firstOrNull { it.id == portId } ?: return
        mgr.openDevice(info, { device -> attach(portId, device) }, handler)
    }

    private fun attach(portId: Int, device: MidiDevice?) {
        if (device == null) {
            Log.w(TAG, "could not open device $portId")
            return
        }
        opened[portId] = device
        val parser = MidiParser { status, d1, d2 -> dispatch(status, d1, d2) }
        parsers[portId] = parser
        val receiver = object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                parser.parse(msg, offset, count)
            }
        }
        for (p in 0 until device.info.outputPortCount) {
            device.openOutputPort(p)?.connect(receiver)
        }
        refresh()
    }

    private fun close(portId: Int) {
        opened.remove(portId)?.close()
        parsers.remove(portId)
        refresh()
    }

    /**
     * Re-address a message and push it at the engine. Channel 10 is not
     * special here: a rack is whatever the routing says it is.
     */
    private fun dispatch(status: Int, d1: Int, d2: Int) {
        val kind = status and 0xf0
        if (kind == 0xc0) return // program change: nothing to address it to yet
        val rack = when (routing) {
            Routing.SelectedTrack -> target()
            Routing.FixedTrack -> fixedRack
            Routing.ChannelToRack -> status and 0x0f
        }
        NativeEngine.midiEvent(rack, kind, d1, d2)
        received += 1
        lastMessage = when (kind) {
            0x90 -> if (d2 == 0) "off $d1" else "on $d1 v$d2"
            0x80 -> "off $d1"
            0xb0 -> "cc $d1 = $d2"
            0xd0 -> "prs $d1"
            0xe0 -> "bend ${((d2 shl 7) or d1) - 8192}"
            else -> "%02x".format(status)
        } + " → rack ${rack + 1}"
    }

    // --- Bluetooth ------------------------------------------------------------
    //
    // A BLE MIDI device is not a MIDI device until it has been found and
    // opened. Scan for the MIDI service, hand the result to MidiManager, and
    // from there it is the same as anything plugged in.

    fun bluetoothReady(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.isEnabled == true
    }

    /** The permissions a scan needs, which differ either side of Android 12. */
    fun bluetoothPermissions(): Array<String> =
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
            // Reading a device's name needs BLUETOOTH_CONNECT on Android 12
            // and up, and throws rather than returning null without it - in
            // a system callback, where it takes the scan down with it.
            val name = try {
                result.device.name ?: result.scanRecord?.deviceName
            } catch (e: SecurityException) {
                null
            } ?: "unnamed"
            val isMidi = result.scanRecord?.serviceUuids?.contains(BLE_MIDI_SERVICE) == true
            if (wide && !isMidi && name == "unnamed") return // nothing to show and nothing to pick
            val at = discovered.indexOfFirst { it.address == result.device.address }
            val found = Found(result.device.address, name, isMidi)
            if (at < 0) {
                discovered += found
            } else if (isMidi && !discovered[at].midi) {
                discovered[at] = found // the service turned up in the scan response
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            scanStatus = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "a scan is already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android refused the scan; turn Bluetooth off and on"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "this phone cannot scan for Bluetooth LE"
                else -> "the scan failed (code $errorCode)"
            }
            scanning = false
        }
    }

    private fun beginScan(filtered: Boolean) {
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = if (filtered) listOf(ScanFilter.Builder().setServiceUuid(BLE_MIDI_SERVICE).build()) else null
        try {
            scanner?.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "scan refused: ${e.message}")
            scanStatus = "Android refused the scan: allow Nearby devices"
            scanning = false
        }
    }

    /**
     * Look for the MIDI service first, and if nothing has answered after a
     * few seconds, widen to everything with a name.
     *
     * Not every peripheral puts its 128-bit service UUID in the advertising
     * packet - there is only room for one, and some put it in the scan
     * response instead, where Android's offloaded filter can miss it. A
     * filtered scan that finds nothing is therefore not proof of absence,
     * and a list you can pick from beats a list that is empty and sure of
     * itself.
     */
    fun scanBluetooth(context: Context) {
        if (scanning) return
        val adapter: BluetoothAdapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        discovered.clear()
        scanStatus = ""
        wide = false
        scanner = adapter.bluetoothLeScanner ?: return
        beginScan(filtered = true)
        if (!scanning) return

        widenTask = Runnable {
            if (!scanning || discovered.isNotEmpty()) return@Runnable
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Log.w(TAG, "stop refused: ${e.message}")
            }
            wide = true
            scanStatus = "Nothing is advertising MIDI. Showing everything nearby - a device that keeps " +
                "its service in the scan response will still open."
            beginScan(filtered = false)
        }.also { handler?.postDelayed(it, 6_000) }
        endTask = Runnable {
            val none = discovered.isEmpty()
            stopScan()
            if (none) {
                scanStatus = "Nothing found. Check the device is switched on and not already paired " +
                    "in Android's own Bluetooth settings - a paired BLE MIDI device stops advertising."
            }
        }.also { handler?.postDelayed(it, 16_000) }
    }

    fun stopScan() {
        widenTask?.let { handler?.removeCallbacks(it) }
        endTask?.let { handler?.removeCallbacks(it) }
        widenTask = null
        endTask = null
        if (!scanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stop refused: ${e.message}")
        }
        scanning = false
    }

    fun connectBluetooth(context: Context, address: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad address $address"); return
        }
        stopScan()
        scanStatus = "opening…"
        try {
            manager?.openBluetoothDevice(device, { opened ->
                if (opened == null) {
                    // Android hands back nothing and says nothing. Usually it
                    // is not a MIDI device at all, or it is already paired in
                    // the system's Bluetooth settings and so is not listening.
                    Log.w(TAG, "openBluetoothDevice gave nothing for $address")
                    scanStatus = "Could not open that device. If it is paired in Android's Bluetooth " +
                        "settings, forget it there and scan again."
                } else {
                    scanStatus = ""
                    attach(opened.info.id, opened)
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "connect refused: ${e.message}")
            scanStatus = "Android refused the connection: allow Nearby devices"
        }
    }

    /**
     * Prove the routing without hardware: middle C, held long enough to be
     * heard and to show up on the meters, down the same path a port uses.
     */
    fun testNote() {
        dispatch(0x90, 60, 100)
        handler?.postDelayed({ dispatch(0x80, 60, 0) }, 1500)
    }
}
