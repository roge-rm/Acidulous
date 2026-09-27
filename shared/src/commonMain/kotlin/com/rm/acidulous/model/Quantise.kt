package com.rm.acidulous.model

import com.rm.acidulous.util.Math

import kotlin.math.roundToInt

/**
 * Moves notes onto a grid, partly or fully, and back again.
 *
 * Just arithmetic over a clip's notes, so the editor, recording and the
 * Launchpad's Quantise button all do the same thing.
 *
 * Every note remembers where it was played. [Note.rawTick] is set the first
 * time a note is quantised and never overwritten, so "as played" can always
 * put it back. A [Note.nudge] is a deliberate placement and is left alone.
 *
 * Groove here is a clip's timing, separate from the song's swing. Swing is
 * applied during playback and clips hold straight time, so quantising to a
 * straight grid already sounds swung. A groove is how far a chosen clip's
 * notes sit from each step (a drummer's late backbeat, say), and notes
 * quantised to it land that far from their step.
 */
data class QuantiseSpec(
    /** The grid, in ticks. */
    val grid: Int,
    /** How far each note moves towards the grid: 1 all the way, 0 not at all. */
    val strength: Float = 1f,
    /** Whether note ends move too, or only starts. */
    val ends: Boolean = false,
    /** Ticks each step of a bar sits off the grid, from [Quantise.grooveFrom]; null for straight. */
    val groove: IntArray? = null,
)

object Quantise {
    /** Where a tick belongs: its nearest grid line plus the groove's offset for that step. */
    fun target(tick: Int, spec: QuantiseSpec): Int {
        val g = spec.grid.coerceAtLeast(1)
        val slot = Math.floorDiv(tick + g / 2, g)
        val off = spec.groove?.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(slot, it.size)] } ?: 0
        return slot * g + off
    }

    /**
     * The notes, with those in [which] (all of them when null) moved
     * [QuantiseSpec.strength] of the way to where they belong. The order is
     * kept so indices still match, so sort before passing it on.
     */
    fun apply(notes: List<Note>, which: Set<Int>?, spec: QuantiseSpec, clipTicks: Int): List<Note> {
        val g = spec.grid.coerceAtLeast(1)
        // A note near the end goes to the last line, not the next loop's
        // first, which would be the clip's start.
        val lastLine = ((clipTicks - 1).coerceAtLeast(0) / g) * g
        return notes.mapIndexed { i, n ->
            if (which != null && i !in which) return@mapIndexed n
            val to = target(n.tick, spec).coerceIn(0, lastLine)
            val tick = (n.tick + ((to - n.tick) * spec.strength).roundToInt()).coerceIn(0, (clipTicks - 1).coerceAtLeast(0))
            val length = if (spec.ends) {
                // The end snaps to its own line, at least one step after the start.
                val end = n.tick + n.length
                val endTo = maxOf(target(end, spec), to + g)
                val newEnd = end + ((endTo - end) * spec.strength).roundToInt()
                (newEnd - tick).coerceAtLeast(1)
            } else n.length
            if (tick == n.tick && length == n.length) n
            else n.copy(tick = tick, length = length, rawTick = n.rawTick ?: n.tick)
        }
    }

    /** Puts the notes in [which] (all when null) back where they were played, if known. */
    fun asPlayed(notes: List<Note>, which: Set<Int>?, clipTicks: Int): List<Note> =
        notes.mapIndexed { i, n ->
            val raw = n.rawTick
            if ((which != null && i !in which) || raw == null) n
            else n.copy(tick = raw.coerceIn(0, (clipTicks - 1).coerceAtLeast(0)))
        }

    /**
     * A clip's timing as a groove: for each step of a bar on [grid], the
     * average distance of the clip's nearest notes from it. Steps with no
     * notes near them stay on the grid.
     */
    fun grooveFrom(notes: List<Note>, grid: Int, ticksPerBar: Int): IntArray {
        val g = grid.coerceAtLeast(1)
        val steps = (ticksPerBar / g).coerceAtLeast(1)
        val sum = LongArray(steps)
        val count = IntArray(steps)
        for (n in notes) {
            val slot = Math.floorDiv(n.tick + g / 2, g)
            val i = Math.floorMod(slot, steps)
            sum[i] += (n.tick - slot * g).toLong()
            count[i]++
        }
        return IntArray(steps) { if (count[it] == 0) 0 else (sum[it] / count[it]).toInt() }
    }

    /**
     * Small random changes in timing, velocity and length, like a player's
     * hands. At [amount] 1 a note moves up to [HUMAN_TICKS] either way, its
     * velocity up to [HUMAN_VELOCITY] and its length up to [HUMAN_LENGTH] of
     * itself, mostly near the middle. The same [seed] gives the same result,
     * so a preview can be redrawn without re-rolling. The as-played position
     * isn't changed.
     */
    fun humanise(notes: List<Note>, which: Set<Int>?, amount: Float, seed: Long, clipTicks: Int): List<Note> {
        if (amount <= 0f) return notes
        val r = com.rm.acidulous.util.JavaRandom(seed)
        // Two uniforms added: a triangle from -1 to 1, mostly near 0.
        fun wobble() = r.nextFloat() + r.nextFloat() - 1f
        return notes.mapIndexed { i, n ->
            // Rolled for every note, selected or not, so a note's wobble
            // doesn't change when the selection does.
            val dt = (wobble() * HUMAN_TICKS * amount).roundToInt()
            val dv = (wobble() * HUMAN_VELOCITY * amount).roundToInt()
            val dl = wobble() * HUMAN_LENGTH * amount
            if (which != null && i !in which) return@mapIndexed n
            n.copy(
                tick = (n.tick + dt).coerceIn(0, (clipTicks - 1).coerceAtLeast(0)),
                velocity = (n.velocity + dv).coerceIn(1, 127),
                length = (n.length * (1f + dl)).roundToInt().coerceAtLeast(1),
            )
        }
    }

    /** At full amount: a fifth of a sixteenth, 15 velocity, 15% of the length. */
    const val HUMAN_TICKS = PPQN / 20
    const val HUMAN_VELOCITY = 15
    const val HUMAN_LENGTH = 0.15f
}
