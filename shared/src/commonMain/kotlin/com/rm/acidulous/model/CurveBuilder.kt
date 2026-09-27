package com.rm.acidulous.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Collects controller values and turns them into one of a [Note]'s curves.
 *
 * An MPE controller sends a few hundred messages a second per dimension per
 * finger. Keeping them all would make a chord's bends bigger than the rest of
 * the song file, when a dozen points describe the shape just as well.
 *
 * So it thins the values as they arrive, and every value it drops is within
 * [epsilon] of the line that will be played back, not just of its neighbours.
 *
 * It does this in one pass with a corridor. From the last kept point, each new
 * value limits the slope the next segment can have and still pass within
 * epsilon of it. The limits are intersected as values come in. While the
 * range isn't empty, one straight line covers the whole run. When a value
 * arrives that no line in the range can reach, the previous value is kept and
 * a new corridor starts there.
 *
 * It costs two floats and one comparison per value, however long the run.
 */
class CurveBuilder(
    /** The value that means "nothing": centred bend, or no pressure. */
    private val neutral: Float,
    /** How far off the played-back curve a dropped value may be. */
    private val epsilon: Float = 1f / 512f,
    /** A hard cap on points, in case a controller sends faster than this thins. */
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
        // Two values at the same tick: keep the later one.
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
            // enough to everything before it, so the previous value ends
            // this segment.
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
     * Nothing means no values, or every value within [epsilon] of [neutral].
     * A finger that never left the centre costs nothing, but a steady pressure
     * of three-quarters still gets one point. Only flat at neutral is dropped.
     */
    fun build(): Lane? {
        // The last value is only kept if it differs from the point before it,
        // since a curve is held flat past its end.
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
