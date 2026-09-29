package com.rm.acidulous.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberTalkBack(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

@Composable
actual fun rememberBeyondTouch(): Boolean {
    val context = LocalContext.current
    val access = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val input = remember(context) { context.getSystemService(android.hardware.input.InputManager::class.java) }
    var service by remember(access) { mutableStateOf(access?.isEnabled == true) }
    var keys by remember(input) { mutableStateOf(hasKeys(input)) }
    DisposableEffect(access, input) {
        val onService = AccessibilityManager.AccessibilityStateChangeListener { service = it }
        access?.addAccessibilityStateChangeListener(onService)
        val onDevice = object : android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { keys = hasKeys(input) }
            override fun onInputDeviceRemoved(id: Int) { keys = hasKeys(input) }
            override fun onInputDeviceChanged(id: Int) { keys = hasKeys(input) }
        }
        input?.registerInputDeviceListener(onDevice, null)
        onDispose {
            access?.removeAccessibilityStateChangeListener(onService)
            input?.unregisterInputDeviceListener(onDevice)
        }
    }
    return service || keys
}

/**
 * A real keyboard, d-pad or controller. Not the phone's own buttons, which
 * are a keyboard without letters, and not the system's virtual keyboard.
 */
private fun hasKeys(input: android.hardware.input.InputManager?): Boolean =
    input?.inputDeviceIds?.any { id ->
        val d = input.getInputDevice(id) ?: return@any false
        if (d.isVirtual) return@any false
        fun has(source: Int) = d.sources and source == source
        has(android.view.InputDevice.SOURCE_GAMEPAD) || has(android.view.InputDevice.SOURCE_JOYSTICK) ||
            has(android.view.InputDevice.SOURCE_DPAD) ||
            (has(android.view.InputDevice.SOURCE_KEYBOARD) && d.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC)
    } == true
