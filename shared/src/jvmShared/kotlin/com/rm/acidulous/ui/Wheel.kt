package com.rm.acidulous.ui

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
 * A turn of the mouse wheel, or a touchpad's two-finger scroll: in notches,
 * down and right positive. Shift turns a plain wheel sideways - the desktop
 * may already have, and says so in [dx] - and Ctrl (or Cmd) makes it zoom:
 * what every desktop editor does with them.
 */
internal class Wheel(val dx: Float, val dy: Float, val zoom: Boolean, val shift: Boolean) {
    /** Across, in notches: a touchpad's own, or a plain wheel's with Shift held. */
    val across: Float get() = if (shift && dx == 0f) dy else dx
    /** Up and down, in notches: nothing while Shift has turned it sideways. */
    val down: Float get() = if (shift && dx == 0f) 0f else dy
    /**
     * A view span's factor for a zoom: below one is in, as the wheel goes up.
     * Either axis, because with Shift held a wheel arrives as a sideways one.
     */
    val zoomFactor: Float get() = Math.pow(1.15, (if (dy != 0f) dy else dx).toDouble()).toFloat()
}

/**
 * The wheel, for a view that moves and zooms by two fingers on the phone.
 * [handle] answers whether it took it, and a taken turn goes no further - a
 * zoom must not scroll the column it is in as well.
 *
 * Only where the pointer is a mouse (AppHost.usesMouse): a phone's view
 * gestures are its fingers', and nothing here changes them.
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
