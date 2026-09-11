package com.rm.acidulous.ui

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
import com.rm.acidulous.model.withEventor
import com.rm.acidulous.model.withEventorBypass
import com.rm.acidulous.model.withEventorParam
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
object UiPrefs {
    private var store: SharedPreferences? = null

    // --- Editing ---------------------------------------------------------
    var automationFolded by mutableStateOf(false)
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

    /** Bits in a recorded or exported WAV. */
    var recordBits by mutableStateOf(24)
        private set

    // --- Screen ----------------------------------------------------------
    /** Keep the screen awake while the transport is running. */
    var keepAwake by mutableStateOf(true)
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

    fun init(context: Context) {
        val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        store = p
        automationFolded = p.getBoolean(KEY_AUTO_FOLDED, false)
        clipMode = p.getBoolean(KEY_CLIP_MODE, false)
        launchQuantise = p.getInt(KEY_LAUNCH_Q, 0)
        theme = runCatching { ThemeMode.valueOf(p.getString(KEY_THEME, null) ?: "Dark") }
            .getOrDefault(ThemeMode.Dark)
        buffer = runCatching { Buffer.valueOf(p.getString(KEY_BUFFER, null) ?: "Balanced") }
            .getOrDefault(Buffer.Balanced)
        voiceLimit = p.getInt(KEY_VOICES, 0)
        fullQuality = p.getBoolean(KEY_QUALITY, true)
        recordBits = p.getInt(KEY_BITS, 24)
        keepAwake = p.getBoolean(KEY_AWAKE, true)
        newTempo = p.getFloat(KEY_TEMPO, 120f)
        newSignature = Signature(p.getInt(KEY_BEATS, 4), p.getInt(KEY_UNIT, 4))
        newScaleOn = p.getBoolean(KEY_SCALE_ON, false)
        newScaleKey = p.getInt(KEY_SCALE_KEY, 0)
        newScaleIndex = p.getInt(KEY_SCALE_INDEX, 0)
        MidiHub.routing = runCatching { MidiHub.Routing.valueOf(p.getString(KEY_MIDI_ROUTE, null) ?: "SelectedTrack") }
            .getOrDefault(MidiHub.Routing.SelectedTrack)
        MidiHub.fixedRack = p.getInt(KEY_MIDI_RACK, 0)
        MidiHub.outOffsetMs = p.getInt(KEY_MIDI_AHEAD, 0)
        MidiHub.chooseClockOut(p.getBoolean(KEY_MIDI_CLOCK_OUT, false))
        MidiHub.chooseExternalSync(p.getBoolean(KEY_MIDI_FOLLOW, false))
    }

    /**
     * Hand the engine what it cannot read for itself. Called once the audio
     * stream is up, and again whenever one of these changes - the engine
     * keeps no preferences of its own, so this is the only thing that puts
     * them there.
     */
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
    }

    fun foldAutomation(folded: Boolean) {
        automationFolded = folded
        store?.edit()?.putBoolean(KEY_AUTO_FOLDED, folded)?.apply()
    }

    // Not setClipMode: the property's own generated setter already owns
    // that JVM signature. Same reason chooseTheme is not setTheme.
    fun chooseClipMode(on: Boolean) {
        clipMode = on
        store?.edit()?.putBoolean(KEY_CLIP_MODE, on)?.apply()
        NativeEngine.setLauncher(on)
    }

    fun chooseQuantise(bars: Int) {
        launchQuantise = bars
        store?.edit()?.putInt(KEY_LAUNCH_Q, bars)?.apply()
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        store?.edit()?.putString(KEY_THEME, mode.name)?.apply()
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

    fun chooseRecordBits(bits: Int) {
        recordBits = bits
        store?.edit()?.putInt(KEY_BITS, bits)?.apply()
        NativeEngine.setRecordBits(bits)
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

    fun chooseExternalSync(on: Boolean) {
        MidiHub.chooseExternalSync(on)
        store?.edit()?.putBoolean(KEY_MIDI_FOLLOW, on)?.apply()
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

    fun chooseMidiRouting(r: MidiHub.Routing, rack: Int = MidiHub.fixedRack) {
        MidiHub.routing = r
        MidiHub.fixedRack = rack
        store?.edit()?.putString(KEY_MIDI_ROUTE, r.name)?.putInt(KEY_MIDI_RACK, rack)?.apply()
    }

    private const val KEY_AUTO_FOLDED = "automation_folded"
    private const val KEY_CLIP_MODE = "clip_mode"
    private const val KEY_LAUNCH_Q = "launch_quantise"
    private const val KEY_THEME = "theme"
    private const val KEY_BUFFER = "buffer"
    private const val KEY_VOICES = "voice_limit"
    private const val KEY_QUALITY = "quality_full"
    private const val KEY_BITS = "record_bits"
    private const val KEY_AWAKE = "keep_awake"
    private const val KEY_TEMPO = "new_tempo"
    private const val KEY_BEATS = "new_beats"
    private const val KEY_UNIT = "new_unit"
    private const val KEY_SCALE_ON = "new_scale_on"
    private const val KEY_SCALE_KEY = "new_scale_key"
    private const val KEY_SCALE_INDEX = "new_scale_index"
    private const val KEY_MIDI_ROUTE = "midi_routing"
    private const val KEY_MIDI_RACK = "midi_rack"
    private const val KEY_MIDI_CLOCK_OUT = "midi_clock_out"
    private const val KEY_MIDI_FOLLOW = "midi_follow"
    private const val KEY_MIDI_AHEAD = "midi_ahead_ms"

    // --- What a new song and a new track start as ------------------------

    /**
     * Fit [index]'s track with a Scale eventor set to the default scale, so
     * a new track agrees with the song from its first note. Does nothing
     * when no default is set - an unasked-for eventor in slot 1 would be a
     * surprise, not a convenience.
     */
    fun Song.withDefaultScale(index: Int): Song {
        if (!newScaleOn) return this
        val track = tracks.getOrNull(index) ?: return this
        val fitted = track.withEventor(0, "Scale")
            .withEventorParam(0, "key", newScaleKey / 11f)
            .withEventorParam(0, "scale", newScaleIndex / (Scales.names.size - 1f))
            .withEventorParam(0, "mode", 0f)
            .withEventorBypass(0, false)
        return copy(tracks = tracks.toMutableList().also { it[index] = fitted })
    }

    /** A new song in the tempo, signature and scale the settings ask for. */
    fun newSong(name: String): Song =
        SongStore.blank(name, newTempo, newSignature).withDefaultScale(0)
}
