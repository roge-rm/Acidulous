package com.rm.acidulous.midi

/**
 * Where every sounding note went, so its release can follow it there.
 *
 * The hub works out a destination rack per message, and under the default
 * routing that destination is "whichever track is selected". A note held while
 * you leave the machine screen therefore has its note-on delivered to one rack
 * and its note-off to another, and the first machine is left holding a note
 * nobody will ever release. The rule the hub already applied to notes a mapping
 * swallowed - that an off must go wherever its on went - simply had not been
 * applied to the target.
 *
 * Notes are keyed by channel *and* number, because MPE gives every finger its
 * own channel and the same note can be down on two of them. The port is kept
 * alongside, because a controller that is unplugged or runs out of battery
 * never sends its offs, and the notes that die with it are only its own: a
 * second controller still has fingers down.
 *
 * No Android here on purpose - this is the part that was wrong, so it is the
 * part that should be testable on its own.
 */
class HeldNotes {
    /** rack, by (channel, note). */
    private val rackOf = HashMap<Int, Int>()
    /** port, by (channel, note). */
    private val portOf = HashMap<Int, Int>()
    /** The rack a member channel's expression belongs to. */
    private val channelRack = IntArray(16) { -1 }

    /** One note, held. */
    data class Held(val rack: Int, val channel: Int, val note: Int)

    private fun key(channel: Int, note: Int) = (channel shl 8) or (note and 0xff)

    fun onNoteOn(port: Int, channel: Int, note: Int, rack: Int) {
        val k = key(channel, note)
        rackOf[k] = rack
        portOf[k] = port
        if (channel in channelRack.indices) channelRack[channel] = rack
    }

    /** Where this note-off belongs, or null if nothing by that name is down. */
    fun rackForOff(channel: Int, note: Int): Int? = rackOf[key(channel, note)]

    /** Where a member channel's bend, pressure and slide belong. */
    fun rackForExpression(channel: Int): Int? =
        if (channel in channelRack.indices && channelRack[channel] >= 0) channelRack[channel] else null

    fun onNoteOff(channel: Int, note: Int) {
        val k = key(channel, note)
        rackOf.remove(k)
        portOf.remove(k)
        if (channel in channelRack.indices && rackOf.keys.none { (it shr 8) == channel }) {
            channelRack[channel] = -1
        }
    }

    /**
     * Everything this port was holding, forgotten and handed back so the caller
     * can send the note-offs the device never will.
     */
    fun release(port: Int): List<Held> {
        val gone = portOf.filterValues { it == port }.keys.toList()
        val out = ArrayList<Held>(gone.size)
        for (k in gone) {
            val rack = rackOf[k] ?: continue
            out += Held(rack, k shr 8, k and 0xff)
        }
        for (h in out) onNoteOff(h.channel, h.note)
        return out
    }

    /** A panic: nothing is held any more, whatever this thought. */
    fun clear() {
        rackOf.clear()
        portOf.clear()
        for (i in channelRack.indices) channelRack[i] = -1
    }

    val count: Int get() = rackOf.size
}
