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
    /** A drum machine's voice notes lowest first - the drum grid's order - or null for a melodic one. */
    val drums: List<Int>? = null,
    /** The same voices in the order the app lays its pads out, where that differs. */
    val pads: List<Int>? = null,
    /** The scenes, by index, this track has a clip in. */
    val clips: Set<Int> = emptySet(),
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** In clip mode: the scene it is playing, and the one it is waiting to, or -1. */
    val playingScene: Int = -1,
    val queuedScene: Int = -1,
    /** Its mixer, each 0..1: level, pan (0.5 the middle), and the two sends. */
    val level: Float = 0f,
    val pan: Float = 0.5f,
    val sendA: Float = 0f,
    val sendB: Float = 0f,
)

/** What the mixer page's faders are. */
enum class LpFader { Level, Pan, SendA, SendB, Device }

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
    /** The played track's scale - its own, or the song's key - or none. */
    val root: Int? = null,
    val intervals: List<Int>? = null,
    /**
     * The scale is the track's own Scale modifier, so notes outside it are
     * snapped away anyway: the note page leaves them out and fits more
     * octaves instead.
     */
    val scaleLocked: Boolean = false,
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
    /** The played machine's first eight continuous knobs, 0..1, for the device faders. */
    val device: List<Float> = emptyList(),
)

/** The surface's own state. */
data class LpState(
    val page: LpPage = LpPage.Note,
    /** The octave the bottom-left pad is in; C3 is 3, MIDI 48. */
    val octave: Int = 3,
    /** Scale degrees the note grid is shifted by. */
    val degree: Int = 0,
    /** The first track and the first scene in view: the arrows move them one at a time. */
    val trackOffset: Int = 0,
    val sceneOffset: Int = 0,
    val shift: Boolean = false,
    /** Pads held down, by LED, and the notes each is sounding - several for a chord. */
    val sounding: Map<Int, List<Int>> = emptyMap(),
    /** Buttons held down that change what a pad or a track button does. */
    val held: Set<Button> = emptySet(),
    /** The sequencer's view: which eight steps, and which row is at the bottom. */
    val stepOffset: Int = 0,
    val seqRow: Int = 0,
    val fader: LpFader = LpFader.Level,
    /** Perform pads held, by LED, oldest first: the newest of a row is the one in force. */
    val performing: List<Int> = emptyList(),
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
    data class SetMix(val track: Int, val fader: LpFader, val value: Float) : LpAction()
    /** One of the played machine's knobs, by its place among the eight. */
    data class SetDevice(val index: Int, val value: Float) : LpAction()
    /** A perform parameter on the played track, as the perform page sends it. */
    data class PerformParam(val name: String, val value: Float) : LpAction()
}

object Surface {
    /** Pages there are yet; their buttons light and choose them. */
    val built = LpPage.entries.toSet()

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

    // The note page is the app's keyboard: a piano, two rows to an octave -
    // the white keys, and the black keys above them, each over the white key
    // to its right - four octaves up the grid from the octave's C at the
    // bottom left. Every note is there, and the scale is what is lit, as the
    // keys on screen show it: a layout of only the scale's notes played the
    // scale and showed nothing of it.
    private val WHITE_KEYS = listOf(0, 2, 4, 5, 7, 9, 11, 12)
    private val BLACK_KEYS = listOf(null, 1, 3, null, 6, 8, 10, null)

    /**
     * With the track's own scale in force, only its notes: an octave a row,
     * from the root at the left to the root above it, eight octaves up the
     * grid from the one below the octave's - where the piano fits four.
     */
    private fun compact(view: LpView): Boolean = view.scaleLocked && !view.intervals.isNullOrEmpty() && view.root != null

    /** The note a pad plays on the note page, or null for a gap or past MIDI's range. */
    fun noteAt(view: LpView, state: LpState, row: Int, col: Int): Int? {
        padsOf(view)?.let { voices -> return drumVoice(row, col, voices.size)?.let { voices.getOrNull(it) } }
        if (compact(view)) {
            val s = steps(view)
            // Up to the root above, which a seven-note scale fills the row with.
            if (col > s.size) return null
            val semis = if (col == s.size) 12 else s[col]
            val note = 12 * state.octave + 12 * row + root(view) + semis
            return note.takeIf { it in 0..127 }
        }
        val semis = (if (row % 2 == 0) WHITE_KEYS[col] else BLACK_KEYS[col]) ?: return null
        val note = 12 * (state.octave + 1) + 12 * (row / 2) + semis
        return note.takeIf { it in 0..127 }
    }

    private fun drumsOf(view: LpView): List<Int>? = view.tracks.getOrNull(view.played)?.drums
    private fun padsOf(view: LpView): List<Int>? = view.tracks.getOrNull(view.played)?.let { it.pads ?: it.drums }

    /**
     * A drum machine's pads as the app lays them out on screen: pad one at the
     * bottom left, the smaller half along the bottom row and the rest in the
     * row above - thirteen are six and seven. A machine with more than
     * sixteen goes on up in rows of eight. The index into its pads, or null.
     */
    fun drumVoice(row: Int, col: Int, count: Int): Int? {
        val rows: List<IntRange> = if (count <= 16) {
            val bottom = count / 2
            if (bottom == 0) listOf(0 until count) else listOf(0 until bottom, bottom until count)
        } else {
            (0 until count step 8).map { it until minOf(it + 8, count) }
        }
        val r = rows.getOrNull(row) ?: return null
        return if (col < r.count()) r.first + col else null
    }

    // --- Drawing ----------------------------------------------------------------

    fun render(view: LpView, state: LpState): IntArray {
        val leds = IntArray(128)
        val colour = view.tracks.getOrNull(view.played)?.colour ?: Rgb.WHITE
        when (state.page) {
            LpPage.Note -> notePage(view, state, colour, leds)
            LpPage.Session -> sessionPage(view, state, leds)
            LpPage.Sequencer -> sequencerPage(view, state, colour, leds)
            LpPage.Mixer -> mixerPage(view, state, leds)
            LpPage.Perform -> performPage(state, leds)
            LpPage.Chord -> chordPage(view, state, colour, leds)
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
        fun trackLed(t: Int): Int {
            val track = view.tracks.getOrNull(t) ?: return Rgb.OFF
            return when {
                // While Mute or Solo is held, the buttons are what they would change.
                muting -> if (track.mute) MUTE else Rgb.scale(track.colour, 0.2f)
                soloing -> if (track.solo) SOLO else Rgb.scale(track.colour, 0.2f)
                t == view.played -> track.colour
                else -> Rgb.scale(track.colour, 0.2f)
            }
        }
        fun sceneLed(s: Int): Int {
            val playingHere = if (view.clipMode) view.tracks.any { it.playingScene == s } else view.playing && view.scene == s
            val queuedHere = if (view.clipMode) view.tracks.any { it.queuedScene == s } else view.queuedScene == s
            return when {
                s >= view.scenes -> Rgb.OFF
                playingHere -> pulse(Rgb.GREEN, view.beat)
                queuedHere -> flash(Rgb.GREEN, view.beat)
                else -> Rgb.scale(Rgb.GREEN, 0.25f)
            }
        }
        // The app's way round, on every page: tracks are rows, so the column
        // beside the grid is the tracks, top to bottom, and the row under it
        // the scenes, left to right - as the song grid on screen is.
        for (i in 0..7) {
            leds[LaunchpadPro.ledOf(Control.Scene(i))] = trackLed(state.trackOffset + i)
            leds[LaunchpadPro.ledOf(Control.Track(i))] = sceneLed(state.sceneOffset + i)
        }
        // Where the arrows move the tracks and scenes, one is lit when there is more that way.
        if (navigates(state)) {
            val lit = { can: Boolean -> if (can) Rgb.WHITE else Rgb.OFF }
            leds[Button.Up.cc] = lit(state.trackOffset > 0)
            leds[Button.Down.cc] = lit(state.trackOffset < maxOffset(view.tracks.size))
            leds[Button.Left.cc] = lit(state.sceneOffset > 0)
            leds[Button.Right.cc] = lit(state.sceneOffset < maxOffset(view.scenes))
        }
        // The track controls this far: record, mute and solo, and stop.
        leds[Button.RecordArm.cc] = leds[Button.Record.cc]
        leds[Button.Mute.cc] = if (muting || view.tracks.any { it.mute }) MUTE else Rgb.scale(MUTE, 0.15f)
        leds[Button.Solo.cc] = if (soloing || view.tracks.any { it.solo }) SOLO else Rgb.scale(SOLO, 0.15f)
        leds[Button.StopClip.cc] = if (view.playing) Rgb.scale(Rgb.RED, 0.6f) else Rgb.scale(Rgb.RED, 0.15f)
        leds[Button.Quantise.cc] = if (view.seq != null) Rgb.DIM else Rgb.OFF
        val mixing = state.page == LpPage.Mixer
        for ((b, f) in listOf(Button.Volume to LpFader.Level, Button.Pan to LpFader.Pan, Button.Device to LpFader.Device)) {
            leds[b.cc] = if (mixing && state.fader == f) Rgb.WHITE else Rgb.DIM
        }
        leds[Button.Sends.cc] = if (mixing && (state.fader == LpFader.SendA || state.fader == LpFader.SendB)) Rgb.WHITE else Rgb.DIM
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
        val held = state.sounding.values.flatten().toSet()
        val drums = drumsOf(view)
        val inKey = steps(view).map { (it + root(view)) % 12 }.toSet()
        val keyed = !view.intervals.isNullOrEmpty() && view.root != null
        val r = root(view)
        for (row in 0..7) for (col in 0..7) {
            val led = LaunchpadPro.ledOf(Control.Pad(row, col))
            val note = noteAt(view, state, row, col)
            leds[led] = when {
                note == null -> Rgb.OFF
                led in state.sounding || note in held -> Rgb.WHITE
                drums != null -> Rgb.scale(colour, 0.35f)
                // The root in the track's colour, the rest of the scale a
                // shade of it, and what is not in the scale barely lit - there
                // to play, and plainly not in key. With no key at all, the Cs
                // are the landmarks, as on a piano.
                Math.floorMod(note - r, 12) == 0 -> colour
                compact(view) -> Rgb.scale(colour, 0.3f)
                Math.floorMod(note, 12) in inKey && keyed -> Rgb.scale(colour, 0.3f)
                !keyed -> Rgb.scale(Rgb.WHITE, if (row % 2 == 0) 0.1f else 0.04f)
                else -> Rgb.scale(Rgb.WHITE, 0.03f)
            }
        }
    }

    /** The pages whose arrows move the tracks and scenes in view - or any page, with Shift. */
    private fun navigates(state: LpState): Boolean =
        state.page == LpPage.Session || state.page == LpPage.Mixer || state.shift

    /**
     * Where a session pad points: its track and its scene. The app's grid,
     * the right way round - the tracks down from the top row, the scenes
     * across from the left.
     */
    fun sessionCell(state: LpState, row: Int, col: Int): Pair<Int, Int> =
        (state.trackOffset + (7 - row)) to (state.sceneOffset + col)

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
        // A drum machine reads down from the kick, as its grid on screen does.
        drumsOf(view)?.let { return it.getOrNull(state.seqRow + (7 - row)) }
        val idx = state.seqRow + row
        val s = steps(view)
        val note = 12 * (state.octave + 1) + root(view) + 12 * Math.floorDiv(idx, s.size) + s[Math.floorMod(idx, s.size)]
        return note.takeIf { it in 0..127 }
    }

    /** The first tick of a sequencer column, or null past the clip's end. */
    fun seqTick(view: LpView, state: LpState, col: Int): Int? {
        val seq = view.seq ?: return null
        val tick = (state.stepOffset + col) * seq.grid
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

    /** A fader's value on a track, 0..1. */
    private fun mixValue(track: LpTrack, f: LpFader): Float = when (f) {
        LpFader.Level -> track.level
        LpFader.Pan -> track.pan
        LpFader.SendA -> track.sendA
        LpFader.SendB -> track.sendB
        LpFader.Device -> 0f
    }

    /** The value a fader pad sets: the left column nought, the right one full. */
    fun faderValue(col: Int): Float = col / 7f

    /**
     * The mixer as the app's grid lies: a row for each track, the top track
     * at the top, and its fader running left to right across the row. With
     * Device the rows are the played machine's eight knobs, first at the top.
     */
    private fun mixerPage(view: LpView, state: LpState, leds: IntArray) {
        val colour = view.tracks.getOrNull(view.played)?.colour ?: Rgb.WHITE
        for (row in 0..7) {
            val line = 7 - row
            val (c, v) = if (state.fader == LpFader.Device) {
                colour to (view.device.getOrNull(line) ?: continue)
            } else {
                val track = view.tracks.getOrNull(state.trackOffset + line) ?: continue
                track.colour to mixValue(track, state.fader)
            }
            val level = Math.round(v * 7f)
            for (col in 0..7) {
                val lit = if (state.fader == LpFader.Pan) {
                    // Pan fills from the middle towards its side.
                    (col in minOf(level, 4)..maxOf(level, 3))
                } else col <= level && v > 0f
                leds[LaunchpadPro.ledOf(Control.Pad(row, col))] = when {
                    col == level -> c
                    lit -> Rgb.scale(c, 0.3f)
                    else -> Rgb.scale(Rgb.WHITE, 0.03f)
                }
            }
        }
    }

    // The perform page: repeat and gate lengths along the top two rows,
    // reverse, tape stop and the riser, the three kills, and under them an
    // XY pad four rows high. All held: let go and it lets go.
    private val REPEAT_ROW = 7
    private val GATE_ROW = 6
    private val MOMENT_ROW = 5
    private val KILL_ROW = 4
    private val MOMENTS = listOf("reverse", "stop", "riser")
    private val KILL_NAMES = listOf("killlow", "killmid", "killhigh")
    private const val LENGTHS = 5

    private fun performPage(state: LpState, leds: IntArray) {
        val held = state.performing.toSet()
        fun lit(row: Int, col: Int, c: Int) {
            val led = LaunchpadPro.ledOf(Control.Pad(row, col))
            leds[led] = if (led in held) Rgb.WHITE else Rgb.scale(c, 0.35f)
        }
        for (col in 0 until LENGTHS) {
            lit(REPEAT_ROW, col, Rgb.of(127, 60, 0))
            lit(GATE_ROW, col, Rgb.of(110, 110, 0))
        }
        for (i in 0..2) for (half in 0..1) {
            lit(MOMENT_ROW, i * 2 + half, Rgb.of(90, 0, 127))
            lit(KILL_ROW, i * 2 + half, Rgb.RED)
        }
        for (row in 0..3) for (col in 0..7) lit(row, col, Rgb.of(0, 40, 127))
    }

    /** What pressing a perform pad sends. */
    private fun performOn(row: Int, col: Int): List<LpAction> = when (row) {
        REPEAT_ROW -> if (col < LENGTHS) listOf(LpAction.PerformParam("repeat", (col + 1) / LENGTHS.toFloat())) else emptyList()
        GATE_ROW -> if (col < LENGTHS) listOf(LpAction.PerformParam("gate", (col + 1) / LENGTHS.toFloat())) else emptyList()
        MOMENT_ROW -> MOMENTS.getOrNull(col / 2)?.let { listOf(LpAction.PerformParam(it, 1f)) } ?: emptyList()
        KILL_ROW -> KILL_NAMES.getOrNull(col / 2)?.let { listOf(LpAction.PerformParam(it, 1f)) } ?: emptyList()
        else -> listOf(LpAction.PerformParam("x", col / 7f), LpAction.PerformParam("y", row / 3f))
    }

    /** Letting go of one: what is still held in its row takes over, or it goes off. */
    private fun performOff(state: LpState, row: Int, col: Int): List<LpAction> {
        val still = state.performing.map { LaunchpadPro.padOf(it)!! }
        fun lastIn(rows: IntRange) = still.lastOrNull { it.row in rows }
        return when (row) {
            REPEAT_ROW, GATE_ROW -> lastIn(row..row)?.let { performOn(it.row, it.col) }
                ?: listOf(LpAction.PerformParam(if (row == REPEAT_ROW) "repeat" else "gate", 0f))
            MOMENT_ROW, KILL_ROW -> {
                val names = if (row == MOMENT_ROW) MOMENTS else KILL_NAMES
                val name = names.getOrNull(col / 2) ?: return emptyList()
                if (still.any { it.row == row && it.col / 2 == col / 2 }) emptyList() else listOf(LpAction.PerformParam(name, 0f))
            }
            else -> lastIn(0..3)?.let { performOn(it.row, it.col) }
                ?: listOf(LpAction.PerformParam("x", 0.5f), LpAction.PerformParam("y", 0f))
        }
    }

    // The chord page: a column for each degree of the scale and its octave,
    // a row for each kind of chord, bottom to top.
    private val CHORD_DEGREES: List<List<Int>> = listOf(
        listOf(0, 2, 4),        // triad
        listOf(0, 2, 4, 6),     // seventh
        listOf(0, 1, 4),        // sus2
        listOf(0, 3, 4),        // sus4
        listOf(0, 2, 4, 6, 8),  // ninth
        listOf(0, 2, 4, 5),     // sixth
        listOf(0, 4, 7),        // power: root, fifth, octave
        listOf(2, 4, 7),        // first inversion: the root on top
    )

    /** The notes a chord pad plays: stacked scale degrees from the column's. */
    fun chordAt(view: LpView, state: LpState, row: Int, col: Int): List<Int> {
        val s = if (view.intervals.isNullOrEmpty() || view.root == null) listOf(0, 2, 4, 5, 7, 9, 11) else steps(view)
        val base = 12 * (state.octave + 1) + (view.root ?: 0)
        return CHORD_DEGREES[row].map { d ->
            val idx = col + d + state.degree
            base + 12 * Math.floorDiv(idx, s.size) + s[Math.floorMod(idx, s.size)]
        }.filter { it in 0..127 }
    }

    private fun chordPage(view: LpView, state: LpState, colour: Int, leds: IntArray) {
        val held = state.sounding.keys
        for (row in 0..7) for (col in 0..7) {
            val led = LaunchpadPro.ledOf(Control.Pad(row, col))
            leds[led] = when {
                led in held -> Rgb.WHITE
                // The tonic's column, and its octave, in the track's colour.
                (col + state.degree) % 7 == 0 -> Rgb.scale(colour, 0.6f)
                else -> Rgb.scale(colour, 0.15f + 0.04f * row)
            }
        }
    }

    // --- Pressing ----------------------------------------------------------------

    fun press(view: LpView, state: LpState, control: Control, velocity: Int): Pair<LpState, List<LpAction>> {
        when (control) {
            is Control.Pad -> {
                if (state.page == LpPage.Session) return state to sessionPress(view, state, control)
                val led = LaunchpadPro.ledOf(control)
                when (state.page) {
                    LpPage.Mixer -> {
                        val v = faderValue(control.col)
                        val line = 7 - control.row
                        return state to if (state.fader == LpFader.Device) {
                            if (line < view.device.size) listOf(LpAction.SetDevice(line, v)) else emptyList()
                        } else {
                            val t = state.trackOffset + line
                            if (t < view.tracks.size) listOf(LpAction.SetMix(t, state.fader, v)) else emptyList()
                        }
                    }
                    LpPage.Perform -> {
                        val on = performOn(control.row, control.col)
                        return (if (on.isEmpty()) state else state.copy(performing = state.performing - led + led)) to on
                    }
                    LpPage.Chord -> {
                        val notes = chordAt(view, state, control.row, control.col)
                        return state.copy(sounding = state.sounding + (led to notes)) to
                            notes.map { LpAction.NoteOn(it, velocity.coerceIn(1, 127)) }
                    }
                    else -> {}
                }
                if (state.page == LpPage.Sequencer) {
                    val seq = view.seq ?: return state to emptyList()
                    val tick = seqTick(view, state, control.col) ?: return state to emptyList()
                    val pitch = seqPitch(view, state, control.row) ?: return state to emptyList()
                    return state to listOf(LpAction.ToggleStep(view.played, seq.scene, tick, pitch, seq.grid))
                }
                if (state.page != LpPage.Note) return state to emptyList()
                val note = noteAt(view, state, control.row, control.col) ?: return state to emptyList()
                return state.copy(sounding = state.sounding + (led to listOf(note))) to
                    listOf(LpAction.NoteOn(note, velocity.coerceIn(1, 127)))
            }
            // The column beside the grid is the tracks and the row under it the
            // scenes, on every page: the app's grid, the right way round.
            is Control.Track -> return state to scenePress(view, state, state.sceneOffset + control.index)
            is Control.Scene -> return state to trackPress(view, state, state.trackOffset + control.index)
            is Control.Key -> return key(view, state, control.button)
        }
    }

    private fun trackPress(view: LpView, state: LpState, t: Int): List<LpAction> {
        if (t >= view.tracks.size) return emptyList()
        return listOf(
            when {
                Button.Mute in state.held -> LpAction.ToggleMute(t)
                Button.Solo in state.held -> LpAction.ToggleSolo(t)
                else -> LpAction.SelectTrack(t)
            },
        )
    }

    private fun scenePress(view: LpView, state: LpState, s: Int): List<LpAction> {
        if (s >= view.scenes) return emptyList()
        return listOf(if (Button.Duplicate in state.held) LpAction.DuplicateScene(s) else LpAction.PlayScene(s))
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
        val nav = navigates(state)
        if (state.page == LpPage.Sequencer && !state.shift) seqKey(view, state, b)?.let { return it to emptyList() }
        return when (b) {
            Button.Shift -> state.copy(shift = true) to emptyList()
            Button.RecordArm -> state to listOf(LpAction.Record)
            // The faders' kind, and the mixer page to see them on. Sends
            // goes to the first send, then the second.
            Button.Volume -> state.copy(page = LpPage.Mixer, fader = LpFader.Level) to emptyList()
            Button.Pan -> state.copy(page = LpPage.Mixer, fader = LpFader.Pan) to emptyList()
            Button.Device -> state.copy(page = LpPage.Mixer, fader = LpFader.Device) to emptyList()
            Button.Sends -> state.copy(
                page = LpPage.Mixer,
                fader = if (state.page == LpPage.Mixer && state.fader == LpFader.SendA) LpFader.SendB else LpFader.SendA,
            ) to emptyList()
            Button.StopClip -> state to listOf(LpAction.StopClips)
            Button.Quantise -> state to (view.seq?.let { listOf(LpAction.QuantiseClip(view.played, it.scene)) } ?: emptyList())
            // On the session and mixer pages - and on any with Shift - the
            // arrows move the view a row or a column at a time, as the app's
            // grid lies: the tracks up and down, the scenes left and right.
            Button.Up -> (if (nav) state.copy(trackOffset = (state.trackOffset - 1).coerceAtLeast(0))
            else state.copy(octave = (state.octave + 1).coerceAtMost(8))) to emptyList()
            Button.Down -> (if (nav) state.copy(trackOffset = (state.trackOffset + 1).coerceAtMost(maxOffset(view.tracks.size)))
            else state.copy(octave = (state.octave - 1).coerceAtLeast(-1))) to emptyList()
            Button.Left -> (if (nav) state.copy(sceneOffset = (state.sceneOffset - 1).coerceAtLeast(0))
            else state.copy(degree = state.degree - 1)) to emptyList()
            Button.Right -> (if (nav) state.copy(sceneOffset = (state.sceneOffset + 1).coerceAtMost(maxOffset(view.scenes)))
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
            if (led in state.performing) {
                val next = state.copy(performing = state.performing - led)
                next to performOff(next, control.row, control.col)
            } else {
                val notes = state.sounding[led].orEmpty()
                // The notes the pad started, whatever the grid says now: an
                // octave moved while it was held must not leave them sounding.
                state.copy(sounding = state.sounding - led) to notes.map { LpAction.NoteOff(it) }
            }
        }
        is Control.Key -> state.copy(
            shift = if (control.button == Button.Shift) false else state.shift,
            held = state.held - control.button,
        ) to emptyList()
        else -> state to emptyList()
    }

    /** A held pad pressed harder: that note's pressure. */
    fun pressure(state: LpState, pad: Control.Pad, value: Int): List<LpAction> =
        state.sounding[LaunchpadPro.ledOf(pad)].orEmpty().map { LpAction.Pressure(it, value) }

    /** How far the view can move: to where the last of [count] is on the last row or column. */
    private fun maxOffset(count: Int): Int = maxOf(0, count - 8)

    /** The arrows on the sequencer page: steps across, rows up and down. Null for anything else. */
    /**
     * The arrows on the sequencer page: a step at a time across, a row at a
     * time up and down. A drum machine reads down from the kick, so there up
     * goes back towards it. Null for anything else.
     */
    private fun seqKey(view: LpView, state: LpState, b: Button): LpState? {
        val seq = view.seq
        val steps = if (seq == null) 0 else (seq.length + seq.grid - 1) / maxOf(1, seq.grid)
        val lastRow = drumsOf(view)?.let { maxOf(0, it.size - 8) }
        return when (b) {
            Button.Left -> state.copy(stepOffset = (state.stepOffset - 1).coerceAtLeast(0))
            Button.Right -> state.copy(stepOffset = (state.stepOffset + 1).coerceAtMost(maxOffset(steps)))
            Button.Up -> state.copy(seqRow = if (lastRow != null) (state.seqRow - 1).coerceAtLeast(0) else state.seqRow + 1)
            Button.Down -> state.copy(seqRow = if (lastRow != null) (state.seqRow + 1).coerceAtMost(lastRow) else state.seqRow - 1)
            else -> null
        }
    }
}
