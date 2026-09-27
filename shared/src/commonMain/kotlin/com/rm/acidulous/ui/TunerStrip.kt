package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the tuner is hearing: the note, and how far off it is, as a note
 * name and a needle. The frequency is shown too, for anyone who wants it.
 */
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** A4, and the only tuning reference offered. */
const val TUNING_REFERENCE = 440f

/** How many cents off still counts as in tune and turns the needle green. */
private const val IN_TUNE = 4f

data class TunerReading(val name: String, val cents: Float, val hz: Float)

/**
 * The nearest note to [hz] and the distance to it in cents. Null when there's
 * no clear note, which is most of the time.
 */
fun readingOf(hz: Float): TunerReading? {
    if (hz <= 0f) return null
    val midi = 69.0 + 12.0 * log2(hz / TUNING_REFERENCE)
    val nearest = midi.roundToInt()
    if (nearest < 12 || nearest > 108) return null
    val want = TUNING_REFERENCE * 2f.pow((nearest - 69) / 12f)
    val cents = (1200.0 * log2(hz / want)).toFloat()
    return TunerReading(NOTE_NAMES[nearest % 12] + (nearest / 12 - 1), cents, hz)
}

@Composable
fun TunerStrip(hz: Float, modifier: Modifier = Modifier) {
    val c = Acid.colors
    val reading = readingOf(hz)
    val inTune = reading != null && abs(reading.cents) <= IN_TUNE
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The name, big enough to read while holding a guitar.
        Text(
            reading?.name ?: "--",
            color = when {
                reading == null -> c.textFaint
                inTune -> c.green
                else -> c.text
            },
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(58.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Needle(reading?.cents, inTune, Modifier.fillMaxWidth().height(22.dp))
            Text(
                if (reading == null) "" else "%.1f Hz   %+d".format(reading.hz, reading.cents.roundToInt()),
                color = c.textDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The needle: fifty cents either way, with the middle marked. Past fifty
 * the next note is nearer and the name changes.
 */
@Composable
private fun Needle(cents: Float?, inTune: Boolean, modifier: Modifier) {
    val c = Acid.colors
    val track = c.sunken
    val tick = c.line
    val mark = if (inTune) c.green else c.accent
    Canvas(modifier) {
        val h = size.height
        val midY = h / 2f
        drawRect(track, topLeft = Offset(0f, midY - 5f), size = Size(size.width, 10f))
        // Every ten cents, with a taller centre line.
        for (step in -5..5) {
            val x = size.width * (0.5f + step / 10f * 0.5f)
            val tall = if (step == 0) h * 0.5f else h * 0.25f
            drawRect(
                if (step == 0) c.teal else tick,
                topLeft = Offset(x - 1f, midY - tall / 2f),
                size = Size(2f, tall),
            )
        }
        if (cents != null) {
            val clamped = cents.coerceIn(-50f, 50f)
            val x = size.width * (0.5f + clamped / 100f)
            drawRect(mark, topLeft = Offset(x - 3f, 0f), size = Size(6f, h))
        }
    }
}
