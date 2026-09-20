package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
                    // A drag, never a jab. This used to report the down's own
                    // position immediately, so the wheel jumped to wherever a
                    // finger landed - including a finger that was aiming at
                    // the outermost piano key three dp away and missed. Mod
                    // does not spring back, and a control change records, so
                    // a missed note could leave the wheel somewhere new and
                    // write a point into the lane on the way. A real wheel
                    // cannot teleport under your thumb either.
                    val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                        ?: return@awaitEachGesture
                    report(slop.position)
                    drag(slop.id) { c -> report(c.position); c.consume() }
                    spring?.let { change(it) }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val v = value.coerceIn(0f, 1f)
            // Ridges, so it reads as something that turns rather than a bar.
            val ridge = c.wheelRidge
            // The pitch of the ridges and the size of the tab below are in dp,
            // so the wheel is the same wheel at every interface scale rather
            // than the same drawing on a larger control.
            val pitch = 7.dp.toPx()
            if (vertical) {
                var y = 4f
                while (y < size.height - 4f) {
                    drawLine(ridge, Offset(3f, y), Offset(size.width - 3f, y), 1f)
                    y += pitch
                }
            } else {
                var x = 4f
                while (x < size.width - 4f) {
                    drawLine(ridge, Offset(x, 3f), Offset(x, size.height - 3f), 1f)
                    x += pitch
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
            val thickness = if (vertical) 5.dp.toPx() else 6.dp.toPx()
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

/**
 * What the octave stepper needs: two arrows and the note between them.
 *
 * Stated, because a weighted share of the performance row does not cover it -
 * upright at 1.0 the share came to fifty-eight dp against the seventy-six of
 * `◀ C4 ▶`, and a `Row` asked for more than it has gives the last child what
 * is left. What was left was thirteen pixels of the arrow you step *up* with,
 * and then, once the arrows shared instead, two pixels of the note. Nothing
 * arranges its way out of a box that is too small; the box has to be the right
 * size, and the slack comes off the pressure wheel at the other end, which can
 * spare it.
 */
val OctaveW = 80.dp

/** Octave up and down, side by side, with the octave between them. */
@Composable
fun OctaveStepper(octave: Int, onOctave: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Acid.colors
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(c.card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // **The arrows share what the stepper is given; they do not state a
        // width and take it.** Stated at thirty-two each they came to more than
        // the weighted box around them, and a `Row` asked for more than it has
        // gives the last child what is left - which was thirteen pixels of the
        // `▶` you step *up* with. This row's own note already said what that
        // costs: a stepper missing an arrow is a control that does not work.
        // Sharing, the two are always the same size as each other and always
        // both there, whatever the row can spare.
        StepArrow("◀", octave > 0, Modifier.weight(1f).widthIn(max = 32.dp)) {
            onOctave(octave - 1)
        }
        Text(
            "C${octave + 1}", color = Acid.colors.accent, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1,
        )
        StepArrow("▶", octave < 8, Modifier.weight(1f).widthIn(max = 32.dp)) {
            onOctave(octave + 1)
        }
    }
}

@Composable
private fun StepArrow(
    glyph: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) c.text else c.textFaint, fontSize = 12.sp)
    }
}
