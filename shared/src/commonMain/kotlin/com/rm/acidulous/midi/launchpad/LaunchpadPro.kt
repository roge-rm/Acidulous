package com.rm.acidulous.midi.launchpad

/**
 * A Novation Launchpad Pro [MK3], as described in its Programmer's Reference.
 *
 * In Programmer mode every pad and button sends one message and is lit by
 * the same number. Grid pads are notes 11..88 (ten times the row plus the
 * column, counting from 1 at the bottom left) and the edge buttons are
 * controllers. Acidulous uses this mode because it's the only one where the
 * app controls the whole surface. The device's own Note mode has its own
 * scale, which can't be set from outside.
 *
 * Plain Kotlin, so the surface's maths can be tested without a device.
 */
object LaunchpadPro {
    /** Every SysEx to and from it starts with this. */
    private val HEADER = intArrayOf(0xF0, 0x00, 0x20, 0x29, 0x02, 0x0E)

    /** The buttons, by the controller number each sends in Programmer mode. */
    enum class Button(val cc: Int) {
        Shift(90),
        // The top row, left to right.
        Left(91), Right(92), Session(93), Note(94), Chord(95), Custom(96), Sequencer(97), Projects(98),
        // The left column, top to bottom.
        Up(80), Down(70), Clear(60), Duplicate(50), Quantise(40), FixedLength(30), Play(20), Record(10),
        // The bottom row: track control.
        RecordArm(1), Mute(2), Solo(3), Volume(4), Pan(5), Sends(6), Device(7), StopClip(8),
        // Lit only.
        Logo(99),
        ;

        companion object {
            private val byCc = entries.associateBy { it.cc }
            fun of(cc: Int): Button? = byCc[cc]
        }
    }

    /** Something on the surface: a pad, a named button, a track select or a scene button. */
    sealed class Control {
        /** [row] from the bottom and [col] from the left, both 0..7. */
        data class Pad(val row: Int, val col: Int) : Control()
        data class Key(val button: Button) : Control()
        /** The row under the grid, 0..7 left to right. */
        data class Track(val index: Int) : Control()
        /** The column right of the grid, 0..7 top to bottom. */
        data class Scene(val index: Int) : Control()
    }

    /** The LED number of a control, which is also the note or controller it sends. */
    fun ledOf(c: Control): Int = when (c) {
        is Control.Pad -> 10 * (c.row + 1) + (c.col + 1)
        is Control.Key -> c.button.cc
        is Control.Track -> 101 + c.index
        is Control.Scene -> 89 - 10 * c.index
    }

    /** Which pad a grid note number is, or null. */
    fun padOf(note: Int): Control.Pad? {
        val row = note / 10 - 1
        val col = note % 10 - 1
        return if (row in 0..7 && col in 0..7) Control.Pad(row, col) else null
    }

    /** Which control a controller number is: a button, a track select, a scene, or null. */
    fun controlOfCc(cc: Int): Control? = when {
        cc in 101..108 -> Control.Track(cc - 101)
        cc in 19..89 && cc % 10 == 9 -> Control.Scene((89 - cc) / 10)
        else -> Button.of(cc)?.let { Control.Key(it) }
    }

    /** Programmer mode on, or back to the device's own Live mode. */
    fun programmer(on: Boolean): ByteArray = sysex(0x0E, if (on) 1 else 0)

    /**
     * Set LEDs to exact colours in as few messages as possible. Each colour
     * is 0xRRGGBB with each part 0..127, the device's own range. 64 per
     * message: the reference allows 106, but smaller packets are easier on a
     * MIDI port's buffer.
     */
    fun lights(changes: List<Pair<Int, Int>>): List<ByteArray> =
        changes.chunked(64).map { chunk ->
            val out = ArrayList<Int>(HEADER.size + 1 + chunk.size * 5 + 1)
            HEADER.forEach { out += it }
            out += 0x03
            for ((led, rgb) in chunk) {
                out += 0x03 // lighting type: RGB
                out += led
                out += (rgb shr 16) and 0x7f
                out += (rgb shr 8) and 0x7f
                out += rgb and 0x7f
            }
            out += 0xF7
            ByteArray(out.size) { out[it].toByte() }
        }

    /** Whether a MIDI device is a Launchpad Pro MK3, going by its name. */
    fun isOne(name: String?, product: String?): Boolean {
        val all = listOfNotNull(name, product).joinToString(" ").lowercase()
        return "lppromk3" in all || ("launchpad pro" in all && "mk3" in all)
    }

    private fun sysex(vararg body: Int): ByteArray {
        val all = HEADER + body + intArrayOf(0xF7)
        return ByteArray(all.size) { all[it].toByte() }
    }
}
