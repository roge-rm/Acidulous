package com.rm.acidulous.model

import com.rm.acidulous.io.*
import kotlin.math.roundToInt

/**
 * Suggest, line up and blend: comping a stack by what the engine hears in it.
 *
 * The engine does the listening (`engine/core/TakeScore.h`). This side
 * writes what it's asked and turns the answers into pieces, each change one
 * undo step through `compEdit`.
 */

/** How far a double may move to sit on its lead: 50 ms. */
const val LINE_UP_LIMIT = ENGINE_RATE / 20

/**
 * What the engine scored each take of a stack over each phrase: 0-100, NaN
 * where a take has no audio over most of the phrase. Also the parts the
 * score came from: pitch error in cents, timing error in ms, clipped peaks
 * and signal over the take's floor in dB, NaN where they couldn't be read.
 */
class TakeScores(val takes: Int, val phrases: Int, private val values: FloatArray) {
    private fun at(take: Int, phrase: Int, part: Int): Float =
        if (take in 0 until takes && phrase in 0 until phrases) values[(take * phrases + phrase) * PARTS + part] else Float.NaN

    fun score(take: Int, phrase: Int): Float = at(take, phrase, 0)
    fun pitchCents(take: Int, phrase: Int): Float = at(take, phrase, 1)
    fun timingMs(take: Int, phrase: Int): Float = at(take, phrase, 2)
    fun clips(take: Int, phrase: Int): Float = at(take, phrase, 3)
    fun snrDb(take: Int, phrase: Int): Float = at(take, phrase, 4)

    /**
     * The best take for [phrase], or -1 if none has audio there. A take
     * within half a point of the best is as good, and [heard] wins those, so
     * suggesting again changes nothing.
     */
    fun best(phrase: Int, heard: Int): Int {
        var best = -1
        var top = Float.NEGATIVE_INFINITY
        for (k in 0 until takes) {
            val s = score(k, phrase)
            if (!s.isNaN() && s > top) { top = s; best = k }
        }
        if (best >= 0 && heard != best && score(heard, phrase).let { !it.isNaN() && it >= top - 0.5f }) return heard
        return best
    }

    companion object {
        const val PARTS = 5

        /** From the engine's flat answer: takes, phrases, then five numbers each. Null if it's short. */
        fun of(raw: FloatArray?): TakeScores? {
            if (raw == null || raw.size < 2) return null
            val takes = raw[0].toInt()
            val phrases = raw[1].toInt()
            if (takes <= 0 || phrases <= 0 || raw.size < 2 + takes * phrases * PARTS) return null
            return TakeScores(takes, phrases, raw.copyOfRange(2, 2 + takes * phrases * PARTS))
        }
    }
}

/**
 * The song's sixteenths in this lane's region frames: the step and where
 * one falls, for the timing score.
 */
fun TakeRef.sixteenths(): Pair<Double, Double> {
    val perTick = ENGINE_RATE * 60.0 / (bpm.toDouble() * PPQN)
    val step = PPQN / 4
    return step * perTick to -((startTick % step) * perTick)
}

private fun List<Int>.joinedCuts() = joinToString(",")

/**
 * What the engine is asked to score: a header with the grid and the phrase
 * cuts, then the lane with every take and where each has audio. Empty if
 * the lane's file is missing.
 *
 *     score|gridStep|gridPhase|cut,cut,...
 */
fun TakeRef.scoreSpec(cuts: List<Int>, root: File): String {
    val body = buildString { appendRegion(1L, 0, this@scoreSpec, root, spans = true) }
    if (body.isEmpty()) return ""
    val (step, phase) = sixteenths()
    return "score|$step|$phase|${cuts.joinedCuts()}\n$body"
}

/**
 * The best take in every phrase, chosen as one edit. A phrase where no take
 * has audio keeps what it had. Unchanged if [scores] were made for another
 * stack.
 */
fun TakeRef.suggested(cuts: List<Int>, scores: TakeScores): TakeRef {
    if (takes.isEmpty() || scores.takes != stack().size || scores.phrases != cuts.size - 1) return this
    var out = this
    for (ph in 0 until cuts.size - 1) {
        val a = cuts[ph]
        val b = cuts[ph + 1]
        val k = scores.best(ph, out.heardIn(a, b))
        if (k >= 0) out = out.choosing(k, a, b)
    }
    return out
}

/** Whether any piece is moved off its take's own time. */
val TakeRef.linedUp: Boolean get() = pieces.any { it.shift != 0 }

/** This lane with every piece back at its take's own time. */
fun TakeRef.unshifted(): TakeRef {
    if (!linedUp) return this
    return withPieces(pieces.map { it.copy(shift = 0) }.joined())
}

/**
 * These pieces, settled as the model keeps them: one take heard whole
 * over a lane with one take is a plain lane again.
 */
private fun TakeRef.withPieces(next: List<Piece>): TakeRef {
    val stack = stack()
    val whole = stack.size == 1 && next == listOf(Piece(0, frames, 0)) && stack[0].start <= 0 && stack[0].end >= frames
    return if (whole) copy(takes = emptyList(), pieces = emptyList()) else copy(takes = stack, pieces = next)
}

/**
 * What the engine is asked to line this lane up against [lead]: the limit,
 * this lane's phrase cuts, the lead as cell 1 and this lane as cell 2, at
 * its takes' own time so lining up again starts from scratch. Empty if a
 * file is missing.
 *
 *     lineup|maxShift|cut,cut,...
 */
fun TakeRef.lineUpSpec(lead: TakeRef, cuts: List<Int>, root: File): String {
    val leadLines = buildString { appendRegion(1L, 0, lead, root) }
    val own = buildString { appendRegion(2L, 0, unshifted(), root) }
    if (leadLines.isEmpty() || own.isEmpty()) return ""
    return "lineup|$LINE_UP_LIMIT|${cuts.joinedCuts()}\n$leadLines$own"
}

/**
 * This lane with each phrase moved by [shifts] (frames, one per phrase, NaN
 * to leave it). Pieces are cut at the phrase lines so each phrase moves on
 * its own, and keep their edges, so putting it back is exact. A moved piece
 * can read up to [LINE_UP_LIMIT] past its take's own audio: the same
 * recording carrying on, or silence where the file ends.
 */
fun TakeRef.linedUpBy(cuts: List<Int>, shifts: FloatArray): TakeRef {
    val base = unshifted().pieces.ifEmpty { listOf(Piece(0, frames, 0)) }
    val out = ArrayList<Piece>()
    for (p in base) {
        for (ph in 0 until cuts.size - 1) {
            val a = maxOf(p.start, cuts[ph])
            val b = minOf(p.end, cuts[ph + 1])
            if (b <= a) continue
            val raw = shifts.getOrNull(ph) ?: Float.NaN
            val shift = if (raw.isNaN()) 0 else raw.roundToInt().coerceIn(-LINE_UP_LIMIT, LINE_UP_LIMIT)
            out += Piece(a, b, p.take, shift)
        }
    }
    return withPieces(out.joined())
}

/** The lanes in this cell [lane] could line up on: any other with audio. */
fun Clip.leadsFor(lane: Int): List<Int> =
    (0 until BIAS_LANES).filter { it != lane && audio?.lane(it) != null }

/** This lane blending every take, or back to its pieces. Only a stack can blend. */
fun TakeRef.blending(on: Boolean): TakeRef =
    if (on && takes.size < 2) this else copy(blend = on)
