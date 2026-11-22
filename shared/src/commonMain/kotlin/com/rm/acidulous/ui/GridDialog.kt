package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.rm.acidulous.res.*

/**
 * The note grid's settings: how many pads, and how a scale running in key is
 * laid out. They belong to the device and apply as you touch them.
 * [autoSteps] is the scale layout's row step when it's left on automatic.
 */
@Composable
fun GridDialog(autoSteps: Int, onDismiss: () -> Unit) {
    PlainDialog(
        title = stringResource(Res.string.second_grid),
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.done),
        spacing = 8.dp,
    ) {
        WindowCards {
            WindowCard(stringResource(Res.string.grid_pads)) {
                CountKnob(stringResource(Res.string.grid_columns), UiPrefs.gridCols, 3..12) { UiPrefs.chooseGridCols(it) }
                CountKnob(stringResource(Res.string.grid_rows), UiPrefs.gridRows, 2..8) { UiPrefs.chooseGridRows(it) }
                SwitchGrid(stringResource(Res.string.second_grid), listOf(stringResource(Res.string.keys_reset)), -1) {
                    UiPrefs.resetGrid()
                }
            }
            WindowCard(stringResource(Res.string.grid_layout)) {
                // No label of its own, the card's title says it; the blank line
                // keeps the choices level with the knob beside them.
                SwitchGrid(
                    "",
                    listOf(
                        stringResource(Res.string.grid_isomorphic),
                        stringResource(Res.string.grid_scale),
                        stringResource(Res.string.grid_piano_rows),
                    ),
                    UiPrefs.gridLayout.ordinal, columns = 1,
                ) { UiPrefs.chooseGridLayout(GridLayout.entries[it]) }
                when (UiPrefs.gridLayout) {
                    GridLayout.Isomorphic ->
                        CountKnob(stringResource(Res.string.grid_row), UiPrefs.gridRowSemis, 1..12) { UiPrefs.chooseGridRowSemis(it) }
                    GridLayout.Scale -> {
                        CountKnob(
                            stringResource(Res.string.grid_row), UiPrefs.gridRowSteps, 0..12,
                            display = if (UiPrefs.gridRowSteps == 0) stringResource(Res.string.grid_auto) else "${UiPrefs.gridRowSteps}",
                        ) { UiPrefs.chooseGridRowSteps(it) }
                    }
                    GridLayout.PianoRows -> {}
                }
            }
        }
    }
}
