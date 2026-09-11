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
    /** MIDI over Bluetooth Low Energy: the GATT service every such device advertises. */
    private val BLE_MIDI_SERVICE = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC8C700")
    private const val TAG = "Acidulous.MIDI"

    data class Port(val id: Int, val name: String, val maker: String, val bluetooth: Boolean, val open: Boolean)
    data class Found(val address: String, val name: String)

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
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "unnamed"
            if (discovered.none { it.address == result.device.address }) {
                discovered += Found(result.device.address, name)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            scanning = false
        }
    }

    fun scanBluetooth(context: Context) {
        if (scanning) return
        val adapter: BluetoothAdapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        discovered.clear()
        scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(BLE_MIDI_SERVICE).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            handler?.postDelayed({ stopScan() }, 12_000)
        } catch (e: SecurityException) {
            Log.w(TAG, "scan refused: ${e.message}")
        }
    }

    fun stopScan() {
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
        try {
            manager?.openBluetoothDevice(device, { opened -> opened?.let { attach(it.info.id, it) } }, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "connect refused: ${e.message}")
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
