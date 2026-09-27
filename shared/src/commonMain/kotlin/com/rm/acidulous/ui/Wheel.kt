package com.rm.acidulous.ui

import com.rm.acidulous.util.Math

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.rm.acidulous.AppHost

/**
 * A mouse wheel turn or touchpad two-finger scroll, in notches, with down
 * and right positive. Shift turns a plain wheel sideways (the desktop may
 * already have, in which case it's in [dx]) and Ctrl or Cmd makes it zoom.
 */
internal class Wheel(val dx: Float, val dy: Float, val zoom: Boolean, val shift: Boolean) {
    /** Sideways, in notches: a touchpad's own, or a plain wheel's with Shift held. */
    val across: Float get() = if (shift && dx == 0f) dy else dx
    /** Up and down, in notches: zero while Shift has turned it sideways. */
    val down: Float get() = if (shift && dx == 0f) 0f else dy
    /**
     * A zoom factor for a view span: below 1 zooms in, as the wheel goes up.
     * Uses either axis, since with Shift held a wheel arrives sideways.
     */
    val zoomFactor: Float get() = Math.pow(1.15, (if (dy != 0f) dy else dx).toDouble()).toFloat()
}

/**
 * The mouse wheel, for a view that moves and zooms with two fingers on the
 * phone. [handle] returns whether it used the turn, and a used turn goes no
 * further, so a zoom doesn't also scroll the column it's in.
 *
 * Only active when the pointer is a mouse (AppHost.usesMouse).
 */
internal fun Modifier.onWheel(handle: PointerInputScope.(Wheel) -> Boolean): Modifier = composed {
    if (!AppHost.current.usesMouse) return@composed Modifier
    val current by rememberUpdatedState(handle)
    Modifier.pointerInput(Unit) {
        val scope = this
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                val keys = event.keyboardModifiers
                val wheel = Wheel(delta.x, delta.y, keys.isCtrlPressed || keys.isMetaPressed, keys.isShiftPressed)
                if (scope.current(wheel)) event.changes.forEach { it.consume() }
            }
        }
    }
}
