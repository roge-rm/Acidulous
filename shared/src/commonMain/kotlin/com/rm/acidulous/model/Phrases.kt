package com.rm.acidulous.model

import kotlin.math.log10

/**
 * Phrases: a stacked lane cut at its silences, one set of cuts for the whole
 * stack, so tapping a phrase means the same stretch of time in every take.
 *
 * The finder works on a coarse peak level per hop, not the audio itself. Each
 * take gets its own quiet threshold from its own floor and loudness, so a
 * noisy take and a clean one vote alike. A hop is quiet for the lane when most
 * of the takes that have audio there are quiet in it.
 */

/** Region frames per step of the phrase finder: 10 ms. */
const val PHRASE_HOP = ENGINE_RATE / 100

/** A quiet stretch at least this long cuts the lane, in hops (180 ms). */
const val PHRASE_MIN_GAP = 18

/** A phrase shorter than this joins its nearer neighbour, in hops (350 ms). Breaths and clicks mostly. */
const val PHRASE_MIN_LENGTH = 35

/**
 * A file's peak shape at a fine resolution: [peaks] holds a (min, max) pair
 * per column, columns splitting [frames] evenly as the engine's shape does.
 */
class Envelope(val frames: Int, val peaks: FloatArray) {
    val columns: Int get() = peaks.size / 2

    /** The largest absolute peak over file frames [from, to). */
    fun level(from: Long, to: Long): Float {
        val n = columns
        if (n <= 0 || frames <= 0) return 0f
        val a = (n * from.coerceIn(0, frames.toLong()) / frames).toInt().coerceIn(0, n - 1)
        val b = (n * to.coerceIn(0, frames.toLong()) / frames).toInt().coerceIn(a + 1, n)
        var m = 0f
        for (c in a until b) {
            val lo = -peaks[c * 2]
            val hi = peaks[c * 2 + 1]
            if (lo > m) m = lo
            if (hi > m) m = hi
        }
        return m
    }
}

/**
 * [samples] measured the way the engine's file survey does, a (min, max) pair
 * per column. For tests and for audio already in hand.
 */
fun envelopeOf(samples: FloatArray, columns: Int): Envelope {
    val n = samples.size
    val out = FloatArray(columns * 2)
    for (c in 0 until columns) {
        val from = (n.toLong() * c / columns).toInt()
        var to = (n.toLong() * (c + 1) / columns).toInt()
        if (to <= from) to = from + 1
        if (to > n) to = n
        var lo = 0f
        var hi = 0f
        for (i in from until to) {
            lo = minOf(lo, samples[i])
            hi = maxOf(hi, samples[i])
        }
        out[c * 2] = lo
        out[c * 2 + 1] = hi
    }
    return Envelope(n, out)
}

/**
 * Each take's peak level per hop across the lane's region, NaN where the take
 * has no audio. A take whose file's [envelopeOf] is null is NaN throughout and
 * takes no part.
 */
fun TakeRef.phraseLevels(hop: Int = PHRASE_HOP, envelopeOf: (String) -> Envelope?): List<FloatArray> {
    val hops = ((frames + hop - 1) / hop).coerceAtLeast(0)
    return stack().map { t ->
        val env = envelopeOf(t.file)
        FloatArray(hops) { h ->
            val a = h * hop
            if (env == null || a < t.start || a >= t.end) Float.NaN
            else env.level(t.offset.toLong() + a, t.offset.toLong() + minOf(a + hop, t.end))
        }
    }
}

/**
 * Where the lane is cut into phrases: region frames, starting at 0 and ending
 * at [frames], so phrase i is [cuts[i], cuts[i + 1]). [levels] is one array per
 * take from [phraseLevels]. A lane with no clear silences is one phrase.
 */
fun findPhrases(levels: List<FloatArray>, frames: Int, hop: Int = PHRASE_HOP): List<Int> {
    val hops = levels.maxOfOrNull { it.size } ?: 0
    if (frames <= 0 || hops == 0) return listOf(0, frames.coerceAtLeast(0))
    // Each take's vote: true where it's quiet, null where it has no audio.
    val votes = levels.map { quietHops(it) }
    val quiet = BooleanArray(hops) { h ->
        var covering = 0
        var still = 0
        for (v in votes) {
            val q = v.getOrNull(h) ?: continue
            covering++
            if (q) still++
        }
        // A tie counts as sound, so a word one take sings isn't cut.
        covering == 0 || still * 2 > covering
    }
    // Quiet runs long enough to cut at, as [start, end) in hops. Runs that
    // touch either end of the lane are lead-in and tail, not gaps.
    val gaps = ArrayList<IntArray>()
    var h = 0
    while (h < hops) {
        if (!quiet[h]) { h++; continue }
        val s = h
        while (h < hops && quiet[h]) h++
        if (s > 0 && h < hops && h - s >= PHRASE_MIN_GAP) gaps += intArrayOf(s, h)
    }
    // A stretch of sound too short to be a phrase (a breath, a click) joins
    // the neighbour across the shorter gap, so the cut stays in the longer one.
    while (true) {
        var worst = -1
        var worstLen = Int.MAX_VALUE
        for (i in 0..gaps.size) {
            val from = if (i == 0) 0 else gaps[i - 1][1]
            val to = if (i == gaps.size) hops else gaps[i][0]
            // The first and last phrase carry the lead-in and the tail, so
            // only what's sounding counts towards their length.
            val len = soundingSpan(quiet, from, to)
            if (len < PHRASE_MIN_LENGTH && len < worstLen && gaps.isNotEmpty()) { worst = i; worstLen = len }
        }
        if (worst < 0) break
        val before = if (worst > 0) gaps[worst - 1] else null
        val after = if (worst < gaps.size) gaps[worst] else null
        val drop = when {
            before == null -> worst
            after == null -> worst - 1
            (before[1] - before[0]) <= (after[1] - after[0]) -> worst - 1
            else -> worst
        }
        gaps.removeAt(drop)
    }
    val cuts = ArrayList<Int>(gaps.size + 2)
    cuts += 0
    for (g in gaps) cuts += (((g[0] + g[1]) / 2) * hop).coerceIn(1, frames - 1)
    cuts += frames
    return cuts.distinct()
}

/** From the first sounding hop to the last in [from, to), or 0 if none sound. */
private fun soundingSpan(quiet: BooleanArray, from: Int, to: Int): Int {
    var a = from
    while (a < to && quiet[a]) a++
    var b = to
    while (b > a && quiet[b - 1]) b--
    return b - a
}

/**
 * One take's quiet hops: null where it has no audio. The threshold sits half
 * way, in dB, between the take's floor (its quietest twentieth) and its loud
 * end (its loudest twentieth), and at least 15 dB under the loud end, for a
 * take that's so busy its floor is still singing. A take with less than 10 dB
 * between them has no silences to find: quiet throughout if it's all low,
 * sound throughout if not.
 */
private fun quietHops(level: FloatArray): Array<Boolean?> {
    val db = FloatArray(level.size) { if (level[it].isNaN()) Float.NaN else 20f * log10(maxOf(level[it], 1e-6f)) }
    val present = db.filter { !it.isNaN() }.sorted()
    if (present.isEmpty()) return arrayOfNulls(level.size)
    val floor = present[(present.size * 0.05).toInt().coerceIn(0, present.size - 1)]
    val loud = present[(present.size * 0.95).toInt().coerceIn(0, present.size - 1)]
    val range = loud - floor
    val threshold = minOf(floor + range * 0.5f, loud - 15f)
    return Array(level.size) { i ->
        val d = db[i]
        when {
            d.isNaN() -> null
            range < 10f -> loud < -40f
            else -> d < threshold
        }
    }
}

/** Which phrase [frame] falls in, given [cuts] from [findPhrases]. */
fun phraseAt(cuts: List<Int>, frame: Int): Int {
    if (cuts.size < 2) return -1
    for (i in 0 until cuts.size - 1) if (frame < cuts[i + 1]) return i
    return cuts.size - 2
}

// --- Comping edits ------------------------------------------------------------

/**
 * Take [k] heard over [start, end), as far as it has audio there. Pieces of
 * the same take that meet become one. Unchanged if [k] has no audio there.
 */
fun TakeRef.choosing(k: Int, start: Int, end: Int): TakeRef {
    if (takes.isEmpty()) return this
    val t = stack().getOrNull(k) ?: return this
    val s = maxOf(start, t.start, 0)
    val e = minOf(end, t.end, frames)
    if (e <= s) return this
    val base = pieces.ifEmpty { listOf(Piece(0, frames, 0)) }
    val next = base.overlay(s, e, k).joined()
    return if (next == pieces) this else copy(takes = stack(), pieces = next)
}

/** Neighbouring pieces of one take at the same shift merged, empty ones dropped. */
fun List<Piece>.joined(): List<Piece> {
    val out = ArrayList<Piece>()
    for (p in sortedBy { it.start }) {
        if (p.end <= p.start) continue
        val last = out.lastOrNull()
        if (last != null && last.take == p.take && last.shift == p.shift && last.end == p.start) out[out.size - 1] = last.copy(end = p.end)
        else out += p
    }
    return out
}

/**
 * The lane with take [k] taken off its stack. Where it was heard, the take
 * that has the most audio over each of its pieces plays instead (ties go to
 * the take heard most elsewhere), and silence where none has any. Takes above
 * it move down one. The recording stays in the sound library, as every
 * recording does.
 *
 * Null when [k] is the lane's only take: that's emptying the lane.
 */
fun TakeRef.withoutTake(k: Int): TakeRef? {
    val stack = stack()
    if (stack.size <= 1 || k !in stack.indices) return null
    val left = stack.filterIndexed { i, _ -> i != k }
    fun renumber(i: Int) = if (i > k) i - 1 else i
    val heard = pieces.ifEmpty { listOf(Piece(0, frames, 0)) }
    val kept = heard.filter { it.take != k }.map { it.copy(take = renumber(it.take)) }
    val heardFor = IntArray(left.size)
    for (p in kept) heardFor[p.take] += p.end - p.start
    val filled = ArrayList<Piece>(kept)
    for (p in heard.filter { it.take == k }) {
        val best = left.indices.maxWithOrNull(
            compareBy<Int>({ overlap(left[it], p) }, { heardFor[it] }, { it }),
        ) ?: continue
        if (overlap(left[best], p) <= 0) continue
        filled += Piece(maxOf(p.start, left[best].start), minOf(p.end, left[best].end), best)
    }
    var pieces = filled.joined()
    // An empty list would play take 0 whole, so a lane left silent keeps one
    // empty piece, as a trim does.
    if (pieces.isEmpty()) pieces = listOf(Piece(0, 0, 0))
    val first = left[0]
    // One take covering the whole lane is a plain lane again.
    if (left.size == 1 && pieces == listOf(Piece(0, frames, 0)) && first.start <= 0 && first.end >= frames) {
        return copy(file = first.file, offset = first.offset, takes = emptyList(), pieces = emptyList())
    }
    return copy(file = first.file, offset = first.offset, takes = left, pieces = pieces)
}

private fun overlap(t: StackTake, p: Piece): Int = minOf(t.end, p.end) - maxOf(t.start, p.start)
