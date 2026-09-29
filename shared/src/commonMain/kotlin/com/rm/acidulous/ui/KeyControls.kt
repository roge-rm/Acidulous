package com.rm.acidulous.ui

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
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.launch
import com.rm.acidulous.res.*

/**
 * Keyboard handling for the controls the TalkBack helpers describe.
 *
 * Arrows move between controls (a phone touchpad swipes as a d-pad), so a knob
 * only takes them once it's grabbed. Enter grabs it, the arrows turn it (Shift
 * for fine, Page Up/Down for big steps), and Enter or Esc lets go. + and - turn
 * it without grabbing. Alt+Enter or the Menu key lists the long-press actions,
 * the same list TalkBack reads.
 */

/** Whether a key is Enter, by any of its key codes. */
private fun isEnter(code: Int) =
    code == KeyCodes.KEYCODE_ENTER || code == KeyCodes.KEYCODE_NUMPAD_ENTER || code == KeyCodes.KEYCODE_DPAD_CENTER

/** Alt+Enter or the Menu key: the long-press actions, if there are any. */
private fun opensActions(e: KeyPress, actions: List<CustomAccessibilityAction>): Boolean {
    if (actions.isEmpty()) return false
    val asked = e.keyCode == KeyCodes.KEYCODE_MENU || (isEnter(e.keyCode) && e.isAltPressed)
    if (asked) KeyHub.actionMenu = actions
    return asked
}

/**
 * Keyboard focus and turning for a knob, fader or slider. See the note at the top of
 * the file.
 */
internal fun Modifier.keyAdjust(
    value: Float,
    steps: Int,
    actions: List<CustomAccessibilityAction>,
    gesture: Triple<() -> Unit, (Float) -> Unit, () -> Unit>?,
    onSet: (Float) -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var grabbed by remember { mutableStateOf(false) }
    val accent = Acid.colors.accent
    val pink = Acid.colors.pink
    // What a controller's left stick turns while this has focus (see Pad).
    val now by androidx.compose.runtime.rememberUpdatedState(value)
    val stepsNow by androidx.compose.runtime.rememberUpdatedState(steps)
    val set by androidx.compose.runtime.rememberUpdatedState(onSet)
    val own by androidx.compose.runtime.rememberUpdatedState(gesture)
    val turnable = remember {
        Pad.Turnable(
            value = { now },
            steps = { stepsNow },
            begin = { own?.first?.invoke() },
            change = { v -> own?.second?.invoke(v) ?: set(v) },
            end = { own?.third?.invoke() },
        )
    }
    androidx.compose.runtime.DisposableEffect(turnable) {
        onDispose { if (Pad.turnable === turnable) Pad.turnable = null }
    }
    this
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) grabbed = false
            if (it.isFocused) Pad.turnable = turnable else if (Pad.turnable === turnable) Pad.turnable = null
        }
        .focusable()
        .onKeyEvent { ev ->
            val e = ev.press
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (opensActions(e, actions)) return@onKeyEvent true
            // One step: a stepped control's step, otherwise 1/20, or 1/100 with
            // Shift.
            val step = if (steps > 0) 1f / (steps + 1) else if (e.isShiftPressed) 0.01f else 0.05f
            fun nudge(by: Float) { onSet((value + by).coerceIn(0f, 1f)) }
            when (e.keyCode) {
                KeyCodes.KEYCODE_ENTER, KeyCodes.KEYCODE_NUMPAD_ENTER, KeyCodes.KEYCODE_DPAD_CENTER -> { grabbed = !grabbed; true }
                KeyCodes.KEYCODE_ESCAPE -> if (grabbed) { grabbed = false; true } else false
                KeyCodes.KEYCODE_PLUS, KeyCodes.KEYCODE_EQUALS, KeyCodes.KEYCODE_NUMPAD_ADD -> { nudge(step); true }
                KeyCodes.KEYCODE_MINUS, KeyCodes.KEYCODE_NUMPAD_SUBTRACT -> { nudge(-step); true }
                KeyCodes.KEYCODE_DPAD_UP, KeyCodes.KEYCODE_DPAD_RIGHT -> if (grabbed) { nudge(step); true } else false
                KeyCodes.KEYCODE_DPAD_DOWN, KeyCodes.KEYCODE_DPAD_LEFT -> if (grabbed) { nudge(-step); true } else false
                KeyCodes.KEYCODE_PAGE_UP -> if (grabbed) { nudge(step * 5); true } else false
                KeyCodes.KEYCODE_PAGE_DOWN -> if (grabbed) { nudge(-step * 5); true } else false
                KeyCodes.KEYCODE_MOVE_HOME -> if (grabbed) { onSet(0f); true } else false
                KeyCodes.KEYCODE_MOVE_END -> if (grabbed) { onSet(1f); true } else false
                else -> false
            }
        }
        .drawWithContent {
            drawContent()
            if (focused) focusRing(if (grabbed) pink else accent)
        }
}

/**
 * Keyboard focus and Enter for a control that handles its own taps instead of
 * using clickable (a pad, a key). With [onClick] null it only adds the
 * long-press actions, for a control that's already clickable and focusable.
 */
internal fun Modifier.keyPress(
    onClick: (() -> Unit)?,
    actions: List<CustomAccessibilityAction>,
): Modifier = if (onClick == null) {
    if (actions.isEmpty()) this
    else onKeyEvent { ev -> ev.type == KeyEventType.KeyDown && opensActions(ev.press, actions) }
} else composed {
    var focused by remember { mutableStateOf(false) }
    val accent = Acid.colors.accent
    this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            val e = ev.press
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (opensActions(e, actions)) return@onKeyEvent true
            if (isEnter(e.keyCode) && e.repeatCount == 0) { onClick(); true } else false
        }
        .drawWithContent {
            drawContent()
            if (focused) focusRing(accent)
        }
}

/**
 * A cell something else draws and takes touches for, like a step in the drum
 * grid: TalkBack's button and a keyboard focus stop in one node.
 *
 * The words are only worked out when TalkBack asks, or when Menu asks for the
 * actions. A grid has a couple of hundred cells and making their strings on
 * every build was a good part of opening an editor.
 */
internal fun Modifier.drawnCell(
    name: () -> String,
    state: () -> String,
    actions: () -> List<CustomAccessibilityAction>,
    ring: Color,
    onClick: () -> Unit,
): Modifier = this then DrawnCellElement(name, state, actions, ring, onClick)

private data class DrawnCellElement(
    val name: () -> String,
    val state: () -> String,
    val actions: () -> List<CustomAccessibilityAction>,
    val ring: Color,
    val onClick: () -> Unit,
) : androidx.compose.ui.node.ModifierNodeElement<DrawnCellNode>() {
    override fun create() = DrawnCellNode(this)
    override fun update(node: DrawnCellNode) = node.update(this)
}

private class DrawnCellNode(private var e: DrawnCellElement) : DelegatingNode(), DrawModifierNode,
    androidx.compose.ui.node.SemanticsModifierNode, androidx.compose.ui.input.key.KeyInputModifierNode {
    private var focused = false

    init {
        delegate(
            androidx.compose.ui.focus.FocusTargetModifierNode(
                onFocusChange = { _, now ->
                    if (now.isFocused != focused) { focused = now.isFocused; invalidateDraw() }
                },
            ),
        )
    }

    fun update(next: DrawnCellElement) {
        e = next
        invalidateSemantics()
        invalidateDraw()
    }

    override val shouldClearDescendantSemantics get() = true

    override fun SemanticsPropertyReceiver.applySemantics() {
        contentDescription = e.name()
        role = Role.Button
        stateDescription = e.state()
        onClick { e.onClick(); true }
        val actions = e.actions()
        if (actions.isNotEmpty()) customActions = actions
    }

    override fun onKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val press = event.press
        if (opensActions(press, e.actions())) return true
        if (isEnter(press.keyCode) && press.repeatCount == 0) { e.onClick(); return true }
        return false
    }

    override fun onPreKeyEvent(event: androidx.compose.ui.input.key.KeyEvent) = false

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) focusRing(e.ring)
    }
}

/**
 * A focused control's long-press actions as a list for Alt+Enter, the same
 * actions TalkBack offers.
 */
@androidx.compose.runtime.Composable
fun KeyActionMenu(actions: List<CustomAccessibilityAction>, onDismiss: () -> Unit) {
    PlainDialog(
        title = stringResource(Res.string.keys_actions_title),
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
