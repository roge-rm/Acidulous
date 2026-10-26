package com.rm.acidulous.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The notes you last copied in an editor, and where the first of them sat.
 *
 * Only kept while the app is running, and apart from the system clipboard. It
 * stays when you switch clips, so notes can be pasted into another one. Compose
 * state so the paste button shows as soon as something is copied.
 */
object NoteClipboard {
    var notes by mutableStateOf<List<Note>>(emptyList())
        private set

    /** The tick the earliest copied note started at. */
    var origin by mutableStateOf(0)
        private set

    val has: Boolean get() = notes.isNotEmpty()

    /** Keeps the notes at [indices] of [clip]. An empty choice leaves the clipboard alone. */
    fun copy(clip: Clip, indices: Set<Int>) {
        val picked = indices.sorted().mapNotNull { clip.notes.getOrNull(it) }
        if (picked.isEmpty()) return
        notes = picked
        origin = picked.minOf { it.tick }
    }

    fun clear() { notes = emptyList(); origin = 0 }
}

/** The clip without the notes at [indices]. */
fun Clip.withoutNotes(indices: Set<Int>): Clip =
    if (indices.isEmpty()) this else copy(notes = notes.filterIndexed { i, _ -> i !in indices })

/**
 * The clip with [copied] added so the earliest starts at [at], and the
 * indices of the added notes. Notes that start past [clipLen] are dropped and
 * ones that run past it are shortened. [origin] is where the earliest was
 * copied from. Their expression curves count from each note's start, so they
 * come along unchanged.
 */
fun Clip.withPasted(copied: List<Note>, origin: Int, at: Int, clipLen: Int): Pair<Clip, Set<Int>> {
    val shift = at - origin
    val moved = copied.mapNotNull { n ->
        val tick = n.tick + shift
        if (tick < 0 || tick >= clipLen) null
        else n.copy(
            tick = tick,
            length = n.length.coerceAtMost(clipLen - tick).coerceAtLeast(1),
            rawTick = n.rawTick?.plus(shift),
        )
    }
    if (moved.isEmpty()) return this to emptySet()
    val first = notes.size
    return copy(notes = notes + moved) to (first until first + moved.size).toSet()
}

/**
 * Copies the notes at [indices] to just after the selection: the span from its
 * first start to its last end, rounded up to the grid.
 */
fun Clip.withDuplicated(indices: Set<Int>, clipLen: Int): Pair<Clip, Set<Int>> {
    val picked = indices.sorted().mapNotNull { notes.getOrNull(it) }
    if (picked.isEmpty()) return this to emptySet()
    val start = picked.minOf { it.tick }
    val end = picked.maxOf { it.tick + it.length.coerceAtLeast(1) }
    val grid = grid.coerceAtLeast(1)
    val span = (end - start + grid - 1) / grid * grid
    return withPasted(picked, start, start + span, clipLen)
}
