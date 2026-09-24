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
import androidx.compose.ui.res.stringResource
import com.rm.acidulous.R

// How hard the top and the bottom of a pad hit. Not 1 at the bottom: below
// about forty most of these machines barely speak, and a pad that can be
// struck inaudibly reads as a broken pad rather than as a quiet one.
/**
 * What the softest and hardest strike are worth.
 *
 * **Both playing surfaces read them**, the pads here and the keys in
 * ui/PianoKeys.kt: a finger low on a pad and a finger low on a key mean the
 * same thing, and two surfaces that answer the same gesture differently would
 * be two instruments to learn rather than one.
 *
 * Forty rather than nought at the bottom, because a note you cannot hear is
 * indistinguishable from a note that did not play, and a playing surface must
 * always tell you it heard you.
 */
internal const val SOFT = 40f
internal const val HARD = 127f

/**
 * Pads in place of the keyboard for a drum machine: two rows, one per voice.
 *
 * **Pad one is bottom left**, and the row above it holds what decorates -
 * which is how a drum machine has always been laid out and is what a hand
 * expects to find. The grid above keeps ascending note order, because a drum
 * grid is read with the kick at the top; the two conventions disagree and
 * both are right, which is why `padOrder` exists.
 *
 * With an odd count the bottom row takes the smaller half, so its cells are
 * the wider ones: thirteen pads put six across the bottom and seven above.
 */
@Composable
fun DrumPads(rack: Int, voices: List<DrumVoice>, selected: Int = -1, onSelect: (Int) -> Unit = {},
             modifier: Modifier = Modifier, type: String = "", onEmpty: (Int) -> Unit = {}) {
    // A pad's index is its distance from the *lowest* note, and it stays that
    // whatever order they are drawn in: it is what retargets the machine panel
    // for Resonance, Dice and Forage. Taking it from `voices.first()` only
    // worked while the list was in ascending note order, which it no longer is.
    val base = voices.minOf { it.note }
    val laid = MachineUi.padOrder(type, voices)
    // The bottom row takes the smaller half so that it is the wider one, and
    // it is drawn second so that it is the lower one.
    val bottomCount = laid.size / 2
    val rows = if (bottomCount == 0) listOf(laid)
               else listOf(laid.drop(bottomCount), laid.take(bottomCount))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (v in row) {
                    Pad(rack, v, v.note - base == selected, { onSelect(v.note - base) },
                        Modifier.weight(1f).fillMaxSize(),
                        // An empty pad has nothing to play, so a tap on one can
                        // only mean "put something here".
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
    // The gesture below is keyed on the note, which does not change when a
    // sample lands on the pad - so the lambda kept the `voice` it was built
    // with, `loaded` still false, and a pad that now held a sample went on
    // opening the file picker every time it was tapped. This is the current
    // one whatever the gesture was started with.
    val current by rememberUpdatedState(voice)
    // Where the last strike landed, 0 at the bottom and 1 at the top, which is
    // both the velocity and how far the highlight fills.
    var strike by remember { mutableStateOf(0f) }
    // An empty pad is drawn as a hole rather than as a pad: no fill, a dashed
    // outline, and a "+" instead of a name. It still triggers - the engine
    // simply has nothing to play - and a Forage track with thirteen of these
    // now says at a glance that it is waiting for samples, which it did not
    // when every empty pad was labelled with its own number.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = listOfNotNull(
        if (selected) stringResource(R.string.a11y_pad_selected) else null,
        if (!voice.loaded) stringResource(R.string.a11y_pad_empty) else null,
    ).joinToString(stringResource(R.string.list_separator)).ifEmpty { null }
    Box(
        modifier
            // A double tap plays it, as a touch would, for a moment.
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
