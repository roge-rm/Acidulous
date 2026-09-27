package com.rm.acidulous.model

/**
 * Where a tick lands once the beat is swung.
 *
 * The same map as `sequencer/Swing.h`, and it has to stay that way. The
 * engine uses it to decide where a note sounds, and this side uses the
 * inverse to decide where a played note should be written. If they differed,
 * recorded parts would land off from where they were heard. `SwingTest`
 * checks values against the numbers the C++ harness prints.
 *
 * Each pair of subdivisions is mapped onto itself with its midpoint moved
 * later, linearly on each side. It's continuous, keeps notes in order, and is
 * exactly the identity at 50%.
 */
object Swing {
    /** A pair of sixteenths, the usual groovebox swing. */
    const val SIXTEENTHS = PPQN / 2
    /** A pair of eighths, for a jazz or shuffle feel. */
    const val EIGHTHS = PPQN

    fun pairOf(unit: Int): Int = if (unit == 1) EIGHTHS else SIXTEENTHS

    fun straight(percent: Float): Boolean = percent <= SWING_STRAIGHT + 0.01f

    /** Where [tick] sounds. */
    fun at(tick: Int, percent: Float, pair: Int): Int {
        if (straight(percent) || pair < 2 || tick < 0) return tick
        val mid = midpoint(percent, pair)
        val half = pair / 2
        val base = tick - tick % pair
        val pos = tick % pair
        val out = if (pos < half) pos * mid / half else mid + (pos - half) * (pair - mid) / (pair - half)
        return base + out
    }

    /** The tick that would sound at [swung]. Live input is put back through this. */
    fun from(swung: Int, percent: Float, pair: Int): Int {
        if (straight(percent) || pair < 2 || swung < 0) return swung
        val mid = midpoint(percent, pair)
        val half = pair / 2
        val base = swung - swung % pair
        val pos = swung % pair
        val out = if (pos < mid) pos * half / mid else half + (pos - mid) * (pair - half) / (pair - mid)
        return base + out
    }

    /** Kept away from both ends so neither half of the pair shrinks to nothing. */
    private fun midpoint(percent: Float, pair: Int): Int {
        val clamped = percent.coerceIn(SWING_STRAIGHT, SWING_MAX)
        val guard = pair / 20 + 1
        return (pair * clamped * 0.01f + 0.5f).toInt().coerceIn(guard, pair - guard)
    }
}

/** This track's swing: the song's unless the track has its own. */
fun Song.swingOf(track: Track): Float = track.swing ?: swing

/** The pair this song's swing moves. */
val Song.swingPair: Int get() = Swing.pairOf(swingUnit)
