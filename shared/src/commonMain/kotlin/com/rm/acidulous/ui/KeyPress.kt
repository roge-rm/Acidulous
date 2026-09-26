package com.rm.acidulous.ui

/**
 * One key event as the app reads it: android.view.KeyEvent's fields, on any
 * platform.
 *
 * The keyboard code was written against Android's KeyEvent, and the codes it
 * compares are Android's - [KeyCodes] - because that is what a saved key
 * binding holds. Android fills this in from its own event; the desktop from
 * AWT's, mapped onto the same codes.
 */
class KeyPress(
    val action: Int,
    val keyCode: Int,
    val repeatCount: Int = 0,
    val isCtrlPressed: Boolean = false,
    val isAltPressed: Boolean = false,
    val isShiftPressed: Boolean = false,
    val isMetaPressed: Boolean = false,
)

/** A Compose key event as a [KeyPress]. */
expect val androidx.compose.ui.input.key.KeyEvent.press: KeyPress
