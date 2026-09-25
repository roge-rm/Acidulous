package com.rm.acidulous.midi

/**
 * A scale shown on a controller's pads: which notes to light, what to send
 * to get from what is lit to what should be, and how to set an Exquis's own.
 *
 * The Exquis lights any note sent to it on channel 1 over USB - its manual
 * calls it highlighting, and it needs none of its developer mode, which
 * would take one of its controls away from it to get. So the scale is
 * "held": a note-on for every note in it, in every octave, and a note-off for
 * each one that leaves. Plain Kotlin apart from MidiHub, so the arithmetic is
 * tested without one plugged in.
 */
object PadLights {
    /** The root's velocity, above the rest, in case the pads show velocity at all. */
    const val ROOT_VELOCITY = 127
    const val NOTE_VELOCITY = 96

    /** Every MIDI note in the scale [intervals] from [root], or none without a key. */
    fun notes(root: Int?, intervals: List<Int>?): Set<Int> {
        if (root == null || intervals.isNullOrEmpty()) return emptySet()
        val steps = intervals.map { Math.floorMod(it, 12) }.toSet()
        return (0..127).filter { Math.floorMod(it - root, 12) in steps }.toSet()
    }

    /** The messages that turn [lit] into [wanted], as (status, note, velocity), offs first. */
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
    // Highlighting has two faults as a way to show a scale. The Exquis draws a
    // highlight in its own green, whatever the player chose for the pads; and
    // it shows each note once, on one pad, when its layout has most of them
    // twice. Worse, the pads already show a scale - the Exquis's own, in the
    // player's colours - so a highlight of another one lays a second pattern
    // over the first. Setting the Exquis's own tonic and scale shows the
    // app's scale the way the player already sees one.

    /** The Exquis's built-in scales, in its settings' order, as intervals. */
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
     * The Exquis tonic and scale number that show [pitchClasses] best, with
     * [root] as the tonic where it can be.
     *
     * The same notes from the same root first; then the same notes from
     * another root, since the notes are what the pads are for; then the
     * smallest of its scales that holds every one of them, so nothing
     * playable is left dark; and chromatic, all lit, when none does.
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

    /**
     * The SysEx that sets the Exquis's tonic and scale: in developer mode,
     * since that is the only time it takes either, and straight out again.
     * It goes in on the slider alone, for the few milliseconds it takes, so
     * nothing the player is touching is taken away.
     */
    fun exquisScaleMessages(root: Int, scale: Int): List<ByteArray> {
        fun sysex(vararg b: Int) = byteArrayOf(0xF0.toByte(), 0x00, 0x21, 0x7E, 0x7F, *b.map { it.toByte() }.toByteArray(), 0xF7.toByte())
        return listOf(
            sysex(0x00, 0x04), // developer mode, the slider only
            sysex(0x06, Math.floorMod(root, 12)),
            sysex(0x07, scale),
            sysex(0x00, 0x00), // and out
        )
    }

    /** Whether a MIDI device is an Exquis, by what it calls itself. */
    fun isExquis(name: String?, product: String?, maker: String?): Boolean {
        val all = listOfNotNull(name, product, maker).joinToString(" ").lowercase()
        return "exquis" in all || "dualo" in all || "intuitive instruments" in all
    }
}
