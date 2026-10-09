package com.rm.acidulous.ui.launchpad

import kotlin.concurrent.Volatile

import com.rm.acidulous.util.postToMain
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.midi.launchpad.LaunchpadPro
import com.rm.acidulous.midi.launchpad.LaunchpadPro.Control
import com.rm.acidulous.midi.launchpad.LpAction
import com.rm.acidulous.midi.launchpad.LpState
import com.rm.acidulous.midi.launchpad.LpView
import com.rm.acidulous.midi.launchpad.Surface

/**
 * Connects the Launchpad to the app: presses in, lights out.
 *
 * All the logic is in [Surface]; this only moves bytes. A press arrives on
 * the MIDI thread and is handed to the main thread, where the state lives
 * and app actions are safe to call. Each frame is drawn from the latest
 * [view] and only what changed since the last one is sent.
 */
class LaunchpadController(private val act: (LpAction) -> Unit) {
    /** The app as last sampled; set by the composition before each frame. */
    @Volatile var view = LpView()
    var state = LpState()
        private set
    /** Stay on the Note page, so the grid is only ever played; its page buttons do nothing. */
    var notesOnly = false
    /** What the surface shows now, or null when it has to be redrawn whole. */
    private var shown: IntArray? = null

    fun attach() {
        MidiHub.launchpadInput = { status, d1, d2 -> postToMain { input(status, d1, d2) } }
        MidiHub.onLaunchpadReady = { postToMain { shown = null } }
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
            // Pressure, per pad or for the whole surface; the device can be set to either.
            0xa0 -> LaunchpadPro.padOf(d1)?.let { pad -> Surface.pressure(state, pad, d2).forEach(act) }
            0xd0 -> state.sounding.values.flatten().forEach { act(LpAction.Pressure(it, d1)) }
        }
    }

    private fun apply(r: Pair<LpState, List<LpAction>>) {
        state = if (notesOnly) r.first.copy(page = com.rm.acidulous.midi.launchpad.LpPage.Note) else r.first
        r.second.forEach(act)
    }

    /** Draws the surface as it should be now, sending only what changed. */
    fun frame() {
        if (notesOnly && state.page != com.rm.acidulous.midi.launchpad.LpPage.Note) {
            state = state.copy(page = com.rm.acidulous.midi.launchpad.LpPage.Note)
        }
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
