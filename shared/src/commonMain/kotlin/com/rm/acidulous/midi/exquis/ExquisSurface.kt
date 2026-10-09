package com.rm.acidulous.midi.exquis


import com.rm.acidulous.midi.launchpad.LpAction
import com.rm.acidulous.midi.launchpad.LpFader
import com.rm.acidulous.midi.launchpad.LpView
import com.rm.acidulous.midi.launchpad.Rgb
import com.rm.acidulous.midi.launchpad.Surface
import com.rm.acidulous.util.Math
import kotlin.math.sign

/**
 * The Exquis as a controller, held upright with its knobs at the top, through
 * its Developer Mode. Like the Launchpad's [Surface], this is the logic only:
 * the app as an [LpView] in, lights out by LED id, and presses back as
 * [LpAction]s. ui/exquis carries the bytes.
 *
 * The pads are 11 rows from the bottom, of 6 and 5 pads in turn (the rows of
 * 5 sit between those of 6). Developer Mode pads only say pressed or not, so
 * the Play page leaves them to the Exquis itself, with its velocity, pressure
 * and MPE. The other pages take them over. The arrows are always the app's,
 * so the Exquis stays at its own octave and the app knows which note is on
 * which pad (MidiHub moves its notes). Its settings and sound buttons always
 * stay its own.
 */
enum class XqPage { Play, Session, Mixer, Steps }

/** The surface's own state. */
data class XqState(
    val page: XqPage = XqPage.Play,
    /** The first track in view on Session and Mixer, at the top row. */
    val trackOffset: Int = 0,
    val sceneOffset: Int = 0,
    /** Which sixteen steps the Steps page shows. */
    val stepPage: Int = 0,
    /** The note Steps edits: a drum voice or a pitch. Null until one is chosen. */
    val lane: Int? = null,
    /** The octave the Steps page's notes start at, for a melodic track. C3 is 3. */
    val octave: Int = 3,
    /** Octaves the Exquis's own notes are moved on Play: the app holds its arrows, so its own octave stays put. */
    val playOctave: Int = 0,
    /** Which four of the played machine's knobs the encoders turn. */
    val knobBank: Int = 0,
    /** The slider portion being touched, or null. */
    val slider: Int? = null,
    /** Lane pads held on Steps, and the note each is sounding. */
    val sounding: Map<Int, Int> = emptyMap(),
    /** The page clips goes back to from Play. */
    val lastPage: XqPage = XqPage.Session,
    /** Clips is held, so the pads show the pages to choose from. */
    val choosing: Boolean = false,
    /** A page was chosen while clips was held, so letting go of it doesn't switch too. */
    val chose: Boolean = false,
)

object ExquisSurface {
    const val ROWS = 11
    const val PADS = 61

    // Developer Mode zones (its setup command's mask).
    const val ZONE_PADS = 0x01
    const val ZONE_ENCODERS = 0x02
    const val ZONE_SLIDER = 0x04
    const val ZONE_UPDOWN = 0x08
    const val ZONE_BUTTONS = 0x20

    // Control and LED ids.
    const val SLIDER_FIRST = 80
    const val SLIDER_POSITION = 90
    const val RECORD = 102
    const val LOOP = 103
    const val CLIPS = 104
    const val PLAY = 105
    const val DOWN = 106
    const val UP = 107
    const val UNDO = 108
    const val REDO = 109
    const val ENCODER_FIRST = 110
    const val ENCODER_PUSH_FIRST = 114

    /** Tracks on Session and Mixer: one per row above the bottom one. */
    const val TRACK_ROWS = 10
    /** Scenes across a track's row. A row of 6 leaves its last pad dark, so every row is the same. */
    const val SCENES = 5
    /** Steps on the Steps page: three rows of 6, 5 and 5. */
    const val STEPS = 16
    /** How far one click of an encoder moves a knob, of its whole range. */
    private const val TURN = 0.01f

    fun rowLength(row: Int) = if (row % 2 == 0) 6 else 5
    private val rowStart = IntArray(ROWS).also { s -> for (r in 1 until ROWS) s[r] = s[r - 1] + rowLength(r - 1) }
    fun padOf(row: Int, col: Int): Int = rowStart[row] + col
    fun rowOf(pad: Int): Int = (ROWS - 1 downTo 0).first { pad >= rowStart[it] }
    fun colOf(pad: Int): Int = pad - rowStart[rowOf(pad)]

    /** The zones a page takes over: everything but the settings and sound buttons, and on Play not the pads or arrows. */
    fun zones(state: XqState): Int {
        val always = ZONE_ENCODERS or ZONE_SLIDER or ZONE_BUTTONS or ZONE_UPDOWN
        return when {
            state.page != XqPage.Play -> always or ZONE_PADS
            // Holding clips on Play: the pads for a moment, to choose a page.
            state.choosing -> always or ZONE_PADS
            else -> always
        }
    }

    /** The pads that choose a page while clips is held, in the middle of the Exquis. */
    val PAGE_CHOICE: Map<Int, XqPage> = XqPage.entries.withIndex().associate { (i, p) -> padOf(6, 1 + i) to p }

    /** Every LED in the zones a page holds, so the controller knows what to draw. */
    fun ledsFor(state: XqState): List<Int> = buildList {
        if (state.page != XqPage.Play || state.choosing) addAll(0 until PADS)
        add(DOWN); add(UP)
        addAll(SLIDER_FIRST until SLIDER_FIRST + 6)
        addAll(listOf(RECORD, LOOP, CLIPS, PLAY, UNDO, REDO))
        addAll(ENCODER_FIRST until ENCODER_FIRST + 4)
    }

    /** What the clips button shows: which page is up. */
    private fun pageColour(page: XqPage): Int = when (page) {
        XqPage.Play -> Rgb.of(30, 30, 30)
        XqPage.Session -> Rgb.of(0, 110, 30)
        XqPage.Mixer -> Rgb.of(120, 60, 0)
        XqPage.Steps -> Rgb.of(0, 60, 127)
    }

    private fun maxTrackOffset(view: LpView) = maxOf(0, view.tracks.size - TRACK_ROWS)
    private fun maxSceneOffset(view: LpView) = maxOf(0, view.scenes - SCENES)

    /** The track on a Session or Mixer row, top to bottom, or null for the bottom row. */
    fun trackAt(state: XqState, row: Int): Int? = if (row in 1..TRACK_ROWS) state.trackOffset + (TRACK_ROWS - row) else null

    // --- Steps ------------------------------------------------------------------

    private val STEP_PADS: List<Int> = buildList {
        for (c in 0..5) add(padOf(10, c))
        for (c in 0..4) add(padOf(9, c))
        for (c in 0..4) add(padOf(8, c))
    }
    private val LANE_PADS: List<Int> = buildList { for (r in 6 downTo 2) for (c in 0 until rowLength(r)) add(padOf(r, c)) }
    private val PAGE_PADS: List<Int> = (0..5).map { padOf(0, it) }

    private fun scaleSteps(view: LpView): List<Int> =
        view.intervals?.map { Math.floorMod(it, 12) }?.distinct()?.sorted()?.takeIf { it.isNotEmpty() && view.root != null }
            ?: (0..11).toList()

    private fun scaleRoot(view: LpView): Int = if (view.intervals.isNullOrEmpty()) 0 else view.root ?: 0

    /** The notes the lane pads choose from: a drum machine's voices, or the scale up from the octave. */
    fun lanes(view: LpView, state: XqState): List<Int> {
        view.tracks.getOrNull(view.played)?.drums?.let { return it.take(LANE_PADS.size) }
        val s = scaleSteps(view)
        val base = 12 * (state.octave + 1) + scaleRoot(view)
        return (0 until LANE_PADS.size).map { base + 12 * (it / s.size) + s[it % s.size] }.filter { it in 0..127 }
    }

    /** The lane being edited: the one chosen, or the first. */
    fun lane(view: LpView, state: XqState): Int? = state.lane ?: lanes(view, state).firstOrNull()

    private fun stepPages(view: LpView): Int {
        val seq = view.seq ?: return 0
        return ((seq.length + STEPS * seq.grid - 1) / (STEPS * seq.grid)).coerceIn(1, PAGE_PADS.size)
    }

    /** The tick a step pad starts at, or null past the clip's end. */
    fun stepTick(view: LpView, state: XqState, step: Int): Int? {
        val seq = view.seq ?: return null
        return ((state.stepPage * STEPS + step) * seq.grid).takeIf { it < seq.length }
    }

    private fun stepOn(view: LpView, tick: Int, pitch: Int): Boolean {
        val seq = view.seq ?: return false
        return seq.notes.any { it.second == pitch && it.first >= tick && it.first < tick + seq.grid }
    }

    // --- Lights -----------------------------------------------------------------

    /** Every LED the page holds, as [Rgb]. */
    fun render(view: LpView, state: XqState): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        val played = view.tracks.getOrNull(view.played)
        val colour = played?.colour ?: Rgb.WHITE
        if (state.page != XqPage.Play || state.choosing) for (p in 0 until PADS) out[p] = Rgb.OFF
        if (state.choosing) {
            // The four pages in their colours, the one showing brightest.
            for ((pad, page) in PAGE_CHOICE) out[pad] = Rgb.scale(pageColour(page), if (page == state.page) 1f else 0.35f)
        } else when (state.page) {
            XqPage.Play -> Unit
            XqPage.Session -> session(view, state, out)
            XqPage.Mixer -> mixer(view, state, out)
            XqPage.Steps -> steps(view, state, colour, out)
        }
        // On Play the arrows are the octave: lit the way it's been moved.
        if (state.page == XqPage.Play) {
            out[UP] = if (state.playOctave > 0) Rgb.WHITE else Rgb.DIM
            out[DOWN] = if (state.playOctave < 0) Rgb.WHITE else Rgb.DIM
        }
        // The arrows, where they scroll: lit when there's more that way.
        if (state.page != XqPage.Play) {
            val (back, on) = when (state.page) {
                XqPage.Steps -> (view.tracks.getOrNull(view.played)?.drums == null && state.octave > 0) to
                    (view.tracks.getOrNull(view.played)?.drums == null && state.octave < 8)
                else -> (state.trackOffset > 0) to (state.trackOffset < maxTrackOffset(view))
            }
            out[UP] = if (back) Rgb.WHITE else Rgb.DIM
            out[DOWN] = if (on) Rgb.WHITE else Rgb.DIM
        }
        // The slider: the played track's level, white while it's being moved.
        val level = played?.level ?: 0f
        val lit = Math.round(level * 6f)
        for (i in 0..5) {
            out[SLIDER_FIRST + i] = when {
                i >= lit -> Rgb.OFF
                state.slider != null -> Rgb.WHITE
                else -> Rgb.scale(colour, 0.5f)
            }
        }
        knobs(view, state, colour, out)
        out[PLAY] = if (view.playing) Rgb.of(0, 127, 0) else Rgb.of(80, 36, 0)
        out[RECORD] = if (view.armed) Rgb.of(127, 0, 0) else Rgb.of(24, 0, 0)
        out[LOOP] = if (view.loop) Rgb.of(110, 80, 0) else Rgb.of(16, 16, 16)
        out[CLIPS] = pageColour(state.page)
        out[UNDO] = Rgb.of(40, 40, 40)
        out[REDO] = Rgb.of(40, 40, 40)
        return out
    }

    private fun session(view: LpView, state: XqState, out: HashMap<Int, Int>) {
        for (r in 1..TRACK_ROWS) {
            val t = trackAt(state, r) ?: continue
            val track = view.tracks.getOrNull(t) ?: continue
            for (c in 0 until SCENES) {
                val s = state.sceneOffset + c
                out[padOf(r, c)] = when {
                    s >= view.scenes || s !in track.clips -> Rgb.OFF
                    view.clipMode && track.playingScene == s -> Surface.pulse(track.colour, view.beat)
                    view.clipMode && track.queuedScene == s -> Surface.flash(track.colour, view.beat)
                    !view.clipMode && view.playing && view.scene == s -> Surface.pulse(track.colour, view.beat)
                    !view.clipMode && view.queuedScene == s -> Surface.flash(track.colour, view.beat)
                    else -> Rgb.scale(track.colour, 0.3f)
                }
            }
            // A row of 6's last pad, faintly, when there are more scenes to the right.
            if (rowLength(r) == 6 && state.sceneOffset + SCENES < view.scenes) out[padOf(r, 5)] = Rgb.of(14, 14, 14)
        }
        // The bottom row: the scenes, and stop on its sixth pad.
        for (c in 0 until SCENES) {
            val s = state.sceneOffset + c
            out[padOf(0, c)] = when {
                s >= view.scenes -> Rgb.OFF
                !view.clipMode && view.playing && view.scene == s -> Surface.pulse(Rgb.GREEN, view.beat)
                !view.clipMode && view.queuedScene == s -> Surface.flash(Rgb.GREEN, view.beat)
                else -> Rgb.scale(Rgb.GREEN, 0.25f)
            }
        }
        out[padOf(0, 5)] = if (view.playing) Rgb.scale(Rgb.RED, 0.7f) else Rgb.scale(Rgb.RED, 0.15f)
    }

    private fun mixer(view: LpView, state: XqState, out: HashMap<Int, Int>) {
        for (r in 1..TRACK_ROWS) {
            val t = trackAt(state, r) ?: continue
            val track = view.tracks.getOrNull(t) ?: continue
            out[padOf(r, 0)] = Rgb.scale(track.colour, if (t == view.played) 1f else 0.3f)
            out[padOf(r, 1)] = if (track.mute) Surface.MUTE else Rgb.scale(Surface.MUTE, 0.12f)
            out[padOf(r, 2)] = if (track.solo) Surface.SOLO else Rgb.scale(Surface.SOLO, 0.12f)
            val bar = rowLength(r) - 3
            val lit = Math.round(track.level * bar)
            for (i in 0 until bar) out[padOf(r, 3 + i)] = if (i < lit) Rgb.scale(track.colour, 0.8f) else Rgb.DIM
        }
    }

    private fun steps(view: LpView, state: XqState, colour: Int, out: HashMap<Int, Int>) {
        val seq = view.seq ?: return
        val lane = lane(view, state) ?: return
        val head = if (seq.playhead >= 0) seq.playhead / seq.grid else -1
        for ((i, pad) in STEP_PADS.withIndex()) {
            val tick = stepTick(view, state, i)
            val index = state.stepPage * STEPS + i
            out[pad] = when {
                tick == null -> Rgb.OFF
                index == head -> Rgb.WHITE
                stepOn(view, tick, lane) -> colour
                i % 4 == 0 -> Rgb.of(18, 18, 18)
                else -> Rgb.DIM
            }
        }
        val used = seq.notes.map { it.second }.toSet()
        val drums = view.tracks.getOrNull(view.played)?.drums != null
        for ((i, note) in lanes(view, state).withIndex()) {
            out[LANE_PADS[i]] = when {
                note == lane -> colour
                note in used -> Rgb.scale(colour, 0.3f)
                !drums && Math.floorMod(note - scaleRoot(view), 12) == 0 -> Rgb.of(20, 20, 20)
                else -> Rgb.of(6, 6, 6)
            }
        }
        val pages = stepPages(view)
        val headPage = if (head >= 0) head / STEPS else -1
        for ((i, pad) in PAGE_PADS.withIndex()) {
            out[pad] = when {
                i >= pages -> Rgb.OFF
                i == state.stepPage -> Rgb.WHITE
                i == headPage -> Surface.pulse(Rgb.scale(Rgb.WHITE, 0.4f), view.beat)
                else -> Rgb.of(16, 16, 16)
            }
        }
    }

    private fun knobs(view: LpView, state: XqState, colour: Int, out: HashMap<Int, Int>) {
        for (i in 0..3) {
            out[ENCODER_FIRST + i] = when (state.page) {
                // Brighter when there's more than fits: scenes on knob 1, tracks on knob 2.
                XqPage.Session -> when (i) {
                    0 -> if (view.scenes > SCENES) Rgb.of(90, 90, 90) else Rgb.of(14, 14, 14)
                    1 -> if (view.tracks.size > TRACK_ROWS) Rgb.of(90, 90, 90) else Rgb.of(14, 14, 14)
                    else -> Rgb.OFF
                }
                XqPage.Mixer -> view.tracks.getOrNull(state.trackOffset + i)?.let { Rgb.scale(it.colour, 0.1f + 0.9f * it.level) } ?: Rgb.OFF
                else -> view.device.getOrNull(state.knobBank * 4 + i)?.let { Rgb.scale(colour, 0.1f + 0.9f * it) } ?: Rgb.OFF
            }
        }
    }

    // --- Presses ----------------------------------------------------------------

    fun pad(view: LpView, state: XqState, pad: Int, down: Boolean): Pair<XqState, List<LpAction>> {
        if (state.choosing) {
            val page = PAGE_CHOICE[pad]
            if (!down || page == null) return state to emptyList()
            return go(state, page).copy(chose = true) to releaseAll(state)
        }
        if (pad !in 0 until PADS || state.page == XqPage.Play) return state to emptyList()
        if (!down) {
            val note = state.sounding[pad] ?: return state to emptyList()
            return state.copy(sounding = state.sounding - pad) to listOf(LpAction.NoteOff(note))
        }
        val row = rowOf(pad)
        val col = colOf(pad)
        return when (state.page) {
            XqPage.Session -> state to sessionPress(view, state, row, col)
            XqPage.Mixer -> state to mixerPress(view, state, row, col)
            XqPage.Steps -> stepsPress(view, state, pad)
            XqPage.Play -> state to emptyList()
        }
    }

    private fun sessionPress(view: LpView, state: XqState, row: Int, col: Int): List<LpAction> {
        if (row == 0) {
            if (col == 5) return listOf(LpAction.StopClips)
            val s = state.sceneOffset + col
            return if (s < view.scenes) listOf(LpAction.PlayScene(s)) else emptyList()
        }
        if (col >= SCENES) return emptyList()
        val t = trackAt(state, row) ?: return emptyList()
        val track = view.tracks.getOrNull(t) ?: return emptyList()
        val s = state.sceneOffset + col
        if (s >= view.scenes) return listOf(LpAction.SelectTrack(t))
        return when {
            view.clipMode -> listOf(LpAction.SelectTrack(t)) + (if (s in track.clips) listOf(LpAction.LaunchClip(t, s)) else emptyList())
            else -> listOf(LpAction.SelectTrack(t), LpAction.PlayScene(s))
        }
    }

    private fun mixerPress(view: LpView, state: XqState, row: Int, col: Int): List<LpAction> {
        val t = trackAt(state, row) ?: return emptyList()
        val track = view.tracks.getOrNull(t) ?: return emptyList()
        return when (col) {
            0 -> listOf(LpAction.SelectTrack(t))
            1 -> listOf(LpAction.ToggleMute(t))
            2 -> listOf(LpAction.ToggleSolo(t))
            else -> {
                val bar = rowLength(row) - 3
                val v = (col - 2).toFloat() / bar
                // The level's own pad again turns it down a step.
                val level = if (Math.round(track.level * bar) == col - 2) (col - 3).toFloat() / bar else v
                listOf(LpAction.SetMix(t, LpFader.Level, level))
            }
        }
    }

    private fun stepsPress(view: LpView, state: XqState, pad: Int): Pair<XqState, List<LpAction>> {
        val seq = view.seq ?: return state to emptyList()
        STEP_PADS.indexOf(pad).takeIf { it >= 0 }?.let { i ->
            val tick = stepTick(view, state, i) ?: return state to emptyList()
            val lane = lane(view, state) ?: return state to emptyList()
            return state to listOf(LpAction.ToggleStep(view.played, seq.scene, tick, lane, seq.grid))
        }
        LANE_PADS.indexOf(pad).takeIf { it >= 0 }?.let { i ->
            val note = lanes(view, state).getOrNull(i) ?: return state to emptyList()
            // Choosing a note plays it, so you hear what you're about to write.
            return state.copy(lane = note, sounding = state.sounding + (pad to note)) to listOf(LpAction.NoteOn(note, 100))
        }
        PAGE_PADS.indexOf(pad).takeIf { it >= 0 }?.let { i ->
            return (if (i < stepPages(view)) state.copy(stepPage = i) else state) to emptyList()
        }
        return state to emptyList()
    }

    /** A button, an arrow or an encoder's push. */
    fun button(view: LpView, state: XqState, id: Int, down: Boolean): Pair<XqState, List<LpAction>> {
        if (id == CLIPS) {
            // Held, the pads choose a page. Tapped, it goes between Play and
            // the last page used.
            if (down) return state.copy(choosing = true, chose = false) to emptyList()
            val held = state.copy(choosing = false)
            if (state.chose) return held to emptyList()
            val next = if (state.page == XqPage.Play) state.lastPage else XqPage.Play
            return go(held, next) to releaseAll(state)
        }
        if (!down) return state to emptyList()
        return when (id) {
            PLAY -> state to listOf(LpAction.Play)
            RECORD -> state to listOf(LpAction.Record)
            UNDO -> state to listOf(LpAction.Undo)
            REDO -> state to listOf(LpAction.Redo)
            LOOP -> state to listOf(LpAction.LoopScene)
            UP, DOWN -> arrow(view, state, if (id == UP) -1 else 1) to emptyList()
            // On Session, clicking knob 1 or 2 jumps to the next five scenes
            // or ten tracks, and back to the start after the last.
            ENCODER_PUSH_FIRST -> if (state.page == XqPage.Session) {
                val next = state.sceneOffset + SCENES
                state.copy(sceneOffset = if (next >= view.scenes) 0 else minOf(next, maxSceneOffset(view))) to emptyList()
            } else nextBank(view, state) to emptyList()
            ENCODER_PUSH_FIRST + 1 -> if (state.page == XqPage.Session) {
                val next = state.trackOffset + TRACK_ROWS
                state.copy(trackOffset = if (next >= view.tracks.size) 0 else minOf(next, maxTrackOffset(view))) to emptyList()
            } else nextBank(view, state) to emptyList()
            in ENCODER_PUSH_FIRST until ENCODER_PUSH_FIRST + 4 -> nextBank(view, state) to emptyList()
            else -> state to emptyList()
        }
    }

    /** To [page], remembering the page left if it wasn't Play. Notes held on Steps are let go. */
    private fun go(state: XqState, page: XqPage): XqState = state.copy(
        page = page,
        lastPage = if (page != XqPage.Play) page else if (state.page != XqPage.Play) state.page else state.lastPage,
        slider = null,
        sounding = emptyMap(),
    )

    private fun releaseAll(state: XqState): List<LpAction> = state.sounding.values.map { LpAction.NoteOff(it) }

    /** The next four of the machine's knobs, on Play and Steps. */
    private fun nextBank(view: LpView, state: XqState): XqState {
        if (state.page != XqPage.Play && state.page != XqPage.Steps) return state
        val banks = ((view.device.size + 3) / 4).coerceAtLeast(1)
        return state.copy(knobBank = (state.knobBank + 1) % banks)
    }

    /** Up is -1: earlier tracks, or an octave down on Steps. */
    private fun arrow(view: LpView, state: XqState, dir: Int): XqState = when (state.page) {
        XqPage.Steps -> if (view.tracks.getOrNull(view.played)?.drums == null) {
            state.copy(octave = (state.octave - dir).coerceIn(0, 8), lane = null)
        } else state
        // Up is -1 here, so take it away: up is an octave higher.
        XqPage.Play -> state.copy(playOctave = (state.playOctave - dir).coerceIn(-3, 3))
        else -> state.copy(trackOffset = (state.trackOffset + dir * TRACK_ROWS / 2).coerceIn(0, maxTrackOffset(view)))
    }

    /** An encoder turned by [delta] clicks, clockwise positive. */
    fun turn(view: LpView, state: XqState, encoder: Int, delta: Int): Pair<XqState, List<LpAction>> {
        if (encoder !in 0..3 || delta == 0) return state to emptyList()
        return when (state.page) {
            XqPage.Session -> when (encoder) {
                0 -> state.copy(sceneOffset = (state.sceneOffset + delta.sign).coerceIn(0, maxSceneOffset(view))) to emptyList()
                1 -> state.copy(trackOffset = (state.trackOffset + delta.sign).coerceIn(0, maxTrackOffset(view))) to emptyList()
                else -> state to emptyList()
            }
            XqPage.Mixer -> {
                val t = state.trackOffset + encoder
                val track = view.tracks.getOrNull(t) ?: return state to emptyList()
                state to listOf(LpAction.SetMix(t, LpFader.Level, (track.level + delta * TURN).coerceIn(0f, 1f)))
            }
            else -> {
                val i = state.knobBank * 4 + encoder
                val v = view.device.getOrNull(i) ?: return state to emptyList()
                state to listOf(LpAction.SetDevice(i, (v + delta * TURN).coerceIn(0f, 1f)))
            }
        }
    }

    /** The slider touched at [portion] 0..5, or let go (anything higher). Sliding moves the played track's level. */
    fun slide(view: LpView, state: XqState, portion: Int): Pair<XqState, List<LpAction>> {
        if (portion > 5) return state.copy(slider = null) to emptyList()
        val before = state.slider
        val track = view.tracks.getOrNull(view.played)
        val actions = if (before != null && portion != before && track != null) {
            listOf(LpAction.SetMix(view.played, LpFader.Level, (track.level + (portion - before) / 6f).coerceIn(0f, 1f)))
        } else emptyList()
        return state.copy(slider = portion) to actions
    }
}
