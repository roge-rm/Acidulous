package com.rm.acidulous.model

import kotlinx.serialization.Serializable

/**
 * What decides whether a note sounds this time round.
 *
 * Serialised **by name**, not by ordinal, so the vocabulary can grow in the
 * middle without silently re-pointing every song already saved - which is the
 * one thing an enum in a document must never do.
 *
 * The *Nth of every M* family is written out rather than generated because it
 * is what the file contains: `N3of4` in a song file says what it is to anybody
 * reading it, and a number would not.
 */
@Serializable
enum class Trig {
    Always,

    /** Only if the previous conditional trig in this pass played, or did not. */
    Prev,
    NotPrev,

    /** Only while the fill control is held, or only while it is not. */
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

    /** `1:2`, `3:4`, `pre`, `!pr`, `fil`, `!fi` - what the lane draws. */
    val short: String
        get() = when (this) {
            Always -> ""
            Prev -> "pre"
            NotPrev -> "!pr"
            Fill -> "fil"
            NotFill -> "!fi"
            else -> "$n:$m"
        }

    /** Which of the M, and the M, for the Nth family; 0 and 0 for the rest. */
    val n: Int get() = if (ordinal < NTH_BASE) 0 else nthOf(ordinal - NTH_BASE).first
    val m: Int get() = if (ordinal < NTH_BASE) 0 else nthOf(ordinal - NTH_BASE).second

    companion object {
        /** Where the Nth family starts. Must match `TrigCond::NthBase` in Clip.h. */
        const val NTH_BASE = 5

        /**
         * The code the engine packs, which is the ordinal.
         *
         * They are the same number on purpose: the two sides describe one
         * vocabulary, and a translation table between them would be a second
         * place for it to be wrong.
         */
        fun codeOf(t: Trig): Int = t.ordinal

        fun ofCode(code: Int): Trig = entries.getOrElse(code) { Always }

        /** Undo the triangular packing: M = 2 takes two codes, M = 3 three. */
        private fun nthOf(offset: Int): Pair<Int, Int> {
            var off = offset
            var m = 2
            while (off >= m) { off -= m; ++m }
            return (off + 1) to m
        }

        /** The Nth of every M, for a UI that wants to offer the family. */
        fun nth(n: Int, m: Int): Trig = ofCode(NTH_BASE + (m - 1) * m / 2 - 1 + (n - 1))

        /** Everything the lane offers, in the order it steps through them. */
        val inOrder: List<Trig> = entries.toList()
    }
}

/**
 * Does this note sound on this pass? The same rule the engine runs.
 *
 * It exists twice - here and in `ClipPlayer::gate` - because a MIDI export has
 * to produce the file the app plays, and an export is Kotlin walking the
 * passes itself. Two copies of a rule drift, so `trig_test` prints its
 * decision table and a unit test asserts this one matches it: a drift fails a
 * test rather than a listen.
 *
 * [prevPlayed] is what the previous *conditional* note in this pass decided;
 * [fill] is false everywhere but a live performance, which is what keeps an
 * export repeatable.
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
    // `% 100u` on the unsigned value, not a masked signed one: dropping the
    // top bit changes the answer for half of all hashes, and the two sides
    // then disagree on exactly the rolls nobody would think to check.
    return (trigRoll(note, pass, seed) % 100u).toInt() < note.chance
}

/** Is this note part of the Prev chain - that is, did it make a decision? */
fun Note.conditional(): Boolean = trig != Trig.Always || chance < 100

/**
 * The roll, bit for bit as `ClipPlayer::roll` computes it.
 *
 * Identity is (tick, pitch) rather than the note's place in the list, so
 * inserting a note at the top of a clip does not reroll everything after it.
 *
 * The tick is the **nudged** one, because that is the tick the engine has:
 * micro-timing is applied when the clip is marshalled, which is what lets it
 * cost the engine no field and no gate. The price is that moving a note off
 * the grid rerolls it, and the two sides agreeing about that is worth more
 * than either of them being clever about it separately.
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
