package com.rm.acidulous.model

/**
 * A lane point that means "back to the knob": whatever the parameter is set
 * to for the whole clip, filled in when the clip is sent to the engine.
 *
 * Normalised values are 0..1, so a negative value can't be a real one. This
 * is what makes locks follow the knob: turn the knob after locking a step and
 * every unlocked step follows it. Storing a number instead would freeze the
 * steps around a lock at the old knob position.
 */
const val LANE_BASE = -1f

/** One step's own value for a parameter, from [tick] until [end]. */
data class Lock(val tick: Int, val end: Int, val value: Float)

/**
 * Step parameter locks, stored as an ordinary stepped lane: the locked value
 * at a step's start and [LANE_BASE] at its end. So they record, undo, copy,
 * freeze and export like any lane, and the engine plays them like any lane.
 */
object Locks {

    /** A lane made of locks rather than drawn or recorded. */
    fun isLocks(lane: Lane?): Boolean = lane != null && !lane.linear && lane.points.any { it.value == LANE_BASE }

    /**
     * A drawn or recorded curve. Locks leave these alone because the two would
     * fight over one parameter, so a knob with a curve can't be locked.
     */
    fun isDrawn(lane: Lane?): Boolean = lane != null && lane.points.isNotEmpty() && !isLocks(lane)

    /** The locks in a lane: each real value until the next point or the clip's end. */
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
        // to the start of the clip, so without this the first lock would.
        if (sorted.first().tick > 0) pts += LanePoint(0, LANE_BASE)
        sorted.forEachIndexed { i, l ->
            pts += LanePoint(l.tick, l.value)
            val next = sorted.getOrNull(i + 1)
            // Back to the knob where it ends, unless the next lock starts
            // right there or it runs to the end of the clip.
            if (next?.tick != l.end && l.end < clipTicks) pts += LanePoint(l.end, LANE_BASE)
        }
        return Lane(pts, linear = false)
    }

    /** Sets [value] on every span, replacing any locks that overlap them. */
    fun set(lane: Lane?, spans: List<IntRange>, value: Float, clipTicks: Int): Lane? {
        val v = value.coerceIn(0f, 1f)
        val kept = of(lane, clipTicks).filter { l -> spans.none { overlaps(l, it) } }
        val added = spans.map { Lock(it.first, it.last + 1, v) }
        return lane(kept + added, clipTicks)
    }

    /** Removes locks on these spans. */
    fun clear(lane: Lane?, spans: List<IntRange>, clipTicks: Int): Lane? =
        lane(of(lane, clipTicks).filter { l -> spans.none { overlaps(l, it) } }, clipTicks)

    /** The lock covering [tick], if there is one. */
    fun at(lane: Lane?, tick: Int, clipTicks: Int): Lock? = of(lane, clipTicks).firstOrNull { tick >= it.tick && tick < it.end }

    /** What the engine is sent: "back to the knob" replaced by the knob's value. */
    fun resolve(lane: Lane, base: Float): Lane =
        if (lane.points.none { it.value == LANE_BASE }) lane
        else lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it.copy(value = base) else it })

    /** Every tick a lock starts on in this clip, for the grid to mark. */
    fun startTicks(clip: Clip, clipTicks: Int): Set<Int> =
        clip.automation.values.flatMap { of(it, clipTicks).map { l -> l.tick } }.toSet()

    private fun overlaps(l: Lock, span: IntRange) = l.tick <= span.last && span.first < l.end
}
