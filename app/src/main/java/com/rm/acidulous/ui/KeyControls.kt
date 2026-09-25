package com.rm.acidulous.ui

import android.view.KeyEvent
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.launch

/**
 * The keyboard's side of the controls the TalkBack helpers describe.
 *
 * Arrows move between controls - they have to, on a phone whose only way
 * round is a touchpad that swipes as a d-pad - so a knob does not take them
 * until it is *grabbed*: Enter grabs it, the arrows then turn it (Shift for
 * fine, Page Up and Down for big steps), and Enter or Esc lets go. A full
 * keyboard can skip the grab with + and -. Alt+Enter, or the Menu key, opens
 * whatever a hold would have done, from the same list TalkBack reads.
 */

/** Whether a key down is Enter by any of its names. */
private fun isEnter(code: Int) =
    code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER

/** Alt+Enter or the Menu key: the hold's actions, if there are any. */
private fun opensActions(e: KeyEvent, actions: List<CustomAccessibilityAction>): Boolean {
    if (actions.isEmpty()) return false
    val asked = e.keyCode == KeyEvent.KEYCODE_MENU || (isEnter(e.keyCode) && e.isAltPressed)
    if (asked) KeyHub.actionMenu = actions
    return asked
}

/** The ring a focused control wears: accent, or pink while a knob is grabbed. */
private fun ContentDrawScope.ring(color: Color) {
    val w = 2.dp.toPx()
    drawRoundRect(color, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(w))
}

/** Keyboard focus and turning for a knob, a fader or a slider. See the file's note. */
internal fun Modifier.keyAdjust(
    value: Float,
    steps: Int,
    actions: List<CustomAccessibilityAction>,
    onSet: (Float) -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var grabbed by remember { mutableStateOf(false) }
    val accent = Acid.colors.accent
    val pink = Acid.colors.pink
    this
        .onFocusChanged { focused = it.isFocused; if (!it.isFocused) grabbed = false }
        .focusable()
        .onKeyEvent { ev ->
            val e = ev.nativeKeyEvent
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (opensActions(e, actions)) return@onKeyEvent true
            // One step: a stepped control's, or a twentieth - a fiftieth with Shift.
            val step = if (steps > 0) 1f / (steps + 1) else if (e.isShiftPressed) 0.01f else 0.05f
            fun nudge(by: Float) { onSet((value + by).coerceIn(0f, 1f)) }
            when (e.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { grabbed = !grabbed; true }
                KeyEvent.KEYCODE_ESCAPE -> if (grabbed) { grabbed = false; true } else false
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { nudge(step); true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { nudge(-step); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> if (grabbed) { nudge(step); true } else false
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> if (grabbed) { nudge(-step); true } else false
                KeyEvent.KEYCODE_PAGE_UP -> if (grabbed) { nudge(step * 5); true } else false
                KeyEvent.KEYCODE_PAGE_DOWN -> if (grabbed) { nudge(-step * 5); true } else false
                KeyEvent.KEYCODE_MOVE_HOME -> if (grabbed) { onSet(0f); true } else false
                KeyEvent.KEYCODE_MOVE_END -> if (grabbed) { onSet(1f); true } else false
                else -> false
            }
        }
        .drawWithContent {
            drawContent()
            if (focused) ring(if (grabbed) pink else accent)
        }
}

/**
 * Keyboard focus and Enter for a drawn control that takes its taps itself
 * rather than through `clickable` - a pad, a key - or, with [onClick] null,
 * only the hold's actions for one that is clickable already and so focusable
 * already.
 */
internal fun Modifier.keyPress(
    onClick: (() -> Unit)?,
    actions: List<CustomAccessibilityAction>,
): Modifier = if (onClick == null) {
    if (actions.isEmpty()) this
    else onKeyEvent { ev -> ev.type == KeyEventType.KeyDown && opensActions(ev.nativeKeyEvent, actions) }
} else composed {
    var focused by remember { mutableStateOf(false) }
    val accent = Acid.colors.accent
    this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            val e = ev.nativeKeyEvent
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (opensActions(e, actions)) return@onKeyEvent true
            if (isEnter(e.keyCode) && e.repeatCount == 0) { onClick(); true } else false
        }
        .drawWithContent {
            drawContent()
            if (focused) ring(accent)
        }
}

/**
 * The app's press indication - Material's ripple - with a focus ring on top,
 * so every `clickable` in the app shows where the keyboard is without each
 * one being told. Provided in AcidulousTheme.
 */
class FocusRingIndication(private val base: IndicationNodeFactory, private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        RingNode(interactionSource, base.create(interactionSource), color)

    override fun equals(other: Any?) = other is FocusRingIndication && other.base == base && other.color == color
    override fun hashCode() = base.hashCode() * 31 + color.hashCode()
}

private class RingNode(
    private val source: InteractionSource,
    inner: DelegatableNode,
    private val color: Color,
) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init { delegate(inner) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) ring(color)
    }
}

/**
 * A focused control's hold actions as a list: Alt+Enter's answer to "what
 * does holding this do", from the same actions TalkBack offers.
 */
@androidx.compose.runtime.Composable
fun KeyActionMenu(actions: List<CustomAccessibilityAction>, onDismiss: () -> Unit) {
    PlainDialog(
        title = androidx.compose.ui.res.stringResource(com.rm.acidulous.R.string.keys_actions_title),
        onDismiss = onDismiss,
        spacing = 6.dp,
    ) {
        for (a in actions) {
            DialogRow(mark = "\u203A", name = a.label) {
                onDismiss()
                a.action?.invoke()
            }
        }
    }
}
