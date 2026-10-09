package com.rm.acidulous.midi

import com.rm.acidulous.util.Math

/**
 * Shows a scale on a controller's pads: which notes to light, what to send
 * to change what's lit, and how to set the Exquis's own scale.
 *
 * The Exquis lights any note sent to it on channel 1 over USB (its manual
 * calls this highlighting), without needing developer mode. So the scale is
 * shown by sending a note-on for every note in it, in every octave, and a
 * note-off for each one that leaves. Plain Kotlin, separate from MidiHub, so
 * it can be tested without a device.
 */
object PadLights {
    /** The root's velocity, higher than the rest, in case the pads show velocity. */
    const val ROOT_VELOCITY = 127
    const val NOTE_VELOCITY = 96

    /** Every MIDI note in the scale [intervals] from [root], or none if there's no key. */
    fun notes(root: Int?, intervals: List<Int>?): Set<Int> {
        if (root == null || intervals.isNullOrEmpty()) return emptySet()
        val steps = intervals.map { Math.floorMod(it, 12) }.toSet()
        return (0..127).filter { Math.floorMod(it - root, 12) in steps }.toSet()
    }

    /** The messages that turn [lit] into [wanted], as (status, note, velocity), note-offs first. */
    fun changes(lit: Set<Int>, wanted: Set<Int>, root: Int?): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (n in (lit - wanted).sorted()) out += Triple(0x80, n, 0)
        for (n in (wanted - lit).sorted()) {
            val isRoot = root != null && Math.floorMod(n - root, 12) == 0
            out += Triple(0x90, n, if (isRoot) ROOT_VELOCITY else NOTE_VELOCITY)
        }
        return out
    }

    // --- The Exquis's own key and scale --------------------------------------
    //
    // Highlighting isn't a great way to show a scale. The Exquis draws
    // highlights in its own green whatever colours the player chose, and
    // lights each note on only one pad when most notes appear on two. The
    // pads also already show the Exquis's own scale, so a highlight adds a
    // second pattern on top. Setting the Exquis's own tonic and scale shows
    // the app's scale the way the player is used to.

    /** The Exquis's built-in scales, in the order of its settings, as intervals. */
    val EXQUIS_SCALES: List<List<Int>> = listOf(
        listOf(0, 2, 4, 5, 7, 9, 11), // major
        listOf(0, 2, 3, 5, 7, 8, 10), // natural minor
        listOf(0, 2, 3, 5, 7, 9, 11), // melodic minor
        listOf(0, 2, 3, 5, 7, 8, 11), // harmonic minor
        listOf(0, 2, 3, 5, 7, 9, 10), // dorian
        listOf(0, 1, 3, 5, 7, 8, 10), // phrygian
        listOf(0, 2, 4, 6, 7, 9, 11), // lydian
        listOf(0, 2, 4, 5, 7, 9, 10), // mixolydian
        listOf(0, 1, 3, 5, 6, 8, 10), // locrian
        listOf(0, 1, 4, 5, 7, 8, 10), // phrygian dominant
        listOf(0, 2, 4, 7, 9), // major pentatonic
        listOf(0, 3, 5, 7, 10), // minor pentatonic
        listOf(0, 2, 4, 6, 8, 10), // whole tone
        (0..11).toList(), // chromatic
    )
    val EXQUIS_CHROMATIC = EXQUIS_SCALES.lastIndex

    /**
     * The Exquis tonic and scale number that best show [pitchClasses], using
     * [root] as the tonic where possible.
     *
     * In order: the same notes from the same root; the same notes from
     * another root, since the notes matter most; the smallest scale that
     * contains all of them, so no playable note stays dark; and chromatic
     * (everything lit) if none does.
     */
    fun exquisScale(root: Int, pitchClasses: Set<Int>): Pair<Int, Int> {
        val r = Math.floorMod(root, 12)
        val want = pitchClasses.map { Math.floorMod(it, 12) }.toSet()
        fun set(tonic: Int, i: Int) = EXQUIS_SCALES[i].map { (it + tonic) % 12 }.toSet()
        EXQUIS_SCALES.indices.firstOrNull { set(r, it) == want }?.let { return r to it }
        for (t in (0..11).map { (r + it) % 12 }) {
            EXQUIS_SCALES.indices.firstOrNull { set(t, it) == want }?.let { return t to it }
        }
        EXQUIS_SCALES.indices.filter { set(r, it).containsAll(want) }
            .minByOrNull { EXQUIS_SCALES[it].size }?.let { return r to it }
        return r to EXQUIS_CHROMATIC
    }

    private fun sysex(vararg b: Int) =
        byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x7E, 0x7F, *b.map { it.toByte() }.toByteArray(), 0xF7.toByte())

    /**
     * The SysEx that sets the Exquis's tonic and scale. It only accepts them
     * in developer mode. If the app already has the buttons it's already in
     * developer mode. Otherwise it enters it for the slider only, for the few
     * milliseconds this takes, and leaves straight away, so nothing the
     * player is using is taken away.
     */
    fun exquisScaleMessages(root: Int, scale: Int, inDeveloperMode: Boolean = false): List<ByteArray> {
        val set = listOf(sysex(0x06, Math.floorMod(root, 12)), sysex(0x07, scale))
        return if (inDeveloperMode) set else listOf(exquisSetup(ZONE_SLIDER)) + set + exquisSetup(0)
    }

    // --- The Exquis's buttons -------------------------------------------------
    //
    // Record, loop, clips, play/stop, undo and redo are one developer-mode
    // zone. When the app takes them, presses arrive as CC on channel 16 and
    // the app sets their lights. The pads, knobs, slider and octave buttons
    // stay the Exquis's own, so it plays as normal.

    /** The note of an Exquis pad at its factory octave: rows of 6 and 5 from the bottom left (D#1), a third up each row. */
    fun exquisPadNote(row: Int, col: Int): Int = 27 + (row / 2) * 7 + (if (row % 2 == 1) 4 else 0) + col

    /**
     * The pads, as row and column, that play a drum machine of [count] drums:
     * three on the rows of 6 and two on the rows of 5 (columns 2-4 and 2-3,
     * all centred on one line), stacked upwards and centred, kick at the
     * bottom left. A note in columns 0-1 is also on a lower pad to the
     * right, and the Exquis lights the lower one, so those columns are left out.
     */
    fun exquisDrumPads(count: Int): List<Pair<Int, Int>> {
        fun width(row: Int) = if (row % 2 == 0) 3 else 2
        fun from(first: Int) = buildList {
            var r = first
            while (size < count && r < 11) {
                for (c in 2 until 2 + width(r)) if (size < count) add(r to c)
                r++
            }
        }
        // Whichever start puts the block's middle nearest row 5, the Exquis's middle.
        return (0..10).map { from(it) }.filter { it.size == minOf(count, from(0).size) }
            .minByOrNull { kotlin.math.abs(it.first().first + it.last().first - 10) } ?: emptyList()
    }

    /** The notes of [exquisDrumPads]. */
    fun exquisDrumNotes(count: Int): List<Int> = exquisDrumPads(count).map { (r, c) -> exquisPadNote(r, c) }

    /**
     * An Exquis note as the app plays it: on a drum machine ([drums] in pad
     * order) the notes of [exquisDrumNotes] are its drums and the rest play
     * nothing (null); otherwise it's moved by [shift] semitones.
     */
    fun exquisNote(note: Int, drums: List<Int>?, shift: Int): Int? =
        if (drums != null) drums.getOrNull(exquisDrumNotes(drums.size).indexOf(note)) else (note + shift).takeIf { it in 0..127 }

    const val ZONE_SLIDER = 0x04
    const val ZONE_BUTTONS = 0x20
    const val BUTTON_RECORD = 102
    const val BUTTON_LOOP = 103
    const val BUTTON_CLIPS = 104
    const val BUTTON_PLAY = 105
    const val BUTTON_UNDO = 108
    const val BUTTON_REDO = 109
    val BUTTONS = listOf(BUTTON_RECORD, BUTTON_LOOP, BUTTON_CLIPS, BUTTON_PLAY, BUTTON_UNDO, BUTTON_REDO)

    /** Enter developer mode for the zones in [mask], or leave it with 0. */
    fun exquisSetup(mask: Int): ByteArray = sysex(0x00, mask)

    /** Set one LED to a colour, each part 0..127, with no effect. */
    fun exquisLed(id: Int, r: Int, g: Int, b: Int): ByteArray =
        sysex(0x04, id, r.coerceIn(0, 127), g.coerceIn(0, 127), b.coerceIn(0, 127), 0)

    /**
     * The Exquis asking for its LEDs to be redrawn. It sends this when
     * entering and leaving its settings menu, which draws over them. [body]
     * is the SysEx between F0 and F7.
     */
    fun isExquisRefresh(body: ByteArray): Boolean =
        body.size >= 5 && body[0].toInt() == 0x00 && body[1].toInt() == 0x21 && body[2].toInt() == 0x7E &&
            body[3].toInt() == 0x7F && body[4].toInt() == 0x03

    /**
     * Lights by id, as [com.rm.acidulous.midi.launchpad.Rgb], in as few
     * messages as can carry them: one per run of neighbouring ids.
     */
    fun exquisLeds(leds: Map<Int, Int>): List<ByteArray> {
        val ids = leds.keys.sorted()
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < ids.size) {
            val start = ids[i]
            val body = arrayListOf(0x04, start)
            var id = start
            while (i < ids.size && ids[i] == id) {
                val c = leds.getValue(id)
                body += listOf((c shr 16) and 0x7f, (c shr 8) and 0x7f, c and 0x7f, 0)
                id++
                i++
            }
            out += sysex(*body.toIntArray())
        }
        return out
    }

    /**
     * The tonic and the exact scale, for while the app holds the Exquis in
     * developer mode, where it takes any set of notes rather than only its
     * own list. It goes back to its own scale when developer mode ends.
     */
    fun exquisExactScale(root: Int, pitchClasses: Set<Int>): List<ByteArray> {
        val r = Math.floorMod(root, 12)
        val degrees = (0..11).map { if (Math.floorMod(it + r, 12) in pitchClasses.map { p -> Math.floorMod(p, 12) }) 1 else 0 }
        return listOf(sysex(0x06, r), sysex(0x08, *degrees.toIntArray()))
    }

    /**
     * Whether a message from the Exquis is one of its developer-mode
     * controls in a zone the app holds ([mask]), rather than playing.
     */
    fun isExquisControl(status: Int, d1: Int, mask: Int): Boolean = when (status) {
        0x9F, 0x8F -> mask and 0x01 != 0 && d1 in 0..60
        0xBF -> when (d1) {
            90 -> mask and ZONE_SLIDER != 0
            106, 107 -> mask and 0x08 != 0
            100, 101 -> mask and 0x10 != 0
            in 102..109 -> mask and ZONE_BUTTONS != 0
            in 110..117 -> mask and 0x02 != 0
            else -> false
        }
        else -> false
    }

    /** A press of one of the app's Exquis buttons: its id, or null. */
    fun exquisButton(status: Int, d1: Int, d2: Int): Int? =
        if (status == 0xBF && d1 in BUTTONS && d2 > 0) d1 else null

    /** Whether a MIDI device is an Exquis, going by its name. */
    fun isExquis(name: String?, product: String?, maker: String?): Boolean {
        val all = listOfNotNull(name, product, maker).joinToString(" ").lowercase()
        return "exquis" in all || "dualo" in all || "intuitive instruments" in all
    }
}
