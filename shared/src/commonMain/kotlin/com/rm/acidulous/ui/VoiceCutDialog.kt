package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.io.File
import com.rm.acidulous.io.absolutePath
import com.rm.acidulous.model.voice.Prompt
import com.rm.acidulous.model.voice.TakeCut
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** The takes are cut at this rate, whatever the phone records at. */
private const val CUT_RATE = 48000

/** The boundaries of a take's cut that can be moved, in the order they fall. */
private enum class Mark { HoldFrom, HoldTo, GlideFrom, GlideTo, ConsonantFrom, ConsonantTo }

private fun marksOf(prompt: Prompt): List<Mark> = when {
    prompt.glides -> listOf(Mark.HoldFrom, Mark.HoldTo, Mark.GlideFrom, Mark.GlideTo)
    prompt.held -> listOf(Mark.HoldFrom, Mark.HoldTo)
    else -> listOf(Mark.ConsonantFrom, Mark.ConsonantTo)
}

private fun TakeCut.at(m: Mark): Int = when (m) {
    Mark.HoldFrom -> holdFrom
    Mark.HoldTo -> holdTo
    Mark.GlideFrom -> glideFrom
    Mark.GlideTo -> glideTo
    Mark.ConsonantFrom -> consonantFrom
    Mark.ConsonantTo -> consonantTo
}

private fun TakeCut.with(m: Mark, v: Int): TakeCut = when (m) {
    Mark.HoldFrom -> copy(holdFrom = v)
    Mark.HoldTo -> copy(holdTo = v)
    Mark.GlideFrom -> copy(glideFrom = v)
    Mark.GlideTo -> copy(glideTo = v)
    Mark.ConsonantFrom -> copy(consonantFrom = v)
    Mark.ConsonantTo -> copy(consonantTo = v)
}

/**
 * A cut with every mark where it can be moved from: the cutter's, or where
 * the guide showed the parts when the cutter found none, as for a take it
 * said had no consonant.
 */
private fun usable(prompt: Prompt, cut: TakeCut?, frames: Int, consonantNear: Float): TakeCut {
    val marks = marksOf(prompt)
    val given = cut ?: TakeCut()
    val ordered = marks.zipWithNext().all { (a, b) -> given.at(a) < given.at(b) } && given.at(marks.first()) > 0
    if (ordered) return given
    val second = CUT_RATE
    return when {
        prompt.held || prompt.glides -> {
            val from = frames * 2 / 5
            val to = frames * 7 / 10
            val base = given.copy(holdFrom = from, holdTo = to)
            if (prompt.glides) base.copy(glideFrom = to, glideTo = minOf(frames - 1, to + second / 5)) else base
        }
        else -> {
            val at = if (consonantNear >= 0f) (consonantNear * second).toInt() else frames / 2
            given.copy(consonantFrom = (at - second / 25).coerceAtLeast(1), consonantTo = (at + second / 25).coerceAtMost(frames - 1))
        }
    }
}

/**
 * Moving a take's cut by hand, for when the cutter got one wrong: the
 * take's waveform where it was sung, with the held part, the glide or the
 * consonant marked and each boundary dragged. [recut] asks the cutter
 * again; kept, the cut is marked as made by hand, so the cutter leaves it.
 */
@Composable
internal fun VoiceCutDialog(
    prompt: Prompt,
    file: File,
    cut: TakeCut?,
    /** Where the guide put the consonant in a take so many seconds long, in seconds, or -1. */
    consonantNear: (Float) -> Float,
    recut: suspend () -> TakeCut?,
    onKeep: (TakeCut) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Acid.colors
    val amber = PanelAmber
    val scope = rememberCoroutineScope()
    val marks = remember(prompt) { marksOf(prompt) }
    var frames by remember { mutableStateOf(0) }
    var edited by remember { mutableStateOf<TakeCut?>(null) }
    // The part shown: where it was sung, with a little either side.
    var viewFrom by remember { mutableStateOf(0) }
    var viewTo by remember { mutableStateOf(0) }
    var shape by remember { mutableStateOf(FloatArray(0)) }
    val columns = 400
    val preview = remember(file) { File(file.absolutePath.removeSuffix(".wav") + ".part-heard.wav") }

    LaunchedEffect(file) {
        val info = withContext(Dispatchers.IO) { NativeEngine.fileInfo(file.absolutePath) }.split('|')
        val n = info.getOrNull(info.size - 4)?.toFloatOrNull()?.toInt() ?: 0
        if (n <= 0) return@LaunchedEffect
        frames = n
        val start = edited ?: usable(prompt, cut, n, consonantNear(n.toFloat() / CUT_RATE))
        edited = start
        val margin = CUT_RATE / 10
        val from = if (cut != null && cut.end > cut.start) (cut.start - margin).coerceAtLeast(0) else 0
        val to = if (cut != null && cut.end > cut.start) (cut.end + margin).coerceAtMost(n) else n
        // Always the marks in view, even where the cutter's singing ends short of them.
        viewFrom = minOf(from, marks.minOf { start.at(it) } - margin).coerceAtLeast(0)
        viewTo = maxOf(to, marks.maxOf { start.at(it) } + margin).coerceAtMost(n)
        val out = FloatArray(columns * 2)
        withContext(Dispatchers.IO) { NativeEngine.fileShape(file.absolutePath, out, viewFrom, viewTo) }
        shape = out
    }
    DisposableEffect(file) {
        onDispose {
            NativeEngine.auditionFile("")
            preview.delete()
        }
    }

    /** Plays the part the marks hold: a consonant with the vowel either side the engine keeps with it. */
    fun playPart() {
        val cutNow = edited ?: return
        val keep = CUT_RATE * 15 / 100
        val (from, to) = when {
            prompt.glides -> cutNow.holdFrom to cutNow.glideTo
            prompt.held -> cutNow.holdFrom to cutNow.holdTo
            else -> (cutNow.consonantFrom - keep).coerceAtLeast(0) to (cutNow.consonantTo + keep).coerceAtMost(frames)
        }
        scope.launch {
            val ops = FloatArray(NativeEngine.EDIT_OPS)
            ops[0] = from.toFloat()
            ops[1] = to.toFloat()
            ops[2] = 5f
            ops[3] = 5f
            val error = withContext(Dispatchers.IO) { NativeEngine.editSample(file.absolutePath, preview.absolutePath, ops) }
            if (error.isEmpty()) NativeEngine.auditionFile(preview.absolutePath)
        }
    }

    PlainDialog(
        title = prompt.sung,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.voice_cut_keep),
        confirmEnabled = edited != null,
        onConfirm = { edited?.let { onKeep(it.copy(problem = "", hand = true, by = TakeCut.CUTTER)) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val cutNow = edited
            val span = (viewTo - viewFrom).coerceAtLeast(1)
            val editedState by rememberUpdatedState(cutNow)
            Canvas(
                Modifier.fillMaxWidth().height(140.dp).pointerInput(frames, viewFrom, viewTo) {
                    var moving: Mark? = null
                    detectDragGestures(
                        onDragStart = { down ->
                            val now = editedState ?: return@detectDragGestures
                            val at = viewFrom + (down.x / size.width * span).toInt()
                            moving = marks.minByOrNull { abs(now.at(it) - at) }
                        },
                        onDragEnd = { moving = null },
                        onDragCancel = { moving = null },
                    ) { change, _ ->
                        val m = moving ?: return@detectDragGestures
                        val now = editedState ?: return@detectDragGestures
                        change.consume()
                        // Not past the marks either side, and 5 ms apart at least.
                        val gap = CUT_RATE / 200
                        val i = marks.indexOf(m)
                        val lo = if (i > 0) now.at(marks[i - 1]) + gap else 1
                        val hi = if (i < marks.size - 1) now.at(marks[i + 1]) - gap else frames - 1
                        val at = viewFrom + (change.position.x / size.width * span).toInt()
                        edited = now.with(m, at.coerceIn(lo, maxOf(lo, hi)))
                    }
                },
            ) {
                val w = size.width
                val h = size.height
                fun xOf(frame: Int) = (frame - viewFrom).toFloat() / span * w
                drawRect(c.sunken, Offset.Zero, size)
                if (cutNow != null) {
                    // The part each pair of marks holds, shaded.
                    fun shade(from: Mark, to: Mark, colour: Color) {
                        val x0 = xOf(cutNow.at(from))
                        val x1 = xOf(cutNow.at(to))
                        drawRect(colour.copy(alpha = 0.18f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), h))
                    }
                    when {
                        prompt.glides -> { shade(Mark.HoldFrom, Mark.HoldTo, c.teal); shade(Mark.GlideFrom, Mark.GlideTo, amber) }
                        prompt.held -> shade(Mark.HoldFrom, Mark.HoldTo, c.teal)
                        else -> shade(Mark.ConsonantFrom, Mark.ConsonantTo, c.pink)
                    }
                }
                if (shape.isNotEmpty()) drawShape(shape.toList(), 0, columns, 0f, w, h / 2f, h / 2f * 0.95f, c.textDim)
                if (cutNow != null) {
                    for (m in marks) {
                        val x = xOf(cutNow.at(m))
                        val colour = when (m) {
                            Mark.HoldFrom, Mark.HoldTo -> c.teal
                            Mark.GlideFrom, Mark.GlideTo -> amber
                            else -> c.pink
                        }
                        drawRect(colour, Offset(x - 1f, 0f), Size(2f, h))
                        drawRect(colour, Offset(x - 6f, 0f), Size(12f, 12f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { NativeEngine.auditionFile(file.absolutePath) }) {
                    Text(stringResource(Res.string.voice_play), fontSize = 12.sp)
                }
                TextButton(onClick = { playPart() }, enabled = cutNow != null) {
                    Text(stringResource(Res.string.voice_cut_part), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    scope.launch { recut()?.let { fresh -> edited = usable(prompt, fresh, frames, consonantNear(frames.toFloat() / CUT_RATE)) } }
                }) { Text(stringResource(Res.string.voice_cut_auto), fontSize = 12.sp) }
            }
        }
    }
}
