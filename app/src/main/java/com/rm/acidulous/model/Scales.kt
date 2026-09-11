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

    /**
     * The pitch classes a Scale eventor on [track] lets through, or null when
     * the track has no Scale eventor, it is bypassed, or it is in degree mode
     * (where every key is in the scale by construction).
     */
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
            return keyNames[key % 12] + " " + (names.getOrNull(index) ?: "") + if (degree) " · degrees" else ""
        }
        return null
    }
}
