package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.SWING_MAX
import com.rm.acidulous.model.SWING_STRAIGHT
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.Tuning
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R

/**
 * Everything that belongs to one track rather than to its machine or its
 * mix: what it is called and its colour, what it does to its notes on the
 * way to the machine, and where they go. Held open from the track's header.
 *
 * Edited as a copy and handed back whole on OK, so the window is one undo
 * step and Cancel leaves the song as it was.
 */
@Composable
fun TrackSettingsDialog(
    song: Song,
    index: Int,
    /** The tunings there are to choose from: built in, and imported. */
    tunings: List<Tuning>,
    onDismiss: () -> Unit,
    onConfirm: (Track) -> Unit,
) {
    val original = song.tracks[index]
    var track by remember(index) { mutableStateOf(original) }
    var name by remember(index) { mutableStateOf(original.name) }
    val type = track.machine.type
    val kind = MachineUi.kindOf(type)
    val groups = song.master.groups.map { it.name }

    PlainDialog(
        title = stringResource(R.string.track_title, original.name),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.ok),
        onConfirm = { onConfirm(track.copy(name = name.trim().ifEmpty { original.name })) },
        spacing = 6.dp,
    ) {
        WindowCards {
            WindowCard(stringResource(R.string.track_track)) {
                // One piece of a known width, so it sits the same in a stack
                // upright and in a row of cards turned.
                Column(Modifier.widthIn(max = 300.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        modifier = Modifier.typing() then Modifier.fillMaxWidth(),
                    )
                    ColourRow(trackColour(index, track.colour)) { track = track.copy(colour = it) }
                }
            }
            // A tape's notes are recordings, not pitches or hits: nothing
            // here would change them.
            if (kind != MachineKind.Audio) {
                WindowCard(stringResource(R.string.track_notes)) {
                    if (MachineUi.takesTranspose(type)) {
                        CountKnob(
                            stringResource(R.string.track_transpose), track.transpose, -48..48, signed(track.transpose),
                            choices = (-48..48).map { signed(it) },
                        ) { track = track.copy(transpose = it) }
                    }
                    if (MachineUi.takesTuning(type)) {
                        TuningKnob(track.tuning, tunings, { track = track.copy(tuning = it) }, followLabel = stringResource(R.string.track_tuning_song))
                    }
                    val asPlayed = stringResource(R.string.track_velocity_as_played)
                    CountKnob(
                        stringResource(R.string.track_velocity), track.velocity ?: 0, 0..127, velocityName(track.velocity ?: 0, asPlayed),
                        choices = (0..127).map { velocityName(it, asPlayed) },
                    ) { track = track.copy(velocity = it.takeIf { v -> v > 0 }) }
                    // Nought is "the song's"; the rest are the amounts.
                    val swingSteps = SWING_STRAIGHT.toInt()..SWING_MAX.toInt()
                    val own = track.swing?.roundToInt()?.coerceIn(swingSteps)
                    val song = stringResource(R.string.track_swing_song)
                    val straight = stringResource(R.string.track_swing_straight)
                    CountKnob(
                        stringResource(R.string.track_swing), own?.let { it - swingSteps.first + 1 } ?: 0, 0..(swingSteps.last - swingSteps.first + 1),
                        swingName(own, song, straight), PanelAmber,
                        choices = listOf(song) + swingSteps.map { swingName(it, song, straight) },
                    ) { track = track.copy(swing = if (it == 0) null else (swingSteps.first + it - 1).toFloat()) }
                }
            }
            WindowCard(stringResource(R.string.track_routing)) {
                if (kind != MachineKind.Audio) {
                    val m = track.mixer
                    SwitchGrid(stringResource(R.string.track_midi_out), stringArrayResource(R.array.track_midi_out_choices).toList(), m.midiMode, columns = 3) {
                        track = track.copy(mixer = m.copy(midiMode = it))
                    }
                    CountKnob(
                        stringResource(R.string.track_channel), m.midiChannel + 1, 1..16, "${m.midiChannel + 1}",
                        choices = (1..16).map { "$it" },
                    ) { track = track.copy(mixer = m.copy(midiChannel = it - 1)) }
                }
                val out = track.mixer.output.takeIf { it in 0..groups.size } ?: 0
                val master = stringResource(R.string.track_output_master)
                CountKnob(
                    stringResource(R.string.track_output), out, 0..groups.size, if (out == 0) master else groups[out - 1],
                    choices = listOf(master) + groups,
                ) { track = track.copy(mixer = track.mixer.copy(output = it)) }
            }
        }
    }
}

private fun signed(n: Int) = if (n > 0) "+$n" else "$n"
private fun velocityName(v: Int, asPlayed: String) = if (v == 0) asPlayed else "$v"
private fun swingName(amount: Int?, song: String, straight: String) = when {
    amount == null -> song
    amount <= SWING_STRAIGHT.toInt() -> straight
    else -> "$amount%"
}

/** The palette as dots, the chosen one ringed; two lines when the interface is large. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColourRow(current: androidx.compose.ui.graphics.Color, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until TRACK_COLOURS) {
            val colour = trackColour(i)
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .then(if (colour == current) Modifier.border(3.dp, Acid.colors.text, CircleShape) else Modifier)
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(colour))
            }
        }
    }
}
