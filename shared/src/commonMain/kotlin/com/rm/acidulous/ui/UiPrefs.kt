package com.rm.acidulous.ui

import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.util.PrefStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.midi.VelocityCurve
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.Signature
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongStore
import com.rm.acidulous.model.withModifier
import com.rm.acidulous.model.withModifierBypass
import com.rm.acidulous.model.withModifierParam
import com.rm.acidulous.ui.theme.ThemeMode
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/** How little and how much of a turned editor the keyboard may take. */
const val KeysFractionMin = 0.18f
const val KeysFractionMax = 0.62f

/**
 * How far the instrument may be stretched from its normal height, upright.
 * Below about two thirds the black keys get too small to hit. The upper
 * limit is rarely reached, since the roll's own minimum height stops it first.
 */
const val KeysStretchMin = 0.65f
const val KeysStretchMax = 3f

/**
 * Settings that belong to the user and the device, not the song. They
 * carry across tracks, songs and sessions.
 *
 * Compose-observable, so every screen reading one recomposes when it
 * changes. Everything is stored by name, not by ordinal, since enum order
 * can change.
 */
object UiPrefs {
    private var store: PrefStore? = null

    // --- Editing ---------------------------------------------------------
    var automationFolded by mutableStateOf(false)
    /**
     * The note lane starts folded, unlike the automation strip, since most
     * clips never use chance or conditions.
     */
    var noteLaneFolded by mutableStateOf(true)

    /**
     * The keyboard or drum pads, folded away to their control row. One flag
     * for both orientations, unlike the lanes below. The performance row
     * (scale, modifiers, octave and the unfold mark) stays either way.
     */
    var keysFolded by mutableStateOf(false)

    // The two lane folds again, for landscape. Kept separate because folding
    // a lane on a short screen is a different choice from folding it upright.
    // Both start folded, since in landscape the roll needs the height.
    var automationFoldedLand by mutableStateOf(true)
    var noteLaneFoldedLand by mutableStateOf(true)

    /**
     * How much of a turned editor the keyboard takes, 0..1. Stored as a share
     * so it means the same on a tablet. Kept inside [KeysFractionMin] and
     * [KeysFractionMax] so neither side can be dragged away to nothing.
     */
    var keysFractionLand by mutableStateOf(0.33f)

    /**
     * The machine panel folded away. Kept here instead of in the panel because
     * in landscape the panel is drawn in two pieces (its header on the left,
     * its cards on the right), which both need the flag.
     */
    var panelFolded by mutableStateOf(false)

    /**
     * Whether fill is held right now. Not saved, but kept here because both the
     * editor's fill pill and a controller pad (`Action.Fill`) press it, and the
     * pill should light up either way.
     */
    var fillHeld by mutableStateOf(false)
        private set

    /**
     * Whether a drum pad always sends full strength. Off, where you hit the pad
     * sets the velocity: low is soft, high is hard. On, every hit sends 127,
     * which is handy for auditioning a kit or tapping in a pattern.
     */
    var padsFullStrength by mutableStateOf(false)
        private set

    /**
     * The same for the keyboard, set separately from the pads since the two
     * are often played differently. Off by default, like the pads.
     */
    var keysFullStrength by mutableStateOf(false)
        private set

    /**
     * How much taller than its normal height the instrument has been dragged,
     * upright. 1 is normal. A multiplier, since the normal height depends on
     * keys or pads and on the UI scale. Landscape uses [keysFractionLand].
     */
    var keysStretch by mutableStateOf(1f)
        private set

    /**
     * The song grid as a clip launcher instead of an arranger. A way of working,
     * so it stays however you left it whichever song is open.
     */
    var clipMode by mutableStateOf(false)
        private set

    /**
     * When a tapped clip starts, in bars. 0 waits for the playing clip to finish
     * its cycle; anything else is a plain grid.
     */
    var launchQuantise by mutableStateOf(0)
        private set

    /** What the time on the readout shows in song mode: one of [SongTime]. */
    var songTime by mutableStateOf(SongTime.Position)
        private set

    // --- Appearance ------------------------------------------------------
    /** Auto follows the phone; the other two ignore it. */
    var theme by mutableStateOf(ThemeMode.Dark)

    /**
     * The app's language: the system's, or one chosen in settings. [tag] is
     * BCP 47. Stored by name, so French stays Canada's for whoever chose it
     * before France's was added; new ones go on the end.
     */
    enum class Language(val tag: String?) { System(null), English("en"), French("fr-CA"), FrenchFrance("fr-FR") }
    var language by mutableStateOf(Language.System)
        private set

    /**
     * How much larger than normal the interface is drawn, 1.0 for normal. Read
     * once at the root of the composition and turned into the density every
     * `dp` and `sp` goes through. See ui/UiScale.kt for the maths and why it
     * only ever goes up.
     */
    var uiScale by mutableStateOf(1f)

    /**
     * The desktop's screen scale: pixels per dp, or 0 for the system's value.
     * Desktops assume 96 dpi, so a small high-res screen (like a Pi's three inch
     * 720 pixel square) comes out tiny and gets the wrong layout. Phones use
     * their own density and never show this.
     */
    var screenScale by mutableStateOf(0f)

    /** The output the desktop or browser plays through, by the host's id; 0 is the system default. */
    var outputDevice by mutableStateOf(0)
        private set

    // --- Audio -----------------------------------------------------------
    /**
     * How deep the output buffer is, in bursts. Tight is as low as the device
     * will go and will crackle on a phone that can't keep up; safe gives a slow
     * phone room to finish late.
     */
    enum class Buffer(val bursts: Int, val label: StringResource) {
        Tight(1, Res.string.settings_buffer_tight), Balanced(2, Res.string.settings_buffer_balanced), Safe(4, Res.string.settings_buffer_safe)
    }

    var buffer by mutableStateOf(Buffer.Balanced)
        private set

    /**
     * The smallest output buffer this device has been found to hold, frames
     * (see NativeEngine.bufferFloor), so a run starts there rather than finding
     * it again with a dropout or two.
     */
    var bufferFloor = 0
        private set

    fun keepBufferFloor(frames: Int) {
        if (frames <= bufferFloor) return
        bufferFloor = frames
        store?.edit()?.putInt(KEY_BUFFER_FLOOR, frames)?.apply()
    }

    /** Held notes per track, 0 for as many as the machine has. */
    var voiceLimit by mutableStateOf(0)
        private set

    /** Cores tracks render on, the audio thread's included; 0 is auto. */
    var cores by mutableStateOf(0)
        private set

    /** Full is everything; lean cuts reverb density and distortion oversampling. */
    var fullQuality by mutableStateOf(true)
        private set

    /**
     * Let the app switch between full and lean while it plays. Off by default,
     * since it changes what you hear without asking. A worst block over budget
     * with few interrupted blocks means the song asks too much and lean helps;
     * many interrupted blocks mean the device is busy and lean won't help.
     */
    var autoQuality by mutableStateOf(false)
        private set

    /** Bits in a recorded or exported WAV. */
    var recordBits by mutableStateOf(24)
    /**
     * Which input the recorder opens, as an `AudioDeviceInfo` id. 0 is the
     * platform's default. An unplugged device keeps its id here and just fails
     * to open, and then the screen offers the list again.
     */
    var inputDevice by mutableStateOf(0)
        private set

    /**
     * The microphone raw, for instruments, or clean, with the platform's
     * noise suppression and level control, for voices. Only offered where
     * AppHost.cleansInput.
     */
    var inputClean by mutableStateOf(false)
        private set

    // --- Screen ----------------------------------------------------------
    /** Keep the screen awake while the transport is running. */
    var keepAwake by mutableStateOf(true)
    /**
     * Show the diagnostics numbers: the line under the transport, the readings
     * in Settings, the MIDI counters and the editor's note count. Only in a
     * debug build (AppHost.debugBuild), where they're on unless switched off;
     * a release has neither the numbers nor the switch.
     */
    var showDiagnostics by mutableStateOf(false)
        private set
    /** Was Link on last time? Acted on by MainActivity, which has a Context. */
    var linkWanted by mutableStateOf(false)
        private set

    // --- Controller mappings ---------------------------------------------
    /** The device's own mappings; a song's win over these. */
    var mappings by mutableStateOf<List<com.rm.acidulous.model.Mapping>>(emptyList())
        private set

    /**
     * Keyboard shortcuts: [DEFAULT_KEYS] with the user's changes on top. Only
     * the changes are stored, so a changed default reaches everyone who never
     * touched it.
     */
    var keyBindings by mutableStateOf(DEFAULT_KEYS)
        private set

    /**
     * A controller's buttons given jobs other than their own (see Pad), by key
     * code. Only the changes are kept, like [keyBindings].
     */
    var padJobs by mutableStateOf(emptyMap<Int, Pad.Job>())
        private set

    /** Which letters play which notes in play mode. */
    var noteLayout by mutableStateOf(NoteLayout.Piano)
        private set

    /**
     * Mapping mode: every mappable control shows it, and a tap arms it. Not
     * saved, so the app never starts up stuck in it.
     */
    var mapMode by mutableStateOf(false)
        private set

    /** The target waiting for a controller to arrive, or null. */
    var mapWaiting by mutableStateOf<String?>(null)
        private set

    // --- Metronome -------------------------------------------------------
    // The click belongs to the user and the device, not the song.
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
     * The machine a new song's one track starts with. Hexbeat by default,
     * since a new song usually starts with a beat.
     */
    var newMachine by mutableStateOf("Hexbeat")
        private set

    fun init(p: PrefStore) {
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
        songTime = SongTime.entries.getOrElse(p.getInt(KEY_SONG_TIME, 0)) { SongTime.Position }
        loopBars = p.getInt(KEY_LOOP_BARS, 0)
        keyBindings = DEFAULT_KEYS + decodeKeys(p.getString(KEY_KEYS, null).orEmpty())
        padJobs = p.getString(KEY_PAD_JOBS, null).orEmpty().split(';').mapNotNull { e ->
            val (code, job) = e.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            (code.toIntOrNull() ?: return@mapNotNull null) to (Pad.decode(job) ?: return@mapNotNull null)
        }.toMap()
        noteLayout = runCatching { NoteLayout.valueOf(p.getString(KEY_NOTE_LAYOUT, null) ?: "Piano") }.getOrDefault(NoteLayout.Piano)
        theme = runCatching { ThemeMode.valueOf(p.getString(KEY_THEME, null) ?: "Dark") }
            .getOrDefault(ThemeMode.Dark)
        language = runCatching { Language.valueOf(p.getString(KEY_LANGUAGE, null) ?: "System") }
            .getOrDefault(Language.System)
        uiScale = p.getFloat(KEY_UI_SCALE, 1f)
            .coerceIn(UiScaleSteps.first(), UiScaleSteps.last())
        screenScale = p.getFloat(KEY_SCREEN_SCALE, 0f).takeIf { it in ScreenScaleSteps } ?: 0f
        outputDevice = p.getInt(KEY_OUTPUT_DEVICE, 0)
        buffer = runCatching { Buffer.valueOf(p.getString(KEY_BUFFER, null) ?: "Balanced") }
            .getOrDefault(Buffer.Balanced)
        voiceLimit = p.getInt(KEY_VOICES, 0)
        cores = p.getInt(KEY_CORES, 0)
        bufferFloor = p.getInt(KEY_BUFFER_FLOOR, 0)
        fullQuality = p.getBoolean(KEY_QUALITY, true)
        autoQuality = p.getBoolean(KEY_AUTO_QUALITY, false)
        qualityNow = fullQuality
        recordBits = p.getInt(KEY_BITS, 24)
        inputDevice = p.getInt(KEY_INPUT_DEVICE, 0)
        inputClean = p.getBoolean(KEY_INPUT_CLEAN, false)
        readZooms(p.getString(KEY_ZOOMS, null))
        keepAwake = p.getBoolean(KEY_AWAKE, true)
        showDiagnostics = com.rm.acidulous.AppHost.current.debugBuild && p.getBoolean(KEY_DIAGNOSTICS, true)
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
        recordReplace = p.getBoolean(KEY_RECORD_REPLACE, false)
        recordOnNote = p.getBoolean(KEY_RECORD_ON_NOTE, false)
        recordOnce = p.getBoolean(KEY_RECORD_ONCE, false)
        recordQuantise = p.getBoolean(KEY_RECORD_Q, true)
        recordStrength = p.getInt(KEY_RECORD_Q_AMOUNT, 100)
        clickWhen = p.getInt(KEY_CLICK_WHEN, 0)
        // Not pushed here: init() runs in onCreate, well before
        // NativeEngine.start(), so anything sent now goes nowhere.
        // applyToEngine() sends settings to a running engine.
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
        MidiHub.velocityCurve = p.getInt(KEY_VELOCITY_CURVE, 0).coerceIn(-VelocityCurve.STEPS, VelocityCurve.STEPS)
        MidiHub.chooseClockOut(p.getBoolean(KEY_MIDI_CLOCK_OUT, false))
        // The switch was on or off before auto; an old "on" is still on.
        val follow = p.getString(KEY_MIDI_FOLLOW_MODE, null)
            ?.let { runCatching { MidiHub.Follow.valueOf(it) }.getOrNull() }
            ?: if (p.getBoolean(KEY_MIDI_FOLLOW, false)) MidiHub.Follow.On else MidiHub.Follow.Off
        MidiHub.chooseFollow(follow)
        // The MPE spec says a receiver should assume 48 semitones, which is
        // nothing like what a keyboard means by a bend.
        MidiHub.chooseMpe(
            // Auto unless chosen otherwise: follow the controller.
            p.getInt(KEY_MPE_ZONE, com.rm.acidulous.midi.MpeZone.AUTO), p.getInt(KEY_MPE_MEMBERS, 15),
            p.getFloat(KEY_MPE_BEND, 48f), p.getBoolean(KEY_MPE_TIMBRE, true),
        )
        // The old on/off switch: off stays off, on becomes the new default.
        val padMode = p.getInt(KEY_PAD_MODE, if (p.getBoolean(KEY_PAD_LIGHTS, true)) 0 else 2)
        MidiHub.choosePadMode(MidiHub.PadMode.entries.getOrElse(padMode) { MidiHub.PadMode.Own })
        MidiHub.chooseLaunchpad(p.getBoolean(KEY_LAUNCHPAD, true))
        MidiHub.chooseExquisButtons(p.getBoolean(KEY_EXQUIS_BUTTONS, true))
    }

    /** Whether an attached Launchpad Pro is played by the app, or left as itself. */
    fun chooseLaunchpad(on: Boolean) {
        MidiHub.chooseLaunchpad(on)
        store?.edit()?.putBoolean(KEY_LAUNCHPAD, on)?.apply()
    }

    /** Whether the app has an attached Exquis's transport and undo buttons. */
    fun chooseExquisButtons(on: Boolean) {
        MidiHub.chooseExquisButtons(on)
        store?.edit()?.putBoolean(KEY_EXQUIS_BUTTONS, on)?.apply()
    }

    /** How an attached Exquis shows the played track's scale on its pads. */
    fun choosePadMode(mode: MidiHub.PadMode) {
        MidiHub.choosePadMode(mode)
        store?.edit()?.putInt(KEY_PAD_MODE, mode.ordinal)?.apply()
    }

    /** Sends the three stepped click params, whenever one of them changes. */
    private fun pushClick() = EngineSync.setClickSettings(clickVoice, clickDivision, clickWhen, clickVolume)

    /**
     * Sends the engine the settings it can't read itself. Called once the audio
     * stream is up and again whenever one changes; the engine keeps no
     * preferences of its own.
     */
    fun applyToEngine() {
        NativeEngine.setBufferFloor(bufferFloor)
        NativeEngine.setBufferBursts(buffer.bursts)
        NativeEngine.setInputClean(inputClean)
        NativeEngine.setVoiceLimit(voiceLimit)
        NativeEngine.setCores(cores)
        NativeEngine.setQuality(if (fullQuality) 1 else 0)
        NativeEngine.setRecordBits(recordBits)
        NativeEngine.setLauncher(clipMode)
        NativeEngine.setExternalSync(MidiHub.clockIn)
        // Launch quantise is in bars here and ticks in the engine. MainScreen
        // re-sends it when the time signature changes.
        NativeEngine.setLaunchQuantise(launchQuantise * 4 * 240)
        NativeEngine.setCountInBars(countInBars)
        NativeEngine.setRecordModes(recordOnNote, recordOnce)
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
     * Where the keyboard's edge was let go, upright. Saved once the finger
     * lifts, see [chooseKeysFraction].
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
     * Where the divider above the keyboard was let go. The caller holds the live
     * value during a drag and stores it once the finger lifts, so the preference
     * file isn't written sixty times a second.
     */
    fun chooseKeysFraction(f: Float) {
        keysFractionLand = f.coerceIn(KeysFractionMin, KeysFractionMax)
        store?.edit()?.putFloat(KEY_KEYS_FRACTION, keysFractionLand)?.apply()
    }

    // Not setClipMode: the property's generated setter already has that JVM
    // signature. Same reason chooseTheme isn't setTheme.
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

    /** The next of [SongTime], for a tap on the time. */
    fun nextSongTime() {
        songTime = SongTime.entries[(songTime.ordinal + 1) % SongTime.entries.size]
        store?.edit()?.putInt(KEY_SONG_TIME, songTime.ordinal)?.apply()
    }

    /** New keys for one action; an empty list leaves it with none. */
    fun chooseKeys(action: KeyAction, chords: List<KeyChord>) {
        keyBindings = keyBindings + (action to chords)
        store?.edit()?.putString(KEY_KEYS, encodeKeys(keyBindings.filter { (a, c) -> DEFAULT_KEYS[a] != c }))?.apply()
    }

    fun chooseNoteLayout(layout: NoteLayout) {
        noteLayout = layout
        store?.edit()?.putString(KEY_NOTE_LAYOUT, layout.name)?.apply()
    }

    fun resetKeys() {
        keyBindings = DEFAULT_KEYS
        padJobs = emptyMap()
        store?.edit()?.remove(KEY_KEYS)?.remove(KEY_PAD_JOBS)?.apply()
    }

    /** A new job for a controller button; its own job again takes the change away. */
    fun choosePadJob(keyCode: Int, job: Pad.Job) {
        padJobs = if (Pad.DEFAULT_JOBS[keyCode] == job || (Pad.DEFAULT_JOBS[keyCode] == null && job == Pad.Job.Nothing)) {
            padJobs - keyCode
        } else {
            padJobs + (keyCode to job)
        }
        store?.edit()?.putString(KEY_PAD_JOBS, padJobs.entries.joinToString(";") { (c, j) -> "$c=${Pad.encode(j)}" })?.apply()
    }

    fun chooseLanguage(l: Language) {
        if (l == language) return
        language = l
        store?.edit()?.putString(KEY_LANGUAGE, l.name)?.apply()
        com.rm.acidulous.AppHost.current.applyLanguage(l.tag)
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        store?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    /**
     * Stored as the multiplier, not an index into the steps, so adding a step
     * later can't change someone's saved choice.
     */
    fun chooseUiScale(scale: Float) {
        uiScale = scale.coerceIn(UiScaleSteps.first(), UiScaleSteps.last())
        store?.edit()?.putFloat(KEY_UI_SCALE, uiScale)?.apply()
    }

    fun chooseOutputDevice(id: Int) {
        outputDevice = id
        store?.edit()?.putInt(KEY_OUTPUT_DEVICE, id)?.apply()
        com.rm.acidulous.AppHost.current.chooseAudioOutput(id)
    }

    fun chooseScreenScale(scale: Float) {
        screenScale = scale
        store?.edit()?.putFloat(KEY_SCREEN_SCALE, scale)?.apply()
    }

    fun chooseBuffer(b: Buffer) {
        buffer = b
        store?.edit()?.putString(KEY_BUFFER, b.name)?.apply()
        NativeEngine.setBufferBursts(b.bursts)
    }

    fun chooseCores(n: Int) {
        cores = n
        store?.edit()?.putInt(KEY_CORES, n)?.apply()
        NativeEngine.setCores(n)
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
     * What auto quality decided, kept apart from your own setting. It never
     * writes over `fullQuality`, so turning auto off gives your setting back.
     * This is the last thing it asked for, so the readout can show what's
     * actually running.
     */
    var qualityNow by mutableStateOf(true)
        private set

    fun applyAutoQuality(full: Boolean) {
        if (qualityNow == full) return
        qualityNow = full
        NativeEngine.setQuality(if (full) 1 else 0)
    }

    /**
     * The editor's zoom for each track, by track id: ticks across and rows
     * down, 0 for the editor's default. Kept across leaving the editor and
     * across launches for the last [ZOOMS_KEPT] tracks, most recent last.
     */
    private val zooms = LinkedHashMap<String, Pair<Float, Float>>()

    fun zoomOf(trackId: String): Pair<Float, Float> = zooms[trackId] ?: (0f to 0f)

    fun chooseZoom(trackId: String, ticks: Float, rows: Float) {
        if (zooms[trackId] == ticks to rows) return
        zooms.remove(trackId)
        if (ticks > 0f || rows > 0f) zooms[trackId] = ticks to rows
        while (zooms.size > ZOOMS_KEPT) zooms.remove(zooms.keys.first())
        store?.edit()?.putString(
            KEY_ZOOMS,
            zooms.entries.joinToString(";") { (id, z) -> "$id=${z.first},${z.second}" }.ifEmpty { null },
        )?.apply()
    }

    private fun readZooms(text: String?) {
        zooms.clear()
        for (entry in text.orEmpty().split(';')) {
            val (id, rest) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            val (ticks, rows) = rest.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: continue
            zooms[id] = ticks to rows
        }
    }

    fun chooseInputClean(on: Boolean) {
        inputClean = on
        store?.edit()?.putBoolean(KEY_INPUT_CLEAN, on)?.apply()
        NativeEngine.setInputClean(on)
    }

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

    /** Whether recording moves notes onto the clip's grid, and how far: 0..100. */
    var recordQuantise by mutableStateOf(true)
        private set
    var recordStrength by mutableStateOf(100)
        private set

    fun chooseRecordQuantise(on: Boolean = recordQuantise, strength: Int = recordStrength) {
        recordQuantise = on
        recordStrength = strength.coerceIn(0, 100)
        store?.edit()?.putBoolean(KEY_RECORD_Q, on)?.putInt(KEY_RECORD_Q_AMOUNT, recordStrength)?.apply()
    }

    /** A take replaces the notes it plays over, rather than adding to them. */
    var recordReplace by mutableStateOf(false)
        private set
    /** Armed and stopped, the first note played starts the song. */
    var recordOnNote by mutableStateOf(false)
        private set
    /** Recording stops by itself after one pass of the clip. */
    var recordOnce by mutableStateOf(false)
        private set

    fun chooseRecordTake(replace: Boolean = recordReplace, onNote: Boolean = recordOnNote, once: Boolean = recordOnce) {
        recordReplace = replace
        recordOnNote = onNote
        recordOnce = once
        store?.edit()?.putBoolean(KEY_RECORD_REPLACE, replace)?.putBoolean(KEY_RECORD_ON_NOTE, onNote)
            ?.putBoolean(KEY_RECORD_ONCE, once)?.apply()
        NativeEngine.setRecordModes(onNote, once)
    }

    fun chooseCountInBars(bars: Int) {
        countInBars = bars.coerceIn(0, 4)
        store?.edit()?.putInt(KEY_COUNT_IN, countInBars)?.apply()
        NativeEngine.setCountInBars(countInBars)
    }

    fun chooseDiagnostics(on: Boolean) {
        showDiagnostics = on && com.rm.acidulous.AppHost.current.debugBuild
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
     * Link is remembered but not switched on by `applyToEngine`: it needs a
     * Context for the multicast lock, so MainActivity turns it on once the
     * stream is up.
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

    /** How controller note-on velocities are curved: below 0 softer, above harder. */
    fun chooseVelocityCurve(step: Int) {
        val v = step.coerceIn(-VelocityCurve.STEPS, VelocityCurve.STEPS)
        MidiHub.velocityCurve = v
        store?.edit()?.putInt(KEY_VELOCITY_CURVE, v)?.apply()
    }

    /**
     * The MPE zone and bend range. Stored here with the other MIDI settings,
     * not in the song, since a zone describes the controller.
     */
    fun chooseMpe(
        zone: Int = MidiHub.mpeSetting,
        members: Int = MidiHub.mpeManualMembers,
        bendSemis: Float = MidiHub.mpeManualBend,
        timbre: Boolean = MidiHub.mpeTimbre,
    ) {
        val m = com.rm.acidulous.midi.MpeZone.clampMembers(members)
        val b = com.rm.acidulous.midi.MpeZone.clampBend(bendSemis)
        MidiHub.chooseMpe(zone, m, b, timbre)
        store?.edit()?.putInt(KEY_MPE_ZONE, MidiHub.mpeSetting)?.putInt(KEY_MPE_MEMBERS, m)
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
    private const val KEY_SONG_TIME = "song_time"
    private const val KEY_LOOP_BARS = "loop_bars"
    private const val KEY_THEME = "theme"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_KEYS = "key_bindings"
    private const val KEY_PAD_JOBS = "pad_jobs"
    private const val KEY_NOTE_LAYOUT = "note_layout"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_SCREEN_SCALE = "screen_scale"
    private const val KEY_OUTPUT_DEVICE = "output_device"
    private const val KEY_BUFFER = "buffer"
    private const val KEY_VOICES = "voice_limit"
    private const val KEY_CORES = "cores"
    private const val KEY_BUFFER_FLOOR = "buffer_floor"
    private const val KEY_QUALITY = "quality_full"
    private const val KEY_AUTO_QUALITY = "quality_auto"
    private const val KEY_BITS = "record_bits"
    private const val KEY_INPUT_DEVICE = "input_device"
    private const val KEY_INPUT_CLEAN = "input_clean"
    private const val KEY_ZOOMS = "editor_zooms"
    private const val ZOOMS_KEPT = 64
    private const val KEY_AWAKE = "keep_awake"
    private const val KEY_DIAGNOSTICS = "diagnostics"
    private const val KEY_MAPPINGS = "cc_mappings"
    private const val KEY_CLICK_VOICE = "click_voice"
    private const val KEY_CLICK_DIV = "click_div"
    private const val KEY_CLICK_VOL = "click_vol"
    private const val KEY_RECORD_Q = "record_quantise"
    private const val KEY_RECORD_Q_AMOUNT = "record_quantise_amount"
    private const val KEY_COUNT_IN = "count_in"
    private const val KEY_RECORD_REPLACE = "record_replace"
    private const val KEY_RECORD_ON_NOTE = "record_on_note"
    private const val KEY_RECORD_ONCE = "record_once"
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
    private const val KEY_PAD_LIGHTS = "exquis_pad_lights"
    private const val KEY_PAD_MODE = "exquis_pad_mode"
    private const val KEY_EXQUIS_BUTTONS = "exquis_buttons"
    private const val KEY_LAUNCHPAD = "launchpad_app"
    private const val KEY_MIDI_RACK = "midi_rack"
    private const val KEY_MIDI_CLOCK_OUT = "midi_clock_out"
    private const val KEY_MIDI_FOLLOW = "midi_follow"
    private const val KEY_MIDI_FOLLOW_MODE = "midi_follow_mode"
    private const val KEY_MIDI_AHEAD = "midi_ahead_ms"
    private const val KEY_VELOCITY_CURVE = "midi_velocity_curve"
    private const val KEY_LINK = "link_on"
    private const val KEY_LINK_STARTSTOP = "link_startstop"

    // --- What a new song and a new track start as ------------------------

    /**
     * Fit [index]'s track with a Scale modifier set to the default scale, so a
     * new track matches the song from its first note. Does nothing when no
     * default is set.
     */
    fun Song.withDefaultScale(index: Int): Song {
        // The song's key wins over the setting. Once a song has a key, a track
        // fitted from the setting would disagree with its roll.
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

    /** A new song with the tempo, signature, scale and machine from the settings. */
    fun newSong(name: String): Song =
        SongStore.blank(name, newTempo, newSignature, newMachine).withDefaultScale(0)
}

/** The time on the readout in song mode: where the playhead is in the song, the time left, or the time since play. */
enum class SongTime { Position, Remaining, Elapsed }
