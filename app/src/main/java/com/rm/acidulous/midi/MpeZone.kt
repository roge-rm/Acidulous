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
    fun clampMembers(n: Int): Int = n.coerceIn(1, 15)
    fun clampBend(semis: Float): Float = semis.coerceIn(1f, 96f)
}
