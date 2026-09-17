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
import android.media.midi.MidiInputPort
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
     * where the spec has a 4, and cost an evening to find.
     */
    private val BLE_MIDI_SERVICE = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private const val TAG = "Acidulous.MIDI"

    data class Port(val id: Int, val name: String, val maker: String, val bluetooth: Boolean, val open: Boolean)
    /** Somewhere to send to. Android calls it the device's *input* port. */
    data class Destination(val id: Int, val name: String, val open: Boolean)
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
    val destinations = mutableStateListOf<Destination>()
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

    // --- out -----------------------------------------------------------------
    private val outPorts = HashMap<Int, MidiInputPort>()
    private val outBuffer = LongArray(256 * 2)
    private val anchor = LongArray(3)
    private val outBytes = ByteArray(3)

    /** Twenty-four pulses a quarter note, to every destination that is open. */
    var clockOut by mutableStateOf(false)
        private set

    /**
     * How far ahead of the audio to send, in milliseconds. The engine's own
     * latency is compensated automatically from the stream's anchor; this is
     * the trim for everything after it - the cable, the synth, and the last
     * few milliseconds of a phone's audio path that nothing can measure.
     */
    var outOffsetMs by mutableStateOf(0)

    /** What the engine has handed over, whether or not anything was listening.
     *  Separate from [sent] because "the clock is running but nothing is
     *  plugged in" and "nothing is happening" are different problems. */
    var produced by mutableStateOf(0)
        private set
    var sent by mutableStateOf(0)
        private set
    /** How far from its intended time the last batch went out. The number to
     *  report when something sounds loose. */
    var outLateMs by mutableStateOf(0f)
        private set
    var anchored by mutableStateOf(false)
        private set

    /** Which rack plays when routing is [Routing.SelectedTrack]. */
    var target: () -> Int = { 0 }

    /**
     * Offered every controller and note-on before it reaches the engine.
     *
     * Returns true when the mapping layer took it, and then it goes no
     * further. The policy - what is mapped, what is being learned, what a
     * mapped thing does - lives with the song and the editor rather than
     * here; this is a hub, and it should not need to know what a lane is.
     */
    var onMappable: (cc: Int?, note: Int?, value: Int, rack: Int) -> Boolean = { _, _, _, _ -> false }

    /** Notes a mapping swallowed, so their note-offs go the same way. */
    private val swallowed = HashSet<Int>()

    /**
     * Where every sounding note went, so its release follows it there - and
     * what a vanished controller was holding, so those notes can be let go.
     * The logic lives in [HeldNotes], which has no Android in it and is tested
     * on its own, because this is the part that was wrong.
     */
    private val held = HeldNotes()

    /** Currently dispatching from this port, or -1 for the test generators. */
    private var currentPort = -1

    /** Nothing is held any more: forget where everything went. */
    fun forgetSounding() = held.clear()

    /** Let go of whatever a port was holding; it will never send the offs. */
    private fun releasePort(portId: Int) {
        val freed = held.release(portId)
        for (h in freed) {
            NativeEngine.midiEvent(h.rack, 0x80, h.note, 0, NativeEngine.NO_CHANNEL)
        }
        if (freed.isNotEmpty()) {
            lastMessage = "released ${freed.size} held note${if (freed.size == 1) "" else "s"}"
        }
    }

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
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                // Plugged in while the app is running: open it. A controller
                // you have just connected is a controller you want to play,
                // and making somebody find a dialog to say so is a step that
                // has no other possible answer. Dan: "any MIDI devices plugged
                // in after the app is started are automatically enabled".
                //
                // Inputs only. Opening an *output* would start sending notes
                // and clock to something the moment it appeared, which is a
                // decision rather than a convenience.
                if (device.outputPortCount > 0 && device.id !in declined) {
                    open(device.id)
                }
                refresh()
            }
            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                // Unplugged mid-note: nothing else will ever send the off.
                releasePort(device.id)
                opened.remove(device.id)?.close()
                refresh()
            }
        }, handler)
        // Preferences are restored one line before this runs, so a clock-out
        // setting that survived a restart asked for a sender that had no
        // thread to run on yet. Ask again now there is one.
        if (clockOut) startSender()
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
        destinations.clear()
        @Suppress("DEPRECATION")
        mgr.devices.filter { it.inputPortCount > 0 }.forEach { info ->
            val props = info.properties
            destinations += Destination(
                id = info.id,
                name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
                    ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device",
                open = outPorts.containsKey(info.id),
            )
        }
    }

    // --- Sending ---------------------------------------------------------------
    //
    // The engine stamps every event with the frame it belongs on; the audio
    // stream says which wall-clock nanosecond a frame will be heard at; and
    // Android's MidiInputPort.send takes a nanosecond timestamp and schedules
    // it. So the sender has to be *early*, not fast - it drains every few
    // milliseconds and hands over events that are still in the future, and
    // the platform does the fine timing. A tight loop would be worse and
    // would still be at the mercy of the scheduler.

    fun toggleDestination(id: Int) {
        val mgr = manager ?: return
        val existing = outPorts.remove(id)
        if (existing != null) {
            runCatching { existing.close() }
            refresh()
            return
        }
        @Suppress("DEPRECATION")
        val info = mgr.devices.firstOrNull { it.id == id } ?: return
        mgr.openDevice(info, { device ->
            val port = device?.openInputPort(0)
            if (port == null) {
                Log.w(TAG, "could not open an input port on $id")
            } else {
                outPorts[id] = port
                startSender()
            }
            refresh()
        }, handler)
    }

    private var sending = false
    private val pumpTask = object : Runnable {
        override fun run() {
            pump()
            if (sending) handler?.postDelayed(this, 4)
        }
    }

    private fun startSender() {
        // Idempotent by re-posting rather than by an early return on a flag:
        // preferences are restored before the thread exists, so the first
        // call sets the flag and loses the post, and a flag-guarded second
        // call would then do nothing at all and the sender would never run.
        sending = true
        handler?.let { h ->
            h.removeCallbacks(pumpTask)
            h.post(pumpTask)
        }
    }

    private fun stopSender() {
        sending = false
        handler?.removeCallbacks(pumpTask)
    }

    /** How many bytes a status byte carries with it. */
    private fun lengthOf(status: Int): Int = when {
        status == 0xf2 -> 3                       // song position
        status >= 0xf8 -> 1                       // clock, start, continue, stop
        status == 0xf1 || status == 0xf3 -> 2
        (status and 0xf0) == 0xc0 -> 2            // program change
        (status and 0xf0) == 0xd0 -> 2            // channel pressure
        else -> 3
    }

    private fun pump() {
        // Drain first and unconditionally. With the clock running and nothing
        // listening the queue would otherwise fill and stay full, and the
        // anchor readout would never say anything at all.
        val n = NativeEngine.drainMidiOut(outBuffer)
        NativeEngine.audioAnchor(anchor)
        anchored = anchor[0] >= 0
        produced += n
        if (n <= 0 || outPorts.isEmpty()) {
            if (!clockOut && outPorts.isEmpty()) stopSender()
            return
        }
        val anchorFrame = anchor[0]
        val anchorNanos = anchor[1]
        val rate = if (anchor[2] > 0) anchor[2] else 48000L
        val trim = outOffsetMs.toLong() * 1_000_000L
        val now = System.nanoTime()
        var worstLate = 0L

        for (i in 0 until n) {
            val frame = outBuffer[i * 2]
            val packed = outBuffer[i * 2 + 1]
            val rack = ((packed shr 24) and 0xff).toInt()
            val status = ((packed shr 16) and 0xff).toInt()
            val d1 = ((packed shr 8) and 0xff).toInt()
            val d2 = (packed and 0xff).toInt()

            // When this frame will actually be heard, less the trim. Without
            // an anchor the stream cannot say, so it goes out now and the
            // readout says as much rather than pretending.
            val at = if (anchorFrame >= 0) {
                anchorNanos + (frame - anchorFrame) * 1_000_000_000L / rate - trim
            } else {
                now
            }
            if (at < now) worstLate = maxOf(worstLate, now - at)

            val len = lengthOf(status)
            outBytes[0] = status.toByte()
            if (len > 1) outBytes[1] = d1.toByte()
            if (len > 2) outBytes[2] = d2.toByte()

            if (rack == 0xff) {
                // The transport's own: clock, start, stop, position. Everyone
                // listening gets it.
                for (port in outPorts.values) runCatching { port.send(outBytes, 0, len, at) }
            } else {
                val port = outPorts.values.firstOrNull()
                if (port != null) runCatching { port.send(outBytes, 0, len, at) }
            }
            sent += 1
        }
        // Anything already in the past by the time it was handed over is the
        // measure of whether this is working. Smoothed, because one late
        // batch is the scheduler and a hundred is a problem.
        outLateMs = outLateMs * 0.9f + (worstLate / 1_000_000.0f) * 0.1f
    }

    // Not setClockOut: the property's own generated setter has that JVM
    // signature already, the same trap as chooseTheme and chooseClipMode.
    // --- Following someone else's clock ---------------------------------------
    var clockIn by mutableStateOf(false)
        private set
    var followBpm by mutableStateOf(0f)
        private set
    var followErrorMs by mutableStateOf(0f)
        private set
    var followLocked by mutableStateOf(false)
        private set

    /**
     * A realtime byte arrived. Its timestamp is turned into a frame here,
     * through the same anchor the sender uses in the other direction, so
     * the engine is handed something already in its own time base.
     */
    private fun clockIn(status: Int, d1: Int, d2: Int, stamp: Long) {
        if (!clockIn) return
        NativeEngine.audioAnchor(anchor)
        val anchorFrame = anchor[0]
        val rate = if (anchor[2] > 0) anchor[2] else 48000L
        val at = if (stamp > 0L) stamp else System.nanoTime()
        val frame = if (anchorFrame >= 0) {
            anchorFrame + (at - anchor[1]) * rate / 1_000_000_000L
        } else {
            0L
        }
        NativeEngine.midiClockIn(frame, status, d1, d2)
    }

    fun chooseExternalSync(on: Boolean) {
        clockIn = on
        NativeEngine.setExternalSync(on)
    }

    /** Called from the poll: what the follower is making of it. */
    /**
     * Which member channels are holding a note, as a bit per channel.
     *
     * State here rather than polled inside the window, because the window
     * measures every one of its pages to size itself to the tallest and a
     * `remember` in a page that is measured and discarded never keeps
     * anything. Everything else on that page reads state from this object
     * for the same reason.
     */
    var mpeHeld by mutableStateOf(0)
        private set

    fun readSync() {
        if (mpeZone != 0) mpeHeld = NativeEngine.mpeHeldMask
        if (!clockIn) return
        val packed = NativeEngine.syncState()
        followLocked = ((packed ushr 56) and 0xff) != 0L
        followBpm = (((packed ushr 32) and 0xffffff).toInt()) / 100f
        followErrorMs = (packed and 0xffffffffL).toInt() / 1000f
    }

    /**
     * Ten seconds of a perfectly regular master, generated here.
     *
     * The follower cannot be tested without something to follow, and an
     * emulator has nothing to plug in. The pulses are stamped from a fixed
     * start rather than from when this thread happens to wake up, so what
     * is being tested is the loop and the whole chain behind it - parser,
     * JNI, queue, clock - rather than the accuracy of a Handler.
     */
    fun testClock(bpm: Float = 120f) {
        if (!clockIn) return
        val periodNs = (60.0e9 / (bpm.toDouble() * 24.0)).toLong()
        val start = System.nanoTime() + 50_000_000L
        val pulses = (10.0 * 24.0 * bpm / 60.0).toInt()
        clockIn(0xfa, 0, 0, start)
        for (i in 0 until pulses) {
            val at = start + i * periodNs
            handler?.postDelayed({ clockIn(0xf8, 0, 0, at) }, ((at - System.nanoTime()) / 1_000_000L).coerceAtLeast(0))
        }
        handler?.postDelayed({ clockIn(0xfc, 0, 0, start + pulses * periodNs) },
            ((start + pulses * periodNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0))
    }

    fun chooseClockOut(on: Boolean) {
        clockOut = on
        NativeEngine.setClockOut(on)
        if (on) startSender()
    }

    fun toggle(portId: Int) {
        if (opened.containsKey(portId)) {
            // Switched off by hand. Remember that, or unplugging and plugging
            // it back in would quietly turn it on again and the switch would
            // look like it does not work.
            declined += portId
            close(portId)
            return
        }
        declined -= portId
        open(portId)
    }

    /** Open a port for input, if it is there and not already open. */
    private fun open(portId: Int) {
        if (opened.containsKey(portId)) return
        val mgr = manager ?: return
        val info = @Suppress("DEPRECATION") mgr.devices.firstOrNull { it.id == portId } ?: return
        if (info.outputPortCount <= 0) return
        mgr.openDevice(info, { device -> attach(portId, device) }, handler)
    }

    /**
     * Ports the user switched off by hand, so a hot-plug does not undo it.
     *
     * Only for this run: a device the user turned off and then physically
     * unplugged and reconnected is a fresh decision, and the far commoner case
     * is that they want it on.
     */
    private val declined = HashSet<Int>()

    private fun attach(portId: Int, device: MidiDevice?) {
        if (device == null) {
            Log.w(TAG, "could not open device $portId")
            return
        }
        opened[portId] = device
        val parser = MidiParser(
            onMessage = { status, d1, d2 -> currentPort = portId; dispatch(status, d1, d2); currentPort = -1 },
            onRealtime = { status, d1, d2, stamp -> clockIn(status, d1, d2, stamp) },
        )
        parsers[portId] = parser
        val receiver = object : MidiReceiver() {
            // The timestamp is the whole point of following a clock: a
            // handler thread's wake-up is jittery by milliseconds, and this
            // is not.
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                parser.parse(msg, offset, count, timestamp)
            }
        }
        for (p in 0 until device.info.outputPortCount) {
            device.openOutputPort(p)?.connect(receiver)
        }
        refresh()
    }

    private fun close(portId: Int) {
        releasePort(portId)
        opened.remove(portId)?.close()
        parsers.remove(portId)
        refresh()
    }

    /**
     * Re-address a message and push it at the engine. Channel 10 is not
     * special here: a rack is whatever the routing says it is.
     */
    /**
     * An MPE zone: 0 off, 1 lower (master channel 1, members climbing from
     * 2), 2 upper (master 16, members descending from 15).
     *
     * A zone is one instrument played with many channels, so while one is
     * on, channel-to-track routing cannot also be true - the member
     * channels are fingers, not tracks. Follow and pinned still choose
     * which track the zone plays.
     */
    // Compose state, like every other setting here: the MIDI window reads
    // these directly and would not redraw for a plain var.
    var mpeZone by mutableStateOf(0)
        private set
    var mpeMembers by mutableStateOf(15)
        private set
    var mpeBendSemis by mutableStateOf(48f)
        private set
    var mpeTimbre by mutableStateOf(true)
        private set

    fun chooseMpe(zone: Int, members: Int, bendSemis: Float, timbre: Boolean) {
        mpeZone = MpeZone.clampZone(zone)
        mpeMembers = MpeZone.clampMembers(members)
        mpeBendSemis = MpeZone.clampBend(bendSemis)
        mpeTimbre = timbre
        NativeEngine.setMpeZone(mpeZone, mpeMembers, mpeBendSemis)
    }

    /** Is this channel one of the zone's fingers? Channels are 0-based here. */
    fun mpeMember(channel: Int): Boolean = MpeZone.member(mpeZone, mpeMembers, channel)

    private fun dispatch(status: Int, d1: Int, d2: Int) {
        val kind = status and 0xf0
        if (kind == 0xc0) return // program change: nothing to address it to yet
        val channel = status and 0x0f
        val member = mpeMember(channel)
        val rack = when {
            // Every finger plays the one instrument the zone is pointed at.
            member -> if (routing == Routing.FixedTrack) fixedRack else target()
            routing == Routing.FixedTrack -> fixedRack
            routing == Routing.ChannelToRack -> channel
            else -> target()
        }
        // A note whose note-on a mapping took must not have its note-off
        // delivered either, or the machine is left holding a note it was
        // never given.
        val isOff = kind == 0x80 || (kind == 0x90 && d2 == 0)
        // Where this actually goes. A note that is already sounding goes back
        // to the rack that was given its note-on, whatever is selected now;
        // expression on a member channel follows the note it is shaping.
        val rackNow = when {
            isOff -> held.rackForOff(channel, d1) ?: rack
            kind == 0x90 -> rack
            member -> held.rackForExpression(channel) ?: rack
            else -> rack
        }
        val taken = when {
            isOff -> swallowed.remove(d1)
            kind == 0xb0 -> onMappable(d1, null, d2, rack)
            kind == 0x90 -> onMappable(null, d1, d2, rack).also { if (it) swallowed += d1 }
            else -> false
        }
        // Slide is only slide if the zone says so; otherwise CC 74 is an
        // ordinary controller and has whatever meaning a mapping gives it.
        val expressive = member && !(kind == 0xb0 && d1 == 74 && !mpeTimbre)
        if (!taken) {
            NativeEngine.midiEvent(
                rackNow, kind, d1, d2,
                if (expressive) channel else NativeEngine.NO_CHANNEL,
            )
        }
        // Remember, and forget, where notes went.
        if (kind == 0x90 && d2 > 0 && !taken) {
            held.onNoteOn(currentPort, channel, d1, rackNow)
        } else if (isOff) {
            held.onNoteOff(channel, d1)
        }
        received += 1
        lastMessage = when (kind) {
            0x90 -> if (d2 == 0) "off $d1" else "on $d1 v$d2"
            0x80 -> "off $d1"
            0xb0 -> "cc $d1 = $d2"
            0xd0 -> "prs $d1"
            0xe0 -> "bend ${((d2 shl 7) or d1) - 8192}"
            else -> "%02x".format(status)
        } + " → rack ${rackNow + 1}"
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

    /**
     * A knob sweep, down the path a real controller takes.
     *
     * There is no way to exercise an incoming CC on an emulator, and the one
     * thing this window exists to do is say out loud what it thinks is
     * happening. [cc] defaults to the mod wheel, which is the one controller
     * the app already answers without any mapping.
     */
    fun testWheel(cc: Int = 1) {
        for (i in 0..20) {
            handler?.postDelayed({ dispatch(0xb0, cc, i * 127 / 20) }, (i * 60).toLong())
        }
    }

    /**
     * Two fingers, and only one of them moves.
     *
     * The whole of MPE in one gesture: two notes arrive on their own member
     * channels, and then a bend, a press and a slide are sent on the first
     * channel only. If the second note moves too, the expression is not
     * reaching the voice that owns it - which is the one thing that can go
     * wrong here and the one thing an emulator cannot otherwise show.
     *
     * The channels are the zone's own first two members, so this exercises
     * the same arithmetic a controller would.
     */
    fun testMpe() {
        if (mpeZone == 0) return
        val a = if (mpeZone == 1) 1 else 14
        val b = if (mpeZone == 1) 2 else 13
        dispatch(0x90 or a, 60, 100)
        dispatch(0x90 or b, 64, 100)
        for (i in 0..20) {
            val t = (i * 140).toLong()
            val bend = 8192 + i * 8191 / 20
            handler?.postDelayed({
                dispatch(0xe0 or a, bend and 0x7f, (bend shr 7) and 0x7f)
                dispatch(0xd0 or a, i * 127 / 20, 0)
                dispatch(0xb0 or a, 74, i * 127 / 20)
            }, t)
        }
        // Long enough to hear, and long enough to watch the readout: the
        // sweep alone is three seconds.
        handler?.postDelayed({ dispatch(0x80 or a, 60, 0); dispatch(0x80 or b, 64, 0) }, 3400)
    }
}
