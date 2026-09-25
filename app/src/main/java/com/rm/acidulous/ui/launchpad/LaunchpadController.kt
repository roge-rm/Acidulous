package com.rm.acidulous.ui.launchpad

import android.os.Handler
import android.os.Looper
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.midi.launchpad.LaunchpadPro
import com.rm.acidulous.midi.launchpad.LaunchpadPro.Control
import com.rm.acidulous.midi.launchpad.LpAction
import com.rm.acidulous.midi.launchpad.LpState
import com.rm.acidulous.midi.launchpad.LpView
import com.rm.acidulous.midi.launchpad.Surface

/**
 * The Launchpad's half in the app: its presses in, its lights out.
 *
 * Everything that decides anything is in [Surface]; this carries bytes. A
 * press arrives on the MIDI thread and is handed to the main thread, where
 * the state lives and the app's actions are safe to call; a frame is drawn
 * from the latest [view] and only what changed since the last is sent.
 */
class LaunchpadController(private val act: (LpAction) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    /** The app as last sampled; set by the composition before each frame. */
    @Volatile var view = LpView()
    var state = LpState()
        private set
    /** What the surface is showing now, or null when it has to be drawn whole. */
    private var shown: IntArray? = null

    fun attach() {
        MidiHub.launchpadInput = { status, d1, d2 -> main.post { input(status, d1, d2) } }
        MidiHub.onLaunchpadReady = { main.post { shown = null } }
    }

    fun detach() {
        MidiHub.launchpadInput = null
        MidiHub.onLaunchpadReady = null
    }

    private fun input(status: Int, d1: Int, d2: Int) {
        when (status and 0xf0) {
            0x90, 0x80 -> {
                val pad = LaunchpadPro.padOf(d1) ?: return
                if (status and 0xf0 == 0x90 && d2 > 0) apply(Surface.press(view, state, pad, d2))
                else apply(Surface.release(state, pad))
            }
            0xb0 -> {
                val c = LaunchpadPro.controlOfCc(d1) ?: return
                if (d2 > 0) apply(Surface.press(view, state, c, 127)) else apply(Surface.release(state, c))
            }
            // Pressure, per pad or for the whole surface: the device can be set to either.
            0xa0 -> LaunchpadPro.padOf(d1)?.let { pad -> Surface.pressure(state, pad, d2).forEach(act) }
            0xd0 -> state.sounding.values.flatten().forEach { act(LpAction.Pressure(it, d1)) }
        }
    }

    private fun apply(r: Pair<LpState, List<LpAction>>) {
        state = r.first
        r.second.forEach(act)
    }

    /** Draw the surface as it should be now: only what changed goes out. */
    fun frame() {
        val leds = Surface.render(view, state)
        val before = shown
        val changes = LEDS.filter { before == null || before[it] != leds[it] }.map { it to leds[it] }
        if (changes.isEmpty()) return
        LaunchpadPro.lights(changes).forEach { MidiHub.launchpadSend(it) }
        shown = leds
    }

    companion object {
        /** Every LED there is: the grid, the buttons, the two rows and the scene column. */
        private val LEDS: List<Int> = buildList {
            for (r in 0..7) for (c in 0..7) add(LaunchpadPro.ledOf(Control.Pad(r, c)))
            for (b in LaunchpadPro.Button.entries) add(b.cc)
            for (i in 0..7) {
                add(LaunchpadPro.ledOf(Control.Track(i)))
                add(LaunchpadPro.ledOf(Control.Scene(i)))
            }
        }
    }
}
