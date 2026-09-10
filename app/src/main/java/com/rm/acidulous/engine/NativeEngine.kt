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

    /** Mod wheel is CC 1; pressure is channel aftertouch. Both 0..127. */
    fun controlChange(rackId: Int, cc: Int, value: Int) = nativeControlChange(rackId, cc, value)
    fun channelPressure(rackId: Int, value: Int) = nativeChannelPressure(rackId, value)

    /**
     * [unit] is "machine", "effect1", "effect2", "eventor1", "eventor2" or "channel";
     * [value] is normalised 0..1. Returns false if the name is unknown for what is mounted.
     */
    fun setParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean = true): Boolean =
        nativeSetParam(rackId, unit, name, value, record)

    // --- Transport -----------------------------------------------------------
    /** Play from the top of [sceneIdx]; -1 restarts the current scene. */
    fun transportPlay(sceneIdx: Int = -1) = nativeTransportPlay(sceneIdx)
    fun transportStop() = nativeTransportStop()
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
    private external fun nativeSampleInfo(rackId: Int, slot: Int): String
    private external fun nativeMachineTypes(): Array<String>
    private external fun nativeNoteOn(rackId: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(rackId: Int, note: Int)
    private external fun nativeControlChange(rackId: Int, cc: Int, value: Int)
    private external fun nativeChannelPressure(rackId: Int, value: Int)
    private external fun nativeSetParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean): Boolean
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
