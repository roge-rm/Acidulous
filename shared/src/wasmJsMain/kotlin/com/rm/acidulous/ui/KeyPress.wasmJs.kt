package com.rm.acidulous.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

// Browser keys, handled like the desktop: Compose's key names are mapped to
// Android key codes, which is what saved bindings use.

actual val androidx.compose.ui.input.key.KeyEvent.press: KeyPress get() {
    // Each event is read twice (the hub's preview, then its fallback). The
    // second read mustn't see its own key already down and call it a repeat.
    last?.let { (event, press) -> if (event === nativeKeyEvent) return press }
    return pressOf(this).also { last = nativeKeyEvent to it }
}

private var last: Pair<Any, KeyPress>? = null
private val held = HashSet<Int>()

private fun pressOf(e: androidx.compose.ui.input.key.KeyEvent): KeyPress {
    val code = ANDROID[e.key] ?: (OFFSET + (e.key.keyCode and 0xffff).toInt())
    val (action, repeat) = when (e.type) {
        KeyEventType.KeyDown -> KeyCodes.ACTION_DOWN to (if (!held.add(code)) 1 else 0)
        KeyEventType.KeyUp -> { held.remove(code); KeyCodes.ACTION_UP to 0 }
        else -> KeyCodes.ACTION_MULTIPLE to 0
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

/** Added to keys Android has no code for, to keep them clear of Android's codes. */
private const val OFFSET = 100_000

private val ANDROID: Map<Key, Int> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, k -> put(k, KeyCodes.KEYCODE_A + i) }
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .forEachIndexed { i, k -> put(k, KeyCodes.KEYCODE_0 + i) }
    listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
        .forEachIndexed { i, k -> put(k, KeyCodes.KEYCODE_F1 + i) }
    listOf(
        Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
        Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
    ).forEachIndexed { i, k -> put(k, KeyCodes.KEYCODE_NUMPAD_0 + i) }
    put(Key.Enter, KeyCodes.KEYCODE_ENTER)
    put(Key.NumPadEnter, KeyCodes.KEYCODE_NUMPAD_ENTER)
    put(Key.ShiftLeft, KeyCodes.KEYCODE_SHIFT_LEFT)
    put(Key.ShiftRight, KeyCodes.KEYCODE_SHIFT_RIGHT)
    put(Key.CtrlLeft, KeyCodes.KEYCODE_CTRL_LEFT)
    put(Key.CtrlRight, KeyCodes.KEYCODE_CTRL_RIGHT)
    put(Key.AltLeft, KeyCodes.KEYCODE_ALT_LEFT)
    put(Key.AltRight, KeyCodes.KEYCODE_ALT_RIGHT)
    put(Key.MetaLeft, KeyCodes.KEYCODE_META_LEFT)
    put(Key.MetaRight, KeyCodes.KEYCODE_META_RIGHT)
    put(Key.Spacebar, KeyCodes.KEYCODE_SPACE)
    put(Key.Tab, KeyCodes.KEYCODE_TAB)
    put(Key.Escape, KeyCodes.KEYCODE_ESCAPE)
    put(Key.Backspace, KeyCodes.KEYCODE_DEL)
    put(Key.Delete, KeyCodes.KEYCODE_FORWARD_DEL)
    put(Key.Insert, KeyCodes.KEYCODE_INSERT)
    put(Key.MoveHome, KeyCodes.KEYCODE_MOVE_HOME)
    put(Key.MoveEnd, KeyCodes.KEYCODE_MOVE_END)
    put(Key.PageUp, KeyCodes.KEYCODE_PAGE_UP)
    put(Key.PageDown, KeyCodes.KEYCODE_PAGE_DOWN)
    put(Key.DirectionUp, KeyCodes.KEYCODE_DPAD_UP)
    put(Key.DirectionDown, KeyCodes.KEYCODE_DPAD_DOWN)
    put(Key.DirectionLeft, KeyCodes.KEYCODE_DPAD_LEFT)
    put(Key.DirectionRight, KeyCodes.KEYCODE_DPAD_RIGHT)
    put(Key.Menu, KeyCodes.KEYCODE_MENU)
    put(Key.Minus, KeyCodes.KEYCODE_MINUS)
    put(Key.Equals, KeyCodes.KEYCODE_EQUALS)
    put(Key.Plus, KeyCodes.KEYCODE_PLUS)
    put(Key.LeftBracket, KeyCodes.KEYCODE_LEFT_BRACKET)
    put(Key.RightBracket, KeyCodes.KEYCODE_RIGHT_BRACKET)
    put(Key.Backslash, KeyCodes.KEYCODE_BACKSLASH)
    put(Key.Semicolon, KeyCodes.KEYCODE_SEMICOLON)
    put(Key.Apostrophe, KeyCodes.KEYCODE_APOSTROPHE)
    put(Key.Grave, KeyCodes.KEYCODE_GRAVE)
    put(Key.Comma, KeyCodes.KEYCODE_COMMA)
    put(Key.Period, KeyCodes.KEYCODE_PERIOD)
    put(Key.Slash, KeyCodes.KEYCODE_SLASH)
    put(Key.NumPadAdd, KeyCodes.KEYCODE_NUMPAD_ADD)
    put(Key.NumPadSubtract, KeyCodes.KEYCODE_NUMPAD_SUBTRACT)
    put(Key.NumPadMultiply, KeyCodes.KEYCODE_NUMPAD_MULTIPLY)
    put(Key.NumPadDivide, KeyCodes.KEYCODE_NUMPAD_DIVIDE)
    put(Key.NumPadDot, KeyCodes.KEYCODE_NUMPAD_DOT)
    put(Key.CapsLock, KeyCodes.KEYCODE_CAPS_LOCK)
}
