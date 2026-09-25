package com.rm.acidulous.ui


actual val androidx.compose.ui.input.key.KeyEvent.press: KeyPress get() = nativeKeyEvent.toPress()

/** An Android key event - Activity.dispatchKeyEvent's - as a [KeyPress]. */
fun android.view.KeyEvent.toPress() = KeyPress(
    action = action,
    keyCode = keyCode,
    repeatCount = repeatCount,
    isCtrlPressed = isCtrlPressed,
    isAltPressed = isAltPressed,
    isShiftPressed = isShiftPressed,
    isMetaPressed = isMetaPressed,
)
