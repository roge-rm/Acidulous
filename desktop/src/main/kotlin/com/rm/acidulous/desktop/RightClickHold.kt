package com.rm.acidulous.desktop

import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * Turns a right-click into a long press.
 *
 * On Android, settings, menus and second actions open on a long press, and on
 * desktop people reach for the right button. So the right button is turned
 * into a left press held past the long-press time and then released. Every
 * long press in the shared code handles it without knowing. Holding the left
 * button already works as a long press.
 *
 * Mouse movement is swallowed while the fake press is down, so the mouse
 * drifting doesn't turn it into a drag.
 */
internal object RightClickHold {
    /** Safely past Compose's long press time (500 ms). */
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

    /** True when [e] is a right-button event, or movement during the hold, and has been handled here. */
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
        // Swallow mouse movement while the hold is down.
        return pressed != null && (e.id == MouseEvent.MOUSE_MOVED || e.id == MouseEvent.MOUSE_DRAGGED)
    }

    private fun press(e: MouseEvent) {
        val target = e.component ?: return
        pressed = target
        pressX = e.x
        pressY = e.y
        pressedAt = System.currentTimeMillis()
        target.dispatchEvent(leftEvent(target, MouseEvent.MOUSE_PRESSED, e.modifiersEx))
        // Release after the hold time even if the right button is still down,
        // since a long press fires at that time, not when the finger lifts.
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
