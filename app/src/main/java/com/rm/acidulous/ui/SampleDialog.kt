package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One pad's sample, with its start and end set against the picture.
 *
 * The two numbers this exists for have always been on the panel as knobs, and
 * a knob is the wrong instrument for them: `start` and `end` are *places in a
 * sound*, and setting a place by turning a dial and listening is guesswork
 * with an audible cost each time round. Here they are where they are.
 *
 * A window rather than a screen. It began as a screen, on the grounds that a
 * waveform wants height - but everything else in the app that opens over what
 * you are doing is a window, and this is the same kind of errand: you come to
 * it from a pad, move two handles and go back. Being a screen also cost the
 * editor underneath, which vanished while you were trimming the sound you
 * were trimming *for* it. See [[dialog-style]].
 */
@Composable
fun SampleDialog(
    track: Track,
    trackIndex: Int,
    pad: Int,
    editor: SongEditor,
    onBack: () -> Unit,
) {
    val c = Acid.colors
    val info = remember(trackIndex, track.machine.type) { NativeEngine.machineParamInfo(track.machine.type) }
    val b = rememberParamBinding(trackIndex, track.machine.type, info, editor)
    fun n(name: String) = "p%02d_%s".format(pad, name)

    // The shape, fetched when the pad or its file changes and not per frame:
    // it walks the whole sample, which for thirty seconds is three million
    // reads. `columns` is generous and fixed - resampling a drawn waveform to
    // the exact pixel width would be a redraw on every rotation for a
    // difference nobody can see.
    val columns = 900
    var shape by remember(trackIndex, pad) { mutableStateOf(FloatArray(0)) }
    val rel = track.machine.settings[n("sample")] ?: track.machine.settings["slice_sample"]
    var meta by remember(trackIndex, pad, rel) { mutableStateOf("") }
    val frames = meta.split('|').getOrNull(1)?.toIntOrNull() ?: 0

    /**
     * Which slice of the sample the waveform is showing, as fractions of it.
     *
     * Doubles, not floats. At twenty-eight million frames a float fraction
     * resolves to about two of them, and this is the number that decides how
     * far in you can go - going blunt at the deep end is the wrong end to go
     * blunt at.
     */
    var viewFrom by remember(trackIndex, pad) { mutableStateOf(0.0) }
    var viewSpan by remember(trackIndex, pad) { mutableStateOf(1.0) }
    val zoomed = viewSpan < 0.999

    LaunchedEffect(trackIndex, pad, rel, viewFrom, viewSpan) {
        // Off the main thread: it walks every frame in the window, and a
        // slice source may be a ten minute track - twenty-eight million of
        // them, which is a visible stall if it runs where the frames are
        // drawn. Re-fetched on a zoom, which is the whole point: the engine
        // shapes the window, so the detail arrives with the magnification
        // instead of the same blur getting bigger.
        val out = FloatArray(columns * 2)
        val total = NativeEngine.sampleInfo(trackIndex, pad).split('|').getOrNull(1)?.toIntOrNull() ?: 0
        val a = (viewFrom * total).toInt().coerceIn(0, maxOf(0, total - 1))
        val z = ((viewFrom + viewSpan) * total).toInt().coerceIn(a + 1, maxOf(1, total))
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            NativeEngine.sampleShape(trackIndex, pad, out, if (total > 0) a else 0, if (total > 0) z else 0)
        }
        shape = if (got > 0) out else FloatArray(0)
        meta = NativeEngine.sampleInfo(trackIndex, pad)
    }

    val startDef = b.infoOf(n("start"))
    val endDef = b.infoOf(n("end"))
    val start = startDef?.map(b.value(n("start"))) ?: 0f
    val end = endDef?.map(b.value(n("end"))) ?: 1f

    val seconds = meta.split('|').getOrNull(1)?.toFloatOrNull()?.let { it / 48000f } ?: 0f
    val name = meta.substringBefore('|').ifEmpty { "no sample" }

    PlainDialog(
        title = "Pad ${pad + 1}",
        onDismiss = onBack,
        dismissLabel = "Done",
        spacing = 6.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                color = c.textHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            androidx.compose.material3.TextButton(
                onClick = { NativeEngine.noteOn(trackIndex, 36 + pad, 110) },
            ) { Text("play", color = c.accent, fontSize = 12.sp) }
            androidx.compose.material3.TextButton(onClick = {
                if (startDef != null) b.set(startDef.name, startDef.unmap(0f))
                if (endDef != null) b.set(endDef.name, endDef.unmap(1f))
            }) { Text("all", color = c.accent, fontSize = 12.sp) }
            // Only where there is something to come back from. A zoom with no
            // way out but pinching back is a trap, and a permanent button for
            // it would be a word on the row saying nothing most of the time.
            if (zoomed) {
                androidx.compose.material3.TextButton(onClick = { viewFrom = 0.0; viewSpan = 1.0 }) {
                    Text("fit", color = c.teal, fontSize = 12.sp)
                }
            }
        }

        // --- the waveform ---------------------------------------------------
        var width by remember { mutableStateOf(1f) }
        var dragging by remember { mutableStateOf(0) } // -1 start, 1 end, 0 nothing
        Box(
            Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(pad, frames) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat().coerceAtLeast(1f)

                        /** Where on the *sample* an x on screen points. */
                        fun atOf(x: Float): Float =
                            (viewFrom + (x / w).coerceIn(0f, 1f) * viewSpan).toFloat().coerceIn(0f, 1f)

                        // **What kind of gesture this is, before it moves
                        // anything.** A handle used to be grabbed on the down
                        // event, which made the first finger of a pinch drag
                        // the trim somewhere before the second one landed.
                        // So nothing happens until the gesture has declared
                        // itself: past the slop it is a drag, a second finger
                        // makes it a zoom, and a release without either is a
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
                            // Pinch to zoom, two fingers to scroll. The same
                            // pair of gestures the roll and the drum grid
                            // take, so the hand already knows them.
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
                                    // Zoom about the midpoint, so whatever is
                                    // between the fingers stays between them.
                                    val anchor = viewFrom + (mid / w) * viewSpan
                                    val floor = if (frames > 0) (64.0 / frames).coerceAtMost(0.5) else 0.001
                                    val next = (viewSpan * (lastSpan / gap)).coerceIn(floor, 1.0)
                                    viewFrom = anchor - (mid / w) * next
                                    viewSpan = next
                                    // And pan by however far the pair moved.
                                    viewFrom -= ((mid - lastMid) / w) * viewSpan
                                    viewFrom = viewFrom.coerceIn(0.0, (1.0 - viewSpan).coerceAtLeast(0.0))
                                }
                                lastSpan = gap
                                lastMid = mid
                                on.forEach { it.consume() }
                            }
                        } else if (kind == 1) {
                            // Whichever handle is nearer, so a drag never has
                            // to begin exactly on a two-pixel line.
                            val at = atOf(down.position.x)
                            dragging = if (abs(at - start) <= abs(at - end)) -1 else 1
                            val def = if (dragging < 0) startDef else endDef
                            if (def != null) b.set(def.name, def.unmap(at))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChange() != Offset.Zero) {
                                    val d = if (dragging < 0) startDef else endDef
                                    if (d != null) b.set(d.name, d.unmap(atOf(change.position.x)))
                                    change.consume()
                                }
                            }
                            dragging = 0
                        } else {
                            val at = atOf(down.position.x)
                            val which = if (abs(at - start) <= abs(at - end)) startDef else endDef
                            if (which != null) b.set(which.name, which.unmap(at))
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                width = size.width
                drawRect(c.panel, size = size)
                val mid = size.height / 2f
                if (shape.isEmpty()) return@Canvas

                // Through the window, not against the whole sample: at
                // eight times in, a trim handle off the left is at a negative
                // x and must draw there rather than be clamped onto the edge,
                // or it reads as a handle sitting where it is not.
                fun xOf(at: Float): Float = ((at - viewFrom) / viewSpan).toFloat() * size.width
                val lo = xOf(min(start, end))
                val hi = xOf(max(start, end))
                // Outside the trim first, so the part that plays is drawn on
                // top of it and reads as the subject rather than as a hole.
                val shadeTo = lo.coerceIn(0f, size.width)
                val shadeFrom = hi.coerceIn(0f, size.width)
                drawRect(c.bgDeep.copy(alpha = 0.55f), Offset(0f, 0f),
                         androidx.compose.ui.geometry.Size(shadeTo, size.height))
                drawRect(c.bgDeep.copy(alpha = 0.55f), Offset(shadeFrom, 0f),
                         androidx.compose.ui.geometry.Size(size.width - shadeFrom, size.height))

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
                    drawLine(c.teal, Offset(x, 0f), Offset(x, size.height), if (dragging == mark) 4f else 2f)
                }

                // Where in the sample this window is. The house rule is that
                // anything you can scroll says so - see ui/Scrollbar.kt - and
                // a waveform zoomed eight times in with no bar is a picture
                // of a sound with no way to tell which part.
                if (zoomed) {
                    val trackY = size.height - 3f
                    drawLine(c.scrollbar.copy(alpha = 0.3f), Offset(0f, trackY), Offset(size.width, trackY), 3f)
                    val a = (viewFrom * size.width).toFloat()
                    val len = (viewSpan * size.width).toFloat().coerceAtLeast(12f)
                    drawLine(c.scrollbar, Offset(a, trackY), Offset((a + len).coerceAtMost(size.width), trackY), 3f)
                }
            }
            if (shape.isEmpty()) {
                Text(
                    "This pad has no sample. Load one from the machine panel.",
                    color = c.textDim, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // Where the trim actually falls, in seconds - the number a player
        // needs when they are matching a slice to a beat.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val from = min(start, end)
            val to = max(start, end)
            Text("start %.3fs".format(from * seconds), color = c.textDim, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            Text("end %.3fs".format(to * seconds), color = c.textDim, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            Text("plays %.3fs".format((to - from) * seconds), color = c.textHi, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            if (zoomed) {
                // How far in, and how much of the sound is on screen. The
                // multiple is the number that answers "am I looking at the
                // attack or at the bar"; the seconds answer "of what".
                Text(
                    "×%.0f · %.3fs".format(1.0 / viewSpan, viewSpan * seconds),
                    color = c.teal, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        // --- the rest of the pad, so this is a page and not a detour --------
        Row(
            Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Group("sample") {
                PanelKnob(b, n("start"), "start")
                PanelKnob(b, n("end"), "end")
                PanelKnob(b, n("pitch"), "pitch", c.accent)
                PanelSwitch(b, n("reverse"), listOf("fwd", "rev"), "reverse")
                PanelSwitch(b, n("play"), listOf("once", "loop", "hold"), "play")
            }
            Group("amp") {
                PanelKnob(b, n("decay"), "decay")
                PanelKnob(b, n("level"), "level")
                PanelKnob(b, n("pan"), "pan")
            }
            Group("tone") {
                PanelKnob(b, n("cutoff"), "cutoff", c.accent)
                PanelKnob(b, n("reso"), "reso", c.accent)
                PanelSwitch(b, n("mode"), listOf("lp", "bp"), "mode")
                PanelKnob(b, n("crush"), "crush", c.pink)
            }
        }
    }
}
