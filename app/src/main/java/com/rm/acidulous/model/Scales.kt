package com.rm.acidulous.model

/**
 * The scale table, mirroring engine/eventor/Scales.h - same names, same
 * order, same intervals. The engine owns the sound; this copy exists so the
 * keyboard can show which notes a Scale eventor will let through.
 */
object Scales {
    val names: List<String> = listOf(
        "Ionian (Major)", "Dorian", "Phrygian", "Lydian", "Mixolydian", "Aeolian (Minor)", "Locrian",
        "Harmonic Minor", "Melodic Minor",
        "Major Pentatonic", "Minor Pentatonic", "Major Blues", "Minor Blues", "Egyptian",
        "Hungarian Minor", "Byzantine", "Persian", "Hirajoshi", "In Sen", "Iwato", "Enigmatic",
        "Phrygian Dominant", "Neapolitan Minor", "Neapolitan Major",
        "Altered", "Lydian Dominant", "Lydian Augmented", "Locrian nat2", "Bebop Dominant", "Bebop Major",
        "Whole Tone", "Dim Whole-Half", "Dim Half-Whole",
    )

    val intervals: List<List<Int>> = listOf(
        listOf(0, 2, 4, 5, 7, 9, 11), listOf(0, 2, 3, 5, 7, 9, 10), listOf(0, 1, 3, 5, 7, 8, 10),
        listOf(0, 2, 4, 6, 7, 9, 11), listOf(0, 2, 4, 5, 7, 9, 10), listOf(0, 2, 3, 5, 7, 8, 10),
        listOf(0, 1, 3, 5, 6, 8, 10),
        listOf(0, 2, 3, 5, 7, 8, 11), listOf(0, 2, 3, 5, 7, 9, 11),
        listOf(0, 2, 4, 7, 9), listOf(0, 3, 5, 7, 10), listOf(0, 2, 3, 4, 7, 9), listOf(0, 3, 5, 6, 7, 10),
        listOf(0, 2, 5, 7, 10),
        listOf(0, 2, 3, 6, 7, 8, 11), listOf(0, 1, 4, 5, 7, 8, 11), listOf(0, 1, 4, 5, 6, 8, 11),
        listOf(0, 2, 3, 7, 8), listOf(0, 1, 5, 7, 10), listOf(0, 1, 5, 6, 10), listOf(0, 1, 4, 6, 8, 10, 11),
        listOf(0, 1, 4, 5, 7, 8, 10), listOf(0, 1, 3, 5, 7, 8, 11), listOf(0, 1, 3, 5, 7, 9, 11),
        listOf(0, 1, 3, 4, 6, 8, 10), listOf(0, 2, 4, 6, 7, 9, 10), listOf(0, 2, 4, 6, 8, 9, 11),
        listOf(0, 2, 3, 5, 6, 8, 10), listOf(0, 2, 4, 5, 7, 9, 10, 11), listOf(0, 2, 4, 5, 7, 8, 9, 11),
        listOf(0, 2, 4, 6, 8, 10), listOf(0, 2, 3, 5, 6, 8, 9, 11), listOf(0, 1, 3, 4, 6, 7, 9, 10),
    )

    val keyNames: List<String> = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    // --- How a scale is written -------------------------------------------------
    //
    // C Dorian is C D E♭ F G A B♭, not C D D♯ F G A A♯. Sharps and flats are
    // not interchangeable: a scale is written so that each letter is used
    // once, and which accidental you get follows from that rather than from
    // a preference. Writing D♯ where the scale means E♭ is the same kind of
    // wrong as spelling a word phonetically - readable, and not how it goes.
    //
    // So: walk the letters C D E F G A B from the root's own letter, one per
    // degree, and give each whatever accidental takes it to the pitch the
    // scale asks for. The root's letter is not given either - B♭ and A♯ are
    // the same key - so every candidate letter is tried and the one needing
    // the fewest accidentals wins. That is what makes the same twelve keys
    // come out as D♭ major (five flats) rather than C♯ major (seven sharps).
    //
    // It only works for scales of seven notes, which is most of them and all
    // the ones with a conventional spelling. A pentatonic or an eight-note
    // bebop scale has no letter-per-degree to follow, so those fall back to
    // one accidental per note, in whichever direction the key itself leans.

    private val LETTERS = listOf("C", "D", "E", "F", "G", "A", "B")
    private val LETTER_PC = listOf(0, 2, 4, 5, 7, 9, 11)
    private val SHARP_NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val FLAT_NAMES = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")

    /** The nearest way from a natural to a pitch: -2..+2 semitones. */
    private fun offset(semitones: Int): Int {
        var d = ((semitones % 12) + 12) % 12
        if (d > 6) d -= 12
        return d
    }

    private fun accidental(n: Int): String = when {
        n > 0 -> "♯".repeat(n)
        n < 0 -> "♭".repeat(-n)
        else -> ""
    }

    /** Letter-per-degree, or null when the intervals will not take it. */
    private fun byLetters(key: Int, iv: List<Int>): Map<Int, String>? {
        if (iv.size != 7) return null
        var best: Map<Int, String>? = null
        var bestCost = Int.MAX_VALUE
        for (letter in LETTERS.indices) {
            if (kotlin.math.abs(offset(key - LETTER_PC[letter])) > 2) continue
            val names = LinkedHashMap<Int, String>()
            var cost = 0
            var usable = true
            for ((degree, interval) in iv.withIndex()) {
                val li = (letter + degree) % 7
                val pc = (key + interval) % 12
                val acc = offset(pc - LETTER_PC[li])
                if (kotlin.math.abs(acc) > 2) { usable = false; break }
                // A double sharp is legal and almost always means the other
                // root was the right one, so it costs far more than one.
                cost += if (kotlin.math.abs(acc) > 1) 10 else kotlin.math.abs(acc)
                names[pc] = LETTERS[li] + accidental(acc)
            }
            if (usable && names.size == 7 && cost < bestCost) {
                bestCost = cost
                best = names
            }
        }
        return best
    }

    /** Does this key read more naturally in flats? Asked of its major scale. */
    private fun prefersFlats(key: Int): Boolean =
        byLetters(key, listOf(0, 2, 4, 5, 7, 9, 11))?.values?.any { "♭" in it } ?: false

    /**
     * How the notes of [scale] in [key] are written, by pitch class.
     *
     * Only the notes in the scale are named; anything else is not part of it
     * and has no spelling here.
     */
    fun spelling(key: Int, scale: Int): Map<Int, String> {
        val k = ((key % 12) + 12) % 12
        val iv = intervals.getOrNull(scale) ?: return emptyMap()
        byLetters(k, iv)?.let { return it }
        val table = if (prefersFlats(k)) FLAT_NAMES else SHARP_NAMES
        return iv.associate { val pc = (k + it) % 12; pc to table[pc] }
    }

    /** How the root itself is written, for a chooser that must name all twelve. */
    fun rootName(pitchClass: Int, scale: Int): String {
        val pc = ((pitchClass % 12) + 12) % 12
        spelling(pc, scale)[pc]?.let { return it }
        return (if (prefersFlats(pc)) FLAT_NAMES else SHARP_NAMES)[pc]
    }

    /**
     * A note as the current scale would write it, with its octave.
     *
     * Notes outside the scale keep the plain sharp name: they are accidentals
     * against it, and inventing a spelling for them would be a guess.
     */
    fun noteName(midi: Int, key: Int, scale: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val name = spelling(key, scale)[pc] ?: SHARP_NAMES[pc]
        return name + (midi / 12 - 1)
    }

    /**
     * The pitch classes a Scale eventor on [track] lets through, or null when
     * the track has no Scale eventor, it is bypassed, or it is in degree mode
     * (where every key is in the scale by construction).
     */
    /**
     * How this track's running Scale eventor writes its notes, by pitch
     * class - empty when there is no scale to spell against.
     *
     * The same walk as [activeFor], because they answer two halves of one
     * question and reading the eventor twice is cheaper than passing a pair
     * through every caller.
     */
    fun spellingFor(track: Track): Map<Int, String> {
        for (slot in 0 until EVENTOR_SLOTS) {
            val ev = track.eventorAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            if ((ev.params["mode"] ?: 0f) >= 0.5f) return emptyMap() // degree mode
            val key = Math.round((ev.params["key"] ?: 0f) * 11f)
            val index = Math.round((ev.params["scale"] ?: 0f) * (names.size - 1))
            return spelling(key, index)
        }
        return emptyMap()
    }

    fun activeFor(track: Track): Set<Int>? {
        for (slot in 0 until EVENTOR_SLOTS) {
            val ev = track.eventorAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            // Parameters are normalised; these mirror the ranges in Scale's table.
            if ((ev.params["mode"] ?: 0f) >= 0.5f) return null // degree mode
            val key = Math.round((ev.params["key"] ?: 0f) * 11f)
            val index = Math.round((ev.params["scale"] ?: 0f) * (names.size - 1))
            val steps = intervals.getOrNull(index) ?: return null
            return steps.map { (it + key) % 12 }.toSet()
        }
        return null
    }

    /** The scale's tonic as a pitch class, or null when no scale is active. */
    fun rootFor(track: Track): Int? {
        for (slot in 0 until EVENTOR_SLOTS) {
            val ev = track.eventorAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            return Math.round((ev.params["key"] ?: 0f) * 11f) % 12
        }
        return null
    }

    /** "C Ionian (Major)" for the label, or null when no scale is active. */
    fun labelFor(track: Track): String? {
        for (slot in 0 until EVENTOR_SLOTS) {
            val ev = track.eventorAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            val key = Math.round((ev.params["key"] ?: 0f) * 11f)
            val index = Math.round((ev.params["scale"] ?: 0f) * (names.size - 1))
            val degree = (ev.params["mode"] ?: 0f) >= 0.5f
            return rootName(key, index) + " " + (names.getOrNull(index) ?: "") + if (degree) " · degrees" else ""
        }
        return null
    }
}
