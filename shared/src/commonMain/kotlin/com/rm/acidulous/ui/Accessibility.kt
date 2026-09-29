package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Most controls here are drawn (knobs, faders, grids, keys), so they don't
 * describe themselves like Material controls do. These give a drawn control
 * one node that says what it is, its value and what you can do with it,
 * replacing whatever its parts would say (a knob's label and value as two
 * stops, or a glyph read by its Unicode name). Hold gestures, which TalkBack
 * can't reach by touch, become named actions in its menu.
 */

/** A named action for TalkBack's actions menu, spelling out what a hold does. */
internal fun action(label: String, run: () -> Unit) = CustomAccessibilityAction(label) { run(); true }

/**
 * A control with a value on a range, like a knob or a fader. TalkBack reads
 * [name] and [state], and swiping up or down steps [value] through 0..1, in
 * [steps] steps if it's stepped or in twentieths if not.
 */
internal fun Modifier.adjustable(
    name: String,
    state: String,
    value: Float,
    steps: Int = 0,
    actions: List<CustomAccessibilityAction> = emptyList(),
    /**
     * The control's own gesture (start, change, end), for a controller's
     * stick turning it: one gesture from push to let go, so one step of undo.
     * Without it each change is a gesture of its own, as a key press is.
     */
    gesture: Triple<() -> Unit, (Float) -> Unit, () -> Unit>? = null,
    onSet: (Float) -> Unit,
): Modifier = clearAndSetSemantics {
    contentDescription = name
    stateDescription = state
    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f, steps)
    setProgress { target -> onSet(target.coerceIn(0f, 1f)); true }
    if (actions.isNotEmpty()) customActions = actions
}.keyAdjust(value, steps, actions, gesture, onSet)

/**
 * A drawn button, or anything tapped: [name] instead of its glyph, and what a
 * hold does as [actions].
 */
internal fun Modifier.button(
    name: String,
    state: String? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    onClick: (() -> Unit)? = null,
    /**
     * Whether the keyboard reaches it through this. Yes when [onClick] is the
     * only way to press it, no when a `clickable` next to it already handles
     * that, since two would make two focus stops for one control.
     */
    keyFocus: Boolean = onClick != null,
): Modifier = clearAndSetSemantics {
    contentDescription = name
    role = Role.Button
    if (state != null) stateDescription = state
    if (onClick != null) onClick { onClick(); true }
    if (actions.isNotEmpty()) customActions = actions
}.keyPress(if (keyFocus) onClick else null, actions)

/** One of a set where one is chosen, like a switch's cell or a tab. */
internal fun Modifier.choice(name: String, chosen: Boolean, tab: Boolean = false, onClick: (() -> Unit)? = null): Modifier =
    clearAndSetSemantics {
        contentDescription = name
        role = if (tab) Role.Tab else Role.RadioButton
        selected = chosen
        if (onClick != null) onClick { onClick(); true }
    }.keyPress(onClick, emptyList())

/**
 * Controls TalkBack reads together before moving on: a switch's cells, a
 * card, a mixer strip. Without it TalkBack reads the screen in lines straight
 * across whatever is side by side, like one strip's name then the next
 * strip's name.
 */
internal fun Modifier.together(): Modifier = semantics { isTraversalGroup = true }

/** Nothing TalkBack should stop on: a decoration, or a value read elsewhere. */
internal fun Modifier.silent(): Modifier = clearAndSetSemantics { }
