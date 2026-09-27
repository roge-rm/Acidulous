package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.DrumVoice
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.res.*

/**
 * Velocity of the softest and hardest strike. Used by the pads here and by the
 * keys in ui/PianoKeys.kt, so both surfaces respond the same way.
 *
 * The bottom is 40 rather than 0 because below about 40 most machines are
 * barely audible, and a pad that plays nothing looks broken.
 */
internal const val SOFT = 40f
internal const val HARD = 127f

/**
 * Pads in place of the keyboard for a drum machine, in two rows.
 *
 * Pad one is bottom left, like on a hardware drum machine. The grid above uses
 * ascending note order with the kick at the top, which is why padOrder exists.
 *
 * With an odd count the bottom row gets the smaller half, so its pads are
 * wider: 13 pads put 6 on the bottom and 7 on top.
 */
@Composable
fun DrumPads(rack: Int, voices: List<DrumVoice>, selected: Int = -1, onSelect: (Int) -> Unit = {},
             modifier: Modifier = Modifier, type: String = "", onEmpty: (Int) -> Unit = {}) {
    // A pad's index is its distance from the lowest note, whatever order
    // they're drawn in. It's what retargets the machine panel for Resonance,
    // Dice and Forage. Don't take it from voices.first(), the list isn't in
    // note order.
    val base = voices.minOf { it.note }
    val laid = MachineUi.padOrder(type, voices)
    // The bottom row takes the smaller half so it's wider, and it's drawn
    // second so it's the lower one.
    val bottomCount = laid.size / 2
    val rows = if (bottomCount == 0) listOf(laid)
               else listOf(laid.drop(bottomCount), laid.take(bottomCount))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (v in row) {
                    Pad(rack, v, v.note - base == selected, { onSelect(v.note - base) },
                        Modifier.weight(1f).fillMaxSize(),
                        // An empty pad has nothing to play, so tapping it opens
                        // the sample picker.
                        onEmpty = { onEmpty(v.note - base) })
                }
            }
        }
    }
}

@Composable
private fun Pad(rack: Int, voice: DrumVoice, selected: Boolean, onSelect: () -> Unit,
                modifier: Modifier = Modifier, onEmpty: () -> Unit = {}) {
    var pressed by remember { mutableStateOf(false) }
    // The gesture below is keyed on the note, which doesn't change when a
    // sample is loaded onto the pad. This keeps the gesture seeing the current
    // voice, otherwise a loaded pad would keep opening the file picker.
    val current by rememberUpdatedState(voice)
    // Where the last strike landed, 0 at the bottom and 1 at the top. It's both
    // the velocity and how far the highlight fills.
    var strike by remember { mutableStateOf(0f) }
    // An empty pad is drawn as a hole: no fill, a dashed outline and a "+"
    // instead of a name. It still triggers, the engine just has nothing to
    // play.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = listOfNotNull(
        if (selected) stringResource(Res.string.a11y_pad_selected) else null,
        if (!voice.loaded) stringResource(Res.string.a11y_pad_empty) else null,
    ).joinToString(stringResource(Res.string.list_separator)).ifEmpty { null }
    Box(
        modifier
            // A TalkBack double tap plays it briefly, like a touch.
            .button(panelWord(voice.name), state, onClick = {
                NativeEngine.noteOn(rack, voice.note, 100)
                onSelect()
                if (!current.loaded) onEmpty()
                scope.launch { kotlinx.coroutines.delay(250); NativeEngine.noteOff(rack, voice.note) }
            })
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    selected -> Acid.colors.padSelected
                    !voice.loaded -> Acid.colors.control.copy(alpha = 0.25f)
                    else -> Acid.colors.control
                },
            )
            .then(
                if (voice.loaded) Modifier
                else Modifier.border(1.dp, Acid.colors.textDim.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            )
            .pointerInput(voice.note, rack) {
                // The loop keeps its own down state. Checking the drawn state
                // instead can miss a fast tap, and the finally stops a pad from
                // hanging when the gesture is cancelled.
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
                                    // Hit high for hard and low for soft. Read
                                    // once when the finger goes down, sliding
                                    // afterwards doesn't change it and the down
                                    // flag stops a second note.
                                    val y = currentEvent.changes.first { it.pressed }.position.y
                                    val h = size.height.toFloat()
                                    strike = when {
                                        // Full strength lights the whole pad.
                                        // The highlight is the only thing
                                        // showing which mode you're in.
                                        UiPrefs.padsFullStrength -> 1f
                                        h > 0f -> (1f - y / h).coerceIn(0f, 1f)
                                        else -> 1f
                                    }
                                    val vel = if (UiPrefs.padsFullStrength) HARD else SOFT + (HARD - SOFT) * strike
                                    NativeEngine.noteOn(rack, voice.note, vel.toInt())
                                    onSelect()
                                    if (!current.loaded) onEmpty()
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
        // The highlight fills from the bottom up to where the pad was struck,
        // so you can see how hard you hit.
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
            color = when {
                pressed -> Acid.colors.onAccent
                !voice.loaded -> Acid.colors.textDim
                else -> Acid.colors.text
            },
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
