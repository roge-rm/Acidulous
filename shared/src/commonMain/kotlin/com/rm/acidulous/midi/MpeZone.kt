package com.rm.acidulous.midi

/**
 * Which incoming channels are MPE member channels (one per finger).
 *
 * Kept out of [MidiHub], which can't be built outside Android, so it can be
 * tested. Off-by-one errors here fail quietly: counting the master channel
 * as a member treats the controller's own bends as a note's, and stopping a
 * channel short drops the last finger when enough notes are played.
 *
 * Channels are 0-based here, but 1-based wherever users see them.
 */
object MpeZone {
    const val OFF = 0
    const val LOWER = 1
    const val UPPER = 2
    /**
     * A setting, never a zone: follow the controller. Uses the zone its MPE
     * configuration message announces, or a lower zone as soon as two notes
     * are held on separate channels.
     */
    const val AUTO = 3

    /** The default the MPE spec tells receivers to assume. */
    const val DEFAULT_BEND_SEMIS = 48f

    fun member(zone: Int, members: Int, channel: Int): Boolean {
        if (zone == OFF || channel !in 0..15) return false
        val n = clampMembers(members)
        // Lower: the master is channel 1 and members go up from 2.
        // Upper: the master is 16 and members go down from 15.
        return if (zone == LOWER) channel in 1..n else channel <= 14 && channel >= 15 - n
    }

    fun clampZone(z: Int): Int = z.coerceIn(OFF, UPPER)
    fun clampSetting(z: Int): Int = z.coerceIn(OFF, AUTO)
    fun clampMembers(n: Int): Int = n.coerceIn(1, 15)
    fun clampBend(semis: Float): Float = semis.coerceIn(1f, 96f)
}

/**
 * Works out a controller's MPE setup for the auto zone setting, from what it
 * announces and how it plays.
 *
 * Two messages describe a controller: the MPE configuration message (RPN 6
 * on channel 1 for a lower zone or 16 for an upper, with the number of
 * member channels as its value) and the pitch bend range (RPN 0, sent to a
 * member channel). An RPN is selected with CC 101 and 100 and set with CC 6,
 * plus CC 38 for the fine part. A controller that sends neither is detected
 * by something no normal keyboard does: two notes held at once on two
 * channels above the first.
 *
 * Plain Kotlin, separate from MidiHub, so it can be tested without a device.
 * Channels are 0-based.
 */
class MpeAuto {
    var zone = MpeZone.OFF
        private set
    var members = 15
        private set
    var bendSemis = MpeZone.DEFAULT_BEND_SEMIS
        private set
    /** The zone came from the controller's configuration message, not from how it played. */
    var fromConfig = false
        private set
    /** Whether the last call changed any of the above. */
    var changed = false
        private set

    private val rpnMsb = IntArray(16) { 127 }
    private val rpnLsb = IntArray(16) { 127 }
    private val rangeSemis = IntArray(16) { 48 }
    private val down = IntArray(16)

    /** A control change. True when it was part of an RPN and has been handled here. */
    fun controller(channel: Int, cc: Int, value: Int): Boolean {
        changed = false
        if (channel !in 0..15) return false
        val selected = rpnMsb[channel] * 128 + rpnLsb[channel]
        when (cc) {
            101 -> { rpnMsb[channel] = value; return true }
            100 -> { rpnLsb[channel] = value; return true }
            // An NRPN deselects the RPN. What it's for isn't our business.
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

    /** A note-on. With [recognise], a second held note on its own channel turns on a lower zone. */
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

    /** Device unplugged: forget everything. */
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
        // Per the spec, a new zone's members bend 48 semitones until told otherwise.
        bendSemis = MpeZone.DEFAULT_BEND_SEMIS
        fromConfig = zone != MpeZone.OFF
        changed = true
    }

    private fun bendRange(channel: Int, semis: Float) {
        // The master channel's bend range applies to the whole track, which
        // each machine's own bend knob handles. Only member channels set the zone's.
        val master = if (zone == MpeZone.UPPER) 15 else 0
        if (channel == master) return
        bendSemis = MpeZone.clampBend(semis)
        changed = true
    }
}
