package com.rm.acidulous.model

import kotlin.math.roundToInt

/**
 * Moving notes onto a grid, part of the way or all of it, and back again.
 *
 * Plain arithmetic over a clip's notes, so the editor's window, recording and
 * a Launchpad's Quantise button all mean the same thing by the word.
 *
 * **Every note remembers where it was played.** [Note.rawTick] is set the
 * first time a note is moved by quantising and never overwritten after, so
 * "as played" can always put it back, however many times it has been
 * quantised since. A [Note.nudge] is a deliberate placement and is left
 * alone.
 *
 * **Groove is a clip's timing, not the song's swing.** Swing is applied as the
 * song plays and clips hold straight time, so quantising to a straight grid
 * already sounds swung. A groove is instead how far a chosen clip's notes sit
 * from each step of the grid - a drummer's late backbeat, say - and a note
 * quantised to it lands that far from its own step.
 */
data class QuantiseSpec(
    /** The grid, in ticks. */
    val grid: Int,
    /** How far each note moves towards the grid: 1 all the way, 0 not at all. */
    val strength: Float = 1f,
    /** Whether the ends move too, or only the starts. */
    val ends: Boolean = false,
    /** Ticks each step of a bar sits off the grid, from [Quantise.grooveFrom]; null for straight. */
    val groove: IntArray? = null,
)

object Quantise {
    /** Where a tick belongs: its nearest grid line, and the groove's offset for that step. */
    fun target(tick: Int, spec: QuantiseSpec): Int {
        val g = spec.grid.coerceAtLeast(1)
        val slot = Math.floorDiv(tick + g / 2, g)
        val off = spec.groove?.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(slot, it.size)] } ?: 0
        return slot * g + off
    }

    /**
     * The notes, with those in [which] - all of them when null - moved
     * [QuantiseSpec.strength] of the way to where they belong. Order is kept,
     * so indices still mean the same notes; sort before handing it on.
     */
    fun apply(notes: List<Note>, which: Set<Int>?, spec: QuantiseSpec, clipTicks: Int): List<Note> {
        val g = spec.grid.coerceAtLeast(1)
        // A note near the end belongs on the last line, not on the next
        // loop's first: in a clip being edited that is its start.
        val lastLine = ((clipTicks - 1).coerceAtLeast(0) / g) * g
        return notes.mapIndexed { i, n ->
            if (which != null && i !in which) return@mapIndexed n
            val to = target(n.tick, spec).coerceIn(0, lastLine)
            val tick = (n.tick + ((to - n.tick) * spec.strength).roundToInt()).coerceIn(0, (clipTicks - 1).coerceAtLeast(0))
            val length = if (spec.ends) {
                // The end goes to a line of its own, at least a step after the start's.
                val end = n.tick + n.length
                val endTo = maxOf(target(end, spec), to + g)
                val newEnd = end + ((endTo - end) * spec.strength).roundToInt()
                (newEnd - tick).coerceAtLeast(1)
            } else n.length
            if (tick == n.tick && length == n.length) n
            else n.copy(tick = tick, length = length, rawTick = n.rawTick ?: n.tick)
        }
    }

    /** The notes in [which] - all when null - back where they were played, where that is known. */
    fun asPlayed(notes: List<Note>, which: Set<Int>?, clipTicks: Int): List<Note> =
        notes.mapIndexed { i, n ->
            val raw = n.rawTick
            if ((which != null && i !in which) || raw == null) n
            else n.copy(tick = raw.coerceIn(0, (clipTicks - 1).coerceAtLeast(0)))
        }

    /**
     * A clip's timing as a groove: for each step of a bar on [grid], how far
     * the clip's notes nearest that step sit from it, averaged. Steps it has
     * no notes near are left on the grid.
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
}
