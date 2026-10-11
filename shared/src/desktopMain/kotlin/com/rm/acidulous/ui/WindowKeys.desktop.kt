package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.KeyEvent

/**
 * On desktop, dialogs are drawn inside the app's one window, so their keys
 * already go through that window's handlers ([previewKey] and [fallbackKey]
 * below). This only records that a window is open. While one is, Esc closes
 * it, like on the phone, and isn't learned as a binding or run as Back.
 */
@Composable
internal actual fun WindowKeys() {
    DisposableEffect(Unit) {
        openWindows++
        onDispose { openWindows-- }
    }
}

private var openWindows = 0

private val KeyPress.esc get() = keyCode == KeyCodes.KEYCODE_ESCAPE

/** The app window's onPreviewKeyEvent: the hub first, like dispatchKeyEvent on the phone. */
fun previewKey(event: KeyEvent): Boolean {
    val press = event.press
    // Steam's copy of a controller press, in SteamOS's Desktop Mode.
    if (PadEvents.isEcho(press.keyCode)) return true
    if (openWindows > 0 && press.esc) {
        KeyHub.usingKeys = true
        return false
    }
    return KeyHub.preview(press)
}

/** The app window's onKeyEvent: whatever the focused control didn't use goes back to the hub. */
fun fallbackKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) return false
    if (KeyHub.fallback(press)) return true
    // An arrow nothing used moves focus, as Android does by itself. Not in
    // play mode, where arrows are left alone.
    if (press.action == KeyCodes.ACTION_DOWN && press.keyCode in ARROW_KEYS && !KeyHub.playMode) {
        KeyHub.usingKeys = true
        return KeyHub.moveFocus(press.keyCode)
    }
    return false
}

private val ARROW_KEYS = setOf(
    KeyCodes.KEYCODE_DPAD_UP, KeyCodes.KEYCODE_DPAD_DOWN, KeyCodes.KEYCODE_DPAD_LEFT, KeyCodes.KEYCODE_DPAD_RIGHT,
)

/**
 * Treats a mouse press anywhere, in a window or the screen, as a touch (see
 * KeyHub.usingKeys). Called once at start-up.
 */
fun watchPointer() {
    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
        { if (it.id == java.awt.event.MouseEvent.MOUSE_PRESSED) KeyHub.usingKeys = false },
        java.awt.AWTEvent.MOUSE_EVENT_MASK,
    )
}

@androidx.compose.runtime.Composable
internal actual fun HideSystemBars() {}
