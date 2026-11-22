package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.rm.acidulous.res.*

/**
 * The piano keys' settings: how many rows, how long each is, what's labelled
 * and each row's octave. They belong to the device and apply as you touch
 * them. Without [roomy] (the phone strip, which has one row) the rows card is
 * left out.
 */
@Composable
fun PianoDialog(roomy: Boolean, onDismiss: () -> Unit) {
    PlainDialog(
        title = stringResource(Res.string.second_piano),
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.done),
        spacing = 8.dp,
    ) {
        WindowCards {
            WindowCard(stringResource(Res.string.piano_keys)) {
                if (roomy) CountKnob(stringResource(Res.string.grid_rows), UiPrefs.pianoRows, PianoRowRange) { UiPrefs.choosePianoRows(it) }
                CountKnob(
                    stringResource(Res.string.piano_length), UiPrefs.keyboardOctaves, PianoLengthRange,
                    display = if (UiPrefs.keyboardOctaves == 0) stringResource(Res.string.grid_auto) else "${UiPrefs.keyboardOctaves}",
                ) { UiPrefs.chooseKeyboardOctaves(it) }
                SwitchGrid(
                    stringResource(Res.string.piano_labels),
                    listOf(
                        stringResource(Res.string.piano_labels_c),
                        stringResource(Res.string.piano_labels_all),
                        stringResource(Res.string.piano_labels_none),
                    ),
                    UiPrefs.pianoLabels.ordinal, columns = 1,
                ) { UiPrefs.choosePianoLabels(PianoLabels.entries[it]) }
                SwitchGrid(stringResource(Res.string.second_piano), listOf(stringResource(Res.string.keys_reset)), -1) {
                    UiPrefs.resetPiano()
                }
            }
            if (roomy) WindowCard(stringResource(Res.string.grid_rows)) {
                // Bottom row first, like the keys read upward.
                for (row in 0 until UiPrefs.pianoRows) {
                    val o = UiPrefs.pianoOffsets[row]
                    CountKnob(
                        stringResource(Res.string.piano_row, row + 1), o, PianoOffsetRange,
                        display = if (o > 0) "+$o" else "$o",
                    ) { n -> UiPrefs.choosePianoOffsets(UiPrefs.pianoOffsets.mapIndexed { i, v -> if (i == row) n else v }) }
                }
            }
        }
    }
}
