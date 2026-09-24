package com.rm.acidulous.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription

/**
 * What TalkBack is told about the app's own controls.
 *
 * Almost everything here is drawn - knobs, faders, the grids, the keys - so
 * none of it describes itself the way a Material control does. These give a
 * drawn control one node that says what it is, what it is set to and what can
 * be done with it, and replace whatever its insides would have said: a knob's
 * label and value as two separate stops, or a glyph read out by its Unicode
 * name. Hold gestures, which TalkBack cannot reach by touch, come back as
 * named actions in its menu.
 */

/** A named action for TalkBack's actions menu: what a hold does, spelled out. */
internal fun action(label: String, run: () -> Unit) = CustomAccessibilityAction(label) { run(); true }

/**
 * A control with a value on a range: a knob or a fader. TalkBack reads [name]
 * and [state], and swiping up or down steps [value] through 0..1 - in [steps]
 * steps when it is stepped, or in twentieths when it is not.
 */
internal fun Modifier.adjustable(
    name: String,
    state: String,
    value: Float,
    steps: Int = 0,
    actions: List<CustomAccessibilityAction> = emptyList(),
    onSet: (Float) -> Unit,
): Modifier = clearAndSetSemantics {
    contentDescription = name
    stateDescription = state
    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f, steps)
    setProgress { target -> onSet(target.coerceIn(0f, 1f)); true }
    if (actions.isNotEmpty()) customActions = actions
}

/**
 * A drawn button, or anything tapped: [name] instead of its glyph, and what a
 * hold would have done as [actions].
 */
internal fun Modifier.button(
    name: String,
    state: String? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    onClick: (() -> Unit)? = null,
): Modifier = clearAndSetSemantics {
    contentDescription = name
    role = Role.Button
    if (state != null) stateDescription = state
    if (onClick != null) onClick { onClick(); true }
    if (actions.isNotEmpty()) customActions = actions
}

/** One of a set, where one is chosen: a switch's cell, a tab. */
internal fun Modifier.choice(name: String, chosen: Boolean, tab: Boolean = false, onClick: (() -> Unit)? = null): Modifier =
    clearAndSetSemantics {
        contentDescription = name
        role = if (tab) Role.Tab else Role.RadioButton
        selected = chosen
        if (onClick != null) onClick { onClick(); true }
    }

/**
 * Controls TalkBack reads as one run before it moves on: a switch's cells, a
 * card, a mixer strip. Without it TalkBack reads a screen in lines, straight
 * across whatever sits side by side - the first row of the theme switch, then
 * the first row of the size switch beside it, then the second row of each; a
 * strip's name, then the next strip's name.
 */
internal fun Modifier.together(): Modifier = semantics { isTraversalGroup = true }

/** Nothing TalkBack should stop on: a decoration, or a reading said elsewhere. */
internal fun Modifier.silent(): Modifier = clearAndSetSemantics { }
