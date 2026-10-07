package com.rm.acidulous.res

/**
 * French as it's written in France.
 *
 * The app's French is written for Canada, and the words are the same in
 * France. The spacing around punctuation isn't: Canada puts a no-break space
 * before a colon and nothing before ; ! and ?, where France puts a narrow
 * no-break space before those three as well. So there's one French to keep
 * up, and France's spacing is put in as each string is read.
 */
object FrenchTypography {
    private const val NBSP = ' '
    private const val NARROW = ' '

    /** Whether the app is in France's French now. */
    val inFrance: Boolean
        get() {
            val l = androidx.compose.ui.text.intl.Locale.current
            return l.language == "fr" && l.region == "FR"
        }

    /** [text] as the app's language writes it: France's spacing when it's France's French. */
    fun ofLocale(text: String): String = if (inFrance) forFrance(text) else text

    /**
     * [text] with France's spacing: a narrow no-break space before ; ! and ?,
     * a no-break space before a colon. Only where the mark ends a clause, so a
     * time (14:05), a web address and code (`a ? b : c`, between backticks)
     * are left alone.
     */
    fun forFrance(text: String): String {
        if (text.none { it == '?' || it == '!' || it == ';' || it == ':' }) return text
        val out = StringBuilder(text.length + 8)
        var code = false
        for (i in text.indices) {
            val c = text[i]
            if (c == '`') code = !code
            if (!code && i > 0 && isMark(c) && endsClause(text, i)) {
                val before = text[i - 1]
                val space = if (c == ':') NBSP else NARROW
                when {
                    before == ' ' || before == NBSP || before == NARROW -> out.setCharAt(out.length - 1, space)
                    before.isLetterOrDigit() || before in ")»\"’'%*…" -> out.append(space)
                }
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun isMark(c: Char) = c == '?' || c == '!' || c == ';' || c == ':'

    /** Whether the mark at [i] ends a clause: the end, a space, or a closing mark after it. */
    private fun endsClause(text: String, i: Int): Boolean {
        if (i + 1 >= text.length) return true
        val next = text[i + 1]
        return next.isWhitespace() || next in ")»\"’*" || isMark(next)
    }
}
