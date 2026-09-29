package com.rm.acidulous.ui

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Android's controller events into [Pad]. Called from the activity and from
 * every window's key hook, since a controller's events go to whichever window
 * has focus. It only looks: the events carry on where they were going.
 */
object PadEvents {
    private const val STAND_IN_MS = 250L

    private fun controller(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    /** The last real button and when, to tell Android's stand-in for it from a key of its own. */
    private var lastButton = 0
    private var lastButtonAt = 0L
    private val STAND_INS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)

    fun key(e: KeyEvent) {
        if (e.repeatCount > 0 || (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP)) return
        if (!controller(e.source) && !KeyEvent.isGamepadButton(e.keyCode)) return
        // A button nothing used comes round again as Android's stand-in for it
        // (A and Start as the d-pad's centre, B as back, Select as menu), right
        // after it. The flag that should mark it isn't set, on the emulator at
        // least, so it's known by following a button within a moment.
        if (e.keyCode in STAND_INS && lastButton != 0 && e.eventTime - lastButtonAt < STAND_IN_MS) return
        if (KeyEvent.isGamepadButton(e.keyCode)) {
            lastButton = e.keyCode
            lastButtonAt = e.eventTime
        }
        Pad.button(e.device?.name ?: "", e.keyCode, e.action == KeyEvent.ACTION_DOWN)
    }

    /**
     * Gives a controller button its job (see [Pad.Job]). A button that stands
     * in for a key is sent on as that key through [dispatch], the window's own
     * way in, so it reaches everything the key would. Returns true when the
     * button was taken, which every controller button is, or null for a key
     * that isn't one.
     */
    fun route(e: KeyEvent, dispatch: (KeyEvent) -> Boolean): Boolean? {
        if (!controller(e.source) && !KeyEvent.isGamepadButton(e.keyCode)) return null
        // In play mode the buttons and the d-pad are notes first.
        if (KeyHub.playMode && (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) &&
            Pad.play(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount)
        ) return true
        when (val job = Pad.jobOf(e.keyCode) ?: return null) {
            is Pad.Job.Key -> {
                // A keyboard's key, so it isn't taken for a controller's again.
                val meta = if (job.alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0
                dispatch(
                    KeyEvent(
                        e.downTime, e.eventTime, e.action, job.code, e.repeatCount, meta,
                        e.deviceId, 0, e.flags, InputDevice.SOURCE_KEYBOARD,
                    ),
                )
            }
            is Pad.Job.Action -> {
                KeyHub.usingKeys = true
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) KeyHub.run(job.action)
            }
            Pad.Job.Nothing -> Unit
        }
        return true
    }

    /**
     * A stick or trigger moved. Taken (true) so Android doesn't also turn the
     * stick into d-pad presses; the sticks' own jobs are in [Pad.tick].
     * [dispatch] is the window's way in, where the right stick's arrows go.
     */
    fun motion(e: MotionEvent, dispatch: (KeyEvent) -> Boolean): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val device = e.device ?: return false
        Pad.sendKey = { code ->
            val now = android.os.SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                dispatch(KeyEvent(now, now, action, code, 0, 0, e.deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD))
            }
        }
        // Every axis the controller says it has, with where each one rests.
        val axes = device.motionRanges
            .filter { it.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK }
            .distinctBy { it.axis }
            .map { Pad.Axis(MotionEvent.axisToString(it.axis).removePrefix("AXIS_"), e.getAxisValue(it.axis), it.flat) }
        Pad.moved(device.name, axes)
        return true
    }
}
