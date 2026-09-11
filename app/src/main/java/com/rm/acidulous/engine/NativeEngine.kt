package com.rm.acidulous.engine

/**
 * The only door into the native audio engine.
 *
 * Everything here is non-blocking from the caller's point of view: note and
 * parameter calls push onto lock-free queues that the audio thread drains, and
 * object construction happens on the calling thread before being handed over.
 */
object NativeEngine {

    init {
        System.loadLibrary("acidulous")
    }

    fun start(): Boolean = nativeStart()

    fun stop() = nativeStop()

    val isRunning: Boolean get() = nativeIsRunning()

    /** Queues a machine of [typeName] to be mounted on [rackId]. Applied at a block boundary. */
    fun mountMachine(rackId: Int, typeName: String): Boolean = nativeMountMachine(rackId, typeName)
    fun unmountMachine(rackId: Int) = nativeUnmountMachine(rackId)
    /** Mounts an insert effect on one of a rack's two slots; an empty [typeName] clears it. */
    fun mountEffect(rackId: Int, slot: Int, typeName: String): Boolean = nativeMountEffect(rackId, slot, typeName)
    /**
     * Builds what machines need before they can be mounted (Trinity's
     * wavetables, ~0.1 s). Call once on a worker at startup; blocks.
     */
    fun prewarm() = nativePrewarm()

    /** Mounts an eventor (Scale, Chord, Arp) ahead of the machine; an empty [typeName] clears it. */
    fun mountEventor(rackId: Int, slot: Int, typeName: String): Boolean = nativeMountEventor(rackId, slot, typeName)

    /** Decodes a WAV and mounts it on a pad; empty path clears. Returns an error message, or "" on success. */
    fun loadSample(rackId: Int, slot: Int, absolutePath: String): String = nativeLoadSample(rackId, slot, absolutePath)
    /**
     * Multisample maps for Mosaic. All three block while the instrument is
     * built and decoded, so call them from a worker.
     */
    fun soundFontPresets(path: String): List<String> {
        val raw = nativeSoundFontPresets(path)
        if (raw.startsWith("!")) return emptyList()
        return raw.trim().lines().filter { it.isNotBlank() }
    }
    fun soundFontError(path: String): String = nativeSoundFontPresets(path).let { if (it.startsWith("!")) it.drop(1) else "" }
    fun loadSoundFont(rackId: Int, path: String, presetIndex: Int): String = nativeLoadSoundFont(rackId, path, presetIndex)
    /** One zone per line: path|lowKey|highKey|rootKey|lowVel|highVel|cents|gain|pan|loop */
    fun loadZoneMap(rackId: Int, spec: String, name: String): String = nativeLoadZoneMap(rackId, spec, name)
    /** "name|zones|samples|seconds" for the mounted map, or "". */
    fun sampleMapInfo(rackId: Int): String = nativeSampleMapInfo(rackId)

    /** "name|frames|stereo" for a loaded pad, or "". */
    fun sampleInfo(rackId: Int, slot: Int): String = nativeSampleInfo(rackId, slot)

    /**
     * Renders the whole song to a 24-bit WAV at [path], blocking the calling
     * thread (use a worker). Returns "" on success or an error; "cancelled" after [cancelRender].
     */
    fun renderSong(path: String, tailSeconds: Float = 2f): String = nativeRenderSong(path, tailSeconds)
    fun cancelRender() = nativeCancelRender()
    val isRendering: Boolean get() = nativeIsRendering()
    val renderedSeconds: Float get() = nativeRenderedSeconds()
    val renderedPeak: Float get() = nativeRenderedPeak()

    /** Every machine type the engine can build, from its registry. */
    val machineTypes: List<String> get() = nativeMachineTypes().toList()
    fun machineParamNames(type: String): List<String> = nativeMachineParamNames(type).toList()
    fun machineParamInfo(type: String): List<ParamInfo> = nativeMachineParamInfo(type).map { ParamInfo.parse(it) }
    /** Every insert effect type, from the effect registry. */
    val effectTypes: List<String> get() = nativeEffectTypes().toList()
    fun effectParamInfo(type: String): List<ParamInfo> = nativeEffectParamInfo(type).map { ParamInfo.parse(it) }
    val eventorTypes: List<String> get() = nativeEventorTypes().toList()
    fun eventorParamInfo(type: String): List<ParamInfo> = nativeEventorParamInfo(type).map { ParamInfo.parse(it) }
    /** Normalised 0..1 value of a mounted unit's parameter, or -1. */
    fun paramNormalized(rackId: Int, unit: String, name: String): Float = nativeParamNormalized(rackId, unit, name)

    fun noteOn(rackId: Int, note: Int, velocity: Int = 100) = nativeNoteOn(rackId, note, velocity)

    fun noteOff(rackId: Int, note: Int) = nativeNoteOff(rackId, note)

    /**
     * A channel message from a MIDI port, addressed to a rack. Everything a
     * keyboard sends arrives this way - notes, wheel, pressure, bend - so
     * playing from hardware takes the same path as playing on the screen,
     * recording included.
     */
    fun midiEvent(rackId: Int, status: Int, data1: Int, data2: Int) =
        nativeMidiEvent(rackId, status, data1, data2)

    /** Mod wheel is CC 1; pressure is channel aftertouch. Both 0..127. */
    fun controlChange(rackId: Int, cc: Int, value: Int) = nativeControlChange(rackId, cc, value)
    fun channelPressure(rackId: Int, value: Int) = nativeChannelPressure(rackId, value)

    /**
     * [unit] is "machine", "effect1", "effect2", "eventor1", "eventor2", "eventor3" or "channel";
     * [value] is normalised 0..1. Returns false if the name is unknown for what is mounted.
     */
    fun setParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean = true): Boolean =
        nativeSetParam(rackId, unit, name, value, record)

    // --- Transport -----------------------------------------------------------
    /** Play from the top of [sceneIdx]; -1 restarts the current scene. */
    fun transportPlay(sceneIdx: Int = -1) = nativeTransportPlay(sceneIdx)
    fun transportStop() = nativeTransportStop()

    /** The scene lined up to follow this one, or -1. Setting it cancels a pending stop. */
    var queuedScene: Int
        get() = nativeQueuedScene()
        set(value) = nativeQueueScene(value)

    /** Let the playing scene finish its repeats and then stop. */
    var stopAtEnd: Boolean
        get() = nativeIsStopAtEndArmed()
        set(value) = nativeSetStopAtEnd(value)
    val isPlaying: Boolean get() = nativeIsPlaying()
    fun setLoopScene(on: Boolean) = nativeSetLoopScene(on)
    fun setLoopSong(on: Boolean) = nativeSetLoopSong(on)

    /** REC stand-by: arm now, and playing records. */
    var recordArmed: Boolean
        get() = nativeIsRecordArmed()
        set(on) = nativeSetRecordArmed(on)

    /**
     * Drains live events stamped by the audio thread while recording. Fills
     * [out] with 5 longs per event - absTick, sceneId, tickInIteration, packed
     * (rack shl 24 or cmd shl 16 or p1 shl 8 or p2), and for parameter events
     * (cmd 0xf0, p1 = unit ordinal) (index shl 32 or float bits) - returns the count.
     */
    fun drainRecorded(out: LongArray): Int = nativeDrainRecorded(out)
    val recordedDropped: Int get() = nativeGetRecordedDropped()

    /** The song tempo. Reading returns the *effective* tempo, which a scene may override. */
    var tempo: Float
        get() = nativeGetTempo()
        set(bpm) = nativeSetTempo(bpm)

    /** Packed scene | repeat | tick-in-iteration; decode with [Position.unpack]. */
    val positionPacked: Long get() = nativeGetPositionPacked()

    // --- Clip mode -----------------------------------------------------------
    /** The grid becomes a launcher: every rack plays whichever clip it was given. */
    fun setLauncher(on: Boolean) = nativeSetLauncher(on)

    /** 0 swaps at the end of the playing clip's cycle; otherwise a tick grid. */
    fun setLaunchQuantise(ticks: Int) = nativeSetLaunchQuantise(ticks)

    /**
     * A cell was tapped. What it means - start, cancel or stop - is decided on
     * the audio thread against what is actually playing, so a tap is never
     * read against a state that is already eighty milliseconds old.
     */
    fun launchClip(rack: Int, sceneId: Long) = nativeLaunchClip(rack, sceneId)
    fun stopAllClips() = nativeStopAllClips()

    /** Forget what this track had queued, whatever has happened since. */
    fun cancelLaunch(rack: Int) = nativeCancelLaunch(rack)

    // --- MIDI out ------------------------------------------------------------
    /** Twenty-four pulses a quarter note, plus start, stop and song position. */
    fun setClockOut(on: Boolean) = nativeSetClockOut(on)

    /** Fills [out] with two longs per event - frame, then rack|status|d1|d2. */
    fun drainMidiOut(out: LongArray): Int = nativeDrainMidiOut(out)

    /**
     * Ties the engine's frame count to the wall clock: [out] becomes
     * frame, nanoseconds, sample rate. The frame is -1 until the audio
     * stream has run long enough to know.
     */
    fun audioAnchor(out: LongArray) = nativeAudioAnchor(out)

    /** Fills [out] (one per rack) with packed scene | pending | tick-in-cycle. */
    fun launchStates(out: LongArray) = nativeLaunchStates(out)

    fun notesOn(rackId: Int): Int = nativeGetNotesOn(rackId)
    fun debugParam(rackId: Int, name: String): Float = nativeDebugParam(rackId, name)
    fun notesOff(rackId: Int): Int = nativeGetNotesOff(rackId)

    // --- Song snapshot builder (see EngineSync) ------------------------------
    fun snapshotBegin(): Long = nativeSnapshotBegin()
    fun snapshotAddScene(
        handle: Long, sceneId: Long, ticksPerBar: Int, repeat: Int, bpmOverride: Float,
        smooth: Boolean, fadeIn: Boolean, fadeOut: Boolean,
    ): Boolean = nativeSnapshotAddScene(handle, sceneId, ticksPerBar, repeat, bpmOverride, smooth, fadeIn, fadeOut)
    /** True if the engine still has this clip rev and reused it; false means marshal it. */
    fun snapshotSetClipCached(handle: Long, rack: Int, scene: Int, rev: Long): Boolean =
        nativeSnapshotSetClipCached(handle, rack, scene, rev)
    fun snapshotSetClip(
        handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, notes: IntArray,
    ): Boolean = nativeSnapshotSetClip(handle, rack, scene, rev, bars, playMode, mute, notes)
    fun snapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean =
        nativeSnapshotSetLane(handle, rack, scene, machineType, unit, name, linear, points)
    fun snapshotCommit(handle: Long): Boolean = nativeSnapshotCommit(handle)
    fun snapshotAbandon(handle: Long) = nativeSnapshotAbandon(handle)

    /** Decode a WAV and mount it with its transients. Worker only. */
    fun loadTake(rack: Int, path: String): String = nativeLoadTake(rack, path)

    /** Compile and mount Formulate's expression and tables. "" or the reason. */
    fun loadFormula(rack: Int, formula: String, arp: String, duty: String, vol: String): String =
        nativeLoadFormula(rack, formula, arp, duty, vol)

    /**
     * Build and mount Cumulus's tables for a rack. Slow; worker only.
     * [spectrum01] is the spectrum parameters in table order, NaN for any
     * the song has never set - passed rather than read from the engine,
     * which may not have been given them yet.
     */
    fun buildCloud(rack: Int, spectrum01: FloatArray): String = nativeBuildCloud(rack, spectrum01)

    // --- Freeze --------------------------------------------------------------
    /** What a freeze produced, or why it did not happen. */
    sealed class FreezeResult {
        data class Ok(val frames: Int, val ticks: Int, val bpm: Float, val peak: Float) : FreezeResult()
        data class Failed(val reason: String) : FreezeResult()
    }

    /**
     * Render one clip to [path]. Stops the audio stream for the duration, so
     * this belongs on a worker and not while the transport is running.
     */
    fun freezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float = 2f): FreezeResult {
        val out = nativeFreezeClip(rack, sceneId, path, tailSeconds)
        val parts = out.split("|")
        return if (parts.size == 5 && parts[0] == "ok") {
            FreezeResult.Ok(parts[1].toInt(), parts[2].toInt(), parts[3].toFloat(), parts[4].toFloat())
        } else {
            FreezeResult.Failed(out)
        }
    }

    /** Give a rack its frozen clips, or none. Returns "" or the reason. */
    fun loadFrozen(rack: Int, sceneIds: LongArray, paths: Array<String>, bpms: FloatArray, ticks: IntArray): String =
        nativeLoadFrozen(rack, sceneIds, paths, bpms, ticks)

    // --- Settings that belong to the device ----------------------------------
    /** Output buffer depth in bursts: 1 tight, 2 default, 4 safe. */
    fun setBufferBursts(bursts: Int) = nativeSetBufferBursts(bursts)
    /** What the stream is actually buffering, which the device may round. */
    val bufferFrames: Int get() = nativeBufferFrames()
    /** Held notes per rack, 0 for no limit. */
    fun setVoiceLimit(notes: Int) = nativeSetVoiceLimit(notes)
    /** 1 full, 0 lean. */
    fun setQuality(level: Int) = nativeSetQuality(level)
    /** Bits in a recorded or exported WAV: 24 or 16. */
    fun setRecordBits(bits: Int) = nativeSetRecordBits(bits)

    // --- Diagnostics ---------------------------------------------------------
    val sampleRate: Int get() = nativeGetSampleRate()
    val framesPerBurst: Int get() = nativeGetFramesPerBurst()
    val isLowLatency: Boolean get() = nativeIsLowLatency()
    val xRunCount: Long get() = nativeGetXRunCount()
    val loadAvg: Float get() = nativeGetLoadAvg()

    /** Peak absolute sample since the last call, then reset. 0.0 means silence. */
    fun readPeakLevel(): Float = nativeReadPeakLevel()
    fun readRackPeak(rackId: Int): Float = nativeReadRackPeak(rackId)
    /** The scene fade multiplier the master is applying right now (1 = none). */
    val masterFade: Float get() = nativeGetMasterFade()

    /**
     * Stop everything and silence every tail, now. For the moment a patch
     * runs away, which a modular makes easy and a headphone amplifier does
     * not forgive.
     */
    fun panic() = nativePanic()

    private external fun nativePanic()

    // --- Nexus -----------------------------------------------------------

    /** Build a patch on this thread and hand it to the rack. "" or an error. */
    fun loadNexusPatch(rack: Int, spec: String): String = nativeLoadNexusPatch(rack, spec)

    /** The module palette, straight from the engine: one line per type. */
    fun nexusPalette(): String = nativeNexusPalette()

    /** Fills [out] with the scope trace and returns how many points landed. */
    fun nexusScope(rack: Int, out: FloatArray): Int = nativeNexusScope(rack, out)

    private external fun nativeLoadNexusPatch(rack: Int, spec: String): String
    private external fun nativeNexusPalette(): String
    private external fun nativeNexusScope(rack: Int, out: FloatArray): Int

    // --- Audio in --------------------------------------------------------

    /** Opens the microphone or line in. Needs RECORD_AUDIO to have been granted. */
    fun startInput(): Boolean = nativeStartInput()
    fun stopInput() = nativeStopInput()
    val inputRunning: Boolean get() = nativeInputRunning()
    /** Peak since the last read, then reset. */
    fun inputPeak(): Float = nativeInputPeak()
    fun setInputGain(gain: Float) = nativeSetInputGain(gain)
    fun setMonitorLevel(level: Float) = nativeSetMonitorLevel(level)

    /** source 0 = what is coming in, 1 = what is going out. Returns "" or an error. */
    fun startCapture(path: String, source: Int): String = nativeStartCapture(path, source)
    fun stopCapture() = nativeStopCapture()
    val capturing: Boolean get() = nativeCapturing()
    val capturedSeconds: Float get() = nativeCapturedSeconds()
    val capturedPeak: Float get() = nativeCapturedPeak()
    val captureOverflowed: Boolean get() = nativeCaptureOverflowed()

    private external fun nativeStartInput(): Boolean
    private external fun nativeStopInput()
    private external fun nativeInputRunning(): Boolean
    private external fun nativeInputPeak(): Float
    private external fun nativeSetInputGain(gain: Float)
    private external fun nativeSetMonitorLevel(level: Float)
    private external fun nativeStartCapture(path: String, source: Int): String
    private external fun nativeStopCapture()
    private external fun nativeCapturing(): Boolean
    private external fun nativeCapturedSeconds(): Float
    private external fun nativeCapturedPeak(): Float
    private external fun nativeCaptureOverflowed(): Boolean
    private external fun nativeMidiEvent(rackId: Int, status: Int, data1: Int, data2: Int)
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeMountMachine(rackId: Int, typeName: String): Boolean
    private external fun nativeUnmountMachine(rackId: Int)
    private external fun nativeRenderSong(path: String, tailSeconds: Float): String
    private external fun nativeCancelRender()
    private external fun nativeIsRendering(): Boolean
    private external fun nativeRenderedSeconds(): Float
    private external fun nativeRenderedPeak(): Float
    private external fun nativePrewarm()
    private external fun nativeMountEventor(rackId: Int, slot: Int, typeName: String): Boolean
    private external fun nativeEventorTypes(): Array<String>
    private external fun nativeEventorParamInfo(type: String): Array<String>
    private external fun nativeMountEffect(rackId: Int, slot: Int, typeName: String): Boolean
    private external fun nativeEffectTypes(): Array<String>
    private external fun nativeEffectParamInfo(type: String): Array<String>
    private external fun nativeLoadSample(rackId: Int, slot: Int, path: String): String
    private external fun nativeSoundFontPresets(path: String): String
    private external fun nativeLoadSoundFont(rackId: Int, path: String, presetIndex: Int): String
    private external fun nativeLoadZoneMap(rackId: Int, spec: String, name: String): String
    private external fun nativeSampleMapInfo(rackId: Int): String
    private external fun nativeSampleInfo(rackId: Int, slot: Int): String
    private external fun nativeMachineTypes(): Array<String>
    private external fun nativeNoteOn(rackId: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(rackId: Int, note: Int)
    private external fun nativeControlChange(rackId: Int, cc: Int, value: Int)
    private external fun nativeChannelPressure(rackId: Int, value: Int)
    private external fun nativeSetParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean): Boolean
    private external fun nativeLoadTake(rack: Int, path: String): String
    private external fun nativeLoadFormula(rack: Int, formula: String, arp: String, duty: String, vol: String): String
    private external fun nativeBuildCloud(rack: Int, spectrum01: FloatArray): String
    private external fun nativeFreezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float): String
    private external fun nativeLoadFrozen(rack: Int, sceneIds: LongArray, paths: Array<String>, bpms: FloatArray, ticks: IntArray): String
    private external fun nativeSetBufferBursts(bursts: Int)
    private external fun nativeBufferFrames(): Int
    private external fun nativeSetVoiceLimit(notes: Int)
    private external fun nativeSetQuality(level: Int)
    private external fun nativeSetRecordBits(bits: Int)
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetFramesPerBurst(): Int
    private external fun nativeIsLowLatency(): Boolean
    private external fun nativeGetXRunCount(): Long
    private external fun nativeGetLoadAvg(): Float
    private external fun nativeReadPeakLevel(): Float
    private external fun nativeReadRackPeak(rackId: Int): Float
    private external fun nativeGetMasterFade(): Float
    private external fun nativeTransportPlay(sceneIdx: Int)
    private external fun nativeTransportStop()
    private external fun nativeSetStopAtEnd(on: Boolean)
    private external fun nativeIsStopAtEndArmed(): Boolean
    private external fun nativeQueueScene(idx: Int)
    private external fun nativeQueuedScene(): Int
    private external fun nativeSetLauncher(on: Boolean)
    private external fun nativeSetLaunchQuantise(ticks: Int)
    private external fun nativeLaunchClip(rack: Int, sceneId: Long)
    private external fun nativeStopAllClips()
    private external fun nativeCancelLaunch(rack: Int)
    private external fun nativeSetClockOut(on: Boolean)
    private external fun nativeDrainMidiOut(out: LongArray): Int
    private external fun nativeAudioAnchor(out: LongArray)
    private external fun nativeLaunchStates(out: LongArray)
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeSetLoopScene(on: Boolean)
    private external fun nativeSetLoopSong(on: Boolean)
    private external fun nativeSetRecordArmed(on: Boolean)
    private external fun nativeIsRecordArmed(): Boolean
    private external fun nativeDrainRecorded(out: LongArray): Int
    private external fun nativeGetRecordedDropped(): Int
    private external fun nativeSetTempo(bpm: Float)
    private external fun nativeGetTempo(): Float
    private external fun nativeGetPositionPacked(): Long
    private external fun nativeGetNotesOn(rackId: Int): Int
    private external fun nativeDebugParam(rackId: Int, name: String): Float
    private external fun nativeGetNotesOff(rackId: Int): Int
    private external fun nativeSnapshotBegin(): Long
    private external fun nativeSnapshotAddScene(
        handle: Long, sceneId: Long, ticksPerBar: Int, repeat: Int, bpmOverride: Float,
        smooth: Boolean, fadeIn: Boolean, fadeOut: Boolean,
    ): Boolean
    private external fun nativeSnapshotSetClipCached(handle: Long, rack: Int, scene: Int, rev: Long): Boolean
    private external fun nativeSnapshotSetClip(
        handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, notes: IntArray,
    ): Boolean
    private external fun nativeSnapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean
    private external fun nativeSnapshotCommit(handle: Long): Boolean
    private external fun nativeMachineParamNames(type: String): Array<String>
    private external fun nativeMachineParamInfo(type: String): Array<String>
    private external fun nativeParamNormalized(rackId: Int, unit: String, name: String): Float
    private external fun nativeSnapshotAbandon(handle: Long)
}
