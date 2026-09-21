package com.rm.acidulous.model

/**
 * Where a tick lands once the beat is not even.
 *
 * The same map as `sequencer/Swing.h`, and it has to stay the same: the engine
 * uses it to decide where a note *sounds*, and this side uses the inverse to
 * decide where a note somebody played should be *written*. If the two ever
 * disagreed, a part played in would land next to where it was heard, which is
 * the one thing the inverse exists to prevent. `SwingTest` checks a row of
 * values against the numbers the C++ harness prints.
 *
 * It is a time warp rather than a delay: each pair of subdivisions is mapped
 * onto itself with its midpoint moved late, linearly on each side. Continuous,
 * monotonic, and exactly the identity at fifty percent, so two notes a tick
 * apart stay a tick apart and stay in order wherever they sit.
 */
object Swing {
    /** A pair of sixteenths - what a groovebox means by swing. */
    const val SIXTEENTHS = PPQN / 2
    /** A pair of eighths, which is the jazz and shuffle feel. */
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

    /** The tick that would sound at [swung] - what a live part has to be put back through. */
    fun from(swung: Int, percent: Float, pair: Int): Int {
        if (straight(percent) || pair < 2 || swung < 0) return swung
        val mid = midpoint(percent, pair)
        val half = pair / 2
        val base = swung - swung % pair
        val pos = swung % pair
        val out = if (pos < mid) pos * half / mid else half + (pos - mid) * (pair - half) / (pair - mid)
        return base + out
    }

    /** Kept off both ends, so neither half of the pair collapses to an instant. */
    private fun midpoint(percent: Float, pair: Int): Int {
        val clamped = percent.coerceIn(SWING_STRAIGHT, SWING_MAX)
        val guard = pair / 20 + 1
        return (pair * clamped * 0.01f + 0.5f).toInt().coerceIn(guard, pair - guard)
    }
}

/** This track's swing, which is the song's unless the track says otherwise. */
fun Song.swingOf(track: Track): Float = track.swing ?: swing

/** The pair this song's swing bends. */
val Song.swingPair: Int get() = Swing.pairOf(swingUnit)
