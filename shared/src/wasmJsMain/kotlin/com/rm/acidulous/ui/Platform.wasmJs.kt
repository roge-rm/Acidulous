package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// The small platform questions, as a browser answers them: mostly as the
// desktop does, for it is a desktop's window more often than not.

/** A page has no camera in it. */
@Composable
actual fun rememberTopCutout(): TopCutout? = null

/** A screen reader reads a canvas as nothing, so the layout is the sighted one. */
@Composable
actual fun rememberTalkBack(): Boolean = false

/** The microphone is the browser's to ask for, when the recorder opens it; nothing to ask here. */
@Composable
actual fun rememberPermissions(onResult: (Boolean) -> Unit): Permissions {
    val result = rememberUpdatedState(onResult)
    return remember {
        object : Permissions {
            override fun has(name: String) = true
            override fun ask(vararg names: String) = result.value(true)
        }
    }
}

/** The page's height in the outer dp: see the desktop's. */
@Composable
internal actual fun windowHeightDp(): Float =
    with(LocalBaseDensity.current ?: LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }

/**
 * A window is drawn in the page's one canvas, so its keys pass through the
 * page's handlers - [previewKey] and [fallbackKey] - and what is left is to
 * say that one is open: while it is, Esc is the window's. As the desktop.
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

/** The page's preview of a key: the hub first. */
fun previewKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) {
        KeyHub.usingKeys = true
        return false
    }
    return KeyHub.preview(press)
}

/** What the focused control left, back to the hub. */
fun fallbackKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) return false
    return KeyHub.fallback(press)
}
