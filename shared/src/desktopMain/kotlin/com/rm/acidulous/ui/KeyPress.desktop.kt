package com.rm.acidulous.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as Awt

actual val androidx.compose.ui.input.key.KeyEvent.press: KeyPress get() {
    // The same event is checked twice (the hub's preview, then its fallback),
    // and the second check must not see its own key already down and treat it
    // as a repeat, which the hub ignores.
    last?.let { (event, press) -> if (event === nativeKeyEvent) return press }
    return pressOf(this).also { last = nativeKeyEvent to it }
}

private var last: Pair<Any, KeyPress>? = null

private fun pressOf(e: androidx.compose.ui.input.key.KeyEvent): KeyPress {
    val key = e.key
    val type = e.type
    val code = androidCode(key)
    // AWT sends a held key as more presses without marking them; Android
    // counts them. So a key that's already down is a repeat. "Typed" events
    // (a character, after the press) are neither down nor up, and the hub
    // ignores anything else.
    val (action, repeat) = when (type) {
        KeyEventType.KeyDown -> KeyCodes.ACTION_DOWN to (if (!held.add(code)) 1 else 0)
        KeyEventType.KeyUp -> { held.remove(code); KeyCodes.ACTION_UP to 0 }
        else -> KeyCodes.ACTION_MULTIPLE to 0
    }
    if (macKeys) {
        // Cmd does what Ctrl does elsewhere, and the bindings call it ctrl.
        // Cmd+Left and Cmd+Right are Home and End, as on a Mac's text, and
        // Cmd+Down and Cmd+Up the same for a knob's bottom and top.
        val home = when (code) {
            KeyCodes.KEYCODE_DPAD_LEFT, KeyCodes.KEYCODE_DPAD_DOWN -> KeyCodes.KEYCODE_MOVE_HOME
            KeyCodes.KEYCODE_DPAD_RIGHT, KeyCodes.KEYCODE_DPAD_UP -> KeyCodes.KEYCODE_MOVE_END
            else -> null
        }
        val cmd = e.isMetaPressed
        val asHome = cmd && home != null && !e.isCtrlPressed && !e.isAltPressed
        return KeyPress(
            action = action,
            keyCode = if (asHome) home!! else code,
            repeatCount = repeat,
            isCtrlPressed = cmd && !asHome,
            isAltPressed = e.isAltPressed,
            isShiftPressed = e.isShiftPressed,
            isMetaPressed = e.isCtrlPressed,
        )
    }
    return KeyPress(
        action = action,
        keyCode = code,
        repeatCount = repeat,
        isCtrlPressed = e.isCtrlPressed,
        isAltPressed = e.isAltPressed,
        isShiftPressed = e.isShiftPressed,
        isMetaPressed = e.isMetaPressed,
    )
}

private val held = HashSet<Int>()

/**
 * The key as an Android key code, which is how bindings are saved. Compose
 * names the sided and numpad keys itself; everything else uses AWT's code,
 * and a key with no Android equivalent keeps AWT's number, offset clear of
 * Android's.
 */
private fun androidCode(key: Key): Int {
    when (key) {
        Key.NumPadEnter -> return KeyCodes.KEYCODE_NUMPAD_ENTER
        Key.ShiftRight -> return KeyCodes.KEYCODE_SHIFT_RIGHT
        Key.CtrlRight -> return KeyCodes.KEYCODE_CTRL_RIGHT
        Key.MetaRight -> return KeyCodes.KEYCODE_META_RIGHT
        Key.AltRight -> return KeyCodes.KEYCODE_ALT_RIGHT
    }
    val k = key.nativeKeyCode
    return when {
        k in Awt.VK_A..Awt.VK_Z -> KeyCodes.KEYCODE_A + (k - Awt.VK_A)
        k in Awt.VK_0..Awt.VK_9 -> KeyCodes.KEYCODE_0 + (k - Awt.VK_0)
        k in Awt.VK_F1..Awt.VK_F12 -> KeyCodes.KEYCODE_F1 + (k - Awt.VK_F1)
        k in Awt.VK_NUMPAD0..Awt.VK_NUMPAD9 -> KeyCodes.KEYCODE_NUMPAD_0 + (k - Awt.VK_NUMPAD0)
        k == Awt.VK_ENTER -> KeyCodes.KEYCODE_ENTER
        k == Awt.VK_SHIFT -> KeyCodes.KEYCODE_SHIFT_LEFT
        k == Awt.VK_CONTROL -> KeyCodes.KEYCODE_CTRL_LEFT
        k == Awt.VK_ALT -> KeyCodes.KEYCODE_ALT_LEFT
        k == Awt.VK_ALT_GRAPH -> KeyCodes.KEYCODE_ALT_RIGHT
        k == Awt.VK_META || k == Awt.VK_WINDOWS -> KeyCodes.KEYCODE_META_LEFT
        else -> AWT_TO_ANDROID[k] ?: (AWT_OFFSET + k)
    }
}

private const val AWT_OFFSET = 100_000

private val AWT_TO_ANDROID = mapOf(
    Awt.VK_SPACE to KeyCodes.KEYCODE_SPACE,
    Awt.VK_TAB to KeyCodes.KEYCODE_TAB,
    Awt.VK_ESCAPE to KeyCodes.KEYCODE_ESCAPE,
    Awt.VK_BACK_SPACE to KeyCodes.KEYCODE_DEL,
    Awt.VK_DELETE to KeyCodes.KEYCODE_FORWARD_DEL,
    Awt.VK_INSERT to KeyCodes.KEYCODE_INSERT,
    Awt.VK_HOME to KeyCodes.KEYCODE_MOVE_HOME,
    Awt.VK_END to KeyCodes.KEYCODE_MOVE_END,
    Awt.VK_PAGE_UP to KeyCodes.KEYCODE_PAGE_UP,
    Awt.VK_PAGE_DOWN to KeyCodes.KEYCODE_PAGE_DOWN,
    Awt.VK_UP to KeyCodes.KEYCODE_DPAD_UP,
    Awt.VK_DOWN to KeyCodes.KEYCODE_DPAD_DOWN,
    Awt.VK_LEFT to KeyCodes.KEYCODE_DPAD_LEFT,
    Awt.VK_RIGHT to KeyCodes.KEYCODE_DPAD_RIGHT,
    Awt.VK_CONTEXT_MENU to KeyCodes.KEYCODE_MENU,
    Awt.VK_MINUS to KeyCodes.KEYCODE_MINUS,
    Awt.VK_EQUALS to KeyCodes.KEYCODE_EQUALS,
    Awt.VK_PLUS to KeyCodes.KEYCODE_PLUS,
    Awt.VK_OPEN_BRACKET to KeyCodes.KEYCODE_LEFT_BRACKET,
    Awt.VK_CLOSE_BRACKET to KeyCodes.KEYCODE_RIGHT_BRACKET,
    Awt.VK_BACK_SLASH to KeyCodes.KEYCODE_BACKSLASH,
    Awt.VK_SEMICOLON to KeyCodes.KEYCODE_SEMICOLON,
    Awt.VK_QUOTE to KeyCodes.KEYCODE_APOSTROPHE,
    Awt.VK_BACK_QUOTE to KeyCodes.KEYCODE_GRAVE,
    Awt.VK_COMMA to KeyCodes.KEYCODE_COMMA,
    Awt.VK_PERIOD to KeyCodes.KEYCODE_PERIOD,
    Awt.VK_SLASH to KeyCodes.KEYCODE_SLASH,
    Awt.VK_ADD to KeyCodes.KEYCODE_NUMPAD_ADD,
    Awt.VK_SUBTRACT to KeyCodes.KEYCODE_NUMPAD_SUBTRACT,
    Awt.VK_MULTIPLY to KeyCodes.KEYCODE_NUMPAD_MULTIPLY,
    Awt.VK_DIVIDE to KeyCodes.KEYCODE_NUMPAD_DIVIDE,
    Awt.VK_DECIMAL to KeyCodes.KEYCODE_NUMPAD_DOT,
    Awt.VK_CAPS_LOCK to KeyCodes.KEYCODE_CAPS_LOCK,
)

/** AWT's key for an Android key code, for a controller's button sent on as a key; null if there's none. */
internal fun awtKeyFor(code: Int): Int? = when (code) {
    in KeyCodes.KEYCODE_A..KeyCodes.KEYCODE_Z -> Awt.VK_A + (code - KeyCodes.KEYCODE_A)
    in KeyCodes.KEYCODE_0..KeyCodes.KEYCODE_9 -> Awt.VK_0 + (code - KeyCodes.KEYCODE_0)
    in KeyCodes.KEYCODE_F1..KeyCodes.KEYCODE_F12 -> Awt.VK_F1 + (code - KeyCodes.KEYCODE_F1)
    KeyCodes.KEYCODE_ENTER -> Awt.VK_ENTER
    else -> AWT_TO_ANDROID.entries.firstOrNull { it.value == code }?.key
}
