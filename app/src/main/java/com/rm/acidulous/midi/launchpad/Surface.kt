package com.rm.acidulous.midi.launchpad

import com.rm.acidulous.midi.launchpad.LaunchpadPro.Button
import com.rm.acidulous.midi.launchpad.LaunchpadPro.Control

/**
 * The Launchpad as Acidulous lays it out: what each page shows, and what
 * pressing anything does.
 *
 * Pure: a page is a function from [LpView] - the app, sampled - and
 * [LpState] - the surface's own page, octave and banks, and what is held -
 * to the colour of every LED; a press is a function to the next state and
 * the [LpAction]s the app should carry out. The controller in the UI layer
 * does the sampling and the carrying out; everything that decides anything
 * is here, where it can be tested.
 */

/** Colours as the device takes them: 0xRRGGBB, each part 0..127. */
object Rgb {
    const val OFF = 0
    fun of(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 127) shl 16) or (g.coerceIn(0, 127) shl 8) or b.coerceIn(0, 127)
    /** From an Android colour, 0xAARRGGBB with 0..255 parts. */
    fun fromArgb(argb: Int): Int = of(((argb shr 16) and 0xff) / 2, ((argb shr 8) and 0xff) / 2, (argb and 0xff) / 2)
    fun scale(c: Int, k: Float): Int = of(
        (((c shr 16) and 0x7f) * k).toInt(), (((c shr 8) and 0x7f) * k).toInt(), ((c and 0x7f) * k).toInt(),
    )
    val WHITE = of(127, 127, 127)
    val DIM = of(10, 10, 10)
    val GREEN = of(0, 127, 20)
    val RED = of(127, 0, 0)
}

enum class LpPage { Note, Session, Chord, Mixer, Sequencer, Perform }

/** One track as the surface needs it. */
data class LpTrack(
    /** In [Rgb]. */
    val colour: Int,
    /** A drum machine's voice notes in pad order, or null for a melodic one. */
    val drums: List<Int>? = null,
    /** The scenes, by index, this track has a clip in. */
    val clips: Set<Int> = emptySet(),
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** In clip mode: the scene it is playing, and the one it is waiting to, or -1. */
    val playingScene: Int = -1,
    val queuedScene: Int = -1,
)

/** The clip the sequencer page edits: the played track's, in the scene it is in. */
data class LpSeq(
    val scene: Int,
    /** A step, in ticks. */
    val grid: Int,
    /** The clip's length, in ticks. */
    val length: Int,
    /** Its notes as (tick, pitch). */
    val notes: List<Pair<Int, Int>> = emptyList(),
    /** Where the song is in it, or -1 when it is not playing. */
    val playhead: Int = -1,
)

/** The app, sampled. */
data class LpView(
    val tracks: List<LpTrack> = emptyList(),
    /** The track the surface plays. */
    val played: Int = 0,
    /** The song's key, or none. */
    val root: Int? = null,
    val intervals: List<Int>? = null,
    val playing: Boolean = false,
    val armed: Boolean = false,
    /** Where in the beat the song is, 0..1. */
    val beat: Float = 0f,
    val scenes: Int = 0,
    /** The grid as a launcher, not an arrangement. */
    val clipMode: Boolean = false,
    /** The scene the song is in, and the one it will go to next (song mode), or -1. */
    val scene: Int = 0,
    val queuedScene: Int = -1,
    /** The clip the sequencer edits, or null when there is no scene to edit in. */
    val seq: LpSeq? = null,
)

/** The surface's own state. */
data class LpState(
    val page: LpPage = LpPage.Note,
    /** The octave the bottom-left pad is in; C3 is 3, MIDI 48. */
    val octave: Int = 3,
    /** Scale degrees the note grid is shifted by. */
    val degree: Int = 0,
    val trackBank: Int = 0,
    val sceneBank: Int = 0,
    val shift: Boolean = false,
    /** Pads held down, by LED, and the note each is sounding. */
    val sounding: Map<Int, Int> = emptyMap(),
    /** Buttons held down that change what a pad or a track button does. */
    val held: Set<Button> = emptySet(),
    /** The sequencer's view: which eight steps, and which row is at the bottom. */
    val stepPage: Int = 0,
    val seqRow: Int = 0,
)

sealed class LpAction {
    data class NoteOn(val note: Int, val velocity: Int) : LpAction()
    data class NoteOff(val note: Int) : LpAction()
    data class Pressure(val note: Int, val value: Int) : LpAction()
    object Play : LpAction()
    object Record : LpAction()
    object Panic : LpAction()
    object Undo : LpAction()
    object Redo : LpAction()
    data class SelectTrack(val index: Int) : LpAction()
    data class PlayScene(val index: Int) : LpAction()
    data class LaunchClip(val track: Int, val scene: Int) : LpAction()
    data class ClearClip(val track: Int, val scene: Int) : LpAction()
    /** A clip copied into the scene below it, where that is empty. */
    data class CopyClipDown(val track: Int, val scene: Int) : LpAction()
    data class DuplicateScene(val scene: Int) : LpAction()
    data class ToggleMute(val track: Int) : LpAction()
    data class ToggleSolo(val track: Int) : LpAction()
    /** Every launched clip in clip mode, the song otherwise. */
    object StopClips : LpAction()
    /** A note one step long at [tick] and [pitch] in the played track's clip in [scene], or taken away if there is one. */
    data class ToggleStep(val track: Int, val scene: Int, val tick: Int, val pitch: Int, val length: Int) : LpAction()
    data class QuantiseClip(val track: Int, val scene: Int) : LpAction()
}

object Surface {
    /** Pages there are yet; their buttons light and choose them. */
    val built = setOf(LpPage.Note, LpPage.Session, LpPage.Sequencer)

    /** Buttons that change what the next press means while held. */
    private val modifiers = setOf(Button.Clear, Button.Duplicate, Button.Mute, Button.Solo)

    val MUTE = Rgb.of(127, 30, 0)
    val SOLO = Rgb.of(0, 60, 127)

    /** On the beat: bright on it, fading through it. */
    fun pulse(c: Int, beat: Float): Int = Rgb.scale(c, 0.35f + 0.65f * (1f - beat))
    /** Half a beat on, half off: waiting for its turn. */
    fun flash(c: Int, beat: Float): Int = if (beat < 0.5f) c else Rgb.scale(c, 0.12f)

    private val pageButtons = mapOf(
        Button.Note to LpPage.Note, Button.Session to LpPage.Session, Button.Chord to LpPage.Chord,
        Button.Custom to LpPage.Mixer, Button.Sequencer to LpPage.Sequencer, Button.Projects to LpPage.Perform,
    )

    // --- The note page --------------------------------------------------------

    /**
     * The scale the grid is in: the song's key, or every note from C when the
     * song has none.
     */
    private fun steps(view: LpView): List<Int> =
        view.intervals?.map { Math.floorMod(it, 12) }?.distinct()?.sorted()?.takeIf { it.isNotEmpty() && view.root != null }
            ?: (0..11).toList()

    private fun root(view: LpView): Int = if (view.intervals.isNullOrEmpty()) 0 else view.root ?: 0

    /**
     * How many scale degrees each row climbs: about a fourth, which is what
     * the Launchpad's own scale layout does - three in a seven-note scale, two
     * in a pentatonic, five when every note is there.
     */
    fun rowStep(notes: Int): Int = maxOf(1, Math.round(notes * 5f / 12f))

    /** The note a pad plays on the note page, or null past MIDI's range. */
    fun noteAt(view: LpView, state: LpState, row: Int, col: Int): Int? {
        drumsOf(view)?.let { voices -> return drumVoice(row, col)?.let { voices.getOrNull(it) } }
        val s = steps(view)
        val d = state.degree + col + row * rowStep(s.size)
        val octave = Math.floorDiv(d, s.size)
        val note = 12 * (state.octave + 1) + root(view) + 12 * octave + s[Math.floorMod(d, s.size)]
        return note.takeIf { it in 0..127 }
    }

    private fun drumsOf(view: LpView): List<Int>? = view.tracks.getOrNull(view.played)?.drums

    /**
     * A drum machine's voices as the pads of a drum rack: sixteen in a square
     * at the bottom left, counted along each row from the bottom, and the next
     * sixteen in the square beside it.
     */
    fun drumVoice(row: Int, col: Int): Int? =
        if (row in 0..3 && col in 0..7) (col / 4) * 16 + row * 4 + col % 4 else null

    // --- Drawing ----------------------------------------------------------------

    fun render(view: LpView, state: LpState): IntArray {
        val leds = IntArray(128)
        val colour = view.tracks.getOrNull(view.played)?.colour ?: Rgb.WHITE
        when (state.page) {
            LpPage.Note -> notePage(view, state, colour, leds)
            LpPage.Session -> sessionPage(view, state, leds)
            LpPage.Sequencer -> sequencerPage(view, state, colour, leds)
            else -> {}
        }
        // The pages, by their printed names.
        for ((b, page) in pageButtons) {
            leds[b.cc] = when {
                page == state.page -> Rgb.WHITE
                page in built -> Rgb.DIM
                else -> Rgb.OFF
            }
        }
        leds[Button.Shift.cc] = if (state.shift) Rgb.WHITE else Rgb.DIM
        leds[Button.Play.cc] = if (view.playing) Rgb.GREEN else Rgb.scale(Rgb.GREEN, 0.15f)
        leds[Button.Record.cc] = if (view.armed) Rgb.RED else Rgb.scale(Rgb.RED, 0.15f)
        for (b in listOf(Button.Up, Button.Down, Button.Left, Button.Right)) leds[b.cc] = Rgb.DIM
        val muting = Button.Mute in state.held
        val soloing = Button.Solo in state.held
        for (i in 0..7) {
            val t = state.trackBank * 8 + i
            val track = view.tracks.getOrNull(t)
            leds[LaunchpadPro.ledOf(Control.Track(i))] = when {
                track == null -> Rgb.OFF
                // While Mute or Solo is held, the row is what they would change.
                muting -> if (track.mute) MUTE else Rgb.scale(track.colour, 0.2f)
                soloing -> if (track.solo) SOLO else Rgb.scale(track.colour, 0.2f)
                t == view.played -> track.colour
                else -> Rgb.scale(track.colour, 0.2f)
            }
            val s = state.sceneBank * 8 + i
            val playingHere = if (view.clipMode) view.tracks.any { it.playingScene == s } else view.playing && view.scene == s
            val queuedHere = if (view.clipMode) view.tracks.any { it.queuedScene == s } else view.queuedScene == s
            leds[LaunchpadPro.ledOf(Control.Scene(i))] = when {
                s >= view.scenes -> Rgb.OFF
                playingHere -> pulse(Rgb.GREEN, view.beat)
                queuedHere -> flash(Rgb.GREEN, view.beat)
                else -> Rgb.scale(Rgb.GREEN, 0.25f)
            }
        }
        // The track controls this far: record, mute and solo, and stop.
        leds[Button.RecordArm.cc] = leds[Button.Record.cc]
        leds[Button.Mute.cc] = if (muting || view.tracks.any { it.mute }) MUTE else Rgb.scale(MUTE, 0.15f)
        leds[Button.Solo.cc] = if (soloing || view.tracks.any { it.solo }) SOLO else Rgb.scale(SOLO, 0.15f)
        leds[Button.StopClip.cc] = if (view.playing) Rgb.scale(Rgb.RED, 0.6f) else Rgb.scale(Rgb.RED, 0.15f)
        leds[Button.Quantise.cc] = if (view.seq != null) Rgb.DIM else Rgb.OFF
        // Clear and Duplicate are live on the session page, and undo and redo under Shift.
        if (state.page == LpPage.Session || state.shift) {
            leds[Button.Clear.cc] = if (Button.Clear in state.held) Rgb.WHITE else Rgb.DIM
            leds[Button.Duplicate.cc] = if (Button.Duplicate in state.held) Rgb.WHITE else Rgb.DIM
        }
        // The logo keeps the beat while the song plays.
        leds[Button.Logo.cc] = if (view.playing) Rgb.scale(colour, 1f - 0.85f * view.beat) else Rgb.scale(colour, 0.3f)
        return leds
    }

    private fun notePage(view: LpView, state: LpState, colour: Int, leds: IntArray) {
        val held = state.sounding.values.toSet()
        val drums = drumsOf(view)
        val s = steps(view)
        val r = root(view)
        for (row in 0..7) for (col in 0..7) {
            val led = LaunchpadPro.ledOf(Control.Pad(row, col))
            val note = noteAt(view, state, row, col)
            leds[led] = when {
                note == null -> Rgb.OFF
                led in state.sounding || note in held -> Rgb.WHITE
                drums != null -> Rgb.scale(colour, 0.35f)
                // The root is the track's colour; the rest of the scale a
                // shade of it, so the octaves read at a glance.
                Math.floorMod(note - r, 12) == 0 -> colour
                s.size >= 12 && Math.floorMod(note, 12) in setOf(1, 3, 6, 8, 10) -> Rgb.DIM
                else -> Rgb.scale(colour, 0.18f)
            }
        }
    }

    /** Where a session pad points: its track and its scene, top row first. */
    fun sessionCell(state: LpState, row: Int, col: Int): Pair<Int, Int> =
        (state.trackBank * 8 + col) to (state.sceneBank * 8 + (7 - row))

    private fun sessionPage(view: LpView, state: LpState, leds: IntArray) {
        for (row in 0..7) for (col in 0..7) {
            val (t, s) = sessionCell(state, row, col)
            val track = view.tracks.getOrNull(t)
            val led = LaunchpadPro.ledOf(Control.Pad(row, col))
            leds[led] = when {
                track == null || s >= view.scenes || s !in track.clips -> Rgb.OFF
                view.clipMode && track.playingScene == s -> pulse(track.colour, view.beat)
                view.clipMode && track.queuedScene == s -> flash(track.colour, view.beat)
                !view.clipMode && view.playing && view.scene == s -> pulse(track.colour, view.beat)
                !view.clipMode && view.queuedScene == s -> flash(track.colour, view.beat)
                else -> Rgb.scale(track.colour, 0.3f)
            }
        }
    }

    private fun sessionPress(view: LpView, state: LpState, pad: Control.Pad): List<LpAction> {
        val (t, s) = sessionCell(state, pad.row, pad.col)
        val track = view.tracks.getOrNull(t) ?: return emptyList()
        if (s >= view.scenes) return emptyList()
        val has = s in track.clips
        return when {
            Button.Clear in state.held -> if (has) listOf(LpAction.ClearClip(t, s)) else emptyList()
            Button.Duplicate in state.held ->
                if (has && s + 1 < view.scenes && (s + 1) !in track.clips) listOf(LpAction.CopyClipDown(t, s)) else emptyList()
            view.clipMode -> listOf(LpAction.SelectTrack(t)) + (if (has) listOf(LpAction.LaunchClip(t, s)) else emptyList())
            else -> listOf(LpAction.SelectTrack(t), LpAction.PlayScene(s))
        }
    }

    /** The pitch a sequencer row edits: a scale note up from the note page's octave, or a drum voice. */
    fun seqPitch(view: LpView, state: LpState, row: Int): Int? {
        val idx = state.seqRow + row
        drumsOf(view)?.let { return it.getOrNull(idx) }
        val s = steps(view)
        val note = 12 * (state.octave + 1) + root(view) + 12 * Math.floorDiv(idx, s.size) + s[Math.floorMod(idx, s.size)]
        return note.takeIf { it in 0..127 }
    }

    /** The first tick of a sequencer column, or null past the clip's end. */
    fun seqTick(view: LpView, state: LpState, col: Int): Int? {
        val seq = view.seq ?: return null
        val tick = (state.stepPage * 8 + col) * seq.grid
        return tick.takeIf { it < seq.length }
    }

    private fun sequencerPage(view: LpView, state: LpState, colour: Int, leds: IntArray) {
        val seq = view.seq ?: return
        val r = root(view)
        val drums = drumsOf(view) != null
        for (col in 0..7) {
            val tick = seqTick(view, state, col)
            val here = tick != null && seq.playhead >= tick && seq.playhead < tick + seq.grid
            for (row in 0..7) {
                val led = LaunchpadPro.ledOf(Control.Pad(row, col))
                val pitch = seqPitch(view, state, row)
                val on = tick != null && pitch != null && seq.notes.any { (t, p) -> p == pitch && t >= tick && t < tick + seq.grid }
                leds[led] = when {
                    tick == null || pitch == null -> Rgb.OFF
                    on && here -> Rgb.WHITE
                    on -> colour
                    here -> Rgb.scale(Rgb.WHITE, 0.15f)
                    // The root's rows, faintly, so the scale can be read.
                    !drums && Math.floorMod(pitch - r, 12) == 0 -> Rgb.scale(colour, 0.12f)
                    else -> Rgb.scale(Rgb.WHITE, 0.03f)
                }
            }
        }
    }

    // --- Pressing ----------------------------------------------------------------

    fun press(view: LpView, state: LpState, control: Control, velocity: Int): Pair<LpState, List<LpAction>> {
        when (control) {
            is Control.Pad -> {
                if (state.page == LpPage.Session) return state to sessionPress(view, state, control)
                if (state.page == LpPage.Sequencer) {
                    val seq = view.seq ?: return state to emptyList()
                    val tick = seqTick(view, state, control.col) ?: return state to emptyList()
                    val pitch = seqPitch(view, state, control.row) ?: return state to emptyList()
                    return state to listOf(LpAction.ToggleStep(view.played, seq.scene, tick, pitch, seq.grid))
                }
                if (state.page != LpPage.Note) return state to emptyList()
                val note = noteAt(view, state, control.row, control.col) ?: return state to emptyList()
                val led = LaunchpadPro.ledOf(control)
                return state.copy(sounding = state.sounding + (led to note)) to
                    listOf(LpAction.NoteOn(note, velocity.coerceIn(1, 127)))
            }
            is Control.Track -> {
                val t = state.trackBank * 8 + control.index
                if (t >= view.tracks.size) return state to emptyList()
                return state to listOf(
                    when {
                        Button.Mute in state.held -> LpAction.ToggleMute(t)
                        Button.Solo in state.held -> LpAction.ToggleSolo(t)
                        else -> LpAction.SelectTrack(t)
                    },
                )
            }
            is Control.Scene -> {
                val s = state.sceneBank * 8 + control.index
                if (s >= view.scenes) return state to emptyList()
                return state to listOf(if (Button.Duplicate in state.held) LpAction.DuplicateScene(s) else LpAction.PlayScene(s))
            }
            is Control.Key -> return key(view, state, control.button)
        }
    }

    private fun key(view: LpView, state: LpState, b: Button): Pair<LpState, List<LpAction>> {
        pageButtons[b]?.let { page ->
            return (if (page in built) state.copy(page = page) else state) to emptyList()
        }
        // Clear, Duplicate, Mute and Solo mean something only with the next
        // press; undo and redo are Clear and Duplicate under Shift.
        if (b in modifiers && !(state.shift && (b == Button.Clear || b == Button.Duplicate))) {
            return state.copy(held = state.held + b) to emptyList()
        }
        val session = state.page == LpPage.Session
        if (state.page == LpPage.Sequencer) seqKey(view, state, b)?.let { return it to emptyList() }
        return when (b) {
            Button.Shift -> state.copy(shift = true) to emptyList()
            Button.RecordArm -> state to listOf(LpAction.Record)
            Button.StopClip -> state to listOf(LpAction.StopClips)
            Button.Quantise -> state to (view.seq?.let { listOf(LpAction.QuantiseClip(view.played, it.scene)) } ?: emptyList())
            // On the session page the arrows move the view of the grid.
            Button.Up -> (if (session) state.copy(sceneBank = (state.sceneBank - 1).coerceAtLeast(0))
            else state.copy(octave = (state.octave + 1).coerceAtMost(8))) to emptyList()
            Button.Down -> (if (session) state.copy(sceneBank = (state.sceneBank + 1).coerceAtMost(maxBank(view.scenes)))
            else state.copy(octave = (state.octave - 1).coerceAtLeast(-1))) to emptyList()
            Button.Left -> (if (state.shift || session) state.copy(trackBank = (state.trackBank - 1).coerceAtLeast(0))
            else state.copy(degree = state.degree - 1)) to emptyList()
            Button.Right -> (if (state.shift || session) state.copy(trackBank = (state.trackBank + 1).coerceAtMost(maxBank(view.tracks.size)))
            else state.copy(degree = state.degree + 1)) to emptyList()
            Button.Play -> state to listOf(if (state.shift) LpAction.Panic else LpAction.Play)
            Button.Record -> state to listOf(LpAction.Record)
            Button.Clear -> state to if (state.shift) listOf(LpAction.Undo) else emptyList()
            Button.Duplicate -> state to if (state.shift) listOf(LpAction.Redo) else emptyList()
            else -> state to emptyList()
        }
    }

    fun release(state: LpState, control: Control): Pair<LpState, List<LpAction>> = when (control) {
        is Control.Pad -> {
            val led = LaunchpadPro.ledOf(control)
            val note = state.sounding[led]
            // The note the pad started, whatever the grid says now: an octave
            // moved while it was held must not leave it sounding.
            (state.copy(sounding = state.sounding - led)) to (if (note != null) listOf(LpAction.NoteOff(note)) else emptyList())
        }
        is Control.Key -> state.copy(
            shift = if (control.button == Button.Shift) false else state.shift,
            held = state.held - control.button,
        ) to emptyList()
        else -> state to emptyList()
    }

    /** A held pad pressed harder: that note's pressure. */
    fun pressure(state: LpState, pad: Control.Pad, value: Int): List<LpAction> =
        state.sounding[LaunchpadPro.ledOf(pad)]?.let { listOf(LpAction.Pressure(it, value)) } ?: emptyList()

    private fun maxBank(tracks: Int): Int = maxOf(0, (tracks - 1) / 8)

    /** The arrows on the sequencer page: steps across, rows up and down. Null for anything else. */
    private fun seqKey(view: LpView, state: LpState, b: Button): LpState? {
        val seq = view.seq
        val lastPage = if (seq == null) 0 else maxOf(0, (seq.length / maxOf(1, seq.grid) - 1) / 8)
        val lastRow = drumsOf(view)?.let { maxOf(0, it.size - 8) }
        return when (b) {
            Button.Left -> state.copy(stepPage = (state.stepPage - 1).coerceAtLeast(0))
            Button.Right -> state.copy(stepPage = (state.stepPage + 1).coerceAtMost(lastPage))
            Button.Up -> state.copy(seqRow = if (lastRow != null) (state.seqRow + 1).coerceAtMost(lastRow) else state.seqRow + 1)
            Button.Down -> state.copy(seqRow = if (lastRow != null) (state.seqRow - 1).coerceAtLeast(0) else state.seqRow - 1)
            else -> null
        }
    }
}
