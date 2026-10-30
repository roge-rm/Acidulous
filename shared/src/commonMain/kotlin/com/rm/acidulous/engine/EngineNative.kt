package com.rm.acidulous.engine

// The platform versions are generated from this list
// (tools/gen_engine_bridge.py). To add an engine call, add a line here, its
// C++ in platform/android/jni_bridge.cpp, and run the script. One line per
// call, since the script reads it line by line.
//
// `suspend` marks slow calls (decoding a file, rendering a song) that are
// only made off the main thread. On a JVM it's the same JNI call. In a
// browser it's handed to an engine thread, since the page has only one.
//
// `// line` marks a call that builds the engine's state (mounts, loads, the
// song snapshot, parameters, tempo), which is written to engine lines while
// recording (EngineLines.kt) and can be replayed by other hosts. After it,
// `path=x` says argument x is a media path, `spec=x:2,3` that x is lines of
// `|` fields where fields 2 and 3 (from 0) may be media paths, and
// `makes-handle` that the call returns a handle that later calls pass as
// their Long `handle`, and `dry=x` what the call answers while recording dry
// when that isn't "it worked" (a fresh engine has no cached clips).

/**
 * The engine's native calls, matching platform/android/jni_bridge.cpp one
 * for one: JNI on Android and desktop, and the same bridge compiled to
 * WebAssembly in a browser. Only NativeEngine calls these.
 */
internal expect object EngineNative {
    fun nativePanic()
    suspend fun nativeLoadNexusPatch(rack: Int, spec: String): String // line
    fun nativeNexusPalette(): String
    fun nativeNexusScope(rack: Int, out: FloatArray): Int
    fun nativeNexusActivity(rack: Int, out: FloatArray): Int
    fun nativeStartInput(deviceId: Int): Boolean
    fun nativeSetInputClean(on: Boolean)
    fun nativeInputSession(): Int
    fun nativeStopInput(): Boolean
    fun nativeInputChannels(): Int
    fun nativeInputRate(): Int
    fun nativeInputDevice(): Int
    fun nativeInputRunning(): Boolean
    fun nativeInputPeak(): Float
    fun nativeSetInputGain(gain: Float)
    fun nativeSetSwingUnit(unit: Int) // line
    fun nativeSetTunerOn(on: Boolean)
    fun nativeTunerHz(): Float
    fun nativeSetMonitorLevel(level: Float)
    fun nativeStartCapture(path: String, source: Int): String
    fun nativeStopCapture()
    fun nativeCapturing(): Boolean
    fun nativeCapturedSeconds(): Float
    fun nativeCapturedPeak(): Float
    fun nativeCaptureOverflowed(): Boolean
    fun nativeCaptureDeaf(): Boolean
    fun nativeCapturedFrames(): Long
    fun nativeRoomTone(on: Boolean, levelDb: Float)
    fun nativeRoomSweepStart(seconds: Float, repeats: Int, levelDb: Float): String
    fun nativeRoomSweepStop()
    fun nativeRoomClose()
    fun nativeRoomSweepState(): Int
    fun nativeRoomSweepProgress(): Float
    fun nativeRoomSweepPeak(): Float
    suspend fun nativeRoomAnalyse(picture: FloatArray): String
    suspend fun nativeRoomListen(frames: Int): String
    suspend fun nativeRoomSave(path: String, frames: Int): String
    suspend fun nativeCompCell(rack: Int, sceneId: Long, frames: Int, bpm: Float, path: String, peakOut: FloatArray): String
    fun nativeArmCapture(rack: Int)
    fun nativeCaptureMarks(out: LongArray): Int
    suspend fun nativeFileShape(path: String, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    suspend fun nativeFileInfo(path: String): String
    suspend fun nativeFileSurvey(path: String, out: FloatArray): String
    suspend fun nativeCutTake(path: String, kind: Int, noteHz: Float, consonantNear: Float): String
    fun nativeAuditionFile(path: String): String
    fun nativeAuditioning(): Boolean
    fun nativeAuditionProgress(): Float
    fun nativeEditSample(src: String, dst: String, ops: FloatArray): String
    suspend fun nativeEditPreview(src: String, ops: FloatArray, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    fun nativeAuditionPreview(): String
    fun nativeMidiEvent(rackId: Int, status: Int, data1: Int, data2: Int, channel: Int)
    fun nativeSetMpeZone(kind: Int, members: Int, bendSemis: Float)
    fun nativeMpeHeldMask(): Int
    fun nativeStart(): Boolean
    fun nativeStop()
    fun nativeIsRunning(): Boolean
    suspend fun nativeLoadUtterance(rack: Int, path: String): String // line path=path
    suspend fun nativeLoadVoice(rack: Int, slot: Int, spec: String): String // line spec=spec:2,3
    fun nativeMountMachine(rackId: Int, typeName: String): Boolean // line
    fun nativeUnmountMachine(rackId: Int) // line
    suspend fun nativeRenderSong(path: String, tailSeconds: Float, format: Int, bits: Int, startScene: Int, maxSeconds: Float): String
    suspend fun nativeRenderStems(paths: Array<String>, racks: IntArray, tailSeconds: Float, format: Int, bits: Int, startScene: Int, maxSeconds: Float): String
    fun nativeSetCountInBars(bars: Int)
    fun nativeSetBufferFloor(frames: Int)
    fun nativeBufferFloor(): Int
    fun nativeSetRecordModes(onNote: Boolean, once: Boolean)
    fun nativeCountInRemaining(): Long
    fun nativeElapsedMs(): Long
    fun nativeSetCores(cores: Int)
    fun nativeCoresInUse(): Int
    fun nativeCoresMax(): Int
    fun nativeReadWorkerWaitPeakUs(): Int
    fun nativeCancelRender()
    fun nativeIsRendering(): Boolean
    fun nativeRenderedSeconds(): Float
    fun nativeRenderedPeak(): Float
    fun nativePrewarm()
    fun nativeMountInputMod(rackId: Int, slot: Int, typeName: String): Boolean // line
    fun nativeInputModTypes(): Array<String>
    fun nativeInputModParamInfo(type: String): Array<String>
    fun nativeMountEffect(rackId: Int, slot: Int, typeName: String): Boolean // line
    fun nativeMountSend(slot: Int, typeName: String): Boolean // line
    suspend fun nativeMeasureLoudness(tailSeconds: Float, startScene: Int, maxSeconds: Float): FloatArray?
    fun nativeSetRenderGain(db: Float)
    fun nativeLoudness(): FloatArray
    fun nativeResetLoudness()
    fun nativeMountMasterInsert(slot: Int, typeName: String): Boolean // line
    fun nativeMountGroupInsert(group: Int, slot: Int, typeName: String): Boolean // line
    fun nativeGroupPeak(group: Int): Float
    fun nativeMountInputEffect(slot: Int, typeName: String): Boolean // line
    fun nativeEffectTypes(): Array<String>
    fun nativeEffectParamInfo(type: String): Array<String>
    fun nativeLoadSample(rackId: Int, slot: Int, path: String, maxSeconds: Int): String // line path=path
    suspend fun nativeSoundFontPresets(path: String): String
    suspend fun nativeLoadSoundFont(rackId: Int, path: String, presetIndex: Int): String // line path=path
    suspend fun nativeLoadZoneMap(rackId: Int, spec: String, name: String): String // line spec=spec:0
    fun nativeSampleMapInfo(rackId: Int): String
    fun nativeSampleInfo(rackId: Int, slot: Int): String
    suspend fun nativeSlicePoints(path: String, mode: Int, count: Int): String
    suspend fun nativeLoopShape(path: String): String
    suspend fun nativeImportAudio(path: String, maxSeconds: Int): String
    fun nativeSampleShape(rack: Int, pad: Int, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    fun nativeMachineTypes(): Array<String>
    fun nativeNoteOn(rackId: Int, note: Int, velocity: Int)
    fun nativeNoteOff(rackId: Int, note: Int)
    fun nativeControlChange(rackId: Int, cc: Int, value: Int, record: Boolean)
    fun nativeChannelPressure(rackId: Int, value: Int, record: Boolean)
    fun nativeSetParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean, quantise: Int): Boolean // line
    suspend fun nativeLoadTake(rack: Int, path: String): String // line path=path
    suspend fun nativeLoadReel(rack: Int, spec: String): String // line spec=spec:2
    fun nativeSetCacheRoot(path: String)
    suspend fun nativeLoadFormula(rack: Int, formula: String, arp: String, duty: String, vol: String): String // line
    suspend fun nativeLoadEffectFormula(rack: Int, unit: String, formula: String): String // line
    suspend fun nativeLoadImpulse(rack: Int, unit: String, path: String, length01: Float, decay01: Float, stretch01: Float): String // line path=path
    suspend fun nativeBuildCloud(rack: Int, spectrum01: FloatArray): String // line
    suspend fun nativeFreezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float): String
    suspend fun nativeLoadFrozen(rack: Int, sceneIds: LongArray, paths: Array<String>, bpms: FloatArray, ticks: IntArray, tails: IntArray): String
    fun nativeClearFrozen() // line
    suspend fun nativeMountFrozen(rack: Int, sceneId: Long, path: String, bpm: Float, ticks: Int, loopFrames: Int, tailFrames: Int, peak: Float): String // line path=path
    fun nativeSetBufferBursts(bursts: Int)
    fun nativeBufferFrames(): Int
    fun nativeSetVoiceLimit(notes: Int)
    fun nativeSetQuality(level: Int)
    fun nativeSetRecordBits(bits: Int)
    fun nativeGetSampleRate(): Int
    fun nativeGetFramesPerBurst(): Int
    fun nativeIsLowLatency(): Boolean
    fun nativeGetXRunCount(): Long
    fun nativeGetLoadAvg(): Float
    fun nativeWorstBlockUs(): Int
    fun nativeWorstCallbackUs(): Int
    fun nativeWorstPhaseUs(phase: Int): Int
    fun nativeWorstRackUs(rack: Int): Int
    fun nativeRackPercentileUs(rack: Int): Int
    fun nativeResetRackCosts()
    fun nativeWorstRackWasFrozen(rack: Int): Boolean
    fun nativeInterruptedPercent(): Float
    fun nativeHintRunning(): Boolean
    fun nativeHintAvailable(): Boolean
    fun nativeHintState(): Int
    fun nativeFastCores(): Int
    fun nativeRackCostUs(rack: Int): Int
    fun nativeRecentCallbackUs(): Int
    fun nativeWorstCallbackCpuUs(): Int
    fun nativeLateCallbacks(): Long
    fun nativeStalledCallbacks(): Long
    fun nativeCallbackBudgetUs(): Int
    fun nativeReadPeakLevel(): Float
    fun nativeReadRackPeak(rackId: Int): Float
    fun nativeGetMasterFade(): Float
    fun nativeTransportPlay(sceneIdx: Int)
    fun nativeTransportStop()
    fun nativeTransportRewind()
    fun nativeSetStopAtEnd(on: Boolean)
    fun nativeIsStopAtEndArmed(): Boolean
    fun nativeQueueScene(idx: Int)
    fun nativeQueuedScene(): Int
    fun nativeSetLauncher(on: Boolean)
    fun nativeSetFill(on: Boolean)
    fun nativeSetLaunchQuantise(ticks: Int)
    fun nativeLaunchClip(rack: Int, sceneId: Long)
    fun nativeLaunchScene(sceneId: Long)
    fun nativeStopAllClips()
    fun nativeCancelLaunch(rack: Int)
    fun nativeSetClockOut(on: Boolean)
    fun nativeDrainMidiOut(out: LongArray): Int
    fun nativeAudioAnchor(out: LongArray)
    fun nativeSetExternalSync(on: Boolean)
    fun nativeSetTuning(rack: Int, ratios: FloatArray?) // line
    fun nativeSetMacroNames(rack: Int, names: String) // line
    fun nativeSetLink(on: Boolean)
    fun nativeLinkEnabled(): Boolean
    fun nativeSetLinkStartStop(on: Boolean)
    fun nativeLinkStatus(): Long
    fun nativeMidiClockIn(frame: Long, status: Int, d1: Int, d2: Int)
    fun nativeSyncState(): Long
    fun nativeLaunchStates(out: LongArray)
    fun nativeIsPlaying(): Boolean
    fun nativeSetLoopScene(on: Boolean)
    fun nativeSetLoopSong(on: Boolean) // line
    fun nativeSetRecordArmed(on: Boolean)
    fun nativeIsRecordArmed(): Boolean
    fun nativeDrainRecorded(out: LongArray): Int
    fun nativeGetRecordedDropped(): Int
    fun nativeSetTempo(bpm: Float) // line
    fun nativeGetTempo(): Float
    fun nativeGetPositionPacked(): Long
    fun nativeGetNotesOn(rackId: Int): Int
    fun nativeDebugParam(rackId: Int, name: String): Float
    fun nativeGetNotesOff(rackId: Int): Int
    fun nativeSnapshotBegin(): Long // line makes-handle
    fun nativeSnapshotAddScene(handle: Long, sceneId: Long, ticksPerBar: Int, repeat: Int, bpmOverride: Float, rampToBpm: Float, rampBars: Int, smooth: Boolean, fadeIn: Boolean, fadeOut: Boolean): Boolean // line
    fun nativeSnapshotSetClipCached(handle: Long, rack: Int, scene: Int, rev: Long): Boolean // line dry=false
    fun nativeSnapshotSetClip(handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, seed: Int, notes: IntArray, expr: FloatArray, lyrics: String?): Boolean // line
    fun nativeSnapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean // line
    fun nativeSnapshotCommit(handle: Long): Boolean // line
    fun nativeMachineParamNames(type: String): Array<String>
    fun nativeMachineParamInfo(type: String): Array<String>
    fun nativeParamNormalized(rackId: Int, unit: String, name: String): Float
    fun nativeSnapshotAbandon(handle: Long) // line
}
