package com.rm.acidulous.model

import java.util.Random

/**
 * Pattern generators: rhythms and lines written as ordinary notes.
 *
 * Each one is a function of its settings and a seed and nothing else, so the
 * same settings give the same notes every time: moving a slider back puts
 * back what was there, and a roll is a new seed rather than a new world. What
 * comes out is notes in the clip, to edit like any others; nothing plays
 * differently for having been generated.
 */
object Generate {

    /**
     * [hits] spread as evenly as they go over [steps], turned by [rotate].
     *
     * The step that lands a hit is the one where the running count of
     * hits-per-step ticks over a whole number, which gives the same patterns
     * as Bjorklund's algorithm up to a rotation - and the rotation is a
     * setting anyway. The first step is always a hit before rotating.
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
     * The pattern across the whole clip, one step at a time, starting over
     * every [Euclid.steps] - so a pattern of five over twelve against a bar of
     * sixteen drifts across the bar line, which is half of why anyone wants one.
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
     * key to be in, a minor pentatonic on [low]: five notes that go with
     * nearly anything, rather than all twelve, which go with nothing.
     */
    fun ladder(pitchClasses: Set<Int>?, low: Int, high: Int): List<Int> {
        val classes = pitchClasses?.takeIf { it.isNotEmpty() }
            ?: setOf(0, 3, 5, 7, 10).map { (it + low) % 12 }.toSet()
        return (low.coerceIn(0, 127)..high.coerceIn(0, 127)).filter { it % 12 in classes }
    }

    /**
     * A line in key: on each step a note or not by [Line.density], each a walk
     * from the last by a step or two of the scale, or a leap anywhere in range.
     * Stronger beats come out a little harder, so a line generated here has a
     * pulse before anyone has touched it.
     */
    fun lineNotes(l: Line, pitchClasses: Set<Int>?, clipTicks: Int, ticksPerBeat: Int = PPQN): List<Note> {
        val rungs = ladder(pitchClasses, l.low, l.low + 12 * l.octaves.coerceIn(1, 4))
        if (rungs.isEmpty()) return emptyList()
        val rng = Random(l.seed.toLong() * 7919L + 17L)
        val step = l.stepTicks.coerceAtLeast(1)
        val count = clipTicks / step
        // Every step's dice drawn up front, whether it sounds or not, so the
        // density and length change which steps sound without reshuffling
        // what the rest of them play.
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
     * the scale, some go, a few new ones arrive beside old ones, and the
     * velocities wander. At nought it is exactly what it was. Drums ([drums]
     * true) keep their pitches - a kick that moves up a scale step is a
     * different drum - and move in time instead.
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
        val rng = Random(m.seed.toLong() * 104729L + 3L)
        val step = grid.coerceAtLeast(1)
        val low = notes.minOf { it.pitch } - 12
        val high = notes.maxOf { it.pitch } + 12
        val rungs = if (drums) emptyList() else ladder(pitchClasses ?: notes.map { it.pitch % 12 }.toSet(), low, high)
        val out = ArrayList<Note>()
        for (n in notes) {
            // Every draw made whatever happens, as in lineNotes, so turning
            // the amount up adds changes rather than choosing different ones.
            val change = rng.nextFloat()
            val what = rng.nextFloat()
            val by = if (rng.nextBoolean()) 1 + rng.nextInt(2) else -(1 + rng.nextInt(2))
            val dv = rng.nextInt(33) - 16
            val add = rng.nextFloat()
            val addAt = if (rng.nextBoolean()) step else -step
            if (change >= a) {
                out += n
            } else if (what < 0.25f) {
                // Gone.
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
            // New ones are a third as likely as changes, and copy a neighbour.
            if (add < a / 3f) {
                val t = n.tick + addAt
                if (t in 0 until clipTicks) out += n.copy(tick = t, length = minOf(n.length, step), rawTick = null)
            }
        }
        // Two notes of the same pitch on the same tick is one note played twice.
        return out.distinctBy { it.tick to it.pitch }.sortedBy { it.tick }
    }
}
