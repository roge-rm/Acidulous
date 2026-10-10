package com.rm.acidulous.ui.exquis

import kotlin.concurrent.Volatile

import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.midi.exquis.ExquisSurface
import com.rm.acidulous.midi.exquis.XqPage
import com.rm.acidulous.midi.exquis.XqState
import com.rm.acidulous.midi.launchpad.LpAction
import com.rm.acidulous.midi.launchpad.LpView

/**
 * Connects the Exquis to the app: its controls in, lights out. The logic is
 * in [ExquisSurface]; this carries messages. Controls arrive on the main
 * thread (MidiHub posts them), where the state lives and actions are safe.
 */
class ExquisController(private val act: (LpAction) -> Unit) {
    /** The app as last sampled; set before each frame. */
    @Volatile var view = LpView()
    var state = XqState()
        private set
    /** Stay on the Play page, so the pads only ever play. */
    var notesOnly = false
    /** How it's held, so the pages turn to match. */
    var hold = com.rm.acidulous.midi.exquis.XqHold.Upright

    fun attach() {
        MidiHub.exquisInput = { status, d1, d2 -> input(status, d1, d2) }
    }

    fun detach() {
        MidiHub.exquisInput = null
    }

    private fun input(status: Int, d1: Int, d2: Int) {
        val r = when {
            status == 0x9F || status == 0x8F -> ExquisSurface.pad(view, state, d1, status == 0x9F && d2 > 0)
            d1 == ExquisSurface.SLIDER_POSITION -> ExquisSurface.slide(view, state, d2)
            d1 in ExquisSurface.ENCODER_FIRST until ExquisSurface.ENCODER_FIRST + 4 ->
                ExquisSurface.turn(view, state, d1 - ExquisSurface.ENCODER_FIRST, d2 - 64)
            // Notes only: the clips button doesn't change page.
            notesOnly && d1 == ExquisSurface.CLIPS -> state to emptyList()
            else -> ExquisSurface.button(view, state, d1, d2 > 0)
        }
        state = r.first
        r.second.forEach(act)
    }

    /** Hands the hub the zones this page holds and what their lights show. */
    fun frame() {
        if (notesOnly && state.page != XqPage.Play) state = state.copy(page = XqPage.Play)
        if (state.hold != hold) state = state.copy(hold = hold)
        MidiHub.setExquisOctave(state.playOctave)
        MidiHub.showExquis(ExquisSurface.zones(state), ExquisSurface.render(view, state))
    }
}
