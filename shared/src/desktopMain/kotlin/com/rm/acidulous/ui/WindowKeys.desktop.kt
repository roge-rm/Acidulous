package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.KeyEvent

/**
 * On the desktop a window is drawn inside the app's one window, so its keys
 * already pass through that window's handlers - [previewKey] and
 * [fallbackKey] below - and what is left to do here is say that a window is
 * open. While one is, Esc is the window's, as it is on the phone: it closes
 * the window and neither is learned as a binding nor runs Back behind it.
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

/** The app window's onPreviewKeyEvent: the hub first, as dispatchKeyEvent does on the phone. */
fun previewKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) {
        KeyHub.usingKeys = true
        return false
    }
    return KeyHub.preview(press)
}

/** The app window's onKeyEvent: whatever the focused control left, back to the hub. */
fun fallbackKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) return false
    return KeyHub.fallback(press)
}

/**
 * A mouse press anywhere, a window's or the screen's, is a touch: see
 * KeyHub.usingKeys. Once, at start-up.
 */
fun watchPointer() {
    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
        { if (it.id == java.awt.event.MouseEvent.MOUSE_PRESSED) KeyHub.usingKeys = false },
        java.awt.AWTEvent.MOUSE_EVENT_MASK,
    )
}
