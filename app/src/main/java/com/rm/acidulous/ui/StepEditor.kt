package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Note
import kotlin.math.roundToInt
import com.rm.acidulous.ui.theme.Acid

/**
 * Subvert's step sequencer: the old way of entering a line, as a second editor
 * over the ordinary clip. A step is the note starting on that grid tick.
 * Accent is velocity at or above 100; slide is a note long enough to overlap
 * the next step, which the voice plays as a legato glide. So the piano roll
 * shows exactly the same thing, and any machine can play the result.
 */
@Composable
fun StepEditor(
    clip: Clip,
    ticksPerBar: Int,
    playheadTick: Long?,
    /** Which bar to show; the Edit screen's header owns the paging. */
    barIndex: Int,
    onSetStep: (tick: Int, note: Note?) -> Unit,          // null clears the step (one undo step)
    onPitchGestureBegin: () -> Unit,
    onPitchGesture: (tick: Int, note: Note) -> Unit,        // absolute from the gesture base
    onPitchGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = clip.grid.coerceAtLeast(1)
    val stepsPerBar = (ticksPerBar / grid).coerceAtLeast(1)
    val bar = barIndex.coerceIn(0, (clip.bars - 1).coerceAtLeast(0))
    val lastPitch = clip.notes.lastOrNull()?.pitch ?: 36

    Column(modifier.background(Acid.colors.bg).padding(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in 0 until stepsPerBar) {
                val tick = bar * ticksPerBar + s * grid
                val note = clip.notes.firstOrNull { it.tick == tick }
                val active = playheadTick != null && playheadTick >= tick && playheadTick < tick + grid
                StepColumn(
                    index = s, note = note, defaultPitch = lastPitch, grid = grid, active = active,
                    onGate = { onSetStep(tick, if (note == null) Note(tick, (grid * 0.8f).toInt(), lastPitch, 85) else null) },
                    onAccent = { note?.let { n -> onSetStep(tick, n.copy(velocity = if (n.velocity >= 100) 85 else 110)) } },
                    onSlide = { note?.let { n -> onSetStep(tick, n.copy(length = if (n.length > grid) (grid * 0.8f).toInt() else grid + grid / 2)) } },
                    onPitchBegin = onPitchGestureBegin,
                    onPitch = { pitch -> note?.let { n -> onPitchGesture(tick, n.copy(pitch = pitch.coerceIn(0, 127))) } },
                    onPitchEnd = onPitchGestureEnd,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepColumn(
    index: Int, note: Note?, defaultPitch: Int, grid: Int, active: Boolean,
    onGate: () -> Unit, onAccent: () -> Unit, onSlide: () -> Unit,
    onPitchBegin: () -> Unit, onPitch: (Int) -> Unit, onPitchEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = note != null
    val accent = note != null && note.velocity >= 100
    val slide = note != null && note.length > grid
    val pitch = note?.pitch ?: defaultPitch
    val cb by rememberUpdatedState(Triple(onPitchBegin, onPitch, onPitchEnd))
    val pitchState by rememberUpdatedState(pitch)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${index + 1}", color = if (active) Acid.colors.accent else Acid.colors.textFaint, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        // Pitch: drag up/down a semitone per 14 px.
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(3.dp))
                .background(if (gate) Acid.colors.green else Acid.colors.card)
                .pointerInput(gate) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!gate) return@awaitEachGesture
                        val start = pitchState
                        cb.first()
                        drag(down.id) { change ->
                            change.consume()
                            cb.second(start + ((down.position.y - change.position.y) / 14f).roundToInt())
                        }
                        cb.third()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (gate) noteName(pitch) else "·", color = if (gate) Color.White else Acid.colors.textFaint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Toggle("on", gate, Acid.colors.teal, onGate)
        Toggle("acc", accent, Acid.colors.accent, onAccent, enabled = gate)
        Toggle("sld", slide, Acid.colors.pink, onSlide, enabled = gate)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, colour: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp))
            .background(if (on) colour.copy(alpha = 0.35f) else Acid.colors.card)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else Acid.colors.textFaint, fontSize = 8.sp, fontFamily = FontFamily.Monospace) }
}

private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
/**
 * A note's name, spelled by the running scale where there is one.
 *
 * [spelling] comes from `Scales.spellingFor(track)`; empty means chromatic,
 * and then everything is a sharp, which is the convention when there is no
 * key to read it against.
 */
fun noteName(pitch: Int, spelling: Map<Int, String> = emptyMap()): String {
    val pc = ((pitch % 12) + 12) % 12
    return (spelling[pc] ?: NAMES[pc]) + (pitch / 12 - 1)
}
