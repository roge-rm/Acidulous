package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The performance controls, shaped like the things they are.
 *
 * A mod wheel is a wheel: it stands beside the keys, it moves up and down
 * under a thumb, and it stays where it is left. A bend wheel is the same
 * wheel on the other side that springs back to the middle when you let go.
 * Drawing them as horizontal sliders above the keyboard was easier and told
 * you nothing about how to use them.
 *
 * All three - both wheels and the pressure strip - share one control, so a
 * position means the same thing wherever it appears: a bright tab against a
 * ridged dark body.
 */
@Composable
fun TouchWheel(
    value: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    /** Where it returns on release, or null to stay put. */
    springBackTo: Float? = null,
    /** A line across the middle, for a control whose rest position is centre. */
    centreMark: Boolean = false,
    label: String? = null,
    onChange: (Float) -> Unit,
) {
    val c = Acid.colors
    val change by rememberUpdatedState(onChange)
    val spring by rememberUpdatedState(springBackTo)
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(c.wheelBg)
            .pointerInput(vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun report(at: Offset) {
                        val v = if (vertical) 1f - (at.y / size.height) else at.x / size.width
                        change(v.coerceIn(0f, 1f))
                    }
                    report(down.position)
                    down.consume()
                    drag(down.id) { c -> report(c.position); c.consume() }
                    spring?.let { change(it) }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val v = value.coerceIn(0f, 1f)
            // Ridges, so it reads as something that turns rather than a bar.
            val ridge = c.wheelRidge
            if (vertical) {
                var y = 4f
                while (y < size.height - 4f) {
                    drawLine(ridge, Offset(3f, y), Offset(size.width - 3f, y), 1f)
                    y += 7f
                }
            } else {
                var x = 4f
                while (x < size.width - 4f) {
                    drawLine(ridge, Offset(x, 3f), Offset(x, size.height - 3f), 1f)
                    x += 7f
                }
            }
            if (centreMark) {
                if (vertical) {
                    drawLine(c.wheelCentre, Offset(2f, size.height * 0.5f),
                        Offset(size.width - 2f, size.height * 0.5f), 1.5f)
                } else {
                    drawLine(c.wheelCentre, Offset(size.width * 0.5f, 2f),
                        Offset(size.width * 0.5f, size.height - 2f), 1.5f)
                }
            }
            // The tab.
            val thickness = if (vertical) 14f else 16f
            if (vertical) {
                val y = (1f - v) * (size.height - thickness)
                drawRoundRect(accent.copy(alpha = 0.85f), Offset(2f, y), Size(size.width - 4f, thickness),
                    CornerRadius(3f, 3f))
                drawLine(c.wheelTab, Offset(3f, y + 2f), Offset(size.width - 3f, y + 2f), 1f)
            } else {
                val x = v * (size.width - thickness)
                drawRoundRect(accent.copy(alpha = 0.85f), Offset(x, 2f), Size(thickness, size.height - 4f),
                    CornerRadius(3f, 3f))
                drawLine(c.wheelTab, Offset(x + 2f, 3f), Offset(x + 2f, size.height - 3f), 1f)
            }
        }
        if (label != null) {
            Text(
                label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, softWrap = false,
                modifier = Modifier.align(if (vertical) Alignment.BottomCenter else Alignment.CenterStart)
                    .padding(start = 6.dp, bottom = 2.dp),
            )
        }
    }
}

/** Octave up and down, side by side, with the octave between them. */
@Composable
fun OctaveStepper(octave: Int, onOctave: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Acid.colors
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(c.card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StepArrow("◀", octave > 0) { onOctave(octave - 1) }
        Text(
            "C${octave + 1}", color = Acid.colors.accent, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1,
        )
        StepArrow("▶", octave < 8) { onOctave(octave + 1) }
    }
}

@Composable
private fun StepArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.width(32.dp).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) c.text else c.textFaint, fontSize = 12.sp)
    }
}
