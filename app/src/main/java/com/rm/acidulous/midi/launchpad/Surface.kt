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
}

object Surface {
    /** Pages there are yet; their buttons light and choose them. */
    val built = setOf(LpPage.Note)

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
        // Undo and redo, while Shift says so.
        leds[Button.Clear.cc] = if (state.shift) Rgb.DIM else Rgb.OFF
        leds[Button.Duplicate.cc] = if (state.shift) Rgb.DIM else Rgb.OFF
        for (i in 0..7) {
            val t = state.trackBank * 8 + i
            val track = view.tracks.getOrNull(t)
            leds[LaunchpadPro.ledOf(Control.Track(i))] = when {
                track == null -> Rgb.OFF
                t == view.played -> track.colour
                else -> Rgb.scale(track.colour, 0.2f)
            }
            val s = state.sceneBank * 8 + i
            leds[LaunchpadPro.ledOf(Control.Scene(i))] = if (s < view.scenes) Rgb.scale(Rgb.GREEN, 0.25f) else Rgb.OFF
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

    // --- Pressing ----------------------------------------------------------------

    fun press(view: LpView, state: LpState, control: Control, velocity: Int): Pair<LpState, List<LpAction>> {
        when (control) {
            is Control.Pad -> {
                if (state.page != LpPage.Note) return state to emptyList()
                val note = noteAt(view, state, control.row, control.col) ?: return state to emptyList()
                val led = LaunchpadPro.ledOf(control)
                return state.copy(sounding = state.sounding + (led to note)) to
                    listOf(LpAction.NoteOn(note, velocity.coerceIn(1, 127)))
            }
            is Control.Track -> {
                val t = state.trackBank * 8 + control.index
                return state to if (t < view.tracks.size) listOf(LpAction.SelectTrack(t)) else emptyList()
            }
            is Control.Scene -> {
                val s = state.sceneBank * 8 + control.index
                return state to if (s < view.scenes) listOf(LpAction.PlayScene(s)) else emptyList()
            }
            is Control.Key -> return key(view, state, control.button)
        }
    }

    private fun key(view: LpView, state: LpState, b: Button): Pair<LpState, List<LpAction>> {
        pageButtons[b]?.let { page ->
            return (if (page in built) state.copy(page = page) else state) to emptyList()
        }
        return when (b) {
            Button.Shift -> state.copy(shift = true) to emptyList()
            Button.Play -> state to listOf(if (state.shift) LpAction.Panic else LpAction.Play)
            Button.Record -> state to listOf(LpAction.Record)
            Button.Clear -> state to if (state.shift) listOf(LpAction.Undo) else emptyList()
            Button.Duplicate -> state to if (state.shift) listOf(LpAction.Redo) else emptyList()
            Button.Up -> state.copy(octave = (state.octave + 1).coerceAtMost(8)) to emptyList()
            Button.Down -> state.copy(octave = (state.octave - 1).coerceAtLeast(-1)) to emptyList()
            // With Shift, the arrows page the tracks; without, they walk the scale.
            Button.Left -> (if (state.shift) state.copy(trackBank = (state.trackBank - 1).coerceAtLeast(0))
            else state.copy(degree = state.degree - 1)) to emptyList()
            Button.Right -> (if (state.shift) state.copy(trackBank = (state.trackBank + 1).coerceAtMost(maxBank(view.tracks.size)))
            else state.copy(degree = state.degree + 1)) to emptyList()
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
        is Control.Key -> (if (control.button == Button.Shift) state.copy(shift = false) else state) to emptyList()
        else -> state to emptyList()
    }

    /** A held pad pressed harder: that note's pressure. */
    fun pressure(state: LpState, pad: Control.Pad, value: Int): List<LpAction> =
        state.sounding[LaunchpadPro.ledOf(pad)]?.let { listOf(LpAction.Pressure(it, value)) } ?: emptyList()

    private fun maxBank(tracks: Int): Int = maxOf(0, (tracks - 1) / 8)
}
