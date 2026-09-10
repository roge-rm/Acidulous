package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.log10

/**
 * Touch-native mixer controls. All values are 0..1; the caller maps to units.
 * Each gesture reports start / change / end so the document can coalesce a
 * drag into one undo step.
 */

@Composable
fun VerticalFader(
    value: Float,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7FD1B9),
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
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
        drawRect(Color(0xFF3A3A40), Offset(cx - trackW / 2, 0f), Size(trackW, size.height))
        val y = (1f - value.coerceIn(0f, 1f)) * size.height
        drawRect(accent, Offset(cx - trackW / 2, y), Size(trackW, size.height - y))
        // the cap
        drawRect(Color(0xFFE8E8E4), Offset(cx - size.width * 0.4f, y - 8f), Size(size.width * 0.8f, 16f))
    }
}

@Composable
fun MiniSlider(
    value: Float,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7FD1B9),
    centered: Boolean = false, // draw from the middle (pan)
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
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
        drawRect(Color(0xFF3A3A40), Offset(0f, cy - h / 2), Size(size.width, h))
        val x = value.coerceIn(0f, 1f) * size.width
        if (centered) {
            val mid = size.width / 2f
            drawRect(accent, Offset(minOf(mid, x), cy - h / 2), Size(kotlin.math.abs(x - mid), h))
        } else {
            drawRect(accent, Offset(0f, cy - h / 2), Size(x, h))
        }
        drawRect(Color(0xFFE8E8E4), Offset(x - 5f, cy - 9f), Size(10f, 18f))
    }
}

/** A peak meter in dB, -60 .. 0. */
@Composable
fun Meter(peak: Float, modifier: Modifier = Modifier, vertical: Boolean = true) {
    Canvas(modifier) {
        val db = if (peak <= 1e-5f) -60f else (20f * log10(peak)).coerceIn(-60f, 0f)
        val frac = (db + 60f) / 60f
        drawRect(Color(0xFF26262B))
        val color = when {
            db > -1f -> Color(0xFFE74C3C)
            db > -8f -> Color(0xFFFFB454)
            else -> Color(0xFF7FD1B9)
        }
        if (vertical) {
            val h = frac * size.height
            drawRect(color, Offset(0f, size.height - h), Size(size.width, h))
        } else {
            drawRect(color, Offset(0f, 0f), Size(frac * size.width, size.height))
        }
    }
}
