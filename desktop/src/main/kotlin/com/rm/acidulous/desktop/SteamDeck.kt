package com.rm.acidulous.desktop

import com.rm.acidulous.ui.KeyCodes
import com.rm.acidulous.ui.Pad
import java.io.File
import java.io.FileInputStream

/**
 * The Steam Deck's own controls, read from its raw HID reports. The kernel
 * only offers the Deck as a gamepad while Steam isn't running, and in Game
 * Mode Steam always is; the raw reports come through either way, with
 * Steam Input on or off, and carry the touchpads and back buttons too.
 *
 * A state report is 64 bytes, about 250 a second: 01 00 09 at the start,
 * then the buttons as bits from byte 8, and little-endian 16-bit axes.
 */
internal class DeckDecoder(private val sink: PadSink) {
    private var held = 0L
    private var lastAxes: List<Pad.Axis>? = null

    fun report(b: ByteArray) {
        if (b.size < 60 || b[0].toInt() != 1 || b[2].toInt() != 9) return
        var now = 0L
        for ((i, bit) in BITS.withIndex()) if (b[bit.first].toInt() and (1 shl bit.second) != 0) now = now or (1L shl i)
        val changed = now xor held
        if (changed != 0L) {
            for ((i, key) in KEYS.withIndex()) if (changed and (1L shl i) != 0L) sink.button(NAME, key, now and (1L shl i) != 0L)
            held = now
        }
        val lpad = b[10].toInt() and 0x08 != 0
        val rpad = b[10].toInt() and 0x10 != 0
        val axes = listOf(
            Pad.Axis("X", s16(b, 48), STICK_FLAT),
            // Up is positive in the report and negative on Android.
            Pad.Axis("Y", -s16(b, 50), STICK_FLAT),
            Pad.Axis("RX", s16(b, 52), STICK_FLAT),
            Pad.Axis("RY", -s16(b, 54), STICK_FLAT),
            Pad.Axis("LTRIGGER", u15(b, 44), TRIGGER_FLAT),
            Pad.Axis("RTRIGGER", u15(b, 46), TRIGGER_FLAT),
            // A pad reads 0 when nothing's on it.
            Pad.Axis("LPAD_X", if (lpad) s16(b, 16) else 0f, 0f),
            Pad.Axis("LPAD_Y", if (lpad) -s16(b, 18) else 0f, 0f),
            Pad.Axis("RPAD_X", if (rpad) s16(b, 20) else 0f, 0f),
            Pad.Axis("RPAD_Y", if (rpad) -s16(b, 22) else 0f, 0f),
            Pad.Axis("LPAD_TOUCH", if (lpad) 1f else 0f, 0f),
            Pad.Axis("RPAD_TOUCH", if (rpad) 1f else 0f, 0f),
        )
        // Only when something moved by more than a step, so a resting Deck sends nothing.
        val last = lastAxes
        if (last == null || axes.indices.any { kotlin.math.abs(axes[it].value - last[it].value) > STEP }) {
            lastAxes = axes
            sink.moved(NAME, axes)
        }
    }

    private fun s16(b: ByteArray, at: Int): Float {
        val v = (b[at].toInt() and 0xff) or (b[at + 1].toInt() shl 8)
        return (v / 32767f).coerceIn(-1f, 1f)
    }

    private fun u15(b: ByteArray, at: Int): Float {
        val v = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        return (v / 32767f).coerceIn(0f, 1f)
    }

    companion object {
        const val NAME = "Steam Deck"
        private const val STICK_FLAT = 0.1f
        private const val TRIGGER_FLAT = 0.05f
        private const val STEP = 1f / 512

        /** Each button's byte and bit in the report, beside the key code it sends, as Linux's hid-steam reads them. */
        private val BUTTONS = listOf(
            (8 to 7) to KeyCodes.KEYCODE_BUTTON_A,
            (8 to 5) to KeyCodes.KEYCODE_BUTTON_B,
            (8 to 6) to KeyCodes.KEYCODE_BUTTON_X,
            (8 to 4) to KeyCodes.KEYCODE_BUTTON_Y,
            (8 to 3) to KeyCodes.KEYCODE_BUTTON_L1,
            (8 to 2) to KeyCodes.KEYCODE_BUTTON_R1,
            (8 to 1) to KeyCodes.KEYCODE_BUTTON_L2,
            (8 to 0) to KeyCodes.KEYCODE_BUTTON_R2,
            (9 to 0) to KeyCodes.KEYCODE_DPAD_UP,
            (9 to 1) to KeyCodes.KEYCODE_DPAD_RIGHT,
            (9 to 2) to KeyCodes.KEYCODE_DPAD_LEFT,
            (9 to 3) to KeyCodes.KEYCODE_DPAD_DOWN,
            (9 to 4) to KeyCodes.KEYCODE_BUTTON_SELECT,
            (9 to 6) to KeyCodes.KEYCODE_BUTTON_START,
            (10 to 6) to KeyCodes.KEYCODE_BUTTON_THUMBL,
            (11 to 2) to KeyCodes.KEYCODE_BUTTON_THUMBR,
            // The back buttons: L4, R4, L5, R5.
            (13 to 1) to KeyCodes.KEYCODE_BUTTON_1,
            (13 to 2) to KeyCodes.KEYCODE_BUTTON_1 + 1,
            (9 to 7) to KeyCodes.KEYCODE_BUTTON_1 + 2,
            (10 to 0) to KeyCodes.KEYCODE_BUTTON_1 + 3,
        )
        private val BITS = BUTTONS.map { it.first }
        private val KEYS = BUTTONS.map { it.second }
    }
}

/** Finds the Deck's controller and reads it on its own thread. */
internal object SteamDeckControls {
    /** Whether the Deck's own reports are being read, so Steam's copy of them can be ignored. */
    @Volatile var reading = false
        private set

    /** The hidraw node of the Deck's controller: Valve's 28DE:1205, its third interface. */
    fun find(): String? = File("/sys/class/hidraw").listFiles()?.sortedBy { it.name }?.firstOrNull { node ->
        val uevent = runCatching { File(node, "device/uevent").readText() }.getOrNull() ?: return@firstOrNull false
        "HID_ID=0003:000028DE:00001205" in uevent && Regex("HID_PHYS=.*input2\\b").containsMatchIn(uevent)
    }?.let { "/dev/${it.name}" }

    /** Reads until the node goes away; true if it was found and opened. */
    fun start(sink: PadSink): Boolean {
        val path = find() ?: return false
        val input = runCatching { FileInputStream(path) }.getOrNull() ?: return false
        reading = true
        // The controller's own desktop mode turns its buttons into keys and
        // its right pad into a mouse, and its reports then carry no buttons.
        // Games switch it off and keep it off, as it comes back on its own.
        val keepOff = Thread({
            while (reading) {
                Evdev.nativeHidFeature(path, CLEAR_DIGITAL_MAPPINGS)
                Evdev.nativeHidFeature(path, TRACKPADS_OFF)
                Thread.sleep(KEEP_OFF_MS)
            }
        }, "steam deck desktop mode off")
        keepOff.isDaemon = true
        keepOff.start()
        val reader = Thread({
            val decoder = DeckDecoder(sink)
            val buf = ByteArray(64)
            try {
                input.use { while (it.read(buf) > 0) decoder.report(buf) }
            } catch (_: Exception) {
            } finally {
                reading = false
            }
        }, "steam deck controls")
        reader.isDaemon = true
        reader.start()
        return true
    }

    private const val KEEP_OFF_MS = 2000L
    /** The commands Linux's hid-steam sends to leave desktop mode: no key mappings, and both trackpads as plain pads. */
    private val CLEAR_DIGITAL_MAPPINGS = byteArrayOf(0x81.toByte())
    private val TRACKPADS_OFF = byteArrayOf(0x87.toByte(), 6, 7, 7, 0, 8, 7, 0)

    /** Steam's virtual Xbox pad, which repeats the Deck's controls while Steam Input is on. */
    fun isSteamVirtualPad(eventPath: String): Boolean {
        val id = File("/sys/class/input/${File(eventPath).name}/device/id")
        return runCatching { File(id, "vendor").readText().trim() == "28de" && File(id, "product").readText().trim() == "11ff" }.getOrDefault(false)
    }
}
