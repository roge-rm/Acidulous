package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * It is a screen of its own for the same reason Nexus's graph is: the
 * waveform wants the height, and the strip under the piano roll has none to
 * give. See [[editor-vertical-space]] - what that rule protects is the notes,
 * and this is not asking for their space, it is asking for a different page.
 */
@Composable
fun SampleScreen(
    track: Track,
    trackIndex: Int,
    pad: Int,
    editor: SongEditor,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
    LaunchedEffect(trackIndex, pad, rel) {
        val out = FloatArray(columns * 2)
        val got = NativeEngine.sampleShape(trackIndex, pad, out)
        shape = if (got > 0) out else FloatArray(0)
        meta = NativeEngine.sampleInfo(trackIndex, pad)
    }

    val startDef = b.infoOf(n("start"))
    val endDef = b.infoOf(n("end"))
    val start = startDef?.map(b.value(n("start"))) ?: 0f
    val end = endDef?.map(b.value(n("end"))) ?: 1f

    val seconds = meta.split('|').getOrNull(1)?.toFloatOrNull()?.let { it / 48000f } ?: 0f
    val name = meta.substringBefore('|').ifEmpty { "no sample" }

    Column(modifier.fillMaxSize().background(c.bgDeep)) {
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp,
            ),
            spacing = 2.dp,
        ) {
            HeaderButton("◀") { onBack() }
            Text(
                "pad ${pad + 1} · $name",
                color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.flexible().padding(horizontal = 4.dp), maxLines = 1,
            )
            HeaderTextButton("play") { NativeEngine.noteOn(trackIndex, 36 + pad, 110) }
            HeaderTextButton("all") {
                if (startDef != null) b.set(startDef.name, startDef.unmap(0f))
                if (endDef != null) b.set(endDef.name, endDef.unmap(1f))
            }
        }

        // --- the waveform ---------------------------------------------------
        var width by remember { mutableStateOf(1f) }
        var dragging by remember { mutableStateOf(0) } // -1 start, 1 end, 0 nothing
        Box(
            Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                .pointerInput(pad, shape.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val at = (down.position.x / w).coerceIn(0f, 1f)
                        // Whichever handle is nearer, so a drag never has to
                        // begin exactly on a two-pixel line.
                        dragging = if (abs(at - start) <= abs(at - end)) -1 else 1
                        val def = if (dragging < 0) startDef else endDef
                        if (def != null) b.set(def.name, def.unmap(at))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                val x = (change.position.x / w).coerceIn(0f, 1f)
                                val d = if (dragging < 0) startDef else endDef
                                if (d != null) b.set(d.name, d.unmap(x))
                                change.consume()
                            }
                        }
                        dragging = 0
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                width = size.width
                drawRect(c.panel, size = size)
                val mid = size.height / 2f
                if (shape.isEmpty()) return@Canvas

                val lo = min(start, end) * size.width
                val hi = max(start, end) * size.width
                // Outside the trim first, so the part that plays is drawn on
                // top of it and reads as the subject rather than as a hole.
                drawRect(c.bgDeep.copy(alpha = 0.55f), Offset(0f, 0f),
                         androidx.compose.ui.geometry.Size(lo, size.height))
                drawRect(c.bgDeep.copy(alpha = 0.55f), Offset(hi, 0f),
                         androidx.compose.ui.geometry.Size(size.width - hi, size.height))

                val cols = shape.size / 2
                for (i in 0 until cols) {
                    val x = size.width * i / cols
                    val top = mid - shape[i * 2 + 1] * mid * 0.95f
                    val bottom = mid - shape[i * 2] * mid * 0.95f
                    val inside = x in lo..hi
                    drawLine(
                        if (inside) c.accent else c.textDim,
                        Offset(x, top), Offset(x, max(bottom, top + 1f)),
                        strokeWidth = size.width / cols + 0.5f,
                    )
                }
                drawLine(c.textDim.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1f)
                for ((x, mark) in listOf(lo to -1, hi to 1)) {
                    drawLine(c.teal, Offset(x, 0f), Offset(x, size.height), if (dragging == mark) 4f else 2f)
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
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
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
        }

        // --- the rest of the pad, so this is a page and not a detour --------
        Row(
            Modifier.fillMaxWidth().padding(8.dp).horizontalScrollWithBar(rememberScrollState()),
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
