package com.rm.acidulous.model

import com.rm.acidulous.io.*

/**
 * Listening through a phrase: the lane loops the phrase with a short run-up,
 * each pass with the next take over the phrase, until one is kept.
 *
 * Nothing is written to the song while listening. The engine is sent the
 * lane as it would be with each take kept ([listenSpec]), so a pass sounds
 * exactly like keeping that take, joins included.
 */

/** How much of the lane plays before the phrase on each pass: one second. */
const val LISTEN_LEAD = ENGINE_RATE

/**
 * What one listen-through plays. [start] and [end] are the phrase and [from]
 * where each pass starts, all in region frames. [takes] are the stack's
 * takes with audio in the phrase, in the order they play.
 */
data class ListenPlan(val lane: Int, val start: Int, val end: Int, val from: Int, val takes: List<Int>) {
    /** One pass, in frames. */
    val length: Int get() = end - from

    /** Which pass [played] frames in is, counting from 0 and going round, or -1 before it starts. */
    fun passAt(played: Long): Int =
        if (played < 0 || length <= 0 || takes.isEmpty()) -1 else ((played / length) % takes.size).toInt()

    /** The take playing [played] frames in, or -1. */
    fun takeAt(played: Long): Int = takes.getOrNull(passAt(played)) ?: -1

    /** Where in the region the loop is [played] frames in. */
    fun frameAt(played: Long): Int = if (length <= 0 || played < 0) from else from + (played % length).toInt()
}

/**
 * Listening through phrase [start, end) of this lane, lane [lane] of its
 * cell. Null if fewer than two takes have audio there, as there'd be
 * nothing to choose between.
 */
fun TakeRef.listenPlan(lane: Int, start: Int, end: Int, lead: Int = LISTEN_LEAD): ListenPlan? {
    val s = start.coerceIn(0, frames)
    val e = end.coerceIn(s, frames)
    if (e <= s) return null
    val takes = stack().withIndex().filter { (_, t) -> minOf(t.end, e) > maxOf(t.start, s) }.map { it.index }
    if (takes.size < 2) return null
    return ListenPlan(lane, s, e, (s - lead).coerceAtLeast(0), takes)
}

/** The lane with [take] kept over the plan's phrase. */
fun TakeRef.keeping(plan: ListenPlan, take: Int): TakeRef = choosing(take, plan.start, plan.end)

/**
 * What the engine is told to listen through: a header and then the lane as
 * it would be with each take kept, one cell per pass in the order they play.
 * Empty if the lane's file is missing.
 *
 *     listen|lane|from|to
 *     ...a reel spec, cells 1, 2, ... each with lane 0...
 */
fun TakeRef.listenSpec(plan: ListenPlan, root: File): String {
    val body = buildString {
        for ((i, k) in plan.takes.withIndex()) appendRegion(i + 1L, 0, keeping(plan, k), root)
    }
    if (body.isEmpty()) return ""
    return "listen|${plan.lane}|${plan.from}|${plan.end}\n$body"
}

/** The take heard most over [start, end), or 0 if none is. */
fun TakeRef.heardIn(start: Int, end: Int): Int {
    val heard = pieces.ifEmpty { listOf(Piece(0, frames, 0)) }
    return heard.groupBy { it.take }
        .mapValues { (_, ps) -> ps.sumOf { (minOf(it.end, end) - maxOf(it.start, start)).coerceAtLeast(0) } }
        .filterValues { it > 0 }
        .maxByOrNull { it.value }?.key ?: 0
}
