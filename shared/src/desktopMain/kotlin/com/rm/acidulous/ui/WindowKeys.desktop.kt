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
    return KeyHub.fallback(press)
}

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
