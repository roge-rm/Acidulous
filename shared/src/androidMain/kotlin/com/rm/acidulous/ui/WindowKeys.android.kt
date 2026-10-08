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
                    PadEvents.key(event)
                    PadEvents.route(event) { dispatchKeyEvent(it) }?.let { return it }
                    val esc = event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                    if (!esc && KeyHub.preview(event.toPress())) return true
                    if (own.dispatchKeyEvent(event)) return true
                    return !esc && KeyHub.fallback(event.toPress())
                }

                override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
                    if (PadEvents.motion(event) { dispatchKeyEvent(it) }) return true
                    return own.dispatchGenericMotionEvent(event)
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

@Composable
@Suppress("DEPRECATION")
internal actual fun HideSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.windowInsetsController?.let {
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            view.rootView.systemUiVisibility = view.rootView.systemUiVisibility or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        onDispose {}
    }
}
