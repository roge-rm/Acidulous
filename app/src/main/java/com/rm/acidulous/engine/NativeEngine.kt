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

    /** Every machine type the engine can build, from its registry. */
    val machineTypes: List<String> get() = nativeMachineTypes().toList()

    fun noteOn(rackId: Int, note: Int, velocity: Int = 100) = nativeNoteOn(rackId, note, velocity)

    fun noteOff(rackId: Int, note: Int) = nativeNoteOff(rackId, note)

    /**
     * [unit] is "machine", "effect1", "effect2", "eventor1", "eventor2" or "channel";
     * [value] is normalised 0..1. Returns false if the name is unknown for what is mounted.
     */
    fun setParam(rackId: Int, unit: String, name: String, value: Float): Boolean =
        nativeSetParam(rackId, unit, name, value)

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
     * Drains live MIDI stamped by the audio thread while recording. Fills [out]
     * with 4 longs per event - absTick, sceneId, tickInIteration, packed
     * (rack shl 24 or cmd shl 16 or p1 shl 8 or p2) - and returns the count.
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

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeMountMachine(rackId: Int, typeName: String): Boolean
    private external fun nativeUnmountMachine(rackId: Int)
    private external fun nativeMachineTypes(): Array<String>
    private external fun nativeNoteOn(rackId: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(rackId: Int, note: Int)
    private external fun nativeSetParam(rackId: Int, unit: String, name: String, value: Float): Boolean
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetFramesPerBurst(): Int
    private external fun nativeIsLowLatency(): Boolean
    private external fun nativeGetXRunCount(): Long
    private external fun nativeGetLoadAvg(): Float
    private external fun nativeReadPeakLevel(): Float
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
    private external fun nativeSnapshotCommit(handle: Long): Boolean
    private external fun nativeSnapshotAbandon(handle: Long)
}
