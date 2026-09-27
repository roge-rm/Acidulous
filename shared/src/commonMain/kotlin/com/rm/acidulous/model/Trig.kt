package com.rm.acidulous.model

import kotlinx.serialization.Serializable

/**
 * What decides whether a note plays this time round.
 *
 * Saved by name, not ordinal, so new values can be added in the middle
 * without changing what old songs mean.
 *
 * The Nth-of-M values are written out rather than generated so the song file
 * is readable: `N3of4` says what it is, a number wouldn't.
 */
@Serializable
enum class Trig {
    Always,

    /** Only if the previous conditional trig in this pass played, or didn't. */
    Prev,
    NotPrev,

    /** Only while the fill control is held, or only while it isn't. */
    Fill,
    NotFill,

    // The Nth of every M passes, M = 2..8. Thirty-five of them, in the order
    // the engine packs them: all of M = 2, then all of M = 3, and so on.
    N1of2, N2of2,
    N1of3, N2of3, N3of3,
    N1of4, N2of4, N3of4, N4of4,
    N1of5, N2of5, N3of5, N4of5, N5of5,
    N1of6, N2of6, N3of6, N4of6, N5of6, N6of6,
    N1of7, N2of7, N3of7, N4of7, N5of7, N6of7, N7of7,
    N1of8, N2of8, N3of8, N4of8, N5of8, N6of8, N7of8, N8of8;

    /** `1:2`, `3:4`, `pre`, `!pr`, `fil`, `!fi`: what the lane draws. */
    val short: String
        get() = when (this) {
            Always -> ""
            Prev -> "pre"
            NotPrev -> "!pr"
            Fill -> "fil"
            NotFill -> "!fi"
            else -> "$n:$m"
        }

    /** N and M for the Nth-of-M family; 0 and 0 for the rest. */
    val n: Int get() = if (ordinal < NTH_BASE) 0 else nthOf(ordinal - NTH_BASE).first
    val m: Int get() = if (ordinal < NTH_BASE) 0 else nthOf(ordinal - NTH_BASE).second

    companion object {
        /** Where the Nth family starts. Must match `TrigCond::NthBase` in Clip.h. */
        const val NTH_BASE = 5

        /**
         * The code the engine packs, which is the ordinal.
         *
         * Kept the same on purpose, so there's no translation table to get
         * wrong.
         */
        fun codeOf(t: Trig): Int = t.ordinal

        fun ofCode(code: Int): Trig = entries.getOrElse(code) { Always }

        /** Undoes the triangular packing: M = 2 takes two codes, M = 3 three. */
        private fun nthOf(offset: Int): Pair<Int, Int> {
            var off = offset
            var m = 2
            while (off >= m) { off -= m; ++m }
            return (off + 1) to m
        }

        /** The Nth of every M, for UI that offers the family. */
        fun nth(n: Int, m: Int): Trig = ofCode(NTH_BASE + (m - 1) * m / 2 - 1 + (n - 1))

        /** Everything the lane offers, in the order it steps through them. */
        val inOrder: List<Trig> = entries.toList()
    }
}

/**
 * Whether this note plays on this pass. Same rule as the engine.
 *
 * It also exists in `ClipPlayer::gate`, because MIDI export walks the passes
 * in Kotlin and has to match what the app plays. `trig_test` prints its
 * decision table and a unit test checks this one against it.
 *
 * [prevPlayed] is what the previous conditional note in this pass decided.
 * [fill] is false everywhere except live playing, which keeps exports
 * repeatable.
 */
fun trigPlays(note: Note, pass: Int, seed: Int, prevPlayed: Boolean, fill: Boolean = false): Boolean {
    when (note.trig) {
        Trig.Prev -> if (!prevPlayed) return false
        Trig.NotPrev -> if (prevPlayed) return false
        Trig.Fill -> if (!fill) return false
        Trig.NotFill -> if (fill) return false
        Trig.Always -> Unit
        else -> if (pass % note.trig.m != note.trig.n - 1) return false
    }
    if (note.chance >= 100) return true
    if (note.chance <= 0) return false
    // `% 100u` on the unsigned value, not a masked signed one. Dropping the
    // top bit changes the answer for half of all hashes, and then the two
    // sides would disagree.
    return (trigRoll(note, pass, seed) % 100u).toInt() < note.chance
}

/** Whether this note is part of the Prev chain, i.e. it made a decision. */
fun Note.conditional(): Boolean = trig != Trig.Always || chance < 100

/**
 * The roll, bit for bit the same as `ClipPlayer::roll`.
 *
 * A note is identified by (tick, pitch) rather than its place in the list,
 * so inserting a note at the start doesn't reroll everything after it.
 *
 * The tick includes the nudge, because that's the tick the engine gets.
 * Nudging is applied when the clip is sent, so it costs the engine nothing.
 * The downside is that nudging a note rerolls it, but both sides agree.
 */
fun trigRoll(note: Note, pass: Int, seed: Int): UInt {
    var h = seed.toUInt()
    h = h * 2654435761u + pass.toUInt() * 40503u + 1u
    h += (note.tick + note.nudge).toUInt() * 2246822519u + note.pitch.toUInt() * 668265263u
    h = h xor (h shr 13)
    h *= 0x5bd1e995u
    h = h xor (h shr 15)
    return h
}
