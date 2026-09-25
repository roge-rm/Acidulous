package com.rm.acidulous.desktop

import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * A right-click is a hold.
 *
 * The phone opens a thing's settings, menus and second actions on a long
 * press, and a desktop's hand goes to the right button for the same. Rather
 * than teach each of the app's holds a second button, the right button is
 * turned here into the left one held down past the long-press time and let
 * go: every hold answers it, whichever way it was written, and nothing in the
 * shared code knows. A held left button is a hold already.
 *
 * While the made-up press is down the mouse's movements are kept back, so a
 * hand drifting off the button does not turn the hold into a drag.
 */
internal object RightClickHold {
    /** Past Compose's long press (500 ms) by enough to be sure of it. */
    private const val HOLD_MS = 600

    private var pressed: Component? = null
    private var pressX = 0
    private var pressY = 0
    private var pressedAt = 0L
    private var releaseTimer: Timer? = null

    fun install() {
        Toolkit.getDefaultToolkit().systemEventQueue.push(object : EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                if (event is MouseEvent && take(event)) return
                super.dispatchEvent(event)
            }
        })
    }

    /** True when [e] was the right button's, or a movement during the hold, and is spoken for. */
    private fun take(e: MouseEvent): Boolean {
        val right = e.button == MouseEvent.BUTTON3 ||
            (e.id == MouseEvent.MOUSE_DRAGGED && e.modifiersEx and InputEvent.BUTTON3_DOWN_MASK != 0)
        if (right) {
            when (e.id) {
                MouseEvent.MOUSE_PRESSED -> if (pressed == null) press(e)
                MouseEvent.MOUSE_RELEASED -> {
                    val left = HOLD_MS - (System.currentTimeMillis() - pressedAt)
                    if (left <= 0) release() else later(left.toInt())
                }
            }
            return true
        }
        // The left button's own, or the mouse moving, while the hold is down.
        return pressed != null && (e.id == MouseEvent.MOUSE_MOVED || e.id == MouseEvent.MOUSE_DRAGGED)
    }

    private fun press(e: MouseEvent) {
        val target = e.component ?: return
        pressed = target
        pressX = e.x
        pressY = e.y
        pressedAt = System.currentTimeMillis()
        target.dispatchEvent(leftEvent(target, MouseEvent.MOUSE_PRESSED, e.modifiersEx))
        // Let go at the hold's length even if the right button is still down,
        // as the phone's hold acts at its length and not at the lift.
        later(HOLD_MS)
    }

    private fun later(ms: Int) {
        releaseTimer?.stop()
        releaseTimer = Timer(ms) { release() }.apply { isRepeats = false; start() }
    }

    private fun release() {
        releaseTimer?.stop()
        releaseTimer = null
        val target = pressed ?: return
        pressed = null
        target.dispatchEvent(leftEvent(target, MouseEvent.MOUSE_RELEASED, 0))
    }

    private fun leftEvent(target: Component, id: Int, keys: Int): MouseEvent {
        val keyMask = keys and (InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK)
        val buttons = if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0
        val screen = target.takeIf { it.isShowing }?.locationOnScreen
        return MouseEvent(
            target, id, System.currentTimeMillis(), keyMask or buttons,
            pressX, pressY, (screen?.x ?: 0) + pressX, (screen?.y ?: 0) + pressY,
            1, false, MouseEvent.BUTTON1,
        )
    }
}
