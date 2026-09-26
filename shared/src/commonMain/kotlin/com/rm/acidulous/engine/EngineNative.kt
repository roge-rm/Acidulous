package com.rm.acidulous.engine

// The list the platforms' halves are written from (tools/gen_engine_bridge.py):
// a new engine call is a line here, its C++ in platform/android/jni_bridge.cpp,
// and a run of the script. One line per call, as the script reads it.

/**
 * The engine's native calls, one for one with platform/android/jni_bridge.cpp:
 * JNI on Android and the desktop, the same bridge compiled to WebAssembly in a
 * browser. NativeEngine is the Kotlin face of them; nothing else calls these.
 */
internal expect object EngineNative {
    fun nativePanic()
    fun nativeLoadNexusPatch(rack: Int, spec: String): String
    fun nativeNexusPalette(): String
    fun nativeNexusScope(rack: Int, out: FloatArray): Int
    fun nativeNexusActivity(rack: Int, out: FloatArray): Int
    fun nativeStartInput(deviceId: Int): Boolean
    fun nativeStopInput(): Boolean
    fun nativeInputChannels(): Int
    fun nativeInputRate(): Int
    fun nativeInputDevice(): Int
    fun nativeInputRunning(): Boolean
    fun nativeInputPeak(): Float
    fun nativeSetInputGain(gain: Float)
    fun nativeSetSwingUnit(unit: Int)
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
    fun nativeCompCell(rack: Int, sceneId: Long, frames: Int, bpm: Float, path: String, peakOut: FloatArray): String
    fun nativeArmCapture(rack: Int)
    fun nativeCaptureMarks(out: LongArray): Int
    fun nativeFileShape(path: String, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    fun nativeFileInfo(path: String): String
    fun nativeFileSurvey(path: String, out: FloatArray): String
    fun nativeAuditionFile(path: String): String
    fun nativeAuditioning(): Boolean
    fun nativeEditSample(src: String, dst: String, ops: FloatArray): String
    fun nativeMidiEvent(rackId: Int, status: Int, data1: Int, data2: Int, channel: Int)
    fun nativeSetMpeZone(kind: Int, members: Int, bendSemis: Float)
    fun nativeMpeHeldMask(): Int
    fun nativeStart(): Boolean
    fun nativeStop()
    fun nativeIsRunning(): Boolean
    fun nativeLoadUtterance(rack: Int, path: String): String
    fun nativeMountMachine(rackId: Int, typeName: String): Boolean
    fun nativeUnmountMachine(rackId: Int)
    fun nativeRenderSong(path: String, tailSeconds: Float, format: Int, bits: Int, startScene: Int, maxSeconds: Float): String
    fun nativeRenderStems(paths: Array<String>, racks: IntArray, tailSeconds: Float, format: Int, bits: Int, startScene: Int, maxSeconds: Float): String
    fun nativeSetCountInBars(bars: Int)
    fun nativeCountInRemaining(): Long
    fun nativeCancelRender()
    fun nativeIsRendering(): Boolean
    fun nativeRenderedSeconds(): Float
    fun nativeRenderedPeak(): Float
    fun nativePrewarm()
    fun nativeMountInputMod(rackId: Int, slot: Int, typeName: String): Boolean
    fun nativeInputModTypes(): Array<String>
    fun nativeInputModParamInfo(type: String): Array<String>
    fun nativeMountEffect(rackId: Int, slot: Int, typeName: String): Boolean
    fun nativeMountSend(slot: Int, typeName: String): Boolean
    fun nativeMeasureLoudness(tailSeconds: Float, startScene: Int, maxSeconds: Float): FloatArray?
    fun nativeSetRenderGain(db: Float)
    fun nativeLoudness(): FloatArray
    fun nativeResetLoudness()
    fun nativeMountMasterInsert(slot: Int, typeName: String): Boolean
    fun nativeMountGroupInsert(group: Int, slot: Int, typeName: String): Boolean
    fun nativeGroupPeak(group: Int): Float
    fun nativeMountInputEffect(slot: Int, typeName: String): Boolean
    fun nativeEffectTypes(): Array<String>
    fun nativeEffectParamInfo(type: String): Array<String>
    fun nativeLoadSample(rackId: Int, slot: Int, path: String, maxSeconds: Int): String
    fun nativeSoundFontPresets(path: String): String
    fun nativeLoadSoundFont(rackId: Int, path: String, presetIndex: Int): String
    fun nativeLoadZoneMap(rackId: Int, spec: String, name: String): String
    fun nativeSampleMapInfo(rackId: Int): String
    fun nativeSampleInfo(rackId: Int, slot: Int): String
    fun nativeSlicePoints(path: String, mode: Int, count: Int): String
    fun nativeLoopShape(path: String): String
    fun nativeImportAudio(path: String, maxSeconds: Int): String
    fun nativeSampleShape(rack: Int, pad: Int, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    fun nativeMachineTypes(): Array<String>
    fun nativeNoteOn(rackId: Int, note: Int, velocity: Int)
    fun nativeNoteOff(rackId: Int, note: Int)
    fun nativeControlChange(rackId: Int, cc: Int, value: Int, record: Boolean)
    fun nativeChannelPressure(rackId: Int, value: Int, record: Boolean)
    fun nativeSetParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean, quantise: Int): Boolean
    fun nativeLoadTake(rack: Int, path: String): String
    fun nativeLoadReel(rack: Int, spec: String): String
    fun nativeSetCacheRoot(path: String)
    fun nativeLoadFormula(rack: Int, formula: String, arp: String, duty: String, vol: String): String
    fun nativeBuildCloud(rack: Int, spectrum01: FloatArray): String
    fun nativeFreezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float): String
    fun nativeLoadFrozen(rack: Int, sceneIds: LongArray, paths: Array<String>, bpms: FloatArray, ticks: IntArray, tails: IntArray): String
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
    fun nativeStopAllClips()
    fun nativeCancelLaunch(rack: Int)
    fun nativeSetClockOut(on: Boolean)
    fun nativeDrainMidiOut(out: LongArray): Int
    fun nativeAudioAnchor(out: LongArray)
    fun nativeSetExternalSync(on: Boolean)
    fun nativeSetTuning(rack: Int, ratios: FloatArray?)
    fun nativeSetLink(on: Boolean)
    fun nativeLinkEnabled(): Boolean
    fun nativeSetLinkStartStop(on: Boolean)
    fun nativeLinkStatus(): Long
    fun nativeMidiClockIn(frame: Long, status: Int, d1: Int, d2: Int)
    fun nativeSyncState(): Long
    fun nativeLaunchStates(out: LongArray)
    fun nativeIsPlaying(): Boolean
    fun nativeSetLoopScene(on: Boolean)
    fun nativeSetLoopSong(on: Boolean)
    fun nativeSetRecordArmed(on: Boolean)
    fun nativeIsRecordArmed(): Boolean
    fun nativeDrainRecorded(out: LongArray): Int
    fun nativeGetRecordedDropped(): Int
    fun nativeSetTempo(bpm: Float)
    fun nativeGetTempo(): Float
    fun nativeGetPositionPacked(): Long
    fun nativeGetNotesOn(rackId: Int): Int
    fun nativeDebugParam(rackId: Int, name: String): Float
    fun nativeGetNotesOff(rackId: Int): Int
    fun nativeSnapshotBegin(): Long
    fun nativeSnapshotAddScene(handle: Long, sceneId: Long, ticksPerBar: Int, repeat: Int, bpmOverride: Float, rampToBpm: Float, rampBars: Int, smooth: Boolean, fadeIn: Boolean, fadeOut: Boolean): Boolean
    fun nativeSnapshotSetClipCached(handle: Long, rack: Int, scene: Int, rev: Long): Boolean
    fun nativeSnapshotSetClip(handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, seed: Int, notes: IntArray, expr: FloatArray): Boolean
    fun nativeSnapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean
    fun nativeSnapshotCommit(handle: Long): Boolean
    fun nativeMachineParamNames(type: String): Array<String>
    fun nativeMachineParamInfo(type: String): Array<String>
    fun nativeParamNormalized(rackId: Int, unit: String, name: String): Float
    fun nativeSnapshotAbandon(handle: Long)
}
