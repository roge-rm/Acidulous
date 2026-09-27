package com.rm.acidulous.model

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lays the patch out again to fill a window [aspect] times as wide as it is
 * tall, for the patch editor's fit button.
 *
 * Inputs are on a module's left and outputs on its right, so the signal has
 * to flow left to right or cables double back. Modules go into columns by how
 * far along the signal they are, sources first and the output last, and a
 * column too tall for the window spills into the next. The columns are then
 * wrapped into bands like lines of text: a sideways phone gets one long band,
 * an upright one several short ones. The layout that draws the patch biggest
 * wins, with a bonus for fewer bands, since cables that wrap to the next band
 * are the hardest to follow.
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
        // A flow column taller than [rows] continues in the next one.
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
        // A short column is centred in its band.
        val drop = (layout.rows - chunk.size) * cellH / 2f
        val top = band * (layout.rows * cellH + BAND_GAP) + drop
        chunk.forEachIndexed { row, slot -> at[slot] = col * cellW to top + row * cellH }
    }
    return copy(modules = modules.map { m ->
        val (x, y) = at[m.slot] ?: return@map m
        m.copy(x = snap(x), y = snap(y))
    })
}

/** Space between columns for the cables to bend, and between rows. */
private const val GAP_X = 70f
private const val GAP_Y = 30f
/** Extra space between bands so they read as separate. */
private const val BAND_GAP = 40f
/**
 * How much of its drawn size a layout keeps for each band after the first,
 * so wrapping only wins if it draws the patch noticeably bigger.
 */
private const val BAND_KEEP = 0.7f

private class Layout(val score: Float, val rows: Int, val perBand: Int, val chunks: List<List<Int>>)

private fun snap(v: Float) = (v / 10f).roundToInt() * 10f

/**
 * The patch's slots in columns by how far along the signal each is. A
 * module's column is one past the furthest module feeding it, by audio or
 * modulation. Feedback cables are left out, or the loop would push itself
 * along forever. Modules that feed nothing end a chain and go in the last
 * column, so the output is always on the right. Modules with no cables
 * start in the first.
 */
fun NexusPatch.flowColumns(): List<List<Int>> {
    val slots = modules.map { it.slot }.toSet()
    val edges = buildSet {
        for (c in cables) {
            add(c.fromSlot to c.toSlot)
            if (c.modSlot >= 0) add(c.modSlot to c.toSlot)
        }
    }.filter { (a, b) -> a != b && a in slots && b in slots }
    val out = edges.groupBy({ it.first }, { it.second })
    val fed = edges.map { it.second }.toSet()

        // Walking from the sources, an edge back to a module that's still
        // being walked is the one that closes a loop.
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

    // Order each column by what feeds it, so the cables into it cross as
    // little as possible.
    val placed = HashMap<Int, Float>()
    val into = forward.groupBy({ it.second }, { it.first })
    return (0..last).map { d ->
        val column = slots.filter { depth[it] == d }.sortedWith(compareBy({ s ->
            val from = into[s].orEmpty().mapNotNull { placed[it] }
            if (from.isEmpty()) Float.MAX_VALUE else from.average().toFloat()
        }, { it }))
        // Its position down its own column, from the top.
        column.forEachIndexed { i, s -> placed[s] = i.toFloat() }
        column
    }.filter { it.isNotEmpty() }
}
