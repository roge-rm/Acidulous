package com.rm.acidulous.ui

import com.rm.acidulous.util.Math

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors
import com.rm.acidulous.res.*

/**
 * A knob: 270° arc, vertical drag (200 px for the full range), label above,
 * value below. Value is 0..1, the caller formats it. Reports gesture start and
 * end so a turn becomes one undo step.
 */
/**
 * Whether this press is a hold or the start of a move.
 *
 * Nothing is decided until the finger moves past the slop or the timeout runs
 * out, so no onStart fires and no undo entry is opened for a resting thumb.
 * Lifting early is a tap and is neither.
 *
 * Shared by the knob and the fader. The fader jumps to where it's touched, so
 * without this a hold would move the value before resetting it.
 */
internal suspend fun AwaitPointerEventScope.wasHeld(
    down: PointerInputChange,
    slop: Float,
    timeoutMillis: Long,
): Boolean = withTimeoutOrNull(timeoutMillis) {
    var moved = false
    while (!moved) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return@withTimeoutOrNull false // lifted: a tap
        moved = event.changes.any { (it.position - down.position).getDistance() > slop }
    }
    true // travelled: a move
} == null

/**
 * Consume the rest of a gesture that's already been handled, so the release does
 * nothing.
 */
internal suspend fun AwaitPointerEventScope.swallowRest() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) break
    }
}

@Composable
fun Knob(
    label: String,
    value: Float,
    display: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    accent: Color = Acid.colors.teal,
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
    /**
     * Hold to put it back where it was when this panel opened. Null when
     * there's nothing to go back to.
     *
     * Modifier.mappable uses the same gesture to clear a mapping, but they
     * don't conflict: in mapping mode mappable consumes the touch on the
     * Initial pass and this never runs.
     */
    onReset: (() -> Unit)? = null,
    /** Automated by a lane in the open clip, marked with ∿ on the dial. */
    automated: Boolean = false,
    /** Locked by steps in the open clip, marked with ◆. */
    locked: Boolean = false,
    /** How many values it has, for TalkBack to step through. 0 for continuous. */
    steps: Int = 0,
    /** A hold opens a list instead of resetting, named for TalkBack. */
    holdOpensList: Boolean = false,
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val reset by rememberUpdatedState(onReset)
    val current by rememberUpdatedState(value)
    // c is the centre point in the drawing below, so the palette is col here.
    val col = Acid.colors
    val measurer = knobTextMeasurer()
    val base = LocalTextStyle.current
    val small = remember(base) { base.merge(TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)) }
    val markStyle = remember(base) { base.merge(TextStyle(fontSize = 11.sp)) }
    val mark = if (locked) "\u25C6" else if (automated) "\u223F" else null
    // What measuring worked out, for drawing and for telling a turn from a
    // touch on the words.
    val laid = remember { KnobLayout() }
    val resetName = stringResource(if (holdOpensList) Res.string.a11y_choose else Res.string.a11y_reset)
    val state = when {
        locked -> stringResource(Res.string.a11y_locked, display)
        automated -> stringResource(Res.string.a11y_automated, display)
        else -> display
    }
    // One node, drawn: the label, the dial and the value. As a column of two
    // texts and a canvas, a knob cost several milliseconds to build and a
    // panel has dozens. The words come from one shared cache, and "tune" or
    // "decay" is laid out once for every knob that says it.
    Layout(
        modifier.adjustable(
            label, state, value, steps = (steps - 2).coerceAtLeast(0),
            actions = onReset?.let { listOf(action(resetName, it)) } ?: emptyList(),
        ) { v -> cb.first(); cb.second(v); cb.third() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Only the dial turns. The words above and below it pass
                    // the touch on, to a scrolling row say.
                    if (!laid.dial.contains(down.position)) return@awaitEachGesture
                    val startValue = current
                    val startY = down.position.y
                    // Decide hold vs turn before starting the gesture. Nothing
                    // is opened (no onStart, no undo entry) until the finger
                    // moves past the slop or the timeout runs out, otherwise
                    // every resting thumb would leave a gesture on the editor.
                    if (reset != null &&
                        wasHeld(down, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
                    ) {
                        reset?.invoke()
                        swallowRest()
                        return@awaitEachGesture
                    }
                    cb.first()
                    drag(down.id) { change ->
                        change.consume()
                        val dv = (startY - change.position.y) / 200f
                        cb.second((startValue + dv).coerceIn(0f, 1f))
                    }
                    cb.third()
                }
            }
            .drawBehind {
                // Named here so a new label, value or mark draws again, not
                // only a new size. Measuring has laid them out by now.
                laid.drawn = Triple(label, display, mark)
                val top = laid.label ?: return@drawBehind
                drawText(top, col.textDim, Offset(centred(this.size.width, top.size.width), 0f))
                val d = laid.dial
                val r = d.width / 2f
                val c = d.center
                val stroke = r * 0.22f
                val start = 135f
                val sweep = 270f
                val v = value.coerceIn(0f, 1f)
                val arcAt = Offset(c.x - r + stroke, c.y - r + stroke)
                val arcSize = Size((r - stroke) * 2f, (r - stroke) * 2f)
                drawArc(col.raised, start, sweep, false, arcAt, arcSize, style = Stroke(stroke))
                drawArc(accent, start, sweep * v, false, arcAt, arcSize, style = Stroke(stroke))
                val a = Math.toRadians((start + sweep * v).toDouble())
                val inner = r * 0.35f
                val outer = r - stroke * 1.6f
                drawLine(col.knobPointer, Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()), 3f)
                laid.mark?.let { drawText(it, col.accent, Offset(d.right - it.size.width, d.top)) }
                laid.value?.let { drawText(it, col.accent, Offset(centred(this.size.width, it.size.width), d.bottom)) }
            },
    ) { _, constraints ->
        // A plain density, not this scope: the cache matches on it, and every
        // knob's scope is a different object.
        val plain = Density(density, fontScale)
        fun text(s: String, style: TextStyle, width: Int = Constraints.Infinity) = measurer.measure(
            s, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = width), layoutDirection = layoutDirection, density = plain,
        )
        val dialPx = size.roundToPx()
        var top = text(label, small)
        var bottom = text(display, small)
        val w = maxOf(dialPx, top.size.width, bottom.size.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        // Cut short with an ellipsis only when the knob is given less room than its words.
        if (top.size.width > w) top = text(label, small, w)
        if (bottom.size.width > w) bottom = text(display, small, w)
        val h = (top.size.height + dialPx + bottom.size.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        val left = centred(w.toFloat(), dialPx)
        laid.label = top
        laid.value = bottom
        laid.mark = mark?.let { text(it, markStyle) }
        laid.dial = Rect(left, top.size.height.toFloat(), left + dialPx, (top.size.height + dialPx).toFloat())
        layout(w, h) {}
    }
}

/** Where something [width] wide starts, centred in [space], on a whole pixel as a centred column puts it. */
private fun centred(space: Float, width: Int): Float = ((space - width) / 2f).roundToInt().toFloat()

/** What a knob's measuring worked out, read when it's drawn and touched. */
private class KnobLayout {
    /** What was last drawn. Read nowhere: see the drawing. */
    var drawn: Triple<String, String, String?>? = null
    var label: TextLayoutResult? = null
    var value: TextLayoutResult? = null
    var mark: TextLayoutResult? = null
    var dial = Rect.Zero
}

/**
 * The text measurer every knob shares, with a cache big enough for a panel's
 * words and values. By font resolver, since a window has its own.
 */
@Composable
private fun knobTextMeasurer(): TextMeasurer {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return knobMeasurers.getOrPut(resolver) {
        if (knobMeasurers.size >= 4) knobMeasurers.remove(knobMeasurers.keys.first())
        TextMeasurer(resolver, density, direction, cacheSize = 512)
    }
}

/** Only the UI thread touches it. */
private val knobMeasurers = LinkedHashMap<FontFamily.Resolver, TextMeasurer>()

/**
 * A knob for a whole number, for windows rather than machines. It only reports
 * when the number changes, so what it drives moves a step at a time.
 *
 * With [choices] (one name per step from the bottom of [range]) holding it
 * opens them as a list, so an exact value is easy to pick.
 */
@Composable
internal fun CountKnob(
    label: String, value: Int, range: IntRange, display: String = "$value", accent: Color = Acid.colors.teal,
    /** Wider than a knob, for a value that's a word like a scale name. */
    width: Dp? = null,
    choices: List<String>? = null,
    /** Around a drag, for a caller that makes the drag one undo step. */
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    /**
     * A pick from the list, when it needs handling differently from a drag
     * step.
     */
    pick: ((Int) -> Unit)? = null,
    set: (Int) -> Unit,
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        Knob(
            label = label, value = (value - range.first).toFloat() / span, display = display,
            modifier = if (width != null) Modifier.width(width) else panelKnobWidth(), accent = accent,
            onStart = onStart,
            onChange = { v -> val n = range.first + (v * span).roundToInt(); if (n != value) set(n.coerceIn(range)) },
            onEnd = onEnd,
            onReset = if (choices != null) ({ open = true }) else null,
            steps = span + 1,
            holdOpensList = choices != null,
        )
        if (choices != null) {
            val scroll = androidx.compose.foundation.rememberScrollState()
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ScaledMenu(scroll) {
                    choices.forEachIndexed { i, name ->
                        val v = range.first + i
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    name, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    color = if (v == value) Acid.colors.accent else Acid.colors.text,
                                )
                            },
                            onClick = { open = false; if (v != value) (pick ?: set)(v) },
                        )
                    }
                }
            }
            // Open scrolled to the current value, not the top of a list of 128.
            androidx.compose.runtime.LaunchedEffect(open) {
                if (!open) return@LaunchedEffect
                val max = androidx.compose.runtime.snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
                val at = (value - range.first).toFloat() / (choices.size - 1).coerceAtLeast(1)
                scroll.scrollTo((max * at).roundToInt())
            }
        }
    }
}
