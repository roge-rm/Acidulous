package com.rm.acidulous.model.lyrics

/**
 * How English words are said, from the CMU Pronouncing Dictionary (built into
 * files/dictionary.bin by tools/gen_dictionary.py, which describes the format).
 *
 * Searched where it lies rather than unpacked: a hundred thousand words in a
 * map would take ten times the file's size.
 */
class Dictionary(private val bytes: ByteArray) {
    private val words: Int
    private val blocks: Int
    private val bodyAt: Int

    init {
        require(bytes.size >= 13 && bytes[0] == 'C'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
            bytes[2] == 'U'.code.toByte() && bytes[3] == 'D'.code.toByte() && bytes[4] == 1.toByte()) { "not a dictionary" }
        words = u32(5)
        blocks = u32(9)
        bodyAt = 13 + blocks * 4
        require(bodyAt <= bytes.size) { "dictionary is cut short" }
    }

    val size: Int get() = words

    /**
     * The sounds of [word], lower case, as dictionary names with stress
     * ("AH0", "B", "AW1", "T"), or null if it isn't there.
     */
    fun lookup(word: String): List<String>? {
        if (word.isEmpty()) return null
        // The last block whose first word is at or before this one.
        var lo = 0
        var hi = blocks - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (compare(firstWordOf(mid), word) <= 0) lo = mid else hi = mid - 1
        }
        var at = bodyAt + u32(13 + lo * 4)
        val end = if (lo + 1 < blocks) bodyAt + u32(13 + (lo + 1) * 4) else bytes.size
        val current = StringBuilder()
        while (at < end) {
            val shared = bytes[at].toInt() and 0xff
            val n = bytes[at + 1].toInt() and 0xff
            current.setLength(shared.coerceAtMost(current.length))
            for (i in 0 until n) current.append((bytes[at + 2 + i].toInt() and 0xff).toChar())
            at += 2 + n
            val k = bytes[at].toInt() and 0xff
            val c = compare(current, word)
            if (c == 0) return List(k) { nameOf(bytes[at + 1 + it].toInt() and 0xff) }
            if (c > 0) return null
            at += 1 + k
        }
        return null
    }

    private fun firstWordOf(block: Int): String {
        val at = bodyAt + u32(13 + block * 4)
        val n = bytes[at + 1].toInt() and 0xff
        return buildString(n) { for (i in 0 until n) append((bytes[at + 2 + i].toInt() and 0xff).toChar()) }
    }

    /** As the generator sorted them: by character code. */
    private fun compare(a: CharSequence, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val d = a[i].code - b[i].code
            if (d != 0) return d
        }
        return a.length - b.length
    }

    private fun u32(at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or ((bytes[at + 3].toInt() and 0xff) shl 24)

    companion object {
        val VOWELS = listOf("AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW")
        val CONSONANTS = listOf(
            "B", "CH", "D", "DH", "F", "G", "HH", "JH", "K", "L", "M", "N", "NG", "P", "R", "S", "SH", "T",
            "TH", "V", "W", "Y", "Z", "ZH",
        )

        private fun nameOf(code: Int): String =
            if (code < 45) VOWELS[code / 3] + (code % 3) else CONSONANTS.getOrElse(code - 45) { "" }
    }
}
