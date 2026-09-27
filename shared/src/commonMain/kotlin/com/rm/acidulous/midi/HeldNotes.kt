package com.rm.acidulous.midi

/**
 * Remembers which rack every held note went to, so its note-off goes there
 * too.
 *
 * The hub picks a rack for each message, and by default that's the selected
 * track. If the selection changes while a note is held, the note-off would
 * go to a different rack and the first one would hang, so an off always
 * follows its on.
 *
 * Notes are keyed by channel and number, because MPE gives every finger its
 * own channel and the same note can be held on two of them. The port is kept
 * too, because an unplugged or flat controller never sends its note-offs, and
 * only its own notes should be released (another controller may still be
 * held).
 *
 * No Android code here, so it can be tested on its own.
 */
class HeldNotes {
    /** Rack, by (channel, note). */
    private val rackOf = HashMap<Int, Int>()
    /** Port, by (channel, note). */
    private val portOf = HashMap<Int, Int>()
    /** The rack a member channel's expression belongs to. */
    private val channelRack = IntArray(16) { -1 }

    /** One held note. */
    data class Held(val rack: Int, val channel: Int, val note: Int)

    private fun key(channel: Int, note: Int) = (channel shl 8) or (note and 0xff)

    fun onNoteOn(port: Int, channel: Int, note: Int, rack: Int) {
        val k = key(channel, note)
        rackOf[k] = rack
        portOf[k] = port
        if (channel in channelRack.indices) channelRack[channel] = rack
    }

    /** Where this note-off belongs, or null if that note isn't held. */
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
     * Forgets everything this port was holding and returns it, so the caller
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

    /** A panic: forget every held note. */
    fun clear() {
        rackOf.clear()
        portOf.clear()
        for (i in channelRack.indices) channelRack[i] = -1
    }

    val count: Int get() = rackOf.size
}
