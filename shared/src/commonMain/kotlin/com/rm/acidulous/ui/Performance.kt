package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import kotlinx.coroutines.launch
import com.rm.acidulous.res.*

/**
 * A wheel control, used for the mod wheel, the bend wheel and the pressure
 * strip. Mod stays where it's left, bend springs back to the middle.
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
    /** The TalkBack label. Without one TalkBack skips it. */
    said: String? = null,
    /** Its value in words, for when a percentage isn't right. */
    state: String = "",
    onChange: (Float) -> Unit,
) {
    val c = Acid.colors
    val change by rememberUpdatedState(onChange)
    val spring by rememberUpdatedState(springBackTo)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(c.wheelBg)
            // A TalkBack swipe sets the value and never releases, so a spring-back
            // wheel returns on its own a moment later. Otherwise a bend set by
            // TalkBack would leave every note out of tune.
            .then(
                if (said == null) Modifier else Modifier.adjustable(
                    said, state.ifEmpty { "%.0f%%".format(value * 100f) }, value,
                ) { v ->
                    change(v)
                    spring?.let { rest -> scope.launch { kotlinx.coroutines.delay(700); change(rest) } }
                },
            )
            .pointerInput(vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun report(at: Offset) {
                        val v = if (vertical) 1f - (at.y / size.height) else at.x / size.width
                        change(v.coerceIn(0f, 1f))
                    }
                    // Only moves once dragged. Jumping to the touch point meant a finger
                    // that missed the nearest piano key could move the mod wheel and record
                    // a point into the lane.
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
            // Ridges, so it looks like something that turns.
            val ridge = c.wheelRidge
            // Ridge pitch and tab size are in dp so the wheel looks the same at
            // every UI scale.
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
 * The width the octave stepper needs for two arrows and the note.
 *
 * A fixed width, because a weighted share of the performance row can be too
 * narrow, and then a `Row` squeezes the last child (the up arrow) to almost
 * nothing. The pressure wheel at the other end gives up the space instead.
 */
val OctaveW = 80.dp

/**
 * Octave down and up arrows with the octave between them.
 *
 * Tapping the octave also toggles the hardware keyboard's play mode, where
 * the letter keys play notes starting at this octave. It's filled in while on.
 * Holding it runs [onOpenPiano]. With [hold], an arrow held down lets a tap on
 * a keyboard row move just that row; see [ArrowHold].
 */
@Composable
fun OctaveStepper(
    octave: Int, onOctave: (Int) -> Unit, modifier: Modifier = Modifier,
    hold: ArrowHold? = null, onOpenPiano: (() -> Unit)? = null,
) {
    val c = Acid.colors
    val playMode = KeyHub.playMode
    val openPiano by rememberUpdatedState(onOpenPiano)
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(if (playMode) c.accent else c.card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // The arrows share the stepper's width instead of each taking a fixed
        // width, so both are always there and the same size. With fixed widths
        // the row could squeeze the up arrow to a few pixels.
        StepArrow("◀", octave > 0, Modifier.weight(1f).widthIn(max = 32.dp), stringResource(Res.string.a11y_octave_down), playMode, hold, -1) {
            onOctave(octave - 1)
        }
        Text(
            "C$octave", color = if (playMode) c.onAccent else c.accent, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = (
                if (onOpenPiano == null) Modifier.clickable { KeyHub.togglePlayMode() }
                else Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { openPiano?.invoke() }, onTap = { KeyHub.togglePlayMode() })
                }
            ).button(
                stringResource(Res.string.a11y_keys_from, spokenNote(12 * (octave + 1), emptyMap(), AppStrings)),
                stringResource(Res.string.keys_play_mode) + ": " + stringResource(if (playMode) Res.string.a11y_on else Res.string.a11y_off),
                actions = if (onOpenPiano == null) emptyList() else listOf(action(stringResource(Res.string.a11y_edit)) { onOpenPiano() }),
                onClick = { KeyHub.togglePlayMode() },
                // The long-press version has no clickable, so the keyboard reaches it here.
                keyFocus = onOpenPiano != null,
            ),
        )
        StepArrow("▶", octave < 8, Modifier.weight(1f).widthIn(max = 32.dp), stringResource(Res.string.a11y_octave_up), playMode, hold, 1) {
            onOctave(octave + 1)
        }
    }
}

@Composable
private fun StepArrow(
    glyph: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    said: String = glyph,
    /** On the play mode's accent fill. */
    onAccent: Boolean = false,
    hold: ArrowHold? = null,
    /** What [hold] is set to while this arrow is down. */
    dir: Int = 0,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    val tap by rememberUpdatedState(if (enabled) onClick else null)
    Box(
        modifier.fillMaxHeight().then(
            if (hold == null) Modifier.clickable(enabled = enabled, onClick = onClick).button(said)
            // Down marks the arrow held. Up steps the octave, unless a row was
            // moved meanwhile.
            else Modifier.pointerInput(hold, dir) {
                awaitEachGesture {
                    awaitFirstDown()
                    val before = hold.moves
                    hold.dir = dir
                    // Only clear the hold if the other arrow hasn't taken it over.
                    val up = try { waitForUpOrCancellation() } finally { if (hold.dir == dir) hold.dir = 0 }
                    if (up != null && hold.moves == before) { up.consume(); tap?.invoke() }
                }
            }.button(said, onClick = if (enabled) onClick else null)
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = when {
            onAccent -> c.onAccent.copy(alpha = if (enabled) 1f else 0.4f)
            enabled -> c.text
            else -> c.textFaint
        }, fontSize = 12.sp)
    }
}
