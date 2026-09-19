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
        // --- the waveform ---------------------------------------------------
        //
        // Drawn by `ui/Waveform.kt`, which the recording screen draws with
        // too. What stays here is where the numbers come from: a mounted pad,
        // through parameters, rather than a file.
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
            empty = "This pad has no sample. Load one from the machine panel.",
        )

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
