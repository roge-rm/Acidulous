package com.rm.acidulous.midi

/**
 * Which arriving channels are fingers.
 *
 * Pure arithmetic, deliberately out of [MidiHub]: that object cannot be
 * built outside Android - it holds handlers and parcel UUIDs - and this is
 * the part worth testing. Off by one here is the whole feature failing
 * quietly. A zone that counts its master channel as a member will treat the
 * controller's own bends as some note's; one that stops a channel short
 * drops the last finger, and only when you play enough of them at once to
 * notice.
 *
 * Channels are 0-based here and 1-based everywhere a musician reads them,
 * which is the other way this goes wrong.
 */
object MpeZone {
    const val OFF = 0
    const val LOWER = 1
    const val UPPER = 2
    /**
     * A setting, never a zone: follow the controller - the zone its MPE
     * configuration message announces, or a lower zone the moment two
     * fingers are down on separate channels.
     */
    const val AUTO = 3

    /** What the MPE specification asks a receiver to assume. */
    const val DEFAULT_BEND_SEMIS = 48f

    fun member(zone: Int, members: Int, channel: Int): Boolean {
        if (zone == OFF || channel !in 0..15) return false
        val n = clampMembers(members)
        // Lower: master is channel 1 and the members climb from 2.
        // Upper: master is 16 and they descend from 15.
        return if (zone == LOWER) channel in 1..n else channel <= 14 && channel >= 15 - n
    }

    fun clampZone(z: Int): Int = z.coerceIn(OFF, UPPER)
    fun clampSetting(z: Int): Int = z.coerceIn(OFF, AUTO)
    fun clampMembers(n: Int): Int = n.coerceIn(1, 15)
    fun clampBend(semis: Float): Float = semis.coerceIn(1f, 96f)
}

/**
 * What an MPE controller says about itself, and what it gives away by how it
 * plays - for the zone setting called auto.
 *
 * Two messages describe a controller: the MPE configuration message (RPN 6 on
 * channel 1 for a lower zone, on 16 for an upper, its value the number of
 * fingers) and the pitch bend range (RPN 0, sent to a finger's channel). An
 * RPN is chosen with CC 101 and 100 and set with CC 6, and CC 38 for the
 * fine part. A controller that sends neither is recognised by what no
 * keyboard does: two notes held at once on two channels above the first.
 *
 * Plain Kotlin, apart from MidiHub, so it can be tested without a device -
 * which, for a controller nobody here has plugged in, is the only proof it
 * gets. Channels are 0-based.
 */
class MpeAuto {
    var zone = MpeZone.OFF
        private set
    var members = 15
        private set
    var bendSemis = MpeZone.DEFAULT_BEND_SEMIS
        private set
    /** The zone came from the controller's own message, not from its fingers. */
    var fromConfig = false
        private set
    /** Whether the last call changed any of the above. */
    var changed = false
        private set

    private val rpnMsb = IntArray(16) { 127 }
    private val rpnLsb = IntArray(16) { 127 }
    private val rangeSemis = IntArray(16) { 48 }
    private val down = IntArray(16)

    /** A control change. True when it was part of an RPN and is used up here. */
    fun controller(channel: Int, cc: Int, value: Int): Boolean {
        changed = false
        if (channel !in 0..15) return false
        val selected = rpnMsb[channel] * 128 + rpnLsb[channel]
        when (cc) {
            101 -> { rpnMsb[channel] = value; return true }
            100 -> { rpnLsb[channel] = value; return true }
            // An NRPN deselects the RPN; what it is for is not ours to say.
            99, 98 -> { rpnMsb[channel] = 127; rpnLsb[channel] = 127; return false }
            6 -> when (selected) {
                6 -> { announced(channel, value); return true }
                0 -> { rangeSemis[channel] = value; bendRange(channel, value.toFloat()); return true }
            }
            38 -> if (selected == 0) {
                bendRange(channel, rangeSemis[channel] + value / 100f)
                return true
            }
        }
        return false
    }

    /** A note-on. With [recognise], a second finger on a channel of its own switches on a lower zone. */
    fun noteOn(channel: Int, recognise: Boolean) {
        changed = false
        if (channel !in 0..15) return
        if (recognise && zone == MpeZone.OFF && channel in 1..15 &&
            (1..15).any { it != channel && down[it] > 0 }
        ) {
            zone = MpeZone.LOWER
            members = 15
            fromConfig = false
            changed = true
        }
        down[channel] += 1
    }

    fun noteOff(channel: Int) {
        changed = false
        if (channel in 0..15 && down[channel] > 0) down[channel] -= 1
    }

    /** Nothing plugged in: back to having heard nothing. */
    fun forget() {
        zone = MpeZone.OFF
        members = 15
        bendSemis = MpeZone.DEFAULT_BEND_SEMIS
        fromConfig = false
        rpnMsb.fill(127); rpnLsb.fill(127); rangeSemis.fill(48); down.fill(0)
        changed = true
    }

    private fun announced(channel: Int, count: Int) {
        if (channel != 0 && channel != 15) return
        zone = if (count == 0) MpeZone.OFF else if (channel == 0) MpeZone.LOWER else MpeZone.UPPER
        members = MpeZone.clampMembers(if (count == 0) 15 else count)
        // The specification: a new zone's fingers bend forty-eight until told otherwise.
        bendSemis = MpeZone.DEFAULT_BEND_SEMIS
        fromConfig = zone != MpeZone.OFF
        changed = true
    }

    private fun bendRange(channel: Int, semis: Float) {
        // The master channel's range is the whole track's, which is each
        // machine's own bend knob; only a finger's is the zone's.
        val master = if (zone == MpeZone.UPPER) 15 else 0
        if (channel == master) return
        bendSemis = MpeZone.clampBend(semis)
        changed = true
    }
}
