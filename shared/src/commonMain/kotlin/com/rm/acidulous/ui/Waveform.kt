package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.rm.acidulous.res.*

/**
 * The part of a sound the waveform shows, as a start and a span in fractions
 * of the whole.
 *
 * The waveform with its trim handles is shared by the pad editor and the
 * recorder. Fetching the shape is left to the caller, since a mounted pad
 * and a file on disk are different engine calls.
 */
@Immutable
data class WaveView(val from: Double = 0.0, val span: Double = 1.0) {
    val zoomed: Boolean get() = span < 0.999
}

/**
 * Draws min/max pairs into a box. The one place the app draws a waveform
 * (the trimmer, grid cells and tape lanes all use it), so they all look the
 * same. Each caller decides what the horizontal axis means.
 *
 * Columns [c0] until [c1] of [shape] are laid across [x0] until [x1]. Drawn
 * as rectangles, since a column wider than a pixel is a block.
 */
fun DrawScope.drawShape(
    shape: List<Float>, c0: Int, c1: Int, x0: Float, x1: Float, mid: Float, half: Float, colour: Color,
) {
    val columns = c1 - c0
    if (columns <= 0 || x1 <= x0 || shape.size < 2) return
    val colW = (x1 - x0) / columns
    for (i in 0 until columns) {
        val k = (c0 + i) * 2
        if (k + 1 >= shape.size) break
        val lo = shape[k].coerceIn(-1f, 1f)
        val hi = shape[k + 1].coerceIn(-1f, 1f)
        val top = mid - hi * half
        val bottom = mid - lo * half
        drawRect(colour, Offset(x0 + i * colW, top), Size(max(1f, colW), max(1f, bottom - top)))
    }
}

@Composable
fun Waveform(
    /** Min/max pairs, as `sampleShape` and `fileShape` both return them. */
    shape: FloatArray,
    /** The total length, which sets how far a pinch can zoom in. */
    frames: Int,
    view: WaveView,
    onView: (WaveView) -> Unit,
    /** The trim, as fractions of the whole. */
    start: Float,
    end: Float,
    onStart: (Float) -> Unit,
    onEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown when there's nothing to draw. */
    empty: String = "",
    /** How far playback has got, as a fraction of the whole; below 0 when not playing. */
    playhead: Float = -1f,
) {
    val c = Acid.colors
    var dragging by remember { mutableStateOf(0) } // -1 start, 1 end, 0 nothing
    // The gesture is keyed on the sample, not these, so it survives a drag
    // changing them. It reads them through these updated states.
    val viewState by rememberUpdatedState(view)
    val startState by rememberUpdatedState(start)
    val endState by rememberUpdatedState(end)
    val setView by rememberUpdatedState(onView)
    val setStart by rememberUpdatedState(onStart)
    val setEnd by rememberUpdatedState(onEnd)

    Box(
        modifier.pointerInput(frames) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat().coerceAtLeast(1f)

                /** Where on the sample an x on screen points. */
                fun atOf(x: Float): Float =
                    (viewState.from + (x / w).coerceIn(0f, 1f) * viewState.span)
                        .toFloat().coerceIn(0f, 1f)

                // Nothing moves until the gesture is decided, so the first finger of a
                // pinch can't drag a handle. Past the slop it's a drag, a second
                // finger makes it a zoom, and a release without either is a tap that
                // moves the nearer handle there.
                val slop = viewConfiguration.touchSlop
                var kind = 0 // 0 undecided, 1 drag a handle, 2 two fingers, 3 tapped
                while (kind == 0) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) { kind = 2; break }
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: run { kind = 3; null } ?: break
                    if (!ch.pressed) { kind = 3; break }
                    if ((ch.position - down.position).getDistance() > slop) kind = 1
                }

                if (kind == 2) {
                    // Pinch to zoom, two fingers to scroll, like the roll and the
                    // drum grid.
                    var lastSpan = 0f
                    var lastMid = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val on = event.changes.filter { it.pressed }
                        if (on.size < 2) break
                        val a = on[0].position.x
                        val z = on[1].position.x
                        val gap = abs(a - z).coerceAtLeast(1f)
                        val mid = (a + z) / 2f
                        if (lastSpan > 0f) {
                            val now = viewState
                            // Zoom about the midpoint, so what's between the fingers
                            // stays there.
                            val anchor = now.from + (mid / w) * now.span
                            val floor = if (frames > 0) (64.0 / frames).coerceAtMost(0.5) else 0.001
                            val next = (now.span * (lastSpan / gap)).coerceIn(floor, 1.0)
                            var from = anchor - (mid / w) * next
                            // And pan by however far the pair moved.
                            from -= ((mid - lastMid) / w) * next
                            setView(WaveView(from.coerceIn(0.0, (1.0 - next).coerceAtLeast(0.0)), next))
                        }
                        lastSpan = gap
                        lastMid = mid
                        on.forEach { it.consume() }
                    }
                } else if (kind == 1) {
                    // Whichever handle is nearer, so a drag doesn't have to start
                    // exactly on a two pixel line.
                    val at = atOf(down.position.x)
                    dragging = if (abs(at - startState) <= abs(at - endState)) -1 else 1
                    if (dragging < 0) setStart(at) else setEnd(at)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            val to = atOf(change.position.x)
                            if (dragging < 0) setStart(to) else setEnd(to)
                            change.consume()
                        }
                    }
                    dragging = 0
                } else {
                    val at = atOf(down.position.x)
                    if (abs(at - startState) <= abs(at - endState)) setStart(at) else setEnd(at)
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(c.panel, size = size)
            val mid = size.height / 2f
            if (shape.isEmpty()) return@Canvas

            // Relative to the view, not the whole sample. A handle off the left
            // edge gets a negative x and must not be clamped onto the edge.
            fun xOf(at: Float): Float = ((at - view.from) / view.span).toFloat() * size.width
            val lo = xOf(min(start, end))
            val hi = xOf(max(start, end))
            // The area outside the trim first, so the part that plays is drawn on
            // top.
            val shadeTo = lo.coerceIn(0f, size.width)
            val shadeFrom = hi.coerceIn(0f, size.width)
            drawRect(c.bgDeep.copy(alpha = 0.55f), Offset(0f, 0f), Size(shadeTo, size.height))
            drawRect(
                c.bgDeep.copy(alpha = 0.55f), Offset(shadeFrom, 0f),
                Size(size.width - shadeFrom, size.height),
            )

            val cols = shape.size / 2
            for (i in 0 until cols) {
                val x = size.width * i / cols
                val top = mid - shape[i * 2 + 1] * mid * 0.95f
                val bottom = mid - shape[i * 2] * mid * 0.95f
                val inside = x in shadeTo..shadeFrom
                drawLine(
                    if (inside) c.accent else c.textDim,
                    Offset(x, top), Offset(x, max(bottom, top + 1f)),
                    strokeWidth = size.width / cols + 0.5f,
                )
            }
            drawLine(c.textDim.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1f)
            // The playhead while the file plays, relative to the view, and only
            // if it's in view.
            if (playhead >= 0f) {
                val x = xOf(playhead)
                if (x in 0f..size.width) drawLine(c.text, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            }
            for ((x, mark) in listOf(lo to -1, hi to 1)) {
                if (x < -2f || x > size.width + 2f) continue // off this window
                drawLine(
                    c.teal, Offset(x, 0f), Offset(x, size.height),
                    if (dragging == mark) 2.dp.toPx() else 1.dp.toPx(),
                )
            }

            // Where in the sample this view is, since anything you can scroll
            // shows a position bar (see ui/Scrollbar.kt).
            if (view.zoomed) {
                val trackY = size.height - 3f
                drawLine(c.scrollbar.copy(alpha = 0.3f), Offset(0f, trackY), Offset(size.width, trackY), 3f)
                val a = (view.from * size.width).toFloat()
                val len = (view.span * size.width).toFloat().coerceAtLeast(12f)
                drawLine(c.scrollbar, Offset(a, trackY), Offset((a + len).coerceAtMost(size.width), trackY), 3f)
            }
        }
        if (shape.isEmpty() && empty.isNotEmpty()) {
            Text(
                empty,
                color = c.textDim, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // The trim ends as controls TalkBack can adjust, since dragging them
        // doesn't work there.
        if (shape.isNotEmpty()) {
            androidx.compose.foundation.layout.Row(Modifier.matchParentSize()) {
                Box(
                    Modifier.weight(1f).fillMaxSize().adjustable(
                        stringResource(Res.string.a11y_trim_start), "%.0f%%".format(start * 100f), start,
                    ) { setStart(it.coerceAtMost(endState)) },
                )
                Box(
                    Modifier.weight(1f).fillMaxSize().adjustable(
                        stringResource(Res.string.a11y_trim_end), "%.0f%%".format(end * 100f), end,
                    ) { setEnd(it.coerceAtLeast(startState)) },
                )
            }
        }
    }
}
