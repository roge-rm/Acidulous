package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.DrumVoice
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.ui.theme.Acid

// How hard the top and the bottom of a pad hit. Not 1 at the bottom: below
// about forty most of these machines barely speak, and a pad that can be
// struck inaudibly reads as a broken pad rather than as a quiet one.
private const val SOFT = 40f
private const val HARD = 127f

/**
 * Pads in place of the keyboard for a drum machine: two rows, one per voice.
 *
 * The order is `MachineUi.padOrder`, not the order the grid lists - rows fill
 * top-first, so the voices a hand reaches for are put last and come out both
 * wider and nearer the thumb. See the note there.
 */
@Composable
fun DrumPads(rack: Int, voices: List<DrumVoice>, selected: Int = -1, onSelect: (Int) -> Unit = {}, modifier: Modifier = Modifier, type: String = "") {
    // A pad's index is its distance from the *lowest* note, and it stays that
    // whatever order they are drawn in: it is what retargets the machine panel
    // for Resonance, Dice and Forage. Taking it from `voices.first()` only
    // worked while the list was in ascending note order, which it no longer is.
    val base = voices.minOf { it.note }
    val laid = MachineUi.padOrder(type, voices)
    val perRow = (laid.size + 1) / 2
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in laid.chunked(perRow)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (v in row) Pad(rack, v, v.note - base == selected, { onSelect(v.note - base) }, Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun Pad(rack: Int, voice: DrumVoice, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    // Where the last strike landed, 0 at the bottom and 1 at the top, which is
    // both the velocity and how far the highlight fills.
    var strike by remember { mutableStateOf(0f) }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Acid.colors.padSelected else Acid.colors.control)
            .pointerInput(voice.note, rack) {
                // The loop keeps its own idea of what is down. Comparing
                // against the drawn state instead lets a fast tap be missed,
                // and the finally is what stops a pad left sounding when the
                // gesture is cancelled out from under it.
                var down = false
                try {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            val now = currentEvent.changes.any { it.pressed }
                            if (now != down) {
                                down = now
                                pressed = now
                                if (now) {
                                    // Hit it high for hard and low for soft.
                                    // Read once, at the moment it goes down: a
                                    // finger that slides afterwards has already
                                    // played its note, and the `down` flag is
                                    // what stops it playing another.
                                    val y = currentEvent.changes.first { it.pressed }.position.y
                                    val h = size.height.toFloat()
                                    strike = when {
                                        // Full strength: the whole pad lights,
                                        // because the whole pad does the same
                                        // thing. The highlight is the only
                                        // thing telling you which mode you are
                                        // in once your finger is down.
                                        UiPrefs.padsFullStrength -> 1f
                                        h > 0f -> (1f - y / h).coerceIn(0f, 1f)
                                        else -> 1f
                                    }
                                    val vel = if (UiPrefs.padsFullStrength) HARD else SOFT + (HARD - SOFT) * strike
                                    NativeEngine.noteOn(rack, voice.note, vel.toInt())
                                    onSelect()
                                } else {
                                    NativeEngine.noteOff(rack, voice.note)
                                }
                            }
                        }
                    }
                } finally {
                    if (down) NativeEngine.noteOff(rack, voice.note)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The highlight fills from the bottom to where the pad was struck
        // rather than flooding it, so how hard you hit is visible and the
        // gesture teaches itself the first time anybody uses it.
        if (pressed) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(strike.coerceAtLeast(0.06f))
                    .background(Acid.colors.accent),
            )
        }
        Text(
            voice.short,
            color = if (pressed) Acid.colors.onAccent else Acid.colors.text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
