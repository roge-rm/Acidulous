package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
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

/**
 * A knob: 270° arc, vertical drag (200 px for the full range), label above,
 * value below. Value is 0..1; the caller formats it. Reports gesture start
 * and end so the document can coalesce a turn into one undo step.
 */
/**
 * Was this press a hold, or the beginning of a move?
 *
 * The only thing that separates them is whether the finger travelled before
 * the clock ran out, so **nothing is decided until one of the two wins** -
 * which is why no `onStart` fires first and no undo entry is opened for a
 * thumb somebody rested on a control. Lifting early is a tap and is neither.
 *
 * Shared by the knob and the fader because the fader needs it more: it jumps
 * to wherever you touched it, so a hold that had not been ruled out first
 * would move the value before putting it back.
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

/** Eat the rest of a gesture we have already acted on, so the release lands nowhere. */
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
     * Hold it to put it back where it was when this panel opened.
     *
     * Null on a knob that has nothing to go back to. It is the same gesture
     * `Modifier.mappable` uses to clear a control's mapping, and there is no
     * argument between them: while mapping mode is on, `mappable` consumes the
     * touch on the Initial pass and this loop never runs at all.
     */
    onReset: (() -> Unit)? = null,
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val reset by rememberUpdatedState(onReset)
    val current by rememberUpdatedState(value)
    // `c` is the centre point inside the Canvas below, so the palette takes
    // the other name here.
    val col = Acid.colors
    val dial: @Composable () -> Unit = {
        Canvas(
            Modifier.size(size).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startValue = current
                    val startY = down.position.y
                    // **The long press is decided before the gesture begins.**
                    //
                    // A hold has to be told from a turn, and the only thing
                    // that separates them is whether the finger moved before
                    // the timeout. So nothing is opened - no `onStart`, no
                    // undo entry - until one of the two has won: a movement
                    // past the touch slop, or the clock. Calling `onStart`
                    // first and taking it back later would leave a gesture on
                    // the editor for every knob anybody rested a thumb on.
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
            },
        ) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val stroke = r * 0.22f
            val start = 135f
            val sweep = 270f
            drawArc(col.raised, start, sweep, false, Offset(c.x - r + stroke, c.y - r + stroke),
                Size((r - stroke) * 2f, (r - stroke) * 2f), style = Stroke(stroke))
            drawArc(accent, start, sweep * value.coerceIn(0f, 1f), false, Offset(c.x - r + stroke, c.y - r + stroke),
                Size((r - stroke) * 2f, (r - stroke) * 2f), style = Stroke(stroke))
            val a = Math.toRadians((start + sweep * value.coerceIn(0f, 1f)).toDouble())
            val inner = r * 0.35f
            val outer = r - stroke * 1.6f
            drawLine(col.knobPointer, Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()), 3f)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        dial()
        Text(
            display, color = Acid.colors.accent, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
