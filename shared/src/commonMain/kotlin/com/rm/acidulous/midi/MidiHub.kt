package com.rm.acidulous.midi

import kotlin.concurrent.Volatile

import com.rm.acidulous.util.Runnable

import com.rm.acidulous.util.System

import com.rm.acidulous.util.Math

import com.rm.acidulous.util.format

import com.rm.acidulous.midi.launchpad.LaunchpadPro
import com.rm.acidulous.util.Log
import com.rm.acidulous.util.postToMain
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * Playing Acidulous from MIDI controllers.
 *
 * Once a device is open, Android handles USB and Bluetooth MIDI through the
 * same interface, so almost all of this is one path. The difference is
 * getting there: a USB device announces itself and can be opened, while a
 * Bluetooth one has to be found by scanning for the MIDI service and handed
 * to [MidiManager.openBluetoothDevice] first. Doing that scan here means no
 * separate bridge app is needed.
 *
 * Everything that arrives is sent to a rack through the same engine call the
 * on-screen keyboard uses, so recording, modifiers and the machine behave
 * the same either way.
 */
object MidiHub {
    /** Roughly how long a Launchpad's start-up lights last: see lpAgain. */
    private const val LP_AGAIN_MS = 1500L

    private const val TAG = "Acidulous.MIDI"
    private const val AUTO_GONE_NS = 1_000_000_000L

    data class Port(val id: Int, val name: String, val maker: String, val bluetooth: Boolean, val open: Boolean)
    /** Somewhere to send to. Android calls it the device's input port. */
    data class Destination(val id: Int, val name: String, val open: Boolean)
    /** [midi] is true when the advertisement named the MIDI service. */
    data class Found(val address: String, val name: String, val midi: Boolean)

    /**
     * Where incoming notes go. Following the selected track suits writing, a
     * fixed track suits using the phone as a sound module, and channel to
     * rack is for a controller that plays several tracks at once.
     */
    enum class Routing { SelectedTrack, FixedTrack, ChannelToRack }

    /** The platform's MIDI: MidiManager on Android. Null until [start]. */
    private var system: MidiSystem? = null

    /** One of the hub's own messages, in the user's language. */
    internal fun say(id: StringResource, vararg args: Any): String = AppStrings.getString(id, *args)
    private val handler: MidiWorker? get() = system?.worker
    private val opened = HashMap<Int, MidiOpenDevice>()
    /** A parser for each output port of each open device: see [attach]. */
    private val parsers = HashMap<Int, List<MidiParser>>()

    val ports = mutableStateListOf<Port>()
    val destinations = mutableStateListOf<Destination>()
    val discovered = mutableStateListOf<Found>()
    var scanning by mutableStateOf(false)
        internal set

    /** Why the list looks the way it does. Empty when there's nothing to say. */
    var scanStatus by mutableStateOf("")
        internal set
    var routing by mutableStateOf(Routing.SelectedTrack)
    var lastMessage by mutableStateOf("")
        private set
    var received by mutableStateOf(0)
        private set

    // --- out -----------------------------------------------------------------
    private val outPorts = HashMap<Int, MidiSendPort>()
    private val outBuffer = LongArray(256 * 2)
    private val anchor = LongArray(3)
    private val outBytes = ByteArray(3)

    /** 24 pulses per quarter note, to every open destination. */
    var clockOut by mutableStateOf(false)
        private set

    /**
     * How far ahead of the audio to send, in milliseconds. The engine's own
     * latency is compensated automatically from the stream's anchor. This is
     * the trim for everything after it: the cable, the synth, and the last
     * few milliseconds of the phone's audio path that can't be measured.
     */
    var outOffsetMs by mutableStateOf(0)
    /** How controller note-on velocities are bent before anything hears them: see [VelocityCurve]. */
    var velocityCurve by mutableStateOf(0)

    /** What the engine has handed over, whether or not anything was listening.
     *  Separate from [sent] because "the clock is running but nothing is
     *  plugged in" and "nothing is happening" are different problems. */
    var produced by mutableStateOf(0)
        private set
    var sent by mutableStateOf(0)
        private set
    /** How far from its intended time the last batch went out. The number to
     *  check when timing sounds loose. */
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
     * further. What's mapped, what's being learned and what a mapping does
     * live with the song and the editor, so the hub doesn't need to know
     * about lanes.
     */
    var onMappable: (cc: Int?, note: Int?, value: Int, rack: Int) -> Boolean = { _, _, _, _ -> false }

    /** Notes a mapping swallowed, so their note-offs go the same way. */
    private val swallowed = HashSet<Int>()

    /**
     * Where every held note went, so its note-off follows it, and what a
     * disconnected controller was holding, so those notes can be released.
     * The logic is in [HeldNotes], which has no Android code and is tested
     * on its own.
     */
    private val held = HeldNotes()

    /**
     * Held while a message is dispatched. Each port delivers on its own
     * thread, the test generators on the handler's, and dispatch keeps what's
     * held and swallowed in plain maps, so they take turns.
     */
    private val dispatching = Any()

    /** Nothing is held any more: forget where everything went. */
    fun forgetSounding() = com.rm.acidulous.util.locked(dispatching) { held.clear() }

    /** Release whatever a port was holding, since it will never send the note-offs. */
    private fun releasePort(portId: Int) {
        val freed = com.rm.acidulous.util.locked(dispatching) { held.release(portId) }
        for (h in freed) {
            NativeEngine.midiEvent(h.rack, 0x80, h.note, 0, NativeEngine.NO_CHANNEL)
        }
        if (freed.isNotEmpty()) {
            lastMessage = AppStrings.getQuantityString(Res.plurals.midi_released, freed.size, freed.size)
        }
    }

    /** Which rack plays when routing is [Routing.FixedTrack]. */
    var fixedRack by mutableStateOf(0)

    val supported: Boolean
        get() = system?.supported == true

    fun start(midi: MidiSystem) {
        if (system != null) return
        system = midi
        refresh()
        midi.watch(
            added = { device ->
                // Plugged in while the app is running: open it, since a
                // controller that was just connected is one the user wants
                // to play.
                //
                // Inputs only. Opening an output would start sending notes
                // and clock to it straight away, which should be a choice.
                if (device.outputPortCount > 0 && device.id !in declined) {
                    open(device.id)
                }
                syncPads()
                syncLaunchpad()
                refresh()
            },
            removed = { device ->
                // Unplugged mid-note: nothing else will send the note-off.
                releasePort(device.id)
                opened.remove(device.id)?.close()
                parsers.remove(device.id)
                if (opened.isEmpty()) forgetController()
                syncPads()
                syncLaunchpad()
                refresh()
            },
        )
        // Already plugged in when the app started: light its pads and take the Launchpad.
        handler?.post { syncPads(); syncLaunchpad() }
        // Preferences are restored just before this runs, so a saved clock
        // out setting asked for a sender before its thread existed. Ask again
        // now there is one.
        if (clockOut) startSender()
    }

    fun refresh() {
        val sys = system ?: return
        val all = sys.devices
        ports.clear()
        all.filter { it.outputPortCount > 0 }.forEach { info ->
            ports += Port(
                id = info.id,
                name = info.name ?: info.product ?: say(Res.string.midi_device),
                maker = info.maker.orEmpty(),
                bluetooth = info.bluetooth,
                open = opened.containsKey(info.id),
            )
        }
        destinations.clear()
        all.filter { it.inputPortCount > 0 }.forEach { info ->
            destinations += Destination(
                id = info.id,
                name = info.name ?: info.product ?: say(Res.string.midi_device),
                open = outPorts.containsKey(info.id),
            )
        }
    }

    // --- The Exquis's pads ----------------------------------------------------
    //
    // Shows the scale of the track it plays on its pads: see PadLights. Uses
    // its own port, opened just for this, unless the Exquis is also a MIDI
    // out destination, in which case the port is shared since an input port
    // only opens once. Everything here runs on the MIDI thread.

    /** How an Exquis shows the scale: its own tonic and scale, a highlight, or not at all. */
    enum class PadMode { Own, Highlight, Off }

    var padMode by mutableStateOf(PadMode.Own)
        private set
    /** Whether the app has the Exquis's transport and undo buttons. */
    var exquisButtons by mutableStateOf(true)
        private set
    /** A press of one of those buttons, by id (PadLights.BUTTON_*), on the main thread. */
    var exquisButtonPressed: ((Int) -> Unit)? = null
    /** Whether the app currently holds the buttons zone in developer mode on this Exquis. */
    @Volatile private var buttonsHeld = false
    private var wantedLeds: Map<Int, Triple<Int, Int, Int>> = emptyMap()
    private val shownLeds = HashMap<Int, Triple<Int, Int, Int>>()
    fun chooseExquisButtons(on: Boolean) {
        exquisButtons = on
        handler?.post { syncPads() }
    }

    /** What the buttons should show, by id, as colours of 0..127 per part. */
    fun showExquisButtons(leds: Map<Int, Triple<Int, Int, Int>>) {
        wantedLeds = leds
        handler?.post { syncPads() }
    }

    /** The channel the Exquis last played on, for routing by channel. */
    var exquisChannel by mutableStateOf(0)
        private set
    /** An Exquis is plugged in, so its switch is worth showing. */
    var exquisHere by mutableStateOf(false)
        private set
    private var padInfo: MidiDeviceDesc? = null
    private var padDevice: MidiOpenDevice? = null
    private var padPort: MidiSendPort? = null
    private val lit = HashSet<Int>()
    private var wantedLit: Set<Int> = emptySet()
    private var wantedRoot: Int? = null
    private var wantedClasses: Set<Int>? = null
    /** The tonic and scale last set on the Exquis itself, or null to set them again. */
    private var sentScale: Pair<Int, Int>? = null
    /** Note-offs for every note have been sent to this Exquis, clearing anything a previous run left lit. */
    private var cleaned = false
    private val padBytes = ByteArray(3)

    fun choosePadMode(mode: PadMode) {
        padMode = mode
        handler?.post { syncPads() }
    }

    /**
     * What the pads should show: a tonic and the pitch classes in the key,
     * or none. The scale of the track the Exquis plays, see
     * [trackForChannel].
     */
    fun showScale(root: Int?, pitchClasses: Set<Int>?) {
        val intervals = if (root == null || pitchClasses == null) null else pitchClasses.map { Math.floorMod(it - root, 12) }.sorted()
        wantedLit = PadLights.notes(root, intervals)
        wantedRoot = root
        wantedClasses = pitchClasses
        handler?.post { syncPads() }
    }

    /** Turn the pads off and hand the buttons back before the app closes. Runs on the caller's thread so it's done in time. */
    fun clearPads() {
        val port = padPortNow() ?: return
        for ((status, note, vel) in PadLights.changes(lit, emptySet(), null)) sendPad(port, status, note, vel)
        lit.clear()
        if (buttonsHeld) {
            sendBytes(port, PadLights.exquisSetup(0))
            buttonsHeld = false
        }
    }

    private fun padPortNow(): MidiSendPort? = padInfo?.let { outPorts[it.id] } ?: padPort

    private fun sendBytes(port: MidiSendPort, bytes: ByteArray) {
        runCatching { port.send(bytes, 0, bytes.size) }
    }

    private fun sendPad(port: MidiSendPort, status: Int, note: Int, vel: Int) {
        padBytes[0] = status.toByte(); padBytes[1] = note.toByte(); padBytes[2] = vel.toByte()
        runCatching { port.send(padBytes, 0, 3) }
    }

    private fun syncPads() {
        val sys = system ?: return
        val info = sys.devices.firstOrNull {
            // Over USB, since that's where its manual says it listens for this.
            it.inputPortCount > 0 && it.usb && PadLights.isExquis(it.name, it.product, it.maker)
        }
        exquisHere = info != null
        if (info == null || info.id != padInfo?.id) {
            // Gone, or a different one: release what we held.
            runCatching { padPort?.close() }
            runCatching { padDevice?.close() }
            padPort = null; padDevice = null; padInfo = null
            lit.clear()
            sentScale = null
            cleaned = false
            buttonsHeld = false
            shownLeds.clear()
        }
        if (info == null) return
        padInfo = info
        val port = padPortNow()
        if (port == null) {
            if ((padMode == PadMode.Off && !exquisButtons) || padDevice != null) return
            sys.openDevice(info) { device ->
                padDevice = device
                padPort = device?.openInputPort(0)
                if (padPort == null) Log.w(TAG, "could not open the Exquis for its pads")
                syncPads()
            }
            return
        }
        // Once per connection: note-offs for every note on channel 1, so
        // nothing left highlighted by a previous run stays lit.
        if (!cleaned) {
            for (n in 0..127) sendPad(port, 0x80, n, 0)
            lit.clear()
            cleaned = true
        }
        // Its buttons: taken or given back, and lit to match the app.
        if (exquisButtons && !buttonsHeld) {
            sendBytes(port, PadLights.exquisSetup(PadLights.ZONE_BUTTONS))
            buttonsHeld = true
            shownLeds.clear()
        } else if (!exquisButtons && buttonsHeld) {
            sendBytes(port, PadLights.exquisSetup(0))
            buttonsHeld = false
        }
        if (buttonsHeld) {
            for ((id, c) in wantedLeds) {
                if (shownLeds[id] == c) continue
                sendBytes(port, PadLights.exquisLed(id, c.first, c.second, c.third))
                shownLeds[id] = c
            }
        }
        val target = if (padMode == PadMode.Highlight) wantedLit else emptySet()
        for ((status, note, vel) in PadLights.changes(lit, target, wantedRoot)) sendPad(port, status, note, vel)
        lit.clear(); lit += target
        // Its own tonic and scale, in the player's own colours.
        val root = wantedRoot
        val classes = wantedClasses
        if (padMode == PadMode.Own && root != null && classes != null) {
            val scale = PadLights.exquisScale(root, classes)
            if (scale != sentScale) {
                for (m in PadLights.exquisScaleMessages(scale.first, scale.second, inDeveloperMode = buttonsHeld)) sendBytes(port, m)
                sentScale = scale
            }
        }
    }

    // --- A Launchpad Pro [MK3] -------------------------------------------------
    //
    // Played by the app in Programmer mode: see midi/launchpad and
    // ui/launchpad. This is just the device side: finding it, switching its
    // mode, and carrying bytes each way. Messages from its port go to
    // [launchpadInput] instead of being played.

    /** Whether an attached Launchpad is controlled by Acidulous or left in its own mode. */
    var launchpadOn by mutableStateOf(true)
        private set
    /** One is plugged in, so its switch is worth showing. */
    var launchpadHere by mutableStateOf(false)
        private set
    /** Its presses, on the MIDI thread. Set by the controller. */
    var launchpadInput: ((Int, Int, Int) -> Unit)? = null
    /** Called when the app has just taken the surface and has to draw all of it. */
    var onLaunchpadReady: (() -> Unit)? = null
    private var lpInfo: MidiDeviceDesc? = null
    private var lpDevice: MidiOpenDevice? = null
    private var lpPort: MidiSendPort? = null
    private var lpProgrammer = false

    /**
     * Enter Programmer mode again shortly after the first time. A Launchpad
     * plugged in while the app runs plays start-up lights for a second or
     * two, and a message sent during them can be lost. Asking twice is
     * harmless: it stays in Programmer mode and the pads are redrawn.
     */
    private val lpAgain = Runnable {
        val port = lpPort
        if (port != null && lpProgrammer && launchpadOn) {
            val bytes = LaunchpadPro.programmer(true)
            runCatching { port.send(bytes, 0, bytes.size) }
            onLaunchpadReady?.invoke()
        }
    }

    fun chooseLaunchpad(on: Boolean) {
        launchpadOn = on
        handler?.post { syncLaunchpad() }
    }

    /** Bytes for the Launchpad, from any thread. Dropped unless the app controls it. */
    fun launchpadSend(bytes: ByteArray) {
        handler?.post {
            val port = lpPort ?: return@post
            if (lpProgrammer) runCatching { port.send(bytes, 0, bytes.size) }
        }
    }

    /** Back to its own Live mode before the app closes. Runs on the caller's thread so it's done in time. */
    fun releaseLaunchpad() {
        val port = lpPort ?: return
        val bytes = LaunchpadPro.programmer(false)
        if (lpProgrammer) runCatching { port.send(bytes, 0, bytes.size) }
        lpProgrammer = false
    }

    private fun syncLaunchpad() {
        val sys = system ?: return
        val info = sys.devices.firstOrNull {
            it.inputPortCount > 0 && it.usb && LaunchpadPro.isOne(it.name, it.product)
        }
        if ((info != null) != launchpadHere) {
            Log.i(TAG, if (info != null) "Launchpad: ${info.name} (${info.inputPortCount} to send to)" else "Launchpad: none")
        }
        launchpadHere = info != null
        if (info == null || info.id != lpInfo?.id) {
            runCatching { lpPort?.close() }
            runCatching { lpDevice?.close() }
            lpPort = null; lpDevice = null; lpInfo = null; lpProgrammer = false
        }
        if (info == null) return
        lpInfo = info
        val port = lpPort
        if (port == null) {
            if (!launchpadOn || lpDevice != null) return
            sys.openDevice(info) { device ->
                lpDevice = device
                lpPort = device?.openInputPort(0)
                if (lpPort == null) Log.w(TAG, "could not open the Launchpad")
                syncLaunchpad()
            }
            return
        }
        if (launchpadOn && !lpProgrammer) {
            val bytes = LaunchpadPro.programmer(true)
            runCatching { port.send(bytes, 0, bytes.size) }.onFailure { Log.w(TAG, "Launchpad: Programmer mode not sent", it) }
            Log.i(TAG, "Launchpad: Programmer mode")
            lpProgrammer = true
            onLaunchpadReady?.invoke()
            handler?.let { it.removeCallbacks(lpAgain); it.postDelayed(lpAgain, LP_AGAIN_MS) }
        } else if (!launchpadOn && lpProgrammer) {
            val bytes = LaunchpadPro.programmer(false)
            runCatching { port.send(bytes, 0, bytes.size) }
            lpProgrammer = false
        }
    }

    // --- Sending ---------------------------------------------------------------
    //
    // The engine stamps every event with the frame it belongs on, the audio
    // stream says which wall-clock nanosecond a frame will be heard at, and
    // Android's MidiInputPort.send takes a nanosecond timestamp and schedules
    // it. So the sender just needs to be early: it drains every few
    // milliseconds and hands over events that are still in the future, and
    // the platform does the precise timing.

    fun toggleDestination(id: Int) {
        val sys = system ?: return
        val existing = outPorts.remove(id)
        if (existing != null) {
            // The Exquis's pads were sharing it, so they keep it.
            if (id == padInfo?.id && padPort == null) padPort = existing else runCatching { existing.close() }
            refresh()
            return
        }
        // An input port only opens once, so share it if the pads have it.
        if (id == padInfo?.id && padPort != null) {
            outPorts[id] = padPort!!
            padPort = null
            startSender()
            refresh()
            return
        }
        val info = sys.devices.firstOrNull { it.id == id } ?: return
        sys.openDevice(info) { device ->
            val port = device?.openInputPort(0)
            if (port == null) {
                Log.w(TAG, "could not open an input port on $id")
            } else {
                outPorts[id] = port
                startSender()
            }
            refresh()
        }
    }

    private var sending = false
    private val pumpTask = object : Runnable {
        override fun run() {
            pump()
            if (sending) handler?.postDelayed(this, 4)
        }
    }

    private fun startSender() {
        // Re-posts every time rather than returning early on a flag.
        // Preferences are restored before the thread exists, so the first
        // call sets the flag but loses the post, and a guarded second call
        // would then never start the sender.
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
        // Always drain first. With the clock running and nothing listening
        // the queue would otherwise fill up and stay full, and the anchor
        // readout would never show anything.
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

            // When this frame will actually be heard, minus the trim. Without
            // an anchor the stream can't say, so it goes out now and the
            // readout shows that.
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
                // The transport's own messages (clock, start, stop, position)
                // go to every listener.
                for (port in outPorts.values) runCatching { port.send(outBytes, 0, len, at) }
            } else {
                val port = outPorts.values.firstOrNull()
                if (port != null) runCatching { port.send(outBytes, 0, len, at) }
            }
            sent += 1
        }
        // How late anything already in the past was when handed over shows
        // whether this is working. Smoothed, because one late batch is the
        // scheduler and a hundred is a problem.
        outLateMs = outLateMs * 0.9f + (worstLate / 1_000_000.0f) * 0.1f
    }

    // --- Following someone else's clock ---------------------------------------
    enum class Follow { Off, On, Auto }

    /** The setting. */
    var follow by mutableStateOf(Follow.Off)
        private set

    /** Whether the engine is following now: on, or auto with a clock arriving. */
    var clockIn by mutableStateOf(false)
        private set

    /**
     * When the last clock byte arrived, for auto. Written on the MIDI thread
     * and read by the poll.
     */
    @Volatile private var lastClockNs = 0L
    @Volatile private var autoFollowing = false
    var followBpm by mutableStateOf(0f)
        private set
    var followErrorMs by mutableStateOf(0f)
        private set
    var followLocked by mutableStateOf(false)
        private set

    /**
     * A real-time byte arrived. Its timestamp is converted to a frame here
     * using the same anchor the sender uses the other way, so the engine gets
     * it in its own time base.
     */
    private fun clockIn(status: Int, d1: Int, d2: Int, stamp: Long) {
        if (follow == Follow.Off) return
        lastClockNs = System.nanoTime()
        // Auto takes the clock from its first byte, here rather than on the
        // next poll: the engine reads the switch when it takes the byte off
        // its queue, so a start that arrives first isn't played on our own
        // clock. If Link is on, it keeps the tempo.
        if (follow == Follow.Auto && !autoFollowing && !com.rm.acidulous.engine.LinkHub.enabled) {
            autoFollowing = true
            NativeEngine.setExternalSync(true)
        }
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

    fun chooseFollow(mode: Follow) {
        follow = mode
        autoFollowing = false
        clockIn = mode == Follow.On
        NativeEngine.setExternalSync(clockIn)
    }

    /**
     * Which member channels are holding a note, one bit per channel.
     *
     * Kept as state here rather than polled inside the window, because the
     * window measures every page to size itself to the tallest, and a
     * `remember` in a page that's measured and discarded keeps nothing.
     * Everything else on that page reads state from this object for the same
     * reason.
     */
    var mpeHeld by mutableStateOf(0)
        private set

    /** Called from the poll: updates what the follower is doing. */
    fun readSync() {
        if (mpeZone != 0) mpeHeld = NativeEngine.mpeHeldMask
        if (follow == Follow.Auto) {
            // A clock master that stops sending has gone, so the song's own
            // tempo comes back. A second is eight pulses at 20 bpm, and longer
            // than a phone stalls for.
            if (autoFollowing && System.nanoTime() - lastClockNs > AUTO_GONE_NS) {
                autoFollowing = false
                NativeEngine.setExternalSync(false)
            }
            clockIn = autoFollowing
        }
        if (!clockIn) {
            followBpm = 0f
            followLocked = false
            return
        }
        val packed = NativeEngine.syncState()
        followLocked = ((packed ushr 56) and 0xff) != 0L
        followBpm = (((packed ushr 32) and 0xffffff).toInt()) / 100f
        followErrorMs = (packed and 0xffffffffL).toInt() / 1000f
    }

    /**
     * Ten seconds of a perfectly regular clock, generated here.
     *
     * The follower can't be tested without something to follow, and an
     * emulator has nothing to plug in. The pulses are timed from a fixed
     * start rather than from when this thread wakes up, so it tests the loop
     * and the whole chain behind it (parser, JNI, queue, clock) rather than
     * a Handler's accuracy.
     */
    fun testClock(bpm: Float = 120f) {
        if (follow == Follow.Off) return
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

    // Not setClockOut: the property's generated setter already has that JVM
    // signature (the same issue as chooseTheme and chooseClipMode).
    fun chooseClockOut(on: Boolean) {
        clockOut = on
        NativeEngine.setClockOut(on)
        if (on) startSender()
    }

    fun toggle(portId: Int) {
        if (opened.containsKey(portId)) {
            // Switched off by hand. Remember that, or unplugging and
            // replugging it would turn it back on and the switch would seem
            // not to work.
            declined += portId
            close(portId)
            return
        }
        declined -= portId
        open(portId)
    }

    /** Open a port for input, if it's there and not already open. */
    private fun open(portId: Int) {
        if (opened.containsKey(portId)) return
        val sys = system ?: return
        val info = sys.devices.firstOrNull { it.id == portId } ?: return
        if (info.outputPortCount <= 0) return
        sys.openDevice(info) { device -> attach(portId, device) }
    }

    /**
     * Ports the user switched off by hand, so plugging in again doesn't undo
     * it.
     *
     * Only for this run: after a restart a replugged device is opened again,
     * since usually the user wants it on.
     */
    private val declined = HashSet<Int>()

    private fun attach(portId: Int, device: MidiOpenDevice?) {
        if (device == null) {
            Log.w(TAG, "could not open device $portId")
            return
        }
        opened[portId] = device
        // One parser per port, not per device. A device with several ports
        // (a Launchpad has its own, a DIN socket and a DAW port) interleaves
        // them, and a single parser could join a message from one port onto
        // another's running status. It also lets the Launchpad's own port go
        // to its controller while its DIN socket plays like any other input.
        val launchpad = LaunchpadPro.isOne(device.desc.name, device.desc.product)
        val exquis = PadLights.isExquis(device.desc.name, device.desc.product, device.desc.maker)
        val list = ArrayList<MidiParser>()
        for (p in 0 until device.desc.outputPortCount) {
            val surface = launchpad && p == 0
            val parser = MidiParser(
                onMessage = { status, d1, d2 ->
                    val to = launchpadInput
                    if (surface && launchpadOn && to != null) {
                        lastMessage = "launchpad · %02x %d %d".format(status, d1, d2)
                        to(status, d1, d2)
                    } else if (exquis && buttonsHeld && status == 0xBF && d1 in PadLights.BUTTONS) {
                        // One of the Exquis buttons the app holds: an action, not a controller.
                        lastMessage = "exquis · button $d1 ${if (d2 > 0) "on" else "off"}"
                        PadLights.exquisButton(status, d1, d2)?.let { id ->
                            exquisButtonPressed?.let { f -> postToMain { f(id) } }
                        }
                    } else {
                        dispatch(status, d1, d2, portId)
                    }
                },
                onRealtime = { status, d1, d2, stamp -> clockIn(status, d1, d2, stamp) },
                // The Exquis says when it has drawn over its LEDs (entering
                // and leaving its settings menu), so the buttons are redrawn
                // then, and only then.
                onSysex = { body ->
                    if (exquis && PadLights.isExquisRefresh(body)) handler?.post {
                        shownLeds.clear()
                        syncPads()
                    }
                },
            )
            list += parser
                // The timestamp is what makes following a clock work: a
                // handler thread wakes up milliseconds late at random, and
                // this doesn't.
            device.connectOutputPort(p) { msg, offset, count, timestamp ->
                parser.parse(msg, offset, count, timestamp)
            }
        }
        parsers[portId] = list
        refresh()
    }

    private fun close(portId: Int) {
        releasePort(portId)
        opened.remove(portId)?.close()
        parsers.remove(portId)
        if (opened.isEmpty()) forgetController()
        refresh()
    }

    // MPE settings, as Compose state like every other setting here, since
    // the MIDI window reads them directly.
    //
    // Auto is the default and follows the controller. Otherwise the zone was
    // usually left off, and every finger's bend and pressure landed on the
    // whole track. On auto the zone and bend range come from the
    // controller's MPE configuration and pitch bend range messages, and a
    // controller that sends neither is recognised by how it plays (two notes
    // held at once on two channels).

    /** What the user chose: off, lower, upper, or auto (MpeZone.AUTO). */
    var mpeSetting by mutableStateOf(MpeZone.AUTO)
        private set
    /** The zone in use: the setting's, or on auto what the controller said. */
    var mpeZone by mutableStateOf(0)
        private set
    var mpeMembers by mutableStateOf(15)
        private set
    var mpeBendSemis by mutableStateOf(48f)
        private set
    var mpeTimbre by mutableStateOf(true)
        private set
    /** The member channel count and bend set by hand, for a lower or upper setting. */
    var mpeManualMembers by mutableStateOf(15)
        private set
    var mpeManualBend by mutableStateOf(48f)
        private set

    /** How auto got the zone it's using. */
    enum class MpeHeard { Nothing, Config, Fingers }
    var mpeHeard by mutableStateOf(MpeHeard.Nothing)
        private set
    /** What the controller has said and done: see [MpeAuto]. */
    private val auto = MpeAuto()

    /**
     * Choose the MPE zone: 0 off, 1 lower (master channel 1, members going up
     * from 2), 2 upper (master 16, members going down from 15), or auto.
     *
     * A zone is one instrument played across many channels, so while one is
     * on, channel-to-track routing can't apply: the member channels are
     * fingers, not tracks. Follow and fixed routing still choose which track
     * the zone plays.
     */
    fun chooseMpe(setting: Int, members: Int, bendSemis: Float, timbre: Boolean) {
        mpeSetting = MpeZone.clampSetting(setting)
        mpeManualMembers = MpeZone.clampMembers(members)
        mpeManualBend = MpeZone.clampBend(bendSemis)
        mpeTimbre = timbre
        applyMpe()
    }

    /** Sends the zone in use (the setting's, or auto's) to the engine. */
    private fun applyMpe() {
        if (mpeSetting == MpeZone.AUTO) {
            mpeZone = auto.zone
            mpeMembers = auto.members
            mpeBendSemis = auto.bendSemis
        } else {
            mpeZone = MpeZone.clampZone(mpeSetting)
            mpeMembers = mpeManualMembers
            mpeBendSemis = mpeManualBend
        }
        mpeHeard = when {
            auto.zone == MpeZone.OFF -> MpeHeard.Nothing
            auto.fromConfig -> MpeHeard.Config
            else -> MpeHeard.Fingers
        }
        NativeEngine.setMpeZone(mpeZone, mpeMembers, mpeBendSemis)
    }

    /** Auto heard something that changes the zone in force. */
    private fun autoChanged() { if (mpeSetting == MpeZone.AUTO) applyMpe() }

    /** Nothing plugged in any more, so auto forgets what it heard. */
    private fun forgetController() {
        auto.forget()
        applyMpe()
    }

    /**
     * The track a note on [channel] plays: the followed one, the fixed one,
     * or the channel's own. MPE member channels are never routed by channel,
     * since the fingers are one player and go wherever the zone points. The
     * pads show the scale of the same track, so this is the only rule.
     */
    fun trackForChannel(channel: Int): Int = when {
        mpeMember(channel) -> if (routing == Routing.FixedTrack) fixedRack else target()
        routing == Routing.FixedTrack -> fixedRack
        routing == Routing.ChannelToRack -> channel
        else -> target()
    }

    /** Whether this channel is one of the zone's member channels. Channels are 0-based here. */
    fun mpeMember(channel: Int): Boolean = MpeZone.member(mpeZone, mpeMembers, channel)

    /**
     * Send a message on to the engine, addressed to a rack. Channel 10 isn't
     * special here: the rack is whatever the routing says. [currentPort] is
     * the port it came from, or -1 for the test generators.
     */
    private fun dispatch(status: Int, d1: Int, d2In: Int, currentPort: Int = -1): Unit = com.rm.acidulous.util.locked(dispatching) {
        val kind = status and 0xf0
        // Before mappings, recording and the readout, so they all see the
        // note as it will sound. The test generators are left alone.
        val d2 = if (kind == 0x90 && currentPort >= 0) VelocityCurve.apply(d2In, velocityCurve) else d2In
        if (kind == 0xc0) return // program change: nothing to address it to yet
        val channel = status and 0x0f
        // The controller describing itself (its zone and bend range) is read
        // here and goes no further.
        if (kind == 0xb0) {
            val used = auto.controller(channel, d1, d2)
            if (auto.changed) autoChanged()
            if (used) {
                received += 1
                lastMessage = "ch ${channel + 1} · rpn cc $d1 = $d2"
                return
            }
        }
        if (kind == 0x90 && d2 > 0) {
            auto.noteOn(channel, recognise = mpeSetting == MpeZone.AUTO)
            if (auto.changed) autoChanged()
        } else if (kind == 0x80 || kind == 0x90) {
            auto.noteOff(channel)
        }
        val member = mpeMember(channel)
        val rack = trackForChannel(channel)
        if (kind == 0x90 && d2 > 0 && currentPort >= 0 && currentPort == padInfo?.id && exquisChannel != channel) {
            exquisChannel = channel
        }
        // If a mapping took a note's note-on, its note-off mustn't be
        // delivered either, or the machine is left with a note it never got.
        val isOff = kind == 0x80 || (kind == 0x90 && d2 == 0)
        // Where this actually goes. A note that's already playing goes back
        // to the rack that got its note-on, whatever is selected now, and
        // expression on a member channel follows the note it's shaping.
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
        // The channel goes with every message. The engine decides what's a
        // member channel and tracks which note each channel holds, even
        // before a zone is on (see Engine.cpp). CC 74 is only slide if the
        // setting says so, otherwise it's a normal controller.
        val plainSlide = kind == 0xb0 && d1 == 74 && !mpeTimbre
        if (!taken) {
            NativeEngine.midiEvent(
                rackNow, kind, d1, d2,
                if (plainSlide) NativeEngine.NO_CHANNEL else channel,
            )
        }
        // Remember, and forget, where notes went.
        if (kind == 0x90 && d2 > 0 && !taken) {
            held.onNoteOn(currentPort, channel, d1, rackNow)
        } else if (isOff) {
            held.onNoteOff(channel, d1)
        }
        received += 1
        lastMessage = "ch ${channel + 1} · " + when (kind) {
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
    // A BLE MIDI device only becomes a MIDI device once it's found and
    // opened. The platform does the finding (AndroidMidi.kt on Android), what
    // it opens comes back through [attachFound], and from there it works like
    // anything plugged in.

    /** Whether this platform can find Bluetooth MIDI itself. If not, there's nothing to offer. */
    val canFindBluetooth: Boolean get() = system?.bluetooth != null

    /** Whether the platform can scan for Bluetooth MIDI and Bluetooth is on. */
    fun bluetoothReady(): Boolean = system?.bluetooth?.ready() == true

    /** The permissions a scan needs. */
    fun bluetoothPermissions(): Array<String> = system?.bluetooth?.permissions() ?: emptyArray()

    fun scanBluetooth() { system?.bluetooth?.scan() }

    fun stopScan() { system?.bluetooth?.stop() }

    fun connectBluetooth(address: String) { system?.bluetooth?.connect(address) }

    /** A device the Bluetooth side found and opened: play it like one plugged in. */
    internal fun attachFound(device: MidiOpenDevice) = attach(device.desc.id, device)

    /**
     * Test the routing without hardware: middle C, held long enough to hear
     * and to show on the meters, along the same path a port uses.
     */
    fun testNote() {
        dispatch(0x90, 60, 100)
        handler?.postDelayed({ dispatch(0x80, 60, 0) }, 1500)
    }

    /**
     * Test a Launchpad's pads without one: four pads along the bottom row,
     * pressed and released, along the path its own port uses. The emulator
     * has no USB MIDI, so this shows the surface plays (and records) its
     * selected track.
     */
    fun testLaunchpad() {
        val to = launchpadInput ?: return
        for (i in 0..3) {
            handler?.postDelayed({ lastMessage = "launchpad · pad ${11 + i}"; received += 1; to(0x90, 11 + i, 100) }, (i * 300).toLong())
            handler?.postDelayed({ to(0x90, 11 + i, 0) }, (i * 300 + 200).toLong())
        }
    }

    /**
     * A knob sweep, along the path a real controller uses.
     *
     * An emulator can't receive a real CC, and this window's job is to show
     * what it thinks is happening. [cc] defaults to the mod wheel, which the
     * app responds to without any mapping.
     */
    fun testWheel(cc: Int = 1) {
        for (i in 0..20) {
            handler?.postDelayed({ dispatch(0xb0, cc, i * 127 / 20) }, (i * 60).toLong())
        }
    }

    /**
     * Two notes, and only one of them moves.
     *
     * Tests MPE in one go: two notes arrive on their own member channels,
     * then a bend, press and slide are sent on the first channel only. If
     * the second note moves too, expression isn't reaching the voice that
     * owns it, which an emulator can't show any other way.
     *
     * Uses the zone's first two member channels, so it exercises the same
     * maths a controller would.
     */
    fun testMpe() {
        // On auto with nothing heard yet, it plays like a lower-zone
        // controller and auto recognises it like a real one.
        if (mpeZone == 0 && mpeSetting != MpeZone.AUTO) return
        val a = if (mpeZone == 2) 14 else 1
        val b = if (mpeZone == 2) 13 else 2
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
        // Long enough to hear, and to watch the readout: the sweep alone is
        // three seconds.
        handler?.postDelayed({ dispatch(0x80 or a, 60, 0); dispatch(0x80 or b, 64, 0) }, 3400)
    }
}
