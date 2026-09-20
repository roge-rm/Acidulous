package com.rm.acidulous.ui

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A picture of a sound, with two handles and a window onto it.
 *
 * It lived inside the pad editor, where it was the only waveform in the app.
 * There are two now - the recording screen trims a file that is not mounted on
 * anything - and two loops drawing the same sound is one of them being subtly
 * different with nobody able to say which. The house rule is that the
 * vocabulary lives in one file.
 *
 * What it does *not* own is fetching the shape: a mounted pad and a file on
 * disk are two different engine calls, and the caller knows which it has.
 */
@Immutable
data class WaveView(val from: Double = 0.0, val span: Double = 1.0) {
    val zoomed: Boolean get() = span < 0.999
}

@Composable
fun Waveform(
    /** Min/max pairs, as `sampleShape` and `fileShape` both return them. */
    shape: FloatArray,
    /** How long the whole thing is, which sets how far in a pinch may go. */
    frames: Int,
    view: WaveView,
    onView: (WaveView) -> Unit,
    /** The trim, as fractions of the whole. */
    start: Float,
    end: Float,
    onStart: (Float) -> Unit,
    onEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** What to say when there is nothing to draw. */
    empty: String = "",
) {
    val c = Acid.colors
    var dragging by remember { mutableStateOf(0) } // -1 start, 1 end, 0 nothing
    // Read here and captured by the gesture, which is keyed on the sample
    // rather than on these so that it survives a drag changing them.
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

                /** Where on the *sample* an x on screen points. */
                fun atOf(x: Float): Float =
                    (viewState.from + (x / w).coerceIn(0f, 1f) * viewState.span)
                        .toFloat().coerceIn(0f, 1f)

                // **What kind of gesture this is, before it moves anything.**
                // A handle used to be grabbed on the down event, which made
                // the first finger of a pinch drag the trim somewhere before
                // the second one landed. So nothing happens until the gesture
                // has declared itself: past the slop it is a drag, a second
                // finger makes it a zoom, and a release without either is a
                // tap that puts the nearer handle where it landed.
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
                    // Pinch to zoom, two fingers to scroll. The same pair of
                    // gestures the roll and the drum grid take, so the hand
                    // already knows them.
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
                            // Zoom about the midpoint, so whatever is between
                            // the fingers stays between them.
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
                    // Whichever handle is nearer, so a drag never has to begin
                    // exactly on a two-pixel line.
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

            // Through the window, not against the whole sample: at eight times
            // in, a trim handle off the left is at a negative x and must draw
            // there rather than be clamped onto the edge, or it reads as a
            // handle sitting where it is not.
            fun xOf(at: Float): Float = ((at - view.from) / view.span).toFloat() * size.width
            val lo = xOf(min(start, end))
            val hi = xOf(max(start, end))
            // Outside the trim first, so the part that plays is drawn on top
            // of it and reads as the subject rather than as a hole.
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
            for ((x, mark) in listOf(lo to -1, hi to 1)) {
                if (x < -2f || x > size.width + 2f) continue // off this window
                drawLine(
                    c.teal, Offset(x, 0f), Offset(x, size.height),
                    if (dragging == mark) 2.dp.toPx() else 1.dp.toPx(),
                )
            }

            // Where in the sample this window is. The house rule is that
            // anything you can scroll says so - see ui/Scrollbar.kt - and a
            // waveform zoomed eight times in with no bar is a picture of a
            // sound with no way to tell which part.
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
    }
}
