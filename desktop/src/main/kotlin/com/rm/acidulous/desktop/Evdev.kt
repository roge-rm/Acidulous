package com.rm.acidulous.desktop

import com.rm.acidulous.ui.KeyCodes
import com.rm.acidulous.ui.Pad
import java.io.File

/** Game controllers on Linux, read from /dev/input (app/src/main/cpp/platform/desktop/evdev.cpp). */
internal object Evdev {
    init {
        System.loadLibrary("acidulous")
    }

    @JvmStatic external fun nativeOpen(path: String): Int
    @JvmStatic external fun nativeClose(fd: Int)
    @JvmStatic external fun nativeName(fd: Int): String
    @JvmStatic external fun nativeIsController(fd: Int): Boolean
    @JvmStatic external fun nativeAxes(fd: Int, out: IntArray): Int
    @JvmStatic external fun nativeRead(fd: Int, out: IntArray, timeoutMs: Int): Int
    @JvmStatic external fun nativeHidFeature(path: String, data: ByteArray): Int
}

/** Where a controller's events go: Android's key codes and axis names, as [Pad] takes them. */
internal interface PadSink {
    fun button(device: String, keyCode: Int, down: Boolean)
    fun moved(device: String, axes: List<Pad.Axis>)
}

/** One axis as the kernel describes it. */
internal data class AxisInfo(val code: Int, val min: Int, val max: Int, val flat: Int)

/**
 * One controller's events, from Linux's codes to Android's: buttons to key
 * codes, axes to the names Android gives them, scaled to -1..1 (0..1 for a
 * trigger). A d-pad that's a hat becomes the d-pad's keys, as on Android.
 * No device in here, so tests can feed it events.
 */
internal class PadTranslator(private val name: String, axes: List<AxisInfo>, private val sink: PadSink) {
    private val info = axes.associateBy { it.code }
    private val hasRightStickR = ABS_RX in info
    private val deck = name.contains("Steam Deck")
    private val values = HashMap<Int, Int>()
    private var changed = false
    private var hatX = 0
    private var hatY = 0

    /** A hat with only -1, 0 and 1 is a d-pad. The Deck's are its touchpads. */
    private fun isDpadHat(code: Int) = (code == ABS_HAT0X || code == ABS_HAT0Y) && (info[code]?.max ?: 1) <= 1

    fun event(type: Int, code: Int, value: Int) {
        when (type) {
            EV_KEY -> BUTTONS[code]?.let { if (value != 2) sink.button(name, it, value == 1) }
            EV_ABS -> if (isDpadHat(code)) hat(code, value) else {
                values[code] = value
                changed = true
            }
            EV_SYN -> if (code == SYN_REPORT && changed) {
                changed = false
                sink.moved(name, axes())
            }
        }
    }

    private fun hat(code: Int, value: Int) {
        val v = value.coerceIn(-1, 1)
        if (code == ABS_HAT0X) {
            if (v != hatX) {
                if (hatX != 0) sink.button(name, if (hatX < 0) KeyCodes.KEYCODE_DPAD_LEFT else KeyCodes.KEYCODE_DPAD_RIGHT, false)
                if (v != 0) sink.button(name, if (v < 0) KeyCodes.KEYCODE_DPAD_LEFT else KeyCodes.KEYCODE_DPAD_RIGHT, true)
                hatX = v
            }
        } else if (v != hatY) {
            if (hatY != 0) sink.button(name, if (hatY < 0) KeyCodes.KEYCODE_DPAD_UP else KeyCodes.KEYCODE_DPAD_DOWN, false)
            if (v != 0) sink.button(name, if (v < 0) KeyCodes.KEYCODE_DPAD_UP else KeyCodes.KEYCODE_DPAD_DOWN, true)
            hatY = v
        }
    }

    /** What Android would call this axis. Null for one Pad has no use for. */
    private fun axisName(code: Int): String? = when (code) {
        ABS_X -> "X"
        ABS_Y -> "Y"
        // An Xbox-style pad has its right stick on RX and RY and its
        // triggers on Z and RZ. Others have the right stick on Z and RZ.
        ABS_Z -> if (hasRightStickR) "LTRIGGER" else "Z"
        ABS_RZ -> if (hasRightStickR) "RTRIGGER" else "RZ"
        ABS_RX -> "RX"
        ABS_RY -> "RY"
        ABS_GAS -> "GAS"
        ABS_BRAKE -> "BRAKE"
        // The Deck: its touchpads are hats 0 and 1 and its triggers hat 2.
        ABS_HAT0X -> if (deck) "LPAD_X" else null
        ABS_HAT0Y -> if (deck) "LPAD_Y" else null
        ABS_HAT1X -> if (deck) "RPAD_X" else null
        ABS_HAT1Y -> if (deck) "RPAD_Y" else null
        ABS_HAT2X -> if (deck) "RTRIGGER" else null
        ABS_HAT2Y -> if (deck) "LTRIGGER" else null
        else -> null
    }

    private fun axes(): List<Pad.Axis> = info.values.mapNotNull { a ->
        val name = axisName(a.code) ?: return@mapNotNull null
        val raw = values[a.code] ?: return@mapNotNull Pad.Axis(name, 0f, 0f)
        val span = (a.max - a.min).toFloat().coerceAtLeast(1f)
        if (name.endsWith("TRIGGER") || name == "GAS" || name == "BRAKE") {
            Pad.Axis(name, ((raw - a.min) / span).coerceIn(0f, 1f), a.flat / span)
        } else {
            // Centred: the middle of the range, so a stick of 0..255 rests
            // at 128 and reads 0.
            val half = span / 2f
            Pad.Axis(name, ((raw - (a.min + half)) / half).coerceIn(-1f, 1f), a.flat / half)
        }
    }

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0

        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_Z = 0x02
        const val ABS_RX = 0x03
        const val ABS_RY = 0x04
        const val ABS_RZ = 0x05
        const val ABS_GAS = 0x09
        const val ABS_BRAKE = 0x0a
        const val ABS_HAT0X = 0x10
        const val ABS_HAT0Y = 0x11
        const val ABS_HAT1X = 0x12
        const val ABS_HAT1Y = 0x13
        const val ABS_HAT2X = 0x14
        const val ABS_HAT2Y = 0x15

        /** Linux's buttons as Android's key codes, the same pairs as Android's own Generic.kl. */
        val BUTTONS = mapOf(
            0x130 to KeyCodes.KEYCODE_BUTTON_A, // BTN_SOUTH
            0x131 to KeyCodes.KEYCODE_BUTTON_B, // BTN_EAST
            0x132 to KeyCodes.KEYCODE_BUTTON_C,
            0x133 to KeyCodes.KEYCODE_BUTTON_X, // BTN_NORTH
            0x134 to KeyCodes.KEYCODE_BUTTON_Y, // BTN_WEST
            0x135 to KeyCodes.KEYCODE_BUTTON_Z,
            0x136 to KeyCodes.KEYCODE_BUTTON_L1,
            0x137 to KeyCodes.KEYCODE_BUTTON_R1,
            0x138 to KeyCodes.KEYCODE_BUTTON_L2,
            0x139 to KeyCodes.KEYCODE_BUTTON_R2,
            0x13a to KeyCodes.KEYCODE_BUTTON_SELECT,
            0x13b to KeyCodes.KEYCODE_BUTTON_START,
            0x13c to KeyCodes.KEYCODE_BUTTON_MODE,
            0x13d to KeyCodes.KEYCODE_BUTTON_THUMBL,
            0x13e to KeyCodes.KEYCODE_BUTTON_THUMBR,
            0x220 to KeyCodes.KEYCODE_DPAD_UP, // BTN_DPAD_UP
            0x221 to KeyCodes.KEYCODE_DPAD_DOWN,
            0x222 to KeyCodes.KEYCODE_DPAD_LEFT,
            0x223 to KeyCodes.KEYCODE_DPAD_RIGHT,
            // Extra buttons, like the Deck's four on the back.
            0x2c0 to KeyCodes.KEYCODE_BUTTON_1, // BTN_TRIGGER_HAPPY1
            0x2c1 to KeyCodes.KEYCODE_BUTTON_1 + 1,
            0x2c2 to KeyCodes.KEYCODE_BUTTON_1 + 2,
            0x2c3 to KeyCodes.KEYCODE_BUTTON_1 + 3,
        )
    }
}

/**
 * Finds controllers as they're plugged in and reads each on its own thread.
 * Linux only; started once at start-up.
 */
internal object LinuxControllers {
    private val open = HashMap<String, Int>()
    /** Nodes looked at that aren't controllers, so they're opened once, not every scan. */
    private val notControllers = HashSet<String>()

    fun start(sink: PadSink) {
        val scanner = Thread({
            while (true) {
                runCatching { scan(sink) }
                Thread.sleep(2000)
            }
        }, "controllers")
        scanner.isDaemon = true
        scanner.start()
    }

    private fun scan(sink: PadSink) {
        if (!SteamDeckControls.reading) SteamDeckControls.start(sink)
        val nodes = File("/dev/input").listFiles { f -> f.name.startsWith("event") }?.map { it.path }?.toSet() ?: return
        // A node that went away may come back as something else.
        notControllers.retainAll(nodes)
        for (path in nodes) {
            if (path in notControllers || synchronized(open) { path in open }) continue
            // The Deck's controls are read directly; Steam's copy would play everything twice.
            if (SteamDeckControls.reading && SteamDeckControls.isSteamVirtualPad(path)) continue
            val fd = Evdev.nativeOpen(path)
            if (fd < 0) continue
            if (!Evdev.nativeIsController(fd)) {
                Evdev.nativeClose(fd)
                notControllers += path
                continue
            }
            synchronized(open) { open[path] = fd }
            read(path, fd, sink)
        }
    }

    private fun read(path: String, fd: Int, sink: PadSink) {
        val name = Evdev.nativeName(fd)
        val raw = IntArray(4 * 64)
        val count = Evdev.nativeAxes(fd, raw)
        val axes = (0 until count).map { AxisInfo(raw[it * 4], raw[it * 4 + 1], raw[it * 4 + 2], raw[it * 4 + 3]) }
        val translator = PadTranslator(name, axes, sink)
        val reader = Thread({
            val events = IntArray(64 * 3)
            try {
                while (true) {
                    val n = Evdev.nativeRead(fd, events, 500)
                    if (n < 0) break
                    for (i in 0 until n) translator.event(events[i * 3], events[i * 3 + 1], events[i * 3 + 2])
                }
            } finally {
                Evdev.nativeClose(fd)
                synchronized(open) { open.remove(path) }
            }
        }, "controller $name")
        reader.isDaemon = true
        reader.start()
    }
}
