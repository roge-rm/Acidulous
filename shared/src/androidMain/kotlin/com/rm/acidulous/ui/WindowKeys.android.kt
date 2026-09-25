package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun WindowKeys() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val own = window?.callback
        if (window != null && own != null) {
            window.callback = object : android.view.Window.Callback by own {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    val esc = event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                    if (!esc && KeyHub.preview(event.toPress())) return true
                    if (own.dispatchKeyEvent(event)) return true
                    return !esc && KeyHub.fallback(event.toPress())
                }

                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    KeyHub.usingKeys = false
                    return own.dispatchTouchEvent(event)
                }
            }
        }
        onDispose { if (window != null && own != null) window.callback = own }
    }
}
