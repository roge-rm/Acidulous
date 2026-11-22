package com.rm.acidulous.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the piano keys are labelled with. */
enum class PianoLabels { C, All, None }

/** How many keyboards the second screen stacks. */
val PianoRowRange = 1..4
/** The longest a row can be set to, in octaves. 0 is auto. */
val PianoLengthRange = 0..5
/** How far a row can sit from the strip's octave. */
val PianoOffsetRange = -3..4
/** Each row's octave over the strip's, bottom row first. */
val PianoOffsetDefaults = listOf(0, 1, 2, 3)

/** The octave stepper's range. */
private val StripOctaves = 0..8

/** The octave row [row] (0 = bottom) plays from. */
fun pianoRowOctave(stripOctave: Int, offsets: List<Int>, row: Int): Int =
    (stripOctave + (offsets.getOrNull(row) ?: PianoOffsetDefaults.getOrElse(row) { row }))
        .coerceIn(StripOctaves)

/**
 * [offsets] with row [row] moved by [delta] octaves, kept in range. With a
 * [stripOctave] the row also stops where its octave reaches the stepper's
 * ends, so a move that changes nothing isn't saved.
 */
fun movePianoRow(offsets: List<Int>, row: Int, delta: Int, stripOctave: Int? = null): List<Int> =
    pianoOffsets(offsets).mapIndexed { i, o ->
        if (i != row) o
        else {
            var n = (o + delta).coerceIn(PianoOffsetRange)
            if (stripOctave != null) {
                val top = StripOctaves.last - stripOctave
                val bottom = StripOctaves.first - stripOctave
                // Already past an end (the knob can put it there): only step back toward it.
                n = if (delta > 0) minOf(n, maxOf(top, o)) else maxOf(n, minOf(bottom, o))
                if (delta > 0 && o >= top) n = o
                if (delta < 0 && o <= bottom) n = o
            }
            n
        }
    }

/** Always four offsets, each in range, defaults filling any gap. */
fun pianoOffsets(offsets: List<Int>): List<Int> =
    PianoOffsetDefaults.indices.map { i -> (offsets.getOrNull(i) ?: PianoOffsetDefaults[i]).coerceIn(PianoOffsetRange) }

/** Saved as "0,1,2,3". Anything unreadable falls back to its default. */
fun encodePianoOffsets(offsets: List<Int>): String = pianoOffsets(offsets).joinToString(",")
fun decodePianoOffsets(saved: String?): List<Int> {
    val parts = saved.orEmpty().split(',')
    return PianoOffsetDefaults.indices.map { i ->
        (parts.getOrNull(i)?.trim()?.toIntOrNull() ?: PianoOffsetDefaults[i]).coerceIn(PianoOffsetRange)
    }
}

/**
 * How many keys a keyboard [width] wide shows.
 *
 * [octaves] over 0 is a set length: 7 white keys an octave, or with a scale
 * running ([scaleSize] notes an octave) that many scale notes an octave.
 * At 0, keys about [fixedKey] wide as many as fit when that's over 0 (the
 * second screen), otherwise the most octaves that keep the keys wide enough:
 * up to two where they can be [minKey], up to five where there's room for
 * [roomyKey]. A scale on auto fits as many notes as it can.
 */
fun pianoKeyCount(
    width: Float, minKey: Float, roomyKey: Float, fixedKey: Float, octaves: Int, scaleSize: Int?,
): Int = when {
    octaves > 0 -> if (scaleSize != null) (scaleSize.coerceAtLeast(1) * octaves).coerceIn(1, 35) else 7 * octaves
    scaleSize != null ->
        if (fixedKey > 0f) (width / fixedKey).toInt().coerceIn(1, 28)
        else (width / minKey).toInt().coerceIn(5, 28)
    fixedKey > 0f -> (width / fixedKey).toInt().coerceIn(1, 35)
    width / 35f >= roomyKey -> 35 // five octaves, on a wide screen
    width / 28f >= roomyKey -> 28
    width / 21f >= roomyKey -> 21
    width / 14f >= minKey -> 14 // two octaves
    width / 11f >= minKey -> 11 // an octave and a half
    else -> 7
}

/**
 * An octave arrow being held. While it is, a tap on a keyboard row moves just
 * that row and counts it in [moves], so letting go of the arrow doesn't also step the
 * strip's octave. Touch only, read from the pointer loops.
 */
class ArrowHold {
    /** -1 for ◀, +1 for ▶, 0 when neither is down. */
    var dir by mutableStateOf(0)
    var moves = 0
}
