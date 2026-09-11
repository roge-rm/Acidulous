package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.log10
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * Touch-native mixer controls. All values are 0..1; the caller maps to units.
 * Each gesture reports start / change / end so the document can coalesce a
 * drag into one undo step.
 */

@Composable
fun VerticalFader(
    value: Float,
    modifier: Modifier = Modifier,
    accent: Color = Acid.colors.teal,
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val c = Acid.colors
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                cb.first()
                fun at(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                cb.second(at(down.position.y))
                drag(down.id) { change ->
                    change.consume()
                    cb.second(at(change.position.y))
                }
                cb.third()
            }
        },
    ) {
        val trackW = 6f
        val cx = size.width / 2f
        drawRect(c.raised, Offset(cx - trackW / 2, 0f), Size(trackW, size.height))
        val y = (1f - value.coerceIn(0f, 1f)) * size.height
        drawRect(accent, Offset(cx - trackW / 2, y), Size(trackW, size.height - y))
        // the cap
        drawRect(c.knobPointer, Offset(cx - size.width * 0.4f, y - 8f), Size(size.width * 0.8f, 16f))
    }
}

@Composable
fun MiniSlider(
    value: Float,
    modifier: Modifier = Modifier,
    accent: Color = Acid.colors.teal,
    centered: Boolean = false, // draw from the middle (pan)
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val c = Acid.colors
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                cb.first()
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                cb.second(at(down.position.x))
                drag(down.id) { change ->
                    change.consume()
                    cb.second(at(change.position.x))
                }
                cb.third()
            }
        },
    ) {
        val h = 6f
        val cy = size.height / 2f
        drawRect(c.raised, Offset(0f, cy - h / 2), Size(size.width, h))
        val x = value.coerceIn(0f, 1f) * size.width
        if (centered) {
            val mid = size.width / 2f
            drawRect(accent, Offset(minOf(mid, x), cy - h / 2), Size(kotlin.math.abs(x - mid), h))
        } else {
            drawRect(accent, Offset(0f, cy - h / 2), Size(x, h))
        }
        drawRect(c.knobPointer, Offset(x - 5f, cy - 9f), Size(10f, 18f))
    }
}

/** A peak meter in dB, -60 .. 0. */
@Composable
fun Meter(peak: Float, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val c = Acid.colors
    Canvas(modifier) {
        val db = if (peak <= 1e-5f) -60f else (20f * log10(peak)).coerceIn(-60f, 0f)
        val frac = (db + 60f) / 60f
        drawRect(c.card)
        val color = when {
            db > -1f -> c.red
            db > -8f -> c.accent
            else -> c.teal
        }
        if (vertical) {
            val h = frac * size.height
            drawRect(color, Offset(0f, size.height - h), Size(size.width, h))
        } else {
            drawRect(color, Offset(0f, 0f), Size(frac * size.width, size.height))
        }
    }
}

/**
 * A header control sized to its glyph. Material's TextButton reserves a 48dp
 * touch target in both directions, and a row of those leaves the title no
 * room - which matters twice over now that a header has to fit beside a
 * camera hole. These take the height of the row and only the width they need.
 */
@Composable
fun HeaderButton(glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.size(width = 30.dp, height = 40.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = if (enabled) c.text else c.textFaint, fontSize = 14.sp) }
}

/** The same, for a control that needs a word rather than a glyph. */
@Composable
fun HeaderTextButton(
    label: String,
    color: Color = Acid.colors.textMid,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        Modifier.height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) color else c.textFaint, fontSize = 13.sp, maxLines = 1) }
}
