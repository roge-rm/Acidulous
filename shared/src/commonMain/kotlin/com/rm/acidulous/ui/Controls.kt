package com.rm.acidulous.ui

import com.rm.acidulous.util.format

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
import androidx.compose.ui.unit.Dp
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
import com.rm.acidulous.res.*

/**
 * Touch mixer controls. All values are 0..1 and the caller maps them to
 * units. Each gesture reports start, change and end so the song can merge a
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
    /** Hold it to reset it to where it was when the panel opened; see [Knob]. */
    onReset: (() -> Unit)? = null,
    /** What TalkBack calls it, and its value. Without a name TalkBack skips it. */
    name: String = "",
    state: String = "",
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val reset by rememberUpdatedState(onReset)
    val resetName = stringResource(Res.string.a11y_reset)
    val c = Acid.colors
    Canvas(
        modifier.then(
            if (name.isEmpty()) Modifier
            else Modifier.adjustable(
                name, state.ifEmpty { "%.0f%%".format(value * 100f) }, value,
                actions = onReset?.let { listOf(action(resetName, it)) } ?: emptyList(),
                gesture = Triple({ cb.first() }, { v -> cb.second(v) }, { cb.third() }),
            ) { v -> cb.first(); cb.second(v); cb.third() },
        ).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                // Check for a hold before anything moves. A fader jumps to
                // where you touch it, so otherwise a hold would move the value
                // and then put it back.
                if (reset != null &&
                    wasHeld(down, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
                ) {
                    reset?.invoke()
                    swallowRest()
                    return@awaitEachGesture
                }
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
        // Sizes in dp, not pixels, so the track and cap scale with the
        // interface scale like the rest of the fader.
        val trackW = 6.dp.toPx()
        val capH = 16.dp.toPx()
        val cx = size.width / 2f
        drawRect(c.raised, Offset(cx - trackW / 2, 0f), Size(trackW, size.height))
        val y = (1f - value.coerceIn(0f, 1f)) * size.height
        drawRect(accent, Offset(cx - trackW / 2, y), Size(trackW, size.height - y))
        // the cap
        drawRect(c.knobPointer, Offset(cx - size.width * 0.4f, y - capH / 2), Size(size.width * 0.8f, capH))
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
    /** Hold it to reset it to where it was when the panel opened; see [Knob]. */
    onReset: (() -> Unit)? = null,
    /** What TalkBack calls it, and its value. Without a name TalkBack skips it. */
    name: String = "",
    state: String = "",
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val reset by rememberUpdatedState(onReset)
    val resetName = stringResource(Res.string.a11y_reset)
    val c = Acid.colors
    Canvas(
        modifier.then(
            if (name.isEmpty()) Modifier
            else Modifier.adjustable(
                name, state.ifEmpty { "%.0f%%".format(value * 100f) }, value,
                actions = onReset?.let { listOf(action(resetName, it)) } ?: emptyList(),
                gesture = Triple({ cb.first() }, { v -> cb.second(v) }, { cb.third() }),
            ) { v -> cb.first(); cb.second(v); cb.third() },
        ).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
        // Check for a hold before the jump, as in the fader above.
                if (reset != null &&
                    wasHeld(down, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
                ) {
                    reset?.invoke()
                    swallowRest()
                    return@awaitEachGesture
                }
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
        // In dp like the fader above. These are every send and pan in the
        // mixer, so they need to scale when the interface is enlarged.
        val h = 6.dp.toPx()
        val thumbW = 10.dp.toPx()
        val thumbH = 18.dp.toPx()
        val cy = size.height / 2f
        drawRect(c.raised, Offset(0f, cy - h / 2), Size(size.width, h))
        val x = value.coerceIn(0f, 1f) * size.width
        if (centered) {
            val mid = size.width / 2f
            drawRect(accent, Offset(minOf(mid, x), cy - h / 2), Size(kotlin.math.abs(x - mid), h))
        } else {
            drawRect(accent, Offset(0f, cy - h / 2), Size(x, h))
        }
        drawRect(c.knobPointer, Offset(x - thumbW / 2, cy - thumbH / 2), Size(thumbW, thumbH))
    }
}

/**
 * A peak meter in dB, -60 .. 0.
 *
 * [track] is the background colour. The default `card` works on a panel but
 * is invisible in a dialog, which is already a card, so dialogs pass another
 * colour to show an empty meter.
 */
@Composable
fun Meter(
    peak: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    track: Color = Acid.colors.card,
) {
    val c = Acid.colors
    // Changes many times a second, so TalkBack would talk over the music.
    Canvas(modifier.silent()) {
        val db = if (peak <= 1e-5f) -60f else (20f * log10(peak)).coerceIn(-60f, 0f)
        val frac = (db + 60f) / 60f
        drawRect(track)
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
 * touch target both ways, which leaves the title no room, especially next to
 * a camera hole. So these only take the width they need, and fill the
 * header's height, which next to a cutout is already as tall as the hole.
 *
 * The default width is a bit more generous, since small buttons at the screen
 * edge (like back) are easy to miss. The extra comes out of the title, which
 * ellipsises.
 */
@Composable
fun HeaderButton(
    glyph: String,
    enabled: Boolean = true,
    /** Overrides the enabled/disabled pair, for a button also showing a mode. */
    color: Color? = null,
    /**
     * Narrower than the default, for a button that isn't its own whole
     * target. The editor's back arrow is one: the title next to it also goes
     * back, so the arrow only needs to be visible. It's a parameter because
     * the size is applied after the caller's modifier and can't be overridden
     * by it.
     */
    width: Dp = 42.dp,
    modifier: Modifier = Modifier,
    /** What TalkBack calls it, since the glyph isn't a word. */
    description: String? = null,
    state: String? = null,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier.size(width = width, height = LocalHeaderBand.current)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (description != null) Modifier.button(description, state) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = color ?: if (enabled) c.text else c.textFaint, fontSize = 14.sp) }
}

/** The same, for a control that needs a word rather than a glyph. */
@Composable
fun HeaderTextButton(
    label: String,
    color: Color = Acid.colors.textMid,
    enabled: Boolean = true,
    /** What TalkBack says when the label is just a number. */
    description: String? = null,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        Modifier.height(LocalHeaderBand.current)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.button(description) else Modifier)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) color else c.textFaint, fontSize = 13.sp, maxLines = 1) }
}
