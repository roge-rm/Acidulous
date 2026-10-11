package com.rm.acidulous.ui

import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * A controller's events into [Pad] on the desktop, as PadEvents does on
 * Android. They arrive on a reader thread and are handled on the UI thread.
 * A button that stands in for a key is sent to the focused control as that
 * key, so it reaches everything the key would, dialogs included.
 */
object PadEvents {
    /** A held d-pad repeats like a held arrow key: after this long, then this often. */
    private const val REPEAT_AFTER_MS = 400
    private const val REPEAT_EVERY_MS = 60

    private var repeating: Timer? = null

    fun button(device: String, keyCode: Int, down: Boolean) = SwingUtilities.invokeLater {
        Pad.button(device, keyCode, down)
        // In play mode the buttons and the d-pad are notes first.
        if (KeyHub.playMode && Pad.play(keyCode, down, 0)) return@invokeLater
        // The d-pad has no job of its own: on Android the system moves focus
        // with it, so here it's sent on as the arrow keys.
        val job = Pad.jobOf(keyCode) ?: if (keyCode in ARROWS) Pad.Job.Key(keyCode) else return@invokeLater
        when (job) {
            is Pad.Job.Key -> {
                if (beaten(job.code, down)) return@invokeLater
                if (down) holding += job.code else holding -= job.code
                send(job.code, down, job.alt)
                repeating?.stop()
                repeating = null
                if (down && job.code in ARROWS) {
                    repeating = Timer(REPEAT_EVERY_MS) { send(job.code, true, job.alt) }.apply {
                        initialDelay = REPEAT_AFTER_MS
                        start()
                    }
                }
            }
            is Pad.Job.Action -> {
                KeyHub.usingKeys = true
                if (down) KeyHub.run(job.action)
            }
            Pad.Job.Nothing -> Unit
        }
    }

    fun moved(device: String, axes: List<Pad.Axis>) = SwingUtilities.invokeLater {
        Pad.sendKey = { code ->
            send(code, true, false)
            send(code, false, false)
        }
        Pad.moved(device, axes)
    }

    private val ARROWS = setOf(
        KeyCodes.KEYCODE_DPAD_UP, KeyCodes.KEYCODE_DPAD_DOWN, KeyCodes.KEYCODE_DPAD_LEFT, KeyCodes.KEYCODE_DPAD_RIGHT,
    )

    /**
     * In SteamOS's Desktop Mode, Steam also turns the Deck's d-pad and
     * buttons into keys, so each press would arrive twice. A key this sent
     * is remembered for a moment, and the same key from outside in that
     * time is Steam's copy (see [isEcho]).
     */
    private const val ECHO_MS = 150L
    private val sentAt = HashMap<Int, Long>()
    /** Keys a controller button is holding down, so Steam's repeats of them are dropped too. */
    private val holding = HashSet<Int>()
    /** When a key last came from outside, in case Steam's copy beats the controller's own. */
    private val heardAt = HashMap<Int, Long>()
    /** Controller presses skipped as Steam's copy came first, so their releases are skipped too. */
    private val skipped = HashSet<Int>()
    private var sending = false

    /** Whether a key press from the keyboard is a copy of one a controller sent. UI thread. */
    fun isEcho(androidCode: Int): Boolean {
        if (sending) return false
        if (androidCode in holding) return true
        val at = sentAt[androidCode]
        if (at != null && System.currentTimeMillis() - at < ECHO_MS) return true
        heardAt[androidCode] = System.currentTimeMillis()
        return false
    }

    /** Whether Steam's copy of this press already came in, so the controller's own is skipped. */
    private fun beaten(androidCode: Int, down: Boolean): Boolean {
        if (!down) return skipped.remove(androidCode)
        val at = heardAt[androidCode] ?: return false
        if (System.currentTimeMillis() - at >= ECHO_MS) return false
        skipped += androidCode
        return true
    }

    /** A key press or release to whatever has the keyboard's focus. */
    private fun send(androidCode: Int, down: Boolean, alt: Boolean) {
        val vk = awtKeyFor(androidCode) ?: return
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val target = focus.focusOwner ?: focus.activeWindow ?: return
        sentAt[androidCode] = System.currentTimeMillis()
        sending = true
        try { target.dispatchEvent(
            KeyEvent(
                target, if (down) KeyEvent.KEY_PRESSED else KeyEvent.KEY_RELEASED, System.currentTimeMillis(),
                if (alt) KeyEvent.ALT_DOWN_MASK else 0, vk, KeyEvent.CHAR_UNDEFINED,
            ),
        ) } finally { sending = false }
    }
}
