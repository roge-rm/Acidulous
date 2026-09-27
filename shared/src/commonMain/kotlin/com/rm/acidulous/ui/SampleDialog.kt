package com.rm.acidulous.ui

import com.rm.acidulous.util.format

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
import com.rm.acidulous.res.*

/**
 * One pad's sample, with its start and end set by dragging handles on the
 * waveform. Easier than setting them with the panel's knobs by ear.
 *
 * A window like the rest of the app's, opened from a pad, so the editor
 * stays visible underneath.
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

    // The shape, fetched when the pad or its file changes, not per frame,
    // since it walks the whole sample. `columns` is fixed and generous;
    // matching the exact pixel width would mean a refetch on every rotation.
    val columns = 900
    var shape by remember(trackIndex, pad) { mutableStateOf(FloatArray(0)) }
    val rel = track.machine.settings[n("sample")] ?: track.machine.settings["slice_sample"]
    var meta by remember(trackIndex, pad, rel) { mutableStateOf("") }
    val frames = meta.split('|').getOrNull(1)?.toIntOrNull() ?: 0

    /**
     * The slice of the sample the waveform shows, as fractions of it.
     *
     * Doubles, because at 28 million frames a float fraction only resolves to
     * about two frames, which limits how far you can zoom in.
     */
    var viewFrom by remember(trackIndex, pad) { mutableStateOf(0.0) }
    var viewSpan by remember(trackIndex, pad) { mutableStateOf(1.0) }
    val zoomed = viewSpan < 0.999

    LaunchedEffect(trackIndex, pad, rel, viewFrom, viewSpan) {
        // Off the main thread: it walks every frame in the window, and a slice
        // source may be a ten minute track, which would stall drawing.
        // Re-fetched on zoom so the engine gives real detail at every zoom level.
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
    val name = meta.substringBefore('|').ifEmpty { stringResource(Res.string.pad_no_sample) }

    PlainDialog(
        title = stringResource(Res.string.pad_title, pad + 1),
        onDismiss = onBack,
        dismissLabel = stringResource(Res.string.done),
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
            ) { Text(stringResource(Res.string.pad_play), color = c.accent, fontSize = 12.sp) }
            androidx.compose.material3.TextButton(onClick = {
                if (startDef != null) b.set(startDef.name, startDef.unmap(0f))
                if (endDef != null) b.set(endDef.name, endDef.unmap(1f))
            }) { Text(stringResource(Res.string.pad_all), color = c.accent, fontSize = 12.sp) }
            // Only shown when zoomed, so there's always a way back out.
            if (zoomed) {
                androidx.compose.material3.TextButton(onClick = { viewFrom = 0.0; viewSpan = 1.0 }) {
                    Text(stringResource(Res.string.pad_fit), color = c.teal, fontSize = 12.sp)
                }
            }
        }

        // --- the waveform ---------------------------------------------------
        //
        // Drawn by `ui/Waveform.kt`, which the recorder uses too. The numbers
        // come from a mounted pad's parameters, not a file.
        Waveform(
            shape = shape,
            frames = frames,
            view = WaveView(viewFrom, viewSpan),
            onView = { viewFrom = it.from; viewSpan = it.span },
            start = start,
            end = end,
            onStart = { at -> startDef?.let { b.set(it.name, it.unmap(at)) } },
            onEnd = { at -> endDef?.let { b.set(it.name, it.unmap(at)) } },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            empty = stringResource(Res.string.pad_empty),
        )

        // Where the trim falls in seconds, for matching a slice to a beat.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val from = min(start, end)
            val to = max(start, end)
            Text(stringResource(Res.string.pad_start, from * seconds), color = c.textDim, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            Text(stringResource(Res.string.pad_end, to * seconds), color = c.textDim, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            Text(stringResource(Res.string.pad_plays, (to - from) * seconds), color = c.textHi, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            if (zoomed) {
                // Zoom as a multiple, and how many seconds are on screen.
                Text(
                    "×%.0f · %.3fs".format(1.0 / viewSpan, viewSpan * seconds),
                    color = c.teal, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        // --- the rest of the pad's settings ---------------------------------
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
