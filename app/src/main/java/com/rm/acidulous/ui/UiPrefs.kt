package com.rm.acidulous.ui

import com.rm.acidulous.engine.EngineSync
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.Signature
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongStore
import com.rm.acidulous.model.withModifier
import com.rm.acidulous.model.withModifierBypass
import com.rm.acidulous.model.withModifierParam
import com.rm.acidulous.ui.theme.ThemeMode

/**
 * The settings that belong to the person and the device, not to the song.
 *
 * Folding the automation strip, or preferring a light screen, or knowing
 * that this particular phone cannot hold a two-burst buffer, are all
 * statements about how and where someone is working: they follow them to
 * the next track, the next song and the next session. A song carries none
 * of them, so opening someone else's song cannot change how your phone
 * behaves.
 *
 * Compose-observable so any screen reading one recomposes when another
 * changes it, and everything is stored by name rather than by ordinal - the
 * order of an enum is not a promise.
 */
/** How little, and how much, of a turned editor the keyboard may take. */
const val KeysFractionMin = 0.18f
const val KeysFractionMax = 0.62f

/**
 * How far the instrument may be dragged from its stated height, upright.
 *
 * Down to about two thirds, which is where a black key stops being worth
 * aiming at, and up to three times, which on a phone is most of the screen -
 * and the roll's own floor stops it before that anyway, because what the
 * keyboard takes it takes from the roll.
 */
const val KeysStretchMin = 0.65f
const val KeysStretchMax = 3f

/**
 * Whether the fault-finding numbers show on a phone that has never been told.
 * **Set to false for 1.0** (Dan, 2026-09-23): until then everybody using it
 * is testing it, and the numbers are what a report needs.
 */
const val DIAGNOSTICS_BY_DEFAULT = true

object UiPrefs {
    private var store: SharedPreferences? = null

    // --- Editing ---------------------------------------------------------
    var automationFolded by mutableStateOf(false)
    /**
     * The note lane starts folded, unlike the automation strip.
     *
     * Most clips never carry a chance or a condition, and the roll should not
     * pay a row for a lane nobody has asked for yet. Like the automation
     * fold, it says how somebody works rather than anything about the song,
     * so it follows them between tracks and across launches.
     */
    var noteLaneFolded by mutableStateOf(true)

    /**
     * The same two folds again, for when the phone is turned.
     *
     * **A fold is a statement about a shape, not about a habit.** Upright
     * there are eight hundred and fifty dp of height and a lane costs
     * eighty-eight of them; turned there are three hundred and ninety-three
     * and the lane still wanted eighty-eight, which left the roll forty-six -
     * about four rows of pitch. Dan: "it feels cluttered". Folding a lane
     * away because the screen is short is not the same decision as folding it
     * away because you are not drawing velocity today, so it is not the same
     * flag: turning the phone must not put the lane away upright, and
     * unfolding it sideways must not open it upright either.
     *
     * Both start folded, because sideways the roll is what the height is for.
     */
    /**
     * The keyboard or the drum pads, folded away to their own control row.
     *
     * One flag for both orientations, unlike the two lanes above. Those are
     * folded sideways because the screen is short, which is a fact about the
     * shape; this is "I am editing rather than playing", which is not. The
     * performance row stays either way - the scale, the modifiers, the octave
     * and the mark that brings the instrument back all live in it.
     */
    var keysFolded by mutableStateOf(false)

    var automationFoldedLand by mutableStateOf(true)
    var noteLaneFoldedLand by mutableStateOf(true)

    /**
     * How much of a turned editor the keyboard takes, 0..1.
     *
     * Dan asked to be able to drag it: the keys were fifty-nine dp against
     * portrait's seventy-two, on the screen he had asked to give the
     * instrument "the entire bottom". A share rather than a stated height so
     * it means the same thing on a tablet, and remembered because where you
     * put the divider is how you work.
     *
     * Bounded well inside 0..1 by [KeysFractionMin] and [KeysFractionMax] -
     * a divider dragged to either end would leave one side unusable and
     * nothing to grab to get it back.
     */
    var keysFractionLand by mutableStateOf(0.33f)

    /**
     * The machine panel folded away.
     *
     * The transport had one of these too while it was a column against the
     * right edge. It has no fold now and no need of one: sideways it stands
     * in the empty half of the header, which was there whether or not
     * anything was in it. See EditScreen's landscape branch.
     *
     * The panel's own flag used to be a `rememberSaveable` inside it, which
     * survived a rotation and nothing else. It has to live out here for a
     * second reason now: turned sideways the panel is drawn in two pieces -
     * its header down the left edge and its cards on the right, with the roll
     * between them - and two composables cannot share a flag one of them owns.
     */
    var panelFolded by mutableStateOf(false)

    /**
     * Whether a finger is on fill right now.
     *
     * Not persisted - it is a gesture, not a setting - but it lives here
     * because two places press it: the pill in the editor's footer and a pad
     * on a controller through `Action.Fill`. One piece of state means the
     * pill lights up when the controller is the one holding it.
     */
    var fillHeld by mutableStateOf(false)
        private set

    /**
     * Whether a drum pad sends full strength wherever it is struck.
     *
     * Off, a pad reads the height of the hit: low is soft, high is hard. That
     * is how you play a part in. On, every pad sends 127 - which is what you
     * want when you are auditioning a kit, checking a patch, or tapping a
     * pattern in where every hit is meant to be identical and a finger landing
     * a little low is a mistake rather than a nuance.
     *
     * A statement about how somebody is working rather than about the song, so
     * it lives here and follows them between tracks and across launches.
     */
    var padsFullStrength by mutableStateOf(false)
        private set

    /**
     * The same question for the keyboard, and it is a separate answer.
     *
     * A key reads the height of the strike exactly as a pad does - low is
     * soft, high is hard - and the two surfaces are set independently because
     * they are played differently: a kit is often tapped in at one strength
     * while a part is played with the hand, or the other way about.
     *
     * Off by default, like the pads', which is the mode that carries more
     * information. Before this the keys sent 100 whatever you did to them.
     */
    var keysFullStrength by mutableStateOf(false)
        private set

    /**
     * How much taller than its stated height the instrument has been dragged,
     * upright. One is as written.
     *
     * A multiplier rather than the share of the window the turned editor keeps
     * (see [keysFractionLand]), because upright the keyboard is one of several
     * stated heights in a column rather than one of two panes: what somebody
     * means by dragging it is "more than it was", and what it was depends on
     * whether this machine has keys or pads and on the interface scale. A
     * multiplier says that and survives all three.
     */
    var keysStretch by mutableStateOf(1f)
        private set

    /**
     * The song grid as a launcher rather than an arranger. A way of working
     * rather than anything about the song, so it follows the person and not
     * the file - open somebody else's song and it is still however you left
     * it.
     */
    var clipMode by mutableStateOf(false)
        private set

    /**
     * When a tapped clip actually starts, in bars. 0 waits for the playing
     * clip to finish the cycle it is in, which is the musical default;
     * anything else is a plain grid.
     */
    var launchQuantise by mutableStateOf(0)
        private set

    // --- Appearance ------------------------------------------------------
    /** Auto follows the phone; the other two ignore it. */
    var theme by mutableStateOf(ThemeMode.Dark)
        private set

    /**
     * How much larger than stated the interface is drawn, 1.0 for as written.
     *
     * A statement about the person's eyes and their screen, not about the
     * song, so it belongs here beside the theme - and like the theme it is
     * read once at the root of the composition, where it is turned into a
     * density every `dp` and `sp` in the app resolves through. See
     * ui/UiScale.kt for the arithmetic and for why it only ever goes up.
     */
    var uiScale by mutableStateOf(1f)
        private set

    // --- Audio -----------------------------------------------------------
    /**
     * How deep the output buffer is, in bursts. Tight is as low as the
     * device will go and will crackle on a phone that cannot keep up; safe
     * buys a phone that cannot the room to finish late.
     */
    enum class Buffer(val bursts: Int, val label: String) {
        Tight(1, "tight"), Balanced(2, "balanced"), Safe(4, "safe")
    }

    var buffer by mutableStateOf(Buffer.Balanced)
        private set

    /** Held notes per track, 0 for as many as the machine has. */
    var voiceLimit by mutableStateOf(0)
        private set

    /** Full is everything; lean trades reverb density and distortion oversampling. */
    var fullQuality by mutableStateOf(true)
        private set

    /**
     * Let the app choose between full and lean while it plays.
     *
     * Off by default, and that is deliberate rather than timid: a setting that
     * changes what you are hearing without being asked has to be something you
     * turned on. What makes it honest at all is that there are now **two**
     * signals and they call for opposite answers - a worst block over budget
     * with few interrupted blocks means the song is asking too much and lean
     * will help; a high interrupted share means the device is busy and lean
     * will do nothing but make it sound worse.
     */
    var autoQuality by mutableStateOf(false)
        private set

    /** Bits in a recorded or exported WAV. */
    var recordBits by mutableStateOf(24)
    /**
     * Which input the recorder opens, as an `AudioDeviceInfo` id.
     *
     * Nought is whatever the platform would have chosen. A device that has
     * been unplugged since keeps its id here and simply fails to open, at
     * which point the screen offers the list again - remembering a wrong
     * answer is cheaper than forgetting a right one every time.
     */
    var inputDevice by mutableStateOf(0)
        private set

    // --- Screen ----------------------------------------------------------
    /** Keep the screen awake while the transport is running. */
    var keepAwake by mutableStateOf(true)
    /**
     * The numbers kept for finding faults: the line under the transport, the
     * readings in Settings, the MIDI counters, the editor's note count. One
     * switch in Settings for all of them. On until 1.0, off from then - see
     * [DIAGNOSTICS_BY_DEFAULT].
     */
    var showDiagnostics by mutableStateOf(DIAGNOSTICS_BY_DEFAULT)
        private set
    /** Was Link on last time? Acted on by MainActivity, which has a Context. */
    var linkWanted by mutableStateOf(false)
        private set

    // --- Controller mappings ---------------------------------------------
    /** The device's own mappings; a song's win over these. */
    var mappings by mutableStateOf<List<com.rm.acidulous.model.Mapping>>(emptyList())
        private set

    /**
     * Mapping mode: every mappable control says so, and a tap arms it.
     *
     * Not persisted. It is a mode you are in for a minute, and coming back
     * to the app in it - with every knob lit and none of them turning - is
     * a puzzle nobody needs to solve twice.
     */
    var mapMode by mutableStateOf(false)
        private set

    /** The target waiting for a controller to arrive, or null. */
    var mapWaiting by mutableStateOf<String?>(null)
        private set

    // --- Metronome -------------------------------------------------------
    // The click belongs to the person and the device rather than the song:
    // two people working on the same file want different clicks, and
    // nobody wants the one they inherited.
    /** 0 blip, 1 stick, 2 cowbell. */
    var clickVoice by mutableStateOf(0)
        private set
    /** 0 bar, 1 beat, 2 eighths, 3 sixteenths, 4 eighth triplets. */
    var clickDivision by mutableStateOf(1)
        private set
    var clickVolume by mutableStateOf(0.5f)
        private set
    /** Bars of clicks before a start actually starts. 0 is none. */
    var countInBars by mutableStateOf(0)
        private set
    /** 0 always, 1 while recording, 2 only for the count-in. */
    var clickWhen by mutableStateOf(0)
        private set

    // --- New songs -------------------------------------------------------
    var newTempo by mutableStateOf(120f)
        private set
    var newSignature by mutableStateOf(Signature())
        private set
    /** Off, or a key and a scale index into [com.rm.acidulous.model.Scales]. */
    var newScaleOn by mutableStateOf(false)
        private set
    var newScaleKey by mutableStateOf(0)
        private set
    var newScaleIndex by mutableStateOf(0)
        private set

    /**
     * The machine a new song's one track starts with.
     *
     * Hexbeat rather than Reflux, because a new song is almost always a beat
     * before it is anything else - you put a pattern down and then write to
     * it. Settable, because "almost always" is a statement about most people
     * and somebody who opens the app to write a bassline should not have to
     * change the machine every time.
     */
    var newMachine by mutableStateOf("Hexbeat")
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        store = p
        automationFolded = p.getBoolean(KEY_AUTO_FOLDED, false)
        noteLaneFolded = p.getBoolean(KEY_NOTE_FOLDED, true)
        keysFolded = p.getBoolean(KEY_KEYS_FOLDED, false)
        automationFoldedLand = p.getBoolean(KEY_AUTO_FOLDED_LAND, true)
        noteLaneFoldedLand = p.getBoolean(KEY_NOTE_FOLDED_LAND, true)
        keysFractionLand = p.getFloat(KEY_KEYS_FRACTION, 0.33f)
            .coerceIn(KeysFractionMin, KeysFractionMax)
        panelFolded = p.getBoolean(KEY_PANEL_FOLDED, false)
        padsFullStrength = p.getBoolean(KEY_PADS_FULL, false)
        keysFullStrength = p.getBoolean(KEY_KEYS_FULL, false)
        keysStretch = p.getFloat(KEY_KEYS_STRETCH, 1f)
            .coerceIn(KeysStretchMin, KeysStretchMax)
        clipMode = p.getBoolean(KEY_CLIP_MODE, false)
        launchQuantise = p.getInt(KEY_LAUNCH_Q, 0)
        loopBars = p.getInt(KEY_LOOP_BARS, 0)
        theme = runCatching { ThemeMode.valueOf(p.getString(KEY_THEME, null) ?: "Dark") }
            .getOrDefault(ThemeMode.Dark)
        uiScale = p.getFloat(KEY_UI_SCALE, 1f)
            .coerceIn(UiScaleSteps.first(), UiScaleSteps.last())
        buffer = runCatching { Buffer.valueOf(p.getString(KEY_BUFFER, null) ?: "Balanced") }
            .getOrDefault(Buffer.Balanced)
        voiceLimit = p.getInt(KEY_VOICES, 0)
        fullQuality = p.getBoolean(KEY_QUALITY, true)
        autoQuality = p.getBoolean(KEY_AUTO_QUALITY, false)
        qualityNow = fullQuality
        recordBits = p.getInt(KEY_BITS, 24)
        inputDevice = p.getInt(KEY_INPUT_DEVICE, 0)
        keepAwake = p.getBoolean(KEY_AWAKE, true)
        showDiagnostics = p.getBoolean(KEY_DIAGNOSTICS, DIAGNOSTICS_BY_DEFAULT)
        linkWanted = p.getBoolean(KEY_LINK, false)
        com.rm.acidulous.engine.LinkHub.chooseStartStop(p.getBoolean(KEY_LINK_STARTSTOP, true))
        mappings = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<com.rm.acidulous.model.Mapping>>(
                p.getString(KEY_MAPPINGS, null) ?: "[]",
            )
        }.getOrDefault(emptyList())
        clickVoice = p.getInt(KEY_CLICK_VOICE, 0)
        clickDivision = p.getInt(KEY_CLICK_DIV, 1)
        clickVolume = p.getFloat(KEY_CLICK_VOL, 0.5f)
        countInBars = p.getInt(KEY_COUNT_IN, 0)
        clickWhen = p.getInt(KEY_CLICK_WHEN, 0)
        // Not pushed here: init() runs in onCreate, hundreds of lines
        // before NativeEngine.start(), so anything sent now goes nowhere.
        // applyToEngine() is where settings meet a running engine.
        newTempo = p.getFloat(KEY_TEMPO, 120f)
        newSignature = Signature(p.getInt(KEY_BEATS, 4), p.getInt(KEY_UNIT, 4))
        newScaleOn = p.getBoolean(KEY_SCALE_ON, false)
        newScaleKey = p.getInt(KEY_SCALE_KEY, 0)
        newScaleIndex = p.getInt(KEY_SCALE_INDEX, 0)
        newMachine = p.getString(KEY_NEW_MACHINE, "Hexbeat") ?: "Hexbeat"
        MidiHub.routing = runCatching { MidiHub.Routing.valueOf(p.getString(KEY_MIDI_ROUTE, null) ?: "SelectedTrack") }
            .getOrDefault(MidiHub.Routing.SelectedTrack)
        MidiHub.fixedRack = p.getInt(KEY_MIDI_RACK, 0)
        MidiHub.outOffsetMs = p.getInt(KEY_MIDI_AHEAD, 0)
        MidiHub.chooseClockOut(p.getBoolean(KEY_MIDI_CLOCK_OUT, false))
        // The switch was on or off before auto; an old "on" is still on.
        val follow = p.getString(KEY_MIDI_FOLLOW_MODE, null)
            ?.let { runCatching { MidiHub.Follow.valueOf(it) }.getOrNull() }
            ?: if (p.getBoolean(KEY_MIDI_FOLLOW, false)) MidiHub.Follow.On else MidiHub.Follow.Off
        MidiHub.chooseFollow(follow)
        // 48 semitones is what the MPE specification asks a receiver to
        // assume, and is nothing like what a keyboard means by a bend.
        MidiHub.chooseMpe(
            p.getInt(KEY_MPE_ZONE, 0), p.getInt(KEY_MPE_MEMBERS, 15),
            p.getFloat(KEY_MPE_BEND, 48f), p.getBoolean(KEY_MPE_TIMBRE, true),
        )
    }

    /**
     * Hand the engine what it cannot read for itself. Called once the audio
     * stream is up, and again whenever one of these changes - the engine
     * keeps no preferences of its own, so this is the only thing that puts
     * them there.
     */
    /** The three stepped click params, whenever one of them changes. */
    private fun pushClick() = EngineSync.setClickSettings(clickVoice, clickDivision, clickWhen, clickVolume)

    fun applyToEngine() {
        NativeEngine.setBufferBursts(buffer.bursts)
        NativeEngine.setVoiceLimit(voiceLimit)
        NativeEngine.setQuality(if (fullQuality) 1 else 0)
        NativeEngine.setRecordBits(recordBits)
        NativeEngine.setLauncher(clipMode)
        NativeEngine.setExternalSync(MidiHub.clockIn)
        // The quantise is in bars here and in ticks there; the song's own
        // signature converts it, and MainScreen re-sends it when that changes.
        NativeEngine.setLaunchQuantise(launchQuantise * 4 * 240)
        NativeEngine.setCountInBars(countInBars)
        pushClick()
    }

    fun choosePadsFullStrength(on: Boolean) {
        padsFullStrength = on
        store?.edit()?.putBoolean(KEY_PADS_FULL, on)?.apply()
    }

    fun chooseKeysFullStrength(on: Boolean) {
        keysFullStrength = on
        store?.edit()?.putBoolean(KEY_KEYS_FULL, on)?.apply()
    }

    /**
     * Where the keyboard's edge was let go, upright.
     *
     * Written once the finger lifts, for the reason [chooseKeysFraction]
     * gives: a preference stored on every frame of a drag is a file written
     * sixty times a second.
     */
    fun chooseKeysStretch(f: Float) {
        keysStretch = f.coerceIn(KeysStretchMin, KeysStretchMax)
        store?.edit()?.putFloat(KEY_KEYS_STRETCH, keysStretch)?.apply()
    }

    fun foldAutomation(folded: Boolean) {
        automationFolded = folded
        store?.edit()?.putBoolean(KEY_AUTO_FOLDED, folded)?.apply()
    }

    fun holdFill(on: Boolean) {
        if (fillHeld == on) return
        fillHeld = on
        NativeEngine.setFill(on)
    }

    fun foldPanel(folded: Boolean) {
        panelFolded = folded
        store?.edit()?.putBoolean(KEY_PANEL_FOLDED, folded)?.apply()
    }

    fun foldNoteLane(folded: Boolean) {
        noteLaneFolded = folded
        store?.edit()?.putBoolean(KEY_NOTE_FOLDED, folded)?.apply()
    }

    fun foldKeys(folded: Boolean) {
        keysFolded = folded
        store?.edit()?.putBoolean(KEY_KEYS_FOLDED, folded)?.apply()
    }

    fun foldAutomationLand(folded: Boolean) {
        automationFoldedLand = folded
        store?.edit()?.putBoolean(KEY_AUTO_FOLDED_LAND, folded)?.apply()
    }

    fun foldNoteLaneLand(folded: Boolean) {
        noteLaneFoldedLand = folded
        store?.edit()?.putBoolean(KEY_NOTE_FOLDED_LAND, folded)?.apply()
    }

    /**
     * Where the divider above the keyboard was let go.
     *
     * Written on every frame of a drag would be a preference file touched
     * sixty times a second, so the caller stores it once the finger lifts and
     * holds the live value itself while the drag is running.
     */
    fun chooseKeysFraction(f: Float) {
        keysFractionLand = f.coerceIn(KeysFractionMin, KeysFractionMax)
        store?.edit()?.putFloat(KEY_KEYS_FRACTION, keysFractionLand)?.apply()
    }

    // Not setClipMode: the property's own generated setter already owns
    // that JVM signature. Same reason chooseTheme is not setTheme.
    fun chooseClipMode(on: Boolean) {
        clipMode = on
        store?.edit()?.putBoolean(KEY_CLIP_MODE, on)?.apply()
        NativeEngine.setLauncher(on)
    }

    /** How long a loop tapped into an empty launcher cell records: 0 until tapped again. */
    var loopBars by mutableStateOf(0)

    fun chooseLoopBars(bars: Int) {
        loopBars = bars
        store?.edit()?.putInt(KEY_LOOP_BARS, bars)?.apply()
    }

    fun chooseQuantise(bars: Int) {
        launchQuantise = bars
        store?.edit()?.putInt(KEY_LAUNCH_Q, bars)?.apply()
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        store?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    /**
     * Stored as the multiplier rather than as an index into the steps, so
     * adding a step later cannot re-point somebody's saved choice - the same
     * reason everything else here is stored by name and not by ordinal.
     */
    fun chooseUiScale(scale: Float) {
        uiScale = scale.coerceIn(UiScaleSteps.first(), UiScaleSteps.last())
        store?.edit()?.putFloat(KEY_UI_SCALE, uiScale)?.apply()
    }

    fun chooseBuffer(b: Buffer) {
        buffer = b
        store?.edit()?.putString(KEY_BUFFER, b.name)?.apply()
        NativeEngine.setBufferBursts(b.bursts)
    }

    fun chooseVoiceLimit(notes: Int) {
        voiceLimit = notes
        store?.edit()?.putInt(KEY_VOICES, notes)?.apply()
        NativeEngine.setVoiceLimit(notes)
    }

    fun chooseQuality(full: Boolean) {
        fullQuality = full
        store?.edit()?.putBoolean(KEY_QUALITY, full)?.apply()
        NativeEngine.setQuality(if (full) 1 else 0)
    }

    fun chooseAutoQuality(on: Boolean) {
        autoQuality = on
        store?.edit()?.putBoolean(KEY_AUTO_QUALITY, on)?.apply()
    }

    /**
     * What the watcher decided, kept apart from what *you* chose.
     *
     * Turning the watcher off has to give you your setting back, so its
     * decision never writes over `fullQuality` in the preferences - it only
     * tells the engine. This is the last thing it asked for, so the readout
     * can say what is actually running.
     */
    var qualityNow by mutableStateOf(true)
        private set

    fun applyAutoQuality(full: Boolean) {
        if (qualityNow == full) return
        qualityNow = full
        NativeEngine.setQuality(if (full) 1 else 0)
    }

    /** Remembered rather than asked for every time the window opens. */
    fun chooseInputDevice(id: Int) {
        if (id == inputDevice) return
        inputDevice = id
        store?.edit()?.putInt(KEY_INPUT_DEVICE, id)?.apply()
    }

    fun chooseRecordBits(bits: Int) {
        recordBits = bits
        store?.edit()?.putInt(KEY_BITS, bits)?.apply()
        NativeEngine.setRecordBits(bits)
    }

    fun chooseMapMode(on: Boolean) {
        mapMode = on
        if (!on) mapWaiting = null
    }

    /** Arm a target, or disarm it if it was already the one waiting. */
    fun chooseMapWaiting(target: String?) {
        mapWaiting = if (target != null && target == mapWaiting) null else target
    }

    fun chooseMappings(list: List<com.rm.acidulous.model.Mapping>) {
        mappings = list
        store?.edit()?.putString(
            KEY_MAPPINGS,
            kotlinx.serialization.json.Json.encodeToString(list),
        )?.apply()
    }

    fun chooseClickVoice(v: Int) {
        clickVoice = v.coerceIn(0, 2)
        store?.edit()?.putInt(KEY_CLICK_VOICE, clickVoice)?.apply()
        pushClick()
    }

    fun chooseClickDivision(d: Int) {
        clickDivision = d.coerceIn(0, 4)
        store?.edit()?.putInt(KEY_CLICK_DIV, clickDivision)?.apply()
        pushClick()
    }

    fun chooseClickVolume(v: Float) {
        clickVolume = v.coerceIn(0f, 1f)
        store?.edit()?.putFloat(KEY_CLICK_VOL, clickVolume)?.apply()
        pushClick()
    }

    fun chooseClickWhen(w: Int) {
        clickWhen = w.coerceIn(0, 2)
        store?.edit()?.putInt(KEY_CLICK_WHEN, clickWhen)?.apply()
        pushClick()
    }

    fun chooseCountInBars(bars: Int) {
        countInBars = bars.coerceIn(0, 4)
        store?.edit()?.putInt(KEY_COUNT_IN, countInBars)?.apply()
        NativeEngine.setCountInBars(countInBars)
    }

    fun chooseDiagnostics(on: Boolean) {
        showDiagnostics = on
        store?.edit()?.putBoolean(KEY_DIAGNOSTICS, on)?.apply()
    }

    fun chooseKeepAwake(on: Boolean) {
        keepAwake = on
        store?.edit()?.putBoolean(KEY_AWAKE, on)?.apply()
    }

    fun chooseNewTempo(bpm: Float) {
        newTempo = bpm.coerceIn(40f, 240f)
        store?.edit()?.putFloat(KEY_TEMPO, newTempo)?.apply()
    }

    fun chooseNewSignature(s: Signature) {
        newSignature = s
        store?.edit()?.putInt(KEY_BEATS, s.beats)?.putInt(KEY_UNIT, s.unit)?.apply()
    }

    fun chooseNewScale(on: Boolean, key: Int = newScaleKey, index: Int = newScaleIndex) {
        newScaleOn = on
        newScaleKey = key
        newScaleIndex = index
        store?.edit()?.putBoolean(KEY_SCALE_ON, on)?.putInt(KEY_SCALE_KEY, key)
            ?.putInt(KEY_SCALE_INDEX, index)?.apply()
    }

    fun chooseFollow(mode: MidiHub.Follow) {
        MidiHub.chooseFollow(mode)
        store?.edit()?.putString(KEY_MIDI_FOLLOW_MODE, mode.name)?.apply()
    }

    /**
     * Link is remembered but **not** switched on by `applyToEngine`: it needs
     * a Context for the multicast lock, so MainActivity turns it on once the
     * stream is up. What is stored here is only the wish.
     */
    fun chooseLink(on: Boolean) {
        linkWanted = on
        store?.edit()?.putBoolean(KEY_LINK, on)?.apply()
    }

    fun chooseLinkStartStop(on: Boolean) {
        com.rm.acidulous.engine.LinkHub.chooseStartStop(on)
        store?.edit()?.putBoolean(KEY_LINK_STARTSTOP, on)?.apply()
    }

    fun chooseClockOut(on: Boolean) {
        MidiHub.chooseClockOut(on)
        store?.edit()?.putBoolean(KEY_MIDI_CLOCK_OUT, on)?.apply()
    }

    /** How far ahead of the audio MIDI goes out, in milliseconds. */
    fun chooseMidiOffset(ms: Int) {
        val v = ms.coerceIn(-50, 50)
        MidiHub.outOffsetMs = v
        store?.edit()?.putInt(KEY_MIDI_AHEAD, v)?.apply()
    }

    /**
     * The MPE zone, and what a finger's bend is worth.
     *
     * Kept here with the other MIDI settings rather than in the song: a
     * zone describes the controller on the desk, not the music.
     */
    fun chooseMpe(
        zone: Int = MidiHub.mpeZone,
        members: Int = MidiHub.mpeMembers,
        bendSemis: Float = MidiHub.mpeBendSemis,
        timbre: Boolean = MidiHub.mpeTimbre,
    ) {
        val m = com.rm.acidulous.midi.MpeZone.clampMembers(members)
        val b = com.rm.acidulous.midi.MpeZone.clampBend(bendSemis)
        MidiHub.chooseMpe(zone, m, b, timbre)
        store?.edit()?.putInt(KEY_MPE_ZONE, MidiHub.mpeZone)?.putInt(KEY_MPE_MEMBERS, m)
            ?.putFloat(KEY_MPE_BEND, b)?.putBoolean(KEY_MPE_TIMBRE, timbre)?.apply()
    }

    fun chooseMidiRouting(r: MidiHub.Routing, rack: Int = MidiHub.fixedRack) {
        MidiHub.routing = r
        MidiHub.fixedRack = rack
        store?.edit()?.putString(KEY_MIDI_ROUTE, r.name)?.putInt(KEY_MIDI_RACK, rack)?.apply()
    }

    private const val KEY_AUTO_FOLDED = "automation_folded"
    private const val KEY_NOTE_FOLDED = "note_lane_folded"
    private const val KEY_KEYS_FOLDED = "keys_folded"
    private const val KEY_AUTO_FOLDED_LAND = "automation_folded_land"
    private const val KEY_NOTE_FOLDED_LAND = "note_lane_folded_land"
    private const val KEY_KEYS_FRACTION = "keys_fraction_land"
    private const val KEY_PANEL_FOLDED = "panel_folded"
    private const val KEY_PADS_FULL = "pads_full_strength"
    private const val KEY_KEYS_FULL = "keys_full_strength"
    private const val KEY_KEYS_STRETCH = "keys_stretch"
    private const val KEY_CLIP_MODE = "clip_mode"
    private const val KEY_LAUNCH_Q = "launch_quantise"
    private const val KEY_LOOP_BARS = "loop_bars"
    private const val KEY_THEME = "theme"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_BUFFER = "buffer"
    private const val KEY_VOICES = "voice_limit"
    private const val KEY_QUALITY = "quality_full"
    private const val KEY_AUTO_QUALITY = "quality_auto"
    private const val KEY_BITS = "record_bits"
    private const val KEY_INPUT_DEVICE = "input_device"
    private const val KEY_AWAKE = "keep_awake"
    private const val KEY_DIAGNOSTICS = "diagnostics"
    private const val KEY_MAPPINGS = "cc_mappings"
    private const val KEY_CLICK_VOICE = "click_voice"
    private const val KEY_CLICK_DIV = "click_div"
    private const val KEY_CLICK_VOL = "click_vol"
    private const val KEY_COUNT_IN = "count_in"
    private const val KEY_CLICK_WHEN = "click_when"
    private const val KEY_TEMPO = "new_tempo"
    private const val KEY_BEATS = "new_beats"
    private const val KEY_UNIT = "new_unit"
    private const val KEY_SCALE_ON = "new_scale_on"
    private const val KEY_SCALE_KEY = "new_scale_key"
    private const val KEY_SCALE_INDEX = "new_scale_index"
    private const val KEY_NEW_MACHINE = "new_machine"
    private const val KEY_MIDI_ROUTE = "midi_routing"
    private const val KEY_MPE_ZONE = "mpe_zone"
    private const val KEY_MPE_MEMBERS = "mpe_members"
    private const val KEY_MPE_BEND = "mpe_bend"
    private const val KEY_MPE_TIMBRE = "mpe_timbre"
    private const val KEY_MIDI_RACK = "midi_rack"
    private const val KEY_MIDI_CLOCK_OUT = "midi_clock_out"
    private const val KEY_MIDI_FOLLOW = "midi_follow"
    private const val KEY_MIDI_FOLLOW_MODE = "midi_follow_mode"
    private const val KEY_MIDI_AHEAD = "midi_ahead_ms"
    private const val KEY_LINK = "link_on"
    private const val KEY_LINK_STARTSTOP = "link_startstop"

    // --- What a new song and a new track start as ------------------------

    /**
     * Fit [index]'s track with a Scale modifier set to the default scale, so
     * a new track agrees with the song from its first note. Does nothing
     * when no default is set - an unasked-for modifier in slot 1 would be a
     * surprise, not a convenience.
     */
    fun Song.withDefaultScale(index: Int): Song {
        // **The song's key wins over the setting.** The setting says what a
        // new song should start in; once a song has said what key it is in,
        // that is the answer, and a track fitted from the preference instead
        // would disagree with the roll it is drawn on.
        val songKey = key
        if (songKey == null && !newScaleOn) return this
        val track = tracks.getOrNull(index) ?: return this
        val root = songKey?.root ?: newScaleKey
        val scale = songKey?.scale ?: newScaleIndex
        val fitted = track.withModifier(0, "Scale")
            .withModifierParam(0, "key", root / 11f)
            .withModifierParam(0, "scale", scale / (Scales.names.size - 1f))
            .withModifierParam(0, "mode", 0f)
            .withModifierBypass(0, false)
        return copy(tracks = tracks.toMutableList().also { it[index] = fitted })
    }

    fun chooseNewMachine(type: String) {
        newMachine = type
        store?.edit()?.putString(KEY_NEW_MACHINE, type)?.apply()
    }

    /**
     * A new song in the tempo, signature, scale and machine the settings ask
     * for.
     */
    fun newSong(name: String): Song =
        SongStore.blank(name, newTempo, newSignature, newMachine).withDefaultScale(0)
}
