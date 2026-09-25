package com.rm.acidulous.ui

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as Awt

actual val androidx.compose.ui.input.key.KeyEvent.press: KeyPress get() {
    val awt = nativeKeyEvent as Awt
    val code = androidCode(awt)
    val down = type == KeyEventType.KeyDown
    // AWT repeats a held key as more presses and says nothing of it; Android
    // counts them. A key already down is a repeat.
    val repeat = if (down) (if (!held.add(code)) 1 else 0) else { held.remove(code); 0 }
    return KeyPress(
        action = if (down) KeyCodes.ACTION_DOWN else KeyCodes.ACTION_UP,
        keyCode = code,
        repeatCount = repeat,
        isCtrlPressed = awt.isControlDown,
        isAltPressed = awt.isAltDown,
        isShiftPressed = awt.isShiftDown,
        isMetaPressed = awt.isMetaDown,
    )
}

private val held = HashSet<Int>()

/** AWT's key code as Android's, the form bindings are saved in; unknown keys keep AWT's number, offset clear of Android's. */
private fun androidCode(e: Awt): Int {
    val k = e.keyCode
    return when {
        k in Awt.VK_A..Awt.VK_Z -> KeyCodes.KEYCODE_A + (k - Awt.VK_A)
        k in Awt.VK_0..Awt.VK_9 -> KeyCodes.KEYCODE_0 + (k - Awt.VK_0)
        k in Awt.VK_F1..Awt.VK_F12 -> KeyCodes.KEYCODE_F1 + (k - Awt.VK_F1)
        k in Awt.VK_NUMPAD0..Awt.VK_NUMPAD9 -> KeyCodes.KEYCODE_NUMPAD_0 + (k - Awt.VK_NUMPAD0)
        k == Awt.VK_ENTER -> if (e.keyLocation == Awt.KEY_LOCATION_NUMPAD) KeyCodes.KEYCODE_NUMPAD_ENTER else KeyCodes.KEYCODE_ENTER
        k == Awt.VK_SHIFT -> if (e.keyLocation == Awt.KEY_LOCATION_RIGHT) KeyCodes.KEYCODE_SHIFT_RIGHT else KeyCodes.KEYCODE_SHIFT_LEFT
        k == Awt.VK_CONTROL -> if (e.keyLocation == Awt.KEY_LOCATION_RIGHT) KeyCodes.KEYCODE_CTRL_RIGHT else KeyCodes.KEYCODE_CTRL_LEFT
        k == Awt.VK_ALT -> KeyCodes.KEYCODE_ALT_LEFT
        k == Awt.VK_ALT_GRAPH -> KeyCodes.KEYCODE_ALT_RIGHT
        k == Awt.VK_META || k == Awt.VK_WINDOWS -> if (e.keyLocation == Awt.KEY_LOCATION_RIGHT) KeyCodes.KEYCODE_META_RIGHT else KeyCodes.KEYCODE_META_LEFT
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
