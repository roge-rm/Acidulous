package com.rm.acidulous.model

import com.rm.acidulous.util.Math

/**
 * The scale table, matching engine/inputmod/Scales.h: same names, order and
 * intervals. The engine does the sound. This copy is so the keyboard can
 * show which notes a Scale modifier lets through.
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
    // C Dorian is C D E♭ F G A B♭, not C D D♯ F G A A♯. A scale is written
    // using each letter once, and the accidentals follow from that.
    //
    // So we walk the letters from the root's letter, one per degree, and give
    // each the accidental that reaches the scale's pitch. The root's letter
    // isn't fixed either (B♭ and A♯ are the same key), so every candidate is
    // tried and the one with the fewest accidentals wins. That's how you get
    // D♭ major (five flats) rather than C♯ major (seven sharps).
    //
    // This only works for seven-note scales, which covers all the ones with a
    // standard spelling. Pentatonic and eight-note bebop scales fall back to
    // one accidental per note, sharps or flats depending on the key.

    private val LETTERS = listOf("C", "D", "E", "F", "G", "A", "B")
    private val LETTER_PC = listOf(0, 2, 4, 5, 7, 9, 11)
    private val SHARP_NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val FLAT_NAMES = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")

    /** The nearest way from a natural to a pitch, -2..+2 semitones. */
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

    /** One letter per degree, or null when the intervals don't allow it. */
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
                // A double sharp is allowed but nearly always means another
                // root would be better, so it costs a lot more.
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

    /** Whether this key reads more naturally in flats, judged by its major scale. */
    private fun prefersFlats(key: Int): Boolean =
        byLetters(key, listOf(0, 2, 4, 5, 7, 9, 11))?.values?.any { "♭" in it } ?: false

    /**
     * How the notes of [scale] in [key] are written, by pitch class.
     *
     * Only notes in the scale are named.
     */
    fun spelling(key: Int, scale: Int): Map<Int, String> {
        val k = ((key % 12) + 12) % 12
        val iv = intervals.getOrNull(scale) ?: return emptyMap()
        byLetters(k, iv)?.let { return it }
        val table = if (prefersFlats(k)) FLAT_NAMES else SHARP_NAMES
        return iv.associate { val pc = (k + it) % 12; pc to table[pc] }
    }

    /** How the root itself is written, for a picker that has to name all twelve. */
    fun rootName(pitchClass: Int, scale: Int): String {
        val pc = ((pitchClass % 12) + 12) % 12
        spelling(pc, scale)[pc]?.let { return it }
        return (if (prefersFlats(pc)) FLAT_NAMES else SHARP_NAMES)[pc]
    }

    /**
     * A note as the current scale would write it, with its octave.
     *
     * Notes outside the scale keep the plain sharp name, since there's no
     * right spelling to pick for them.
     */
    fun noteName(midi: Int, key: Int, scale: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val name = spelling(key, scale)[pc] ?: SHARP_NAMES[pc]
        return name + (midi / 12 - 1)
    }

    /**
     * How this track's active Scale modifier writes its notes, by pitch class.
     * Empty when there's no scale to spell against.
     *
     * Walks the modifiers the same way as [activeFor].
     */
    fun spellingFor(track: Track): Map<Int, String> {
        for (slot in 0 until MODIFIER_SLOTS) {
            val ev = track.modifierAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            if ((ev.params["mode"] ?: 0f) >= 0.5f) return emptyMap() // degree mode
            val key = Math.round((ev.params["key"] ?: 0f) * 11f)
            val index = Math.round((ev.params["scale"] ?: 0f) * (names.size - 1))
            return spelling(key, index)
        }
        return emptyMap()
    }

    /**
     * The pitch classes a Scale modifier on [track] lets through, or null when
     * the track has no Scale modifier, it's bypassed, or it's in degree mode
     * (where every key is in the scale).
     */
    fun activeFor(track: Track): Set<Int>? {
        for (slot in 0 until MODIFIER_SLOTS) {
            val ev = track.modifierAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            // Parameters are normalised. These match the ranges in Scale's table.
            if ((ev.params["mode"] ?: 0f) >= 0.5f) return null // degree mode
            val key = Math.round((ev.params["key"] ?: 0f) * 11f)
            val index = Math.round((ev.params["scale"] ?: 0f) * (names.size - 1))
            val steps = intervals.getOrNull(index) ?: return null
            return steps.map { (it + key) % 12 }.toSet()
        }
        return null
    }

    /**
     * The pitch classes this track's roll should treat as in key.
     *
     * The track's own Scale modifier comes first, and the song's key is only
     * used when the track has none. A track set to a different scale than the
     * song is on purpose.
     */
    fun activeFor(song: Song, track: Track): Set<Int>? =
        activeFor(track) ?: song.key?.let { k ->
            intervals.getOrNull(k.scale)?.map { (it + k.root) % 12 }?.toSet()
        }

    /** The tonic the roll should mark, from the track or else the song. */
    fun rootFor(song: Song, track: Track): Int? = rootFor(track) ?: song.key?.root?.rem(12)

    /** How to write a note, from the track's scale or else the song's key. */
    fun spellingFor(song: Song, track: Track): Map<Int, String> {
        val own = spellingFor(track)
        if (own.isNotEmpty()) return own
        val k = song.key ?: return emptyMap()
        return spelling(k.root, k.scale)
    }

    /** The scale's tonic as a pitch class, or null when no scale is active. */
    fun rootFor(track: Track): Int? {
        for (slot in 0 until MODIFIER_SLOTS) {
            val ev = track.modifierAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            return Math.round((ev.params["key"] ?: 0f) * 11f) % 12
        }
        return null
    }

    /** "C Ionian (Major)" for the label, or null when no scale is active. */
    fun labelFor(track: Track): String? {
        for (slot in 0 until MODIFIER_SLOTS) {
            val ev = track.modifierAt(slot)
            if (ev.type != "Scale" || ev.bypass) continue
            val key = Math.round((ev.params["key"] ?: 0f) * 11f)
            val index = Math.round((ev.params["scale"] ?: 0f) * (names.size - 1))
            val degree = (ev.params["mode"] ?: 0f) >= 0.5f
            return rootName(key, index) + " " + (names.getOrNull(index) ?: "") + if (degree) " · degrees" else ""
        }
        return null
    }
}
