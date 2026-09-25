package com.rm.acidulous.model

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The patch laid out afresh to fill a window [aspect] times as wide as it is
 * tall, for the patch editor's fit button.
 *
 * A module's inputs are on its left and its outputs on its right, so the
 * sound has to read left to right or every cable doubles back on itself.
 * The modules go into columns by how far along the signal they are -
 * sources first, the output last - and a column taller than the window
 * allows spills into the next. Those columns are then wrapped into bands,
 * like lines of text, as many to a band as the window's shape wants: a
 * turned phone gets one long band, an upright one several short ones. Of
 * every way of doing that, the one that lets the patch be drawn biggest
 * wins, and a band fewer is worth some size, because a cable that
 * wraps to the next band is the hardest kind to follow.
 *
 * [nodeW] and [nodeH] are a module's size in the patch's own units.
 */
fun NexusPatch.arranged(aspect: Float, nodeW: Float, nodeH: Float): NexusPatch {
    if (modules.isEmpty()) return this
    val columns = flowColumns()
    val n = modules.size
    val cellW = nodeW + GAP_X
    val cellH = nodeH + GAP_Y
    val want = aspect.coerceIn(0.2f, 5f)

    var best: Layout? = null
    for (rows in 1..n) {
        // A column of the flow taller than [rows] carries on in the next.
        val chunks = columns.flatMap { it.chunked(rows) }
        for (perBand in 1..chunks.size) {
            val bands = ceil(chunks.size / perBand.toFloat()).toInt()
            val w = perBand * cellW - GAP_X
            val h = bands * rows * cellH - GAP_Y + (bands - 1) * BAND_GAP
            // How big the patch can be drawn in a window of this shape.
            val size = min(want / w, 1f / h)
            val score = size * BAND_KEEP.pow(bands - 1)
            if (best == null || score > best.score) best = Layout(score, rows, perBand, chunks)
        }
    }
    val layout = best ?: return this

    val at = HashMap<Int, Pair<Float, Float>>()
    layout.chunks.forEachIndexed { i, chunk ->
        val band = i / layout.perBand
        val col = i % layout.perBand
        // A short column sits in the middle of its band, not at the top.
        val drop = (layout.rows - chunk.size) * cellH / 2f
        val top = band * (layout.rows * cellH + BAND_GAP) + drop
        chunk.forEachIndexed { row, slot -> at[slot] = col * cellW to top + row * cellH }
    }
    return copy(modules = modules.map { m ->
        val (x, y) = at[m.slot] ?: return@map m
        m.copy(x = snap(x), y = snap(y))
    })
}

/** Room between columns for the cables to bend, and between rows. */
private const val GAP_X = 70f
private const val GAP_Y = 30f
/** A band's extra distance from the next, so the two read as two. */
private const val BAND_GAP = 40f
/**
 * What each band after the first keeps of the drawn size it is weighed at:
 * a wrapped chain is only worth it for a patch drawn noticeably bigger.
 */
private const val BAND_KEEP = 0.7f

private class Layout(val score: Float, val rows: Int, val perBand: Int, val chunks: List<List<Int>>)

private fun snap(v: Float) = (v / 10f).roundToInt() * 10f

/**
 * The patch's slots in columns by how far down the signal each is: a
 * module's column is one past the furthest of whatever feeds it, audio or
 * modulation. A cable that closes a loop - feedback - is left out of that
 * count, or the loop would push itself along forever. Anything feeding
 * nothing is the end of a chain and goes in the last column, so the output
 * is always on the right; a module with no cables at all starts in the first.
 */
internal fun NexusPatch.flowColumns(): List<List<Int>> {
    val slots = modules.map { it.slot }.toSet()
    val edges = buildSet {
        for (c in cables) {
            add(c.fromSlot to c.toSlot)
            if (c.modSlot >= 0) add(c.modSlot to c.toSlot)
        }
    }.filter { (a, b) -> a != b && a in slots && b in slots }
    val out = edges.groupBy({ it.first }, { it.second })
    val fed = edges.map { it.second }.toSet()

    // Walked from the sources, an edge back to a module still being walked
    // is the one that closes a loop.
    val state = HashMap<Int, Boolean>() // false while being walked, true after
    val forward = ArrayList<Pair<Int, Int>>()
    fun walk(s: Int) {
        state[s] = false
        for (t in out[s].orEmpty().sorted()) {
            when (state[t]) {
                null -> { forward += s to t; walk(t) }
                true -> forward += s to t
                false -> {}
            }
        }
        state[s] = true
    }
    for (s in slots.sortedWith(compareBy({ it in fed }, { it }))) if (state[s] == null) walk(s)

    val depth = HashMap<Int, Int>().apply { slots.forEach { put(it, 0) } }
    repeat(slots.size) {
        for ((a, b) in forward) if (depth.getValue(b) < depth.getValue(a) + 1) depth[b] = depth.getValue(a) + 1
    }
    val feeds = forward.map { it.first }.toSet()
    val last = depth.values.max()
    for (s in slots) if (s in fed && s !in feeds) depth[s] = last

    // Down each column in the order of what feeds it, so the cables into a
    // column cross each other as little as they can.
    val placed = HashMap<Int, Float>()
    val into = forward.groupBy({ it.second }, { it.first })
    return (0..last).map { d ->
        val column = slots.filter { depth[it] == d }.sortedWith(compareBy({ s ->
            val from = into[s].orEmpty().mapNotNull { placed[it] }
            if (from.isEmpty()) Float.MAX_VALUE else from.average().toFloat()
        }, { it }))
        // Where it sits down its own column, from the top.
        column.forEachIndexed { i, s -> placed[s] = i.toFloat() }
        column
    }.filter { it.isNotEmpty() }
}
