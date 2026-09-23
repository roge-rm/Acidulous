package com.rm.acidulous.model

/**
 * A lane point that means "back to the knob": whatever the parameter is set
 * to for the whole clip, resolved when the clip is sent to the engine.
 *
 * Normalised values are 0..1, so a negative one can never be a real value.
 * It is what makes a lock *follow* the knob (Dan's choice, 2026-09-23): turn
 * the knob for the whole clip after locking a step, and every step without a
 * lock goes where the knob went. Written down as a number instead, the steps
 * around a lock would stay where the knob was on the day it was made.
 */
const val LANE_BASE = -1f

/** One step's own value for a parameter, from [tick] until [end]. */
data class Lock(val tick: Int, val end: Int, val value: Float)

/**
 * Step parameter locks, stored as an ordinary stepped lane: the locked value
 * at a step's start and [LANE_BASE] at its end. So they record, undo, copy,
 * freeze and export as lanes do, and the engine plays them with no idea they
 * are anything else.
 */
object Locks {

    /** A lane made of locks, as opposed to one drawn or recorded. */
    fun isLocks(lane: Lane?): Boolean = lane != null && !lane.linear && lane.points.any { it.value == LANE_BASE }

    /**
     * A drawn or recorded curve, which locks leave alone: the two would fight
     * over one parameter, so a knob with one refuses to lock (Dan's choice).
     */
    fun isDrawn(lane: Lane?): Boolean = lane != null && lane.points.isNotEmpty() && !isLocks(lane)

    /** The locks a lane holds: each real value until the next point, or the clip's end. */
    fun of(lane: Lane?, clipTicks: Int): List<Lock> {
        if (lane == null || !isLocks(lane)) return emptyList()
        val pts = lane.points
        return pts.indices.mapNotNull { i ->
            val p = pts[i]
            if (p.value == LANE_BASE) return@mapNotNull null
            val end = pts.getOrNull(i + 1)?.tick ?: clipTicks
            if (end <= p.tick) null else Lock(p.tick, end, p.value)
        }
    }

    /** The lane for these locks, or null when there are none. */
    fun lane(locks: List<Lock>, clipTicks: Int): Lane? {
        if (locks.isEmpty()) return null
        val sorted = locks.sortedBy { it.tick }
        val pts = ArrayList<LanePoint>()
        // Before the first lock, the knob. A lane holds its first value back
        // to the top of the clip, so without this the first lock would.
        if (sorted.first().tick > 0) pts += LanePoint(0, LANE_BASE)
        sorted.forEachIndexed { i, l ->
            pts += LanePoint(l.tick, l.value)
            val next = sorted.getOrNull(i + 1)
            // Back to the knob where it ends, unless the next lock takes over
            // right there - or it runs to the end, where the top of the clip
            // takes over.
            if (next?.tick != l.end && l.end < clipTicks) pts += LanePoint(l.end, LANE_BASE)
        }
        return Lane(pts, linear = false)
    }

    /** [value] on every span, replacing whatever locks overlapped them. */
    fun set(lane: Lane?, spans: List<IntRange>, value: Float, clipTicks: Int): Lane? {
        val v = value.coerceIn(0f, 1f)
        val kept = of(lane, clipTicks).filter { l -> spans.none { overlaps(l, it) } }
        val added = spans.map { Lock(it.first, it.last + 1, v) }
        return lane(kept + added, clipTicks)
    }

    /** No locks on these spans any more. */
    fun clear(lane: Lane?, spans: List<IntRange>, clipTicks: Int): Lane? =
        lane(of(lane, clipTicks).filter { l -> spans.none { overlaps(l, it) } }, clipTicks)

    /** The lock covering [tick], if there is one. */
    fun at(lane: Lane?, tick: Int, clipTicks: Int): Lock? = of(lane, clipTicks).firstOrNull { tick >= it.tick && tick < it.end }

    /** What the engine is sent: "back to the knob" replaced by where the knob is. */
    fun resolve(lane: Lane, base: Float): Lane =
        if (lane.points.none { it.value == LANE_BASE }) lane
        else lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it.copy(value = base) else it })

    /** Every tick a lock starts on in this clip, for the grid to mark. */
    fun startTicks(clip: Clip, clipTicks: Int): Set<Int> =
        clip.automation.values.flatMap { of(it, clipTicks).map { l -> l.tick } }.toSet()

    private fun overlaps(l: Lock, span: IntRange) = l.tick <= span.last && span.first < l.end
}
