package com.rm.acidulous.midi

/**
 * The song's scale shown on a controller's pads: which notes to light, and
 * what to send to get from what is lit to what should be.
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

    /** Whether a MIDI device is an Exquis, by what it calls itself. */
    fun isExquis(name: String?, product: String?, maker: String?): Boolean {
        val all = listOfNotNull(name, product, maker).joinToString(" ").lowercase()
        return "exquis" in all || "dualo" in all || "intuitive instruments" in all
    }
}
