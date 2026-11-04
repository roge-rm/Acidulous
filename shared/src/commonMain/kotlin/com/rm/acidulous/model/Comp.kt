package com.rm.acidulous.model

import com.rm.acidulous.io.*
import com.rm.acidulous.util.Math
import kotlin.math.pow
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
 * The notes pitch is judged against, for the engine: the root note's
 * pitch, the period and each step in cents from the root. Empty for equal
 * temperament, which the engine reads as semitones from A 440. The root
 * keeps its equal-tempered pitch, as `Tunings.ratios` plays it.
 */
fun pitchSpec(tuning: Tuning?, root: Int): String {
    if (tuning == null || tuning.isEqual || tuning.cents.isEmpty()) return ""
    val refHz = 440.0 * 2.0.pow((60 + Math.floorMod(root, 12) - 69) / 12.0)
    val steps = listOf(0f) + tuning.cents.dropLast(1)
    return "$refHz:${tuning.cents.last()}:${steps.joinToString(",")}"
}

/** The tuning a track's takes are judged in: its own, else the song's. */
fun Song.pitchSpecFor(track: Track?): String = pitchSpec(track?.tuning ?: tuning, key?.root ?: 0)

/** Frames from this lane's frame 0 to [other]'s at the same moment, both at this lane's tempo. */
fun TakeRef.deltaTo(other: TakeRef): Int {
    val perTick = ENGINE_RATE * 60.0 / (bpm.toDouble() * PPQN)
    return ((startTick - other.startTick) * perTick).roundToInt()
}

/**
 * What the engine is asked to score: a header with the grid, the phrase
 * cuts, the tuning (see [pitchSpec]) and how far the lead sits from this
 * lane, then the lane with every take and where each has audio, then the
 * lead as cell 2 if it has one. Empty if the lane's file is missing.
 *
 *     score|gridStep|gridPhase|cut,cut,...|tuning|leadDelta
 */
fun TakeRef.scoreSpec(cuts: List<Int>, root: File, tuning: String = "", lead: TakeRef? = null): String {
    val body = buildString { appendRegion(1L, 0, this@scoreSpec, root, spans = true) }
    if (body.isEmpty()) return ""
    val leadLines = lead?.let { buildString { appendRegion(2L, 0, it, root) } }.orEmpty()
    val (step, phase) = sixteenths()
    val delta = if (lead != null && leadLines.isNotEmpty()) deltaTo(lead) else 0
    return "score|$step|$phase|${cuts.joinedCuts()}|$tuning|$delta\n$body$leadLines"
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

/** This lane with every piece back at its take's own time, and no longer a double. */
fun TakeRef.unshifted(): TakeRef {
    if (!linedUp) return if (lead >= 0) copy(lead = -1) else this
    return withPieces(pieces.map { it.copy(shift = 0) }.joined()).copy(lead = -1)
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
 * What the engine is asked to line this lane up against [lead], word by
 * word: the limit, this lane's phrase cuts, how far the lead sits from it,
 * the lead as cell 1 and this lane as cell 2, at its takes' own time so
 * lining up again starts from scratch. Empty if a file is missing.
 *
 *     lineup|maxShift|cut,cut,...|delta
 */
fun TakeRef.lineUpSpec(lead: TakeRef, cuts: List<Int>, root: File): String {
    val leadLines = buildString { appendRegion(1L, 0, lead, root) }
    val own = buildString { appendRegion(2L, 0, unshifted(), root) }
    if (leadLines.isEmpty() || own.isEmpty()) return ""
    return "lineup|$LINE_UP_LIMIT|${cuts.joinedCuts()}|${deltaTo(lead)}\n$leadLines$own"
}

/** A shift from the engine in frames: whole, at most [LINE_UP_LIMIT] either way, 0 for NaN. */
private fun frameShift(raw: Float): Int = if (raw.isNaN()) 0 else raw.roundToInt().coerceIn(-LINE_UP_LIMIT, LINE_UP_LIMIT)

/**
 * This lane lined up word by word on lane [lead]: [words] is start, end and
 * shift per word in region frames, as the engine answers. Pieces are cut at
 * the words so each moves on its own, and keep their edges, so putting it
 * back is exact. A moved piece can read up to [LINE_UP_LIMIT] past its
 * take's own audio: the same recording carrying on, or silence where the
 * file ends.
 */
fun TakeRef.linedUpBy(words: FloatArray, lead: Int): TakeRef {
    val base = unshifted().pieces.ifEmpty { listOf(Piece(0, frames, 0)) }
    val out = ArrayList<Piece>()
    val count = words.size / 3
    for (p in base) {
        var at = p.start
        for (w in 0 until count) {
            val a = maxOf(p.start, words[w * 3].toInt())
            val b = minOf(p.end, words[w * 3 + 1].toInt())
            if (b <= a) continue
            if (a > at) out += Piece(at, a, p.take)
            out += Piece(a, b, p.take, frameShift(words[w * 3 + 2]))
            at = b
        }
        if (at < p.end) out += Piece(at, p.end, p.take)
    }
    return withPieces(out.joined()).copy(lead = lead)
}

/** The lane this one doubles, if it's set and still has audio. */
fun Clip.leadOf(lane: Int): TakeRef? {
    val l = audio?.lane(lane)?.lead ?: return null
    return if (l != lane && l in 0 until BIAS_LANES) audio?.lane(l) else null
}

/**
 * What a lined-up blend lines its takes up on: the lead if this lane
 * doubles one, else the lane as you hear it with blend off (your comp, or
 * take 1).
 */
fun Clip.blendReference(lane: Int): TakeRef? =
    leadOf(lane) ?: audio?.lane(lane)?.copy(blend = false, blendLinedUp = false, spread = 0f, peaks = emptyList())

/**
 * What the engine is asked to line every take up on [reference]: the limit,
 * the phrase cuts and how far the reference sits from this lane, the
 * reference as cell 1 and the whole stack as cell 2.
 *
 *     blendup|maxShift|cut,cut,...|delta
 */
fun TakeRef.blendLineUpSpec(reference: TakeRef, cuts: List<Int>, root: File): String {
    val ref = buildString { appendRegion(1L, 0, reference, root) }
    val bare = copy(blend = true, blendLinedUp = false, spread = 0f, takes = takes.map { it.copy(nudges = emptyList()) })
    val own = buildString { appendRegion(2L, 0, bare, root, spans = true) }
    if (ref.isEmpty() || own.isEmpty()) return ""
    return "blendup|$LINE_UP_LIMIT|${cuts.joinedCuts()}|${deltaTo(reference)}\n$ref$own"
}

/**
 * This blend with every take lined up as the engine answered: the take
 * count, then per take its word count and start, end and shift per word.
 * Words that don't move aren't kept. Unchanged if the answer is for
 * another stack.
 */
fun TakeRef.blendLinedUpBy(raw: FloatArray): TakeRef {
    if (raw.isEmpty() || raw[0].toInt() != takes.size) return this
    var i = 1
    val out = takes.map { t ->
        val n = raw.getOrNull(i)?.toInt() ?: 0
        i++
        val nudges = ArrayList<Nudge>()
        for (w in 0 until n) {
            if (i + 2 >= raw.size) break
            val shift = frameShift(raw[i + 2])
            if (shift != 0) nudges += Nudge(raw[i].toInt(), raw[i + 1].toInt(), shift)
            i += 3
        }
        t.copy(nudges = nudges)
    }
    return copy(takes = out, blendLinedUp = true)
}

/** This blend with its takes back at their own time. */
fun TakeRef.blendUnlined(): TakeRef =
    copy(takes = takes.map { it.copy(nudges = emptyList()) }, blendLinedUp = false)

/** This blend spread across the stereo field by [amount], 0 to 1. */
fun TakeRef.spreading(amount: Float): TakeRef = copy(spread = amount.coerceIn(0f, 1f))

/** The lanes in this cell [lane] could line up on: any other with audio. */
fun Clip.leadsFor(lane: Int): List<Int> =
    (0 until BIAS_LANES).filter { it != lane && audio?.lane(it) != null }

/** This lane blending every take, or back to its pieces. Only a stack can blend. */
fun TakeRef.blending(on: Boolean): TakeRef =
    if (on && takes.size < 2) this else copy(blend = on)
