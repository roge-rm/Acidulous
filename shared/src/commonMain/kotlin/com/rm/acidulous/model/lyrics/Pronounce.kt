package com.rm.acidulous.model.lyrics

/** How the words are said. The order is the accent control's on Diction. */
enum class Accent {
    /** Alberta and the prairies: Canadian, with bag and egg raised and the vowels of go and say steadier. */
    Prairie,
    /** Ontario: the Canadian most people mean. */
    Central,
    American;

    val canadian: Boolean get() = this != American

    companion object {
        /** From the control's normalised value: 0, a half, 1. */
        fun of(normalised: Float): Accent = entries[(normalised * 2f + 0.5f).toInt().coerceIn(0, 2)]
    }
}

/**
 * A word's sounds, as the singer's phone names.
 *
 * The dictionary is American, so the accent is rules on top of it. Stress
 * stays on the vowels ("AY1") until [Pronounce.forSinger], since a few rules
 * need it.
 */
object Pronounce {
    private val VOICELESS = setOf("P", "T", "K", "F", "TH", "S", "SH", "CH", "HH")
    private val NASALS = setOf("M", "N")
    private val VELARS = setOf("G", "NG")

    /** Words a Canadian says differently from the dictionary. */
    private val CANADIAN = mapOf(
        "sorry" to "S AO1 R IY0",
        "sorrow" to "S AO1 R OW0",
        "tomorrow" to "T AH0 M AO1 R OW0",
        "borrow" to "B AO1 R OW0",
        "been" to "B IY1 N",
        "again" to "AH0 G EY1 N",
        "against" to "AH0 G EY1 N S T",
        "process" to "P R OW1 S EH2 S",
        "pasta" to "P AE1 S T AH0",
    )

    /** The accents' own vowels, beside the dictionary's. */
    private val ACCENT_VOWELS = setOf("AX", "AYC", "AWC", "AWP", "EYP", "OWP", "EG", "AEN", "OC", "IHC", "EHC", "AEC", "OR")

    fun isVowel(sound: String): Boolean {
        val b = sound.trimEnd('0', '1', '2')
        return b in Dictionary.VOWELS || b in ACCENT_VOWELS
    }
    private fun base(sound: String) = sound.trimEnd('0', '1', '2')
    private fun stress(sound: String) = sound.lastOrNull()?.takeIf { it.isDigit() }?.digitToInt() ?: 0

    /** [word]'s sounds with stress, from [dictionary] or, failing that, from its spelling. */
    fun word(word: String, dictionary: Dictionary?, accent: Accent): List<String> {
        val w = word.lowercase().filter { it in 'a'..'z' || it == '\'' }.trim('\'')
        if (w.isEmpty()) return emptyList()
        val said = (if (accent.canadian) CANADIAN[w]?.split(' ') else null)
            ?: dictionary?.lookup(w)
            ?: Spelling.sounds(w)
        // The accent first: a tapped T is still a T to the vowel before it
        // (writer is raised, rider isn't).
        return flap(applyAccent(said, accent))
    }

    /**
     * T and D between vowels, the second unstressed, are tapped: butter,
     * water, little. Canadian and American both.
     */
    private fun flap(s: List<String>): List<String> = s.mapIndexed { i, x ->
        val tap = (x == "T" || x == "D") && i > 0 && i + 1 < s.size &&
            (isVowel(s[i - 1]) || s[i - 1] == "R") && isVowel(s[i + 1]) && stress(s[i + 1]) == 0
        if (tap) "DX" else x
    }

    private fun applyAccent(s: List<String>, accent: Accent): List<String> = s.mapIndexed { i, x ->
        if (!isVowel(x)) return@mapIndexed x
        val v = base(x)
        val next = s.getOrNull(i + 1)
        val beforeVoiceless = next != null && next in VOICELESS
        val out = when (accent) {
            Accent.American -> when {
                v == "AE" && next in NASALS -> "AEN"
                else -> v
            }
            Accent.Prairie, Accent.Central -> when {
                v == "AY" && beforeVoiceless -> "AYC"
                v == "AW" && beforeVoiceless -> if (accent == Accent.Prairie) "AWP" else "AWC"
                (v == "AE" || v == "EH") && next in VELARS && accent == Accent.Prairie -> "EG"
                // Cot and caught are one vowel, except before an R, where
                // car keeps its own and sore its own.
                (v == "AA" || v == "AO") && next == "R" -> v
                v == "AA" || v == "AO" -> "OC"
                // The shift, but not before a nasal, where bat's vowel stays put.
                v == "AE" && next in NASALS -> "AE"
                v == "AE" -> "AEC"
                v == "EH" -> "EHC"
                v == "IH" -> "IHC"
                v == "EY" && accent == Accent.Prairie -> "EYP"
                v == "OW" && accent == Accent.Prairie -> "OWP"
                else -> v
            }
        }
        // The a in about: an unstressed AH is the neutral vowel.
        val reduced = if (out == "AH" && stress(x) == 0) "AX" else out
        reduced + stress(x)
    }

    /** Stress marks off, for the engine. */
    fun forSinger(sounds: List<String>): List<String> = sounds.map { if (isVowel(it)) base(it) else it.trimEnd('0', '1', '2') }
}

/**
 * Letters to sounds for words the dictionary doesn't have: sung nonsense,
 * names, typos. Rough, as spelling is to English, but it gives every word
 * something to sing.
 */
internal object Spelling {
    private val RULES: List<Pair<String, String>> = listOf(
        "tion" to "SH AH0 N", "sion" to "ZH AH0 N", "igh" to "AY1", "tch" to "CH", "dge" to "JH",
        "ch" to "CH", "sh" to "SH", "th" to "TH", "ph" to "F", "wh" to "W", "ck" to "K", "ng" to "NG", "qu" to "K W",
        "oo" to "UW1", "ee" to "IY1", "ea" to "IY1", "ai" to "EY1", "ay" to "EY1", "oa" to "OW1", "ow" to "AW1",
        "ou" to "AW1", "oi" to "OY1", "oy" to "OY1", "au" to "AO1", "aw" to "AO1", "ie" to "IY1", "ey" to "EY1",
        "ah" to "AA1", "oh" to "OW1", "uh" to "AH1",
        "a" to "AE1", "e" to "EH1", "i" to "IH1", "o" to "AA1", "u" to "AH1",
        "b" to "B", "c" to "K", "d" to "D", "f" to "F", "g" to "G", "h" to "HH", "j" to "JH", "k" to "K", "l" to "L",
        "m" to "M", "n" to "N", "p" to "P", "r" to "R", "s" to "S", "t" to "T", "v" to "V", "w" to "W", "x" to "K S",
        "z" to "Z",
    )
    private val LONG = mapOf('a' to "EY1", 'e' to "IY1", 'i' to "AY1", 'o' to "OW1", 'u' to "UW1")

    fun sounds(word: String): List<String> {
        var w = word.replace("'", "")
        // A silent e makes the vowel before it long: made, ride, home.
        var longAt = -1
        if (w.length >= 3 && w.last() == 'e' && w[w.length - 2] !in "aeiouy" && w[w.length - 3] in "aeiou") {
            longAt = w.length - 3
            w = w.dropLast(1)
        }
        val out = ArrayList<String>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            if (i == longAt) {
                out += LONG.getValue(c); i++; continue
            }
            // Y: a consonant at the start, ee at the end, i elsewhere.
            if (c == 'y') {
                out += when (i) { 0 -> "Y"; w.length - 1 -> if (i == 1) "AY1" else "IY0"; else -> "IH1" }
                i++; continue
            }
            // A doubled consonant is one sound.
            if (i > 0 && c == w[i - 1] && c !in "aeiou") { i++; continue }
            // A vowel alone at the end is its own name: go, me, hi.
            if (c in "aeiou" && i == w.length - 1 && i > 0 && out.none { Pronounce.isVowel(it) }) {
                out += LONG.getValue(c); i++; continue
            }
            val rule = RULES.firstOrNull { w.startsWith(it.first, i) }
            if (rule == null) { i++; continue }
            out += rule.second.split(' ')
            i += rule.first.length
        }
        return out
    }
}
