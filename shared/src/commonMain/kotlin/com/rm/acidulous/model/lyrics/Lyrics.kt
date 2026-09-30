package com.rm.acidulous.model.lyrics

import com.rm.acidulous.model.Note

/**
 * What each note of a clip sings, for the engine.
 *
 * A note's lyric is a syllable, a word or several ("hel-", "lo", "the cat"),
 * sounds in brackets ("[hh ax l ow]"), or "-" to hold the last vowel over
 * another note. A syllable ending in "-" joins the next note's into one word,
 * so the word is looked up whole and its sounds shared back out: "hel-" and
 * "lo" sing HH AX and L OW, not "hel" and "lo" said on their own.
 */
object Lyrics {
    /** Joins a syllable to the next note's. */
    private const val JOIN = '-'
    private val HOLD = setOf("-", "+")
    /** In a line of words, a note that sings none. */
    const val NONE = "_"

    private val ONSETS: Set<String> = (
        listOf(
            "P R", "P L", "B R", "B L", "T R", "D R", "K R", "K L", "G R", "G L", "F R", "F L", "TH R", "SH R",
            "S P", "S T", "S K", "S M", "S N", "S L", "S W", "T W", "D W", "K W", "G W",
            "P Y", "B Y", "K Y", "F Y", "M Y", "HH Y", "V Y",
            "S P R", "S P L", "S T R", "S K R", "S K W", "S P Y", "S K Y",
        )
        ).toSet()

    /**
     * The notes a singer sings words on, in the order they're sung: one a
     * start, since a chord sings one syllable, the rest of it harmony. The top
     * note, where a tune usually is; with [byWords], the top one that has
     * words, if any does.
     */
    fun sungOrder(notes: List<Note>, byWords: Boolean = false): List<Int> {
        val sorted = notes.indices.sortedWith(compareBy({ notes[it].tick + notes[it].nudge }, { -notes[it].pitch }))
        val out = ArrayList<Int>()
        var i = 0
        while (i < sorted.size) {
            val start = notes[sorted[i]].tick + notes[sorted[i]].nudge
            var j = i
            while (j < sorted.size && notes[sorted[j]].tick + notes[sorted[j]].nudge == start) j++
            val chord = sorted.subList(i, j)
            out += if (byWords) chord.firstOrNull { notes[it].lyric.isNotBlank() } ?: chord[0] else chord[0]
            i = j
        }
        return out
    }

    /**
     * Each note's sounds, in [notes]' order, as the engine's phone names split
     * by spaces ("" for a note with no words). Null when no note has words.
     */
    fun forNotes(notes: List<Note>, accent: Accent, dictionary: Dictionary?): List<String>? {
        if (notes.none { it.lyric.isNotBlank() }) return null
        val order = sungOrder(notes, byWords = true)
        val sounds = Array(notes.size) { mutableListOf<String>() }

        // A word being built across notes: its text so far and which note
        // each piece of it is on.
        val pieces = ArrayList<Pair<Int, String>>()
        fun finishWord() {
            if (pieces.isEmpty()) return
            val word = pieces.joinToString("") { it.second }
            val said = Pronounce.word(word, dictionary, accent)
            if (pieces.size == 1) {
                sounds[pieces[0].first] += said
            } else {
                val syllables = syllables(said)
                val shares = shareOut(syllables.size, pieces.map { vowelGroups(it.second) })
                var next = 0
                pieces.forEachIndexed { j, (note, _) ->
                    repeat(shares[j]) { sounds[note] += syllables[next++] }
                    // More notes than the word has syllables: hold the vowel.
                    if (shares[j] == 0 && j > 0) holdOver(sounds[pieces[j - 1].first], sounds[note])
                }
            }
            pieces.clear()
        }

        var last = -1 // the note a "-" holds over from
        for (i in order) {
            val lyric = notes[i].lyric.trim()
            when {
                lyric.isEmpty() -> {
                    finishWord()
                    last = -1
                }
                lyric in HOLD -> {
                    finishWord()
                    if (last >= 0) holdOver(sounds[last], sounds[i])
                    if (sounds[i].isNotEmpty()) last = i
                }
                lyric.startsWith("[") -> {
                    finishWord()
                    sounds[i] += lyric.trim('[', ']').split(' ', ',').filter { it.isNotBlank() }.map { it.uppercase() }
                    last = i
                }
                else -> {
                    // Words on this note: every one but a joined last one is
                    // finished here.
                    val words = lyric.split(' ').filter { it.isNotBlank() }
                    words.forEachIndexed { k, w ->
                        val joins = k == words.size - 1 && w.endsWith(JOIN) && w.length > 1
                        pieces += i to w.trimEnd(JOIN)
                        if (!joins) finishWord()
                    }
                    last = i
                }
            }
        }
        finishWord()
        return List(notes.size) { Pronounce.forSinger(sounds[it]).joinToString(" ") }
    }

    /**
     * A typed line cut into one piece a note: words at the spaces, syllables
     * at the hyphens ("hel-lo" is "hel-" and "lo"), sounds in brackets whole,
     * and [NONE] for a note with no words.
     */
    fun spread(line: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '[' -> {
                    val end = line.indexOf(']', i).let { if (it < 0) line.length else it + 1 }
                    out += line.substring(i, end)
                    i = end
                }
                else -> {
                    var end = i
                    while (end < line.length && !line[end].isWhitespace() && line[end] != '[') end++
                    val word = line.substring(i, end)
                    i = end
                    if (word in HOLD || word == NONE) { out += word; continue }
                    // hel-lo-o: every syllable but the last keeps its hyphen.
                    val parts = word.split(JOIN).filter { it.isNotEmpty() }
                    parts.forEachIndexed { k, part -> out += if (k < parts.size - 1 || word.endsWith(JOIN)) "$part$JOIN" else part }
                }
            }
        }
        return out.map { if (it == NONE) "" else it }
    }

    /** The inverse of [spread]: the lyrics of some notes as one line. */
    fun gather(lyrics: List<String>): String {
        val last = lyrics.indexOfLast { it.isNotBlank() }
        if (last < 0) return ""
        val sb = StringBuilder()
        var previous = ""
        for (k in 0..last) {
            val l = lyrics[k].trim().ifEmpty { NONE }
            // A syllable that joins the next is written against it: hel-lo.
            val joined = previous.length > 1 && previous.endsWith(JOIN) && l.first().isLetter()
            if (sb.isNotEmpty() && !joined) sb.append(' ')
            sb.append(l)
            previous = l
        }
        return sb.toString()
    }

    /**
     * Holds [from]'s last vowel over [to]: the vowel goes on, and the
     * consonants that ended [from] move to the end of the held note.
     */
    private fun holdOver(from: MutableList<String>, to: MutableList<String>) {
        val v = from.indexOfLast { Pronounce.isVowel(it) }
        if (v < 0) return
        val coda = from.subList(v + 1, from.size).toList()
        repeat(coda.size) { from.removeAt(from.size - 1) }
        to += from[v]
        to += coda
    }

    /** A word's sounds cut into syllables, each consonant run split so the next syllable starts as English allows. */
    internal fun syllables(said: List<String>): List<List<String>> {
        val vowels = said.indices.filter { Pronounce.isVowel(said[it]) }
        if (vowels.size <= 1) return listOf(said)
        val out = ArrayList<List<String>>()
        var start = 0
        for (k in 0 until vowels.size - 1) {
            val between = said.subList(vowels[k] + 1, vowels[k + 1]).map { it.trimEnd('0', '1', '2') }
            // The longest run from the end of the gap that can begin a syllable.
            var onset = 0
            for (n in between.size downTo 1) {
                val tail = between.takeLast(n)
                if (n == 1 && tail[0] != "NG" || tail.joinToString(" ") in ONSETS) { onset = n; break }
            }
            val cut = vowels[k + 1] - onset
            out += said.subList(start, cut)
            start = cut
        }
        out += said.subList(start, said.size)
        return out
    }

    /** How many syllables a written piece has, roughly: its runs of vowel letters, at least one. */
    internal fun vowelGroups(text: String): Int {
        var groups = 0
        var inVowel = false
        val t = text.lowercase().let { if (it.length > 2 && it.endsWith("e") && !it.endsWith("le")) it.dropLast(1) else it }
        for (c in t) {
            val v = c in "aeiouy"
            if (v && !inVowel) groups++
            inVowel = v
        }
        return groups.coerceAtLeast(1)
    }

    /**
     * [total] syllables shared among pieces that look like they have [wanted]
     * each: every piece one while there are enough, the spelling's guess for
     * the rest, and whatever's left over to the last.
     */
    internal fun shareOut(total: Int, wanted: List<Int>): List<Int> {
        val share = IntArray(wanted.size)
        var left = total
        for (j in wanted.indices) if (left > 0) { share[j] = 1; left-- }
        for (j in wanted.indices) {
            val more = minOf(left, wanted[j] - 1).coerceAtLeast(0)
            share[j] += more
            left -= more
        }
        if (left > 0 && share.isNotEmpty()) share[share.size - 1] += left
        return share.toList()
    }
}
