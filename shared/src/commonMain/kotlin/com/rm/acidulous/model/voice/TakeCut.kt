package com.rm.acidulous.model.voice

import kotlinx.serialization.Serializable

/**
 * How a take was cut up, in frames at 48 kHz: where the singing is, the steady
 * part of its first vowel, where a diphthong moves and where a consonant is.
 * [problem] is empty for a take that's fine, otherwise why it should be sung
 * again. [rootHz] is the pitch it was sung at. [by] is which cutter cut it:
 * takes cut by an older one are cut again.
 */
@Serializable
data class TakeCut(
    val problem: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val holdFrom: Int = 0,
    val holdTo: Int = 0,
    val glideFrom: Int = 0,
    val glideTo: Int = 0,
    val consonantFrom: Int = 0,
    val consonantTo: Int = 0,
    val rootHz: Float = 0f,
    val centsOff: Float = 0f,
    val by: Int = 0,
) {
    companion object {
        /**
         * The cutter now. Raise it when cutting changes, so voices are cut again.
         * 2: a diphthong's move is found wherever it's sung, not only at the end.
         */
        const val CUTTER = 2

        /** A cut as the engine reports it: "problem|start|end|...|rootHz|centsOff". */
        fun parse(text: String): TakeCut? {
            val f = text.split('|')
            if (f.size < 11) return null
            val i = f.subList(1, 9).map { it.toIntOrNull() ?: return null }
            return TakeCut(
                f[0], i[0], i[1], i[2], i[3], i[4], i[5], i[6], i[7],
                f[9].toFloatOrNull() ?: 0f, f[10].toFloatOrNull() ?: 0f, CUTTER,
            )
        }
    }
}
