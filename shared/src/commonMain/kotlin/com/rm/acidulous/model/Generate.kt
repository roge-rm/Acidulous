package com.rm.acidulous.model

import com.rm.acidulous.util.Math

import com.rm.acidulous.util.JavaRandom

/**
 * Pattern generators: rhythms and lines written as ordinary notes.
 *
 * Each one depends only on its settings and a seed, so the same settings give
 * the same notes every time. Moving a slider back restores what was there,
 * and a roll just picks a new seed. The result is normal notes in the clip
 * that you can edit like any others.
 */
object Generate {

    /**
     * [hits] spread as evenly as possible over [steps], shifted by [rotate].
     *
     * A step gets a hit where the running count of hits per step passes a
     * whole number. That gives the same patterns as Bjorklund's algorithm up
     * to a rotation. Before rotating, the first step is always a hit.
     */
    fun euclid(hits: Int, steps: Int, rotate: Int = 0): BooleanArray {
        val n = steps.coerceAtLeast(1)
        val k = hits.coerceIn(0, n)
        val r = Math.floorMod(rotate, n)
        return BooleanArray(n) { i ->
            val j = Math.floorMod(i + r, n)
            // (j * k) mod n < k marks exactly k of the n steps, evenly.
            (j * k) % n < k
        }
    }

    data class Euclid(
        val hits: Int = 5,
        val steps: Int = 16,
        val rotate: Int = 0,
        /** How long a step is, in ticks. */
        val stepTicks: Int = PPQN / 4,
        val pitch: Int = 36,
        val velocity: Int = 100,
    )

    /**
     * The pattern across the whole clip, restarting every [Euclid.steps]. So
     * five over twelve against a bar of sixteen drifts across the bar line.
     */
    fun euclidNotes(e: Euclid, clipTicks: Int): List<Note> {
        val pattern = euclid(e.hits, e.steps, e.rotate)
        val step = e.stepTicks.coerceAtLeast(1)
        val len = (step * 3 / 4).coerceAtLeast(1)
        return (0 until clipTicks / step).mapNotNull { i ->
            if (pattern[i % pattern.size]) Note(i * step, len, e.pitch, e.velocity) else null
        }
    }

    data class Line(
        /** The chance of a note on each step. */
        val density: Float = 0.6f,
        /** The lowest note it may use. */
        val low: Int = 48,
        /** How far above [low] it may go, in octaves. */
        val octaves: Int = 1,
        /** 0 walks to a neighbour; 1 jumps anywhere in range. */
        val leap: Float = 0.3f,
        /** 0 short, 1 a whole step, 2 some tied across steps. */
        val length: Int = 1,
        val stepTicks: Int = PPQN / 4,
        val seed: Int = 1,
    )

    /**
     * The notes of [pitchClasses] between [low] and [high], in order. With no
     * key set it uses a minor pentatonic on [low], which fits with nearly
     * anything.
     */
    fun ladder(pitchClasses: Set<Int>?, low: Int, high: Int): List<Int> {
        val classes = pitchClasses?.takeIf { it.isNotEmpty() }
            ?: setOf(0, 3, 5, 7, 10).map { (it + low) % 12 }.toSet()
        return (low.coerceIn(0, 127)..high.coerceIn(0, 127)).filter { it % 12 in classes }
    }

    /**
     * A line in key. Each step plays a note or not by [Line.density], and each
     * note moves a step or two in the scale from the last or leaps anywhere in
     * range. Notes on the beat are a little louder so the line has a pulse.
     */
    fun lineNotes(l: Line, pitchClasses: Set<Int>?, clipTicks: Int, ticksPerBeat: Int = PPQN): List<Note> {
        val rungs = ladder(pitchClasses, l.low, l.low + 12 * l.octaves.coerceIn(1, 4))
        if (rungs.isEmpty()) return emptyList()
        val rng = JavaRandom(l.seed.toLong() * 7919L + 17L)
        val step = l.stepTicks.coerceAtLeast(1)
        val count = clipTicks / step
        // Every step's random values are drawn up front, played or not, so
        // changing density or length changes which steps sound without
        // reshuffling what the others play.
        class Draw(val roll: Float, val jump: Boolean, val anywhere: Int, val walk: Int, val tie: Float, val ties: Int, val vel: Int)
        val draws = List(count) {
            Draw(rng.nextFloat(), rng.nextFloat() < l.leap, rng.nextInt(rungs.size), rng.nextInt(5) - 2,
                rng.nextFloat(), 2 + rng.nextInt(2), rng.nextInt(12))
        }
        val out = ArrayList<Note>()
        var at = (rungs.size / 3).coerceAtMost(rungs.size - 1)
        var i = 0
        while (i < count) {
            val d = draws[i]
            if (d.roll >= l.density) { i++; continue }
            at = (if (d.jump) d.anywhere else at + d.walk).coerceIn(0, rungs.size - 1)
            val tick = i * step
            val steps = if (l.length == 2 && d.tie < 0.3f) d.ties.coerceAtMost(count - i) else 1
            val len = if (l.length == 0) (step / 2).coerceAtLeast(1) else (steps * step - step / 8).coerceAtLeast(1)
            val strong = tick % ticksPerBeat == 0
            out += Note(tick, len, rungs[at], (if (strong) 104 else 84) + d.vel - 6)
            i += steps
        }
        return out
    }

    data class Mutation(
        /** How much of it changes, 0..1. */
        val amount: Float = 0.25f,
        val seed: Int = 1,
    )

    /**
     * [notes] changed by about [Mutation.amount]: some move a step or two in
     * the scale, some are removed, a few new ones are added next to old ones,
     * and velocities wander. At 0 nothing changes. Drums ([drums] true) keep
     * their pitches, since a moved pitch is a different drum, and move in
     * time instead.
     */
    fun mutate(
        notes: List<Note>,
        m: Mutation,
        pitchClasses: Set<Int>?,
        clipTicks: Int,
        grid: Int,
        drums: Boolean,
    ): List<Note> {
        if (m.amount <= 0f || notes.isEmpty()) return notes
        val a = m.amount.coerceIn(0f, 1f)
        val rng = JavaRandom(m.seed.toLong() * 104729L + 3L)
        val step = grid.coerceAtLeast(1)
        val low = notes.minOf { it.pitch } - 12
        val high = notes.maxOf { it.pitch } + 12
        val rungs = if (drums) emptyList() else ladder(pitchClasses ?: notes.map { it.pitch % 12 }.toSet(), low, high)
        val out = ArrayList<Note>()
        for (n in notes) {
            // Every value is drawn for every note, as in lineNotes, so turning
            // the amount up adds changes instead of picking different ones.
            val change = rng.nextFloat()
            val what = rng.nextFloat()
            val by = if (rng.nextBoolean()) 1 + rng.nextInt(2) else -(1 + rng.nextInt(2))
            val dv = rng.nextInt(33) - 16
            val add = rng.nextFloat()
            val addAt = if (rng.nextBoolean()) step else -step
            if (change >= a) {
                out += n
            } else if (what < 0.25f) {
                // Removed.
            } else if (what < 0.7f && !drums) {
                val idx = rungs.indexOfFirst { it >= n.pitch }.let { if (it < 0) rungs.size - 1 else it }
                val p = rungs.getOrElse((idx + by).coerceIn(0, rungs.size - 1)) { n.pitch }
                out += n.copy(pitch = p)
            } else if (what < 0.7f) {
                val t = Math.floorMod(n.tick + by * step, clipTicks.coerceAtLeast(1))
                out += n.copy(tick = t, rawTick = null)
            } else {
                out += n.copy(velocity = (n.velocity + dv).coerceIn(1, 127))
            }
            // New notes are a third as likely as changes and copy a neighbour.
            if (add < a / 3f) {
                val t = n.tick + addAt
                if (t in 0 until clipTicks) out += n.copy(tick = t, length = minOf(n.length, step), rawTick = null)
            }
        }
        // Keep only one note per pitch per tick.
        return out.distinctBy { it.tick to it.pitch }.sortedBy { it.tick }
    }
}
