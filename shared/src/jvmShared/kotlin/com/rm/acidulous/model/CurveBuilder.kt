package com.rm.acidulous.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A stream of controller values on its way to becoming one of a [Note]'s
 * curves.
 *
 * A finger on an MPE controller sends two or three hundred messages a second,
 * per dimension, per finger. Kept as they arrive, a two-second bend would be
 * four hundred points in the document, and a chord's worth of them would make
 * the song file larger than everything else in it put together - for a shape
 * that a dozen points describe to well under a cent.
 *
 * So it thins as it goes, and the promise it makes is the one that matters:
 * **every value that was dropped is within [epsilon] of the curve that is
 * kept**. Not within epsilon of its neighbours - of the line that will
 * actually be played back through where it was.
 *
 * The way that is done in one pass is a *corridor*. From the last point
 * committed, each value that arrives says the next segment must have a slope
 * somewhere in a small range if it is to pass within epsilon of it; the
 * ranges are intersected as the values come in, and while the intersection is
 * non-empty the whole run can be drawn with one straight line and none of it
 * need be written down. The moment a value arrives that no line in the
 * corridor can reach, the previous value is committed - it is the last one
 * that could be - and a new corridor opens from there.
 *
 * That costs two floats and one comparison a sample, holds regardless of how
 * long the run is, and gives a slow even glide back as its two ends.
 */
class CurveBuilder(
    /** The value the curve says nothing at: centred bend, or no pressure. */
    private val neutral: Float,
    /** How far off the played-back curve a dropped value may be. */
    private val epsilon: Float = 1f / 512f,
    /** A hard ceiling, in case a controller sends faster than the rule thins. */
    private val limit: Int = 512,
) {
    private val kept = ArrayList<LanePoint>(16)
    private var anchorTick = 0
    private var anchorValue = 0f
    private var pendingTick = 0
    private var pendingValue = 0f
    private var havePending = false
    private var loSlope = Float.NEGATIVE_INFINITY
    private var hiSlope = Float.POSITIVE_INFINITY

    fun add(tick: Int, value: Float) {
        val t = tick.coerceAtLeast(0)
        val v = value.coerceIn(0f, 1f)
        if (kept.isEmpty()) {
            kept.add(LanePoint(t, v))
            anchorTick = t
            anchorValue = v
            return
        }
        // Two values at the same tick: the later one is what was meant.
        if (t <= anchorTick) {
            pendingTick = t
            pendingValue = v
            havePending = true
            return
        }
        val span = (t - anchorTick).toFloat()
        val slope = (v - anchorValue) / span
        if (havePending && kept.size < limit && (slope < loSlope || slope > hiSlope)) {
            // No line from the anchor can reach here and still pass close
            // enough to everything between: the last value that could be
            // reached is the end of this segment.
            kept.add(LanePoint(pendingTick, pendingValue))
            anchorTick = pendingTick
            anchorValue = pendingValue
            openCorridor(t, v)
        } else {
            narrowCorridor(t, v)
        }
        pendingTick = t
        pendingValue = v
        havePending = true
    }

    /**
     * The curve, or null when it says nothing.
     *
     * Nothing means: no values at all, or every one of them within [epsilon]
     * of [neutral]. A finger that never left the centre should cost a note no
     * bytes; a note whose pressure was a steady three-quarters should cost it
     * one point. So it is flatness *at neutral* that is dropped, not flatness.
     */
    fun build(): Lane? {
        // The last value is only worth writing down if it says something the
        // point before it does not - a curve is held flat past its end.
        if (havePending && (kept.isEmpty() || abs(pendingValue - kept.last().value) > epsilon)) {
            kept.add(LanePoint(pendingTick, pendingValue))
        }
        havePending = false
        if (kept.isEmpty()) return null
        if (kept.all { abs(it.value - neutral) <= epsilon }) return null
        return Lane(points = kept.toList(), linear = true)
    }

    private fun openCorridor(t: Int, v: Float) {
        val span = (t - anchorTick).toFloat()
        if (span <= 0f) {
            loSlope = Float.NEGATIVE_INFINITY
            hiSlope = Float.POSITIVE_INFINITY
            return
        }
        loSlope = (v - epsilon - anchorValue) / span
        hiSlope = (v + epsilon - anchorValue) / span
    }

    private fun narrowCorridor(t: Int, v: Float) {
        val span = (t - anchorTick).toFloat()
        if (span <= 0f) return
        loSlope = max(loSlope, (v - epsilon - anchorValue) / span)
        hiSlope = min(hiSlope, (v + epsilon - anchorValue) / span)
    }
}
