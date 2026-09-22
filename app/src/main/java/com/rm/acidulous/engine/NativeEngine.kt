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
     * Put an effect on one of the two send buses, or empty it with "".
     *
     * No rack: the sends belong to the song. Their parameters are then
     * addressed as the units `send1` and `send2`, exactly as an insert's are
     * addressed as `effect1` and `effect2`.
     */
    fun mountSend(slot: Int, typeName: String): Boolean = nativeMountSend(slot, typeName)
    fun mountMasterInsert(slot: Int, typeName: String): Boolean = nativeMountMasterInsert(slot, typeName)

    /**
     * An effect on the way **in**, before anything hears the input.
     *
     * The difference from a track's insert is the whole point: what is here is
     * **printed into the recording**, because it runs before the capture ever
     * sees the block. Its parameters are addressed under the units `input1`
     * and `input2`, exactly as a send's are under `send1` and `send2`.
     */
    fun mountInputEffect(slot: Int, typeName: String): Boolean =
        nativeMountInputEffect(slot, typeName)
    /**
     * Builds what machines need before they can be mounted (Trinity's
     * wavetables, ~0.1 s). Call once on a worker at startup; blocks.
     */
    fun prewarm() = nativePrewarm()

    /** Mounts an modifier (Scale, Chord, Arp) ahead of the machine; an empty [typeName] clears it. */
    fun mountInputMod(rackId: Int, slot: Int, typeName: String): Boolean = nativeMountInputMod(rackId, slot, typeName)

    /**
     * How much of a long file a sample may hold, in seconds.
     *
     * Two numbers because the two uses cost differently. [PAD_SECONDS] is a
     * pad's own sample - one of thirteen, so thirty seconds each is already
     * 150 MB of kit. [SLICE_SECONDS] is the one file a whole Forage slices:
     * it is mounted once and every pad reads a region of it, so it can afford
     * to be a whole track. Ten minutes of it is 230 MB, which is the price of
     * slicing an album track. Must match kMaxDecodeSeconds and
     * kMaxSliceSeconds in engine/format/Decoded.h.
     */
    const val PAD_SECONDS = 30
    const val SLICE_SECONDS = 600

    /** Decodes a WAV and mounts it on a pad; empty path clears. Returns an error message, or "" on success. */
    fun loadSample(rackId: Int, slot: Int, absolutePath: String, maxSeconds: Int = PAD_SECONDS): String =
        nativeLoadSample(rackId, slot, absolutePath, maxSeconds)
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
     * A pad's sample as [out].size / 2 pairs of min and max, for drawing.
     *
     * Returns how many columns were filled - nought when the pad is empty.
     * Cheap enough to call on a resize and far too expensive to call per
     * frame: it walks the whole sample.
     */
    /**
     * [fromFrame] until [toFrame], or the whole sample when they are equal.
     *
     * A window rather than always the whole thing, because that is what makes
     * zooming mean anything: ten minutes across nine hundred columns is thirty
     * thousand frames a column, and magnifying that only makes the same
     * blur bigger.
     */
    fun sampleShape(rack: Int, pad: Int, out: FloatArray, fromFrame: Int = 0, toFrame: Int = 0): Int =
        nativeSampleShape(rack, pad, out, fromFrame, toFrame)

    /** What an import produced: where it went, and whether all of it got there. */
    data class Imported(val path: String, val truncated: Boolean)

    /**
     * Make an imported file readable: where to find it, or a failure with the
     * reason.
     *
     * A WAV comes back unchanged. Anything else is decoded, written beside
     * itself as a WAV and the original removed, so nothing downstream ever
     * sees a second format. Decodes the file, so call it off the main thread.
     *
     * [Imported.truncated] says the file was longer than [maxSeconds] and
     * only that much of it arrived - which the player has to be told, because
     * everything else about it looks like it worked.
     */
    fun importAudio(absolutePath: String, maxSeconds: Int = PAD_SECONDS): Result<Imported> {
        val out = nativeImportAudio(absolutePath, maxSeconds)
        val word = out.substringBefore('\n')
        val rest = out.substringAfter('\n', "")
        return when (word) {
            "ok" -> Result.success(Imported(rest, truncated = false))
            "cut" -> Result.success(Imported(rest, truncated = true))
            else -> Result.failure(IllegalArgumentException(rest.ifEmpty { "it would not load" }))
        }
    }

    /**
     * Where [count] slices fall in a file, as fractions of its length.
     *
     * [mode] 0 finds transients, 1 divides evenly. Returns count+1 boundaries
     * so slice n is points[n]..points[n+1]; empty if the file will not read.
     * Decodes the file, so call it off the main thread.
     */
    fun slicePoints(absolutePath: String, mode: Int, count: Int): List<Float> =
        nativeSlicePoints(absolutePath, mode, count)
            .split(',').mapNotNull { it.trim().toFloatOrNull() }

    /**
     * Renders the whole song to a 24-bit WAV at [path], blocking the calling
     * thread (use a worker). Returns "" on success or an error; "cancelled" after [cancelRender].
     */
    /** [format] indexes AudioFormat in the engine: 0 wav, 1 aiff, 2 flac. */
    fun renderSong(
        path: String, tailSeconds: Float = 2f, format: Int = 0, bits: Int = 24,
        startScene: Int = 0, maxSeconds: Float = 0f,
    ): String = nativeRenderSong(path, tailSeconds, format, bits, startScene, maxSeconds)

    /** One pass, one file per entry; a rack of -1 is the master mix. */
    fun renderStems(
        paths: Array<String>, racks: IntArray, tailSeconds: Float = 2f, format: Int = 0, bits: Int = 24,
        startScene: Int = 0, maxSeconds: Float = 0f,
    ): String = nativeRenderStems(paths, racks, tailSeconds, format, bits, startScene, maxSeconds)
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
    val inputModTypes: List<String> get() = nativeInputModTypes().toList()
    fun inputModParamInfo(type: String): List<ParamInfo> = nativeInputModParamInfo(type).map { ParamInfo.parse(it) }
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
    fun midiEvent(rackId: Int, status: Int, data1: Int, data2: Int, channel: Int = NO_CHANNEL) =
        nativeMidiEvent(rackId, status, data1, data2, channel)

    /**
     * An MPE zone on one rack. [kind] is 0 off, 1 lower, 2 upper.
     *
     * The rack needs it to know which arriving channels are fingers rather
     * than channels, and how far a per-note bend goes - 48 semitones by
     * the specification, where an ordinary bend means 2.
     */
    fun setMpeZone(kind: Int, members: Int, bendSemis: Float) =
        nativeSetMpeZone(kind, members, bendSemis)

    /** Which member channels are holding a note, a bit per channel. */
    val mpeHeldMask: Int get() = nativeMpeHeldMask()

    /** What the app's own notes carry: no channel, because they came from no cable. */
    const val NO_CHANNEL = 0xff

    /** Mod wheel is CC 1; pressure is channel aftertouch. Both 0..127. */
    /**
     * [record] says this was a gesture. Mod and pressure are recorded into a
     * lane when the transport is armed, so re-asserting a control's current
     * value - which the editor does when you change track - must say false,
     * or opening a track would write a point nobody played.
     */
    fun controlChange(rackId: Int, cc: Int, value: Int, record: Boolean = true) =
        nativeControlChange(rackId, cc, value, record)

    fun channelPressure(rackId: Int, value: Int, record: Boolean = true) =
        nativeChannelPressure(rackId, value, record)

    /**
     * [unit] is "machine", "effect1", "effect2", "mod1", "mod2", "mod3" or "channel";
     * [value] is normalised 0..1. Returns false if the name is unknown for what is mounted.
     */
    fun setParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean = true): Boolean =
        nativeSetParam(rackId, unit, name, value, record)

    // --- Transport -----------------------------------------------------------
    /** Play from the top of [sceneIdx]; -1 restarts the current scene. */
    fun transportPlay(sceneIdx: Int = -1) = nativeTransportPlay(sceneIdx)
    fun transportStop() = nativeTransportStop()

    /** Back to the top of the song without starting it - see Transport.h. */
    fun transportRewind() = nativeTransportRewind()

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

    /**
     * Whether Fill trigs may sound.
     *
     * Held rather than latched, so it is a plain flag with no state machine
     * behind it. Nobody holds a button during an offline render, which is what
     * keeps a song with fill trigs in it exporting the same way twice.
     */
    fun setFill(on: Boolean) = nativeSetFill(on)

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

    // --- Clock in ------------------------------------------------------------
    /** Follow an incoming clock rather than the song's own tempo. */
    fun setExternalSync(on: Boolean) = nativeSetExternalSync(on)

    // --- Ableton Link -------------------------------------------------------
    fun setLink(on: Boolean) = nativeSetLink(on)
    fun linkEnabled(): Boolean = nativeLinkEnabled()
    fun setLinkStartStop(on: Boolean) = nativeSetLinkStartStop(on)
    /** Peers in the top word, the session tempo in hundredths in the bottom. */
    fun linkStatus(): Long = nativeLinkStatus()

    /** A realtime byte, on the frame it was heard. */
    fun midiClockIn(frame: Long, status: Int, d1: Int, d2: Int) = nativeMidiClockIn(frame, status, d1, d2)

    /** Packed locked | bpm x100 | phase error in microseconds. */
    fun syncState(): Long = nativeSyncState()

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
    /**
     * @param notes flat [tick, length, pitch, velocity, curvePointCount, trig] x count,
     * where `trig` is `Note.trigWord` - chance, condition and ratchet in one
     * int - and `tick` already has the note's nudge folded into it
     * @param playMode bit 0 one-shot, bit 1 the dice roll free
     * @param seed the clip's own dice, so a probability repeats
     * @param expr flat [kind, tick, value] x point, in note order; each note
     * takes the number of points it declared
     */
    fun snapshotSetClip(
        handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, seed: Int,
        notes: IntArray, expr: FloatArray,
    ): Boolean = nativeSnapshotSetClip(handle, rack, scene, rev, bars, playMode, mute, seed, notes, expr)
    fun snapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean =
        nativeSnapshotSetLane(handle, rack, scene, machineType, unit, name, linear, points)
    fun snapshotCommit(handle: Long): Boolean = nativeSnapshotCommit(handle)
    fun snapshotAbandon(handle: Long) = nativeSnapshotAbandon(handle)

    /** Decode a WAV and mount it with its transients. Worker only. */
    fun loadTake(rack: Int, path: String): String = nativeLoadTake(rack, path)

    /**
     * What an audio track is holding, a line per region:
     *
     *     sceneId|lane|absPath|offset|frames|startTick|ticks|bpm|loop
     *
     * One line per lane per cell, so a take sung across four scenes is four
     * lines naming one file - and the engine decodes each distinct file once
     * however many lines mention it. An empty spec clears the reel.
     */
    fun loadReel(rack: Int, spec: String): String = nativeLoadReel(rack, spec)

    /**
     * Where a take too long to hold is converted to, set once at startup.
     *
     * Unset, a long take is held in memory up to the resident ceiling instead
     * of being mapped: nowhere to put a cache is a reason to do less, not a
     * reason to refuse.
     */
    fun setCacheRoot(path: String) = nativeSetCacheRoot(path)

    /** How long one take may be, which the engine refuses to exceed. */
    const val REEL_SECONDS = 300

    /**
     * A sung take for a Molt. Decoding and pitch-marking happen on the caller's
     * thread - a worker - so this must never be called from the main one; an
     * empty path clears what is mounted.
     */
    fun loadUtterance(rack: Int, path: String): String = nativeLoadUtterance(rack, path)

    /** Turn what a Molt just recorded into a take. Also a worker's job. */

    /** Changes when a Molt finishes recording, so the UI can notice. */

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
        data class Ok(val frames: Int, val ticks: Int, val bpm: Float, val peak: Float, val tail: Int) :
            FreezeResult()
        data class Failed(val reason: String) : FreezeResult()
    }

    /**
     * Render one clip to [path]. Stops the audio stream for the duration, so
     * this belongs on a worker and not while the transport is running.
     */
    fun freezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float = 8f): FreezeResult {
        val out = nativeFreezeClip(rack, sceneId, path, tailSeconds)
        val parts = out.split("|")
        return if (parts.size == 6 && parts[0] == "ok") {
            FreezeResult.Ok(
                parts[1].toInt(), parts[2].toInt(), parts[3].toFloat(), parts[4].toFloat(), parts[5].toInt(),
            )
        } else {
            FreezeResult.Failed(out)
        }
    }

    /** Give a rack its frozen clips, or none. Returns "" or the reason. */
    fun loadFrozen(
        rack: Int,
        sceneIds: LongArray,
        paths: Array<String>,
        bpms: FloatArray,
        ticks: IntArray,
        tails: IntArray,
    ): String = nativeLoadFrozen(rack, sceneIds, paths, bpms, ticks, tails)

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

    /**
     * The worst block and the worst callback since this was last read, in
     * microseconds, and how many callbacks have overrun.
     *
     * **Reading clears the two peaks**, so exactly one thing may poll them -
     * the diagnostics loop. [loadAvg] is a smoothed average and stays what it
     * is: it answers "how hard is it working", and these answer "how close did
     * it come to missing", which is the only question a dropout asks.
     */
    val worstBlockUs: Int get() = nativeWorstBlockUs()
    val worstCallbackUs: Int get() = nativeWorstCallbackUs()
    val worstCallbackCpuUs: Int get() = nativeWorstCallbackCpuUs()
    val lateCallbacks: Long get() = nativeLateCallbacks()

    /**
     * Late callbacks that were late **without doing the work** - descheduled
     * rather than slow. If this tracks [lateCallbacks] the device is not short
     * of CPU, it is short of priority, and no amount of cheaper DSP will help.
     */
    val stalledCallbacks: Long get() = nativeStalledCallbacks()

    /** One callback's worth of audio in microseconds, from the stream's own rate. */
    val callbackBudgetUs: Int get() = nativeCallbackBudgetUs()

    /** Mirrors `Engine::Phase`, in order. */
    enum class Phase { Input, Sequencer, Racks, Master, Capture }

    fun worstPhaseUs(phase: Phase): Int = nativeWorstPhaseUs(phase.ordinal)

    /** The worst this rack has cost since last asked, in microseconds. Reading clears it. */
    fun worstRackUs(rack: Int): Int = nativeWorstRackUs(rack)

    /**
     * What this rack costs in its worst block in a hundred, in microseconds.
     *
     * The peak above is one block out of a whole song, so one interrupt sets
     * it and nothing afterwards can bring it down: three runs of one build on
     * the same phone put the per-track peaks up to 26% apart. This needs one
     * block in a hundred to agree before it moves. Not cleared by reading.
     */
    fun rackPercentileUs(rack: Int): Int = nativeRackPercentileUs(rack)

    /** Start the distributions above again. The reset button. */
    fun resetRackCosts() = nativeResetRackCosts()
    /** Was that rack playing frozen audio when it set its peak? Read before [worstRackUs]. */
    fun worstRackWasFrozen(rack: Int): Boolean = nativeWorstRackWasFrozen(rack)
    /**
     * How often a block is interrupted rather than slow, 0 to 100.
     *
     * The worst-block, per-phase and per-track figures are only taken from
     * blocks that ran uninterrupted, so this is also how much they are not
     * seeing. High means the fix is scheduling, not DSP.
     */
    val interruptedPercent: Float get() = nativeInterruptedPercent()

    /** Whether the scheduler is being told about our deadline, and whether it could be. */
    val hintRunning: Boolean get() = nativeHintRunning()
    val hintAvailable: Boolean get() = nativeHintAvailable()
    /** 0 no api, 1 waiting, 2 the audio thread never named itself, 3 refused, 4 on. */
    val hintState: Int get() = nativeHintState()

    /** What this rack costs lately, in microseconds. Falls by itself; reading does not clear it. */
    fun rackCostUs(rack: Int): Int = nativeRackCostUs(rack)

    /** The worst recent callback, decaying. Safe for any number of readers. */
    val recentCallbackUs: Int get() = nativeRecentCallbackUs()

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

    /**
     * How much is moving in the patch: [NEXUS_SLOTS] module levels followed by
     * [NEXUS_CABLES] cable levels, so [out] wants to be that long.
     *
     * Slots are indexed by slot number and cables by their order in the patch
     * text, which is the same index their depth knobs use - so the editor can
     * read straight into what it is already drawing. Levels are decaying
     * peaks, not instantaneous samples.
     */
    fun nexusActivity(rack: Int, out: FloatArray): Int = nativeNexusActivity(rack, out)

    private external fun nativeLoadNexusPatch(rack: Int, spec: String): String
    private external fun nativeNexusPalette(): String
    private external fun nativeNexusScope(rack: Int, out: FloatArray): Int
    private external fun nativeNexusActivity(rack: Int, out: FloatArray): Int

    // --- Audio in --------------------------------------------------------

    /**
     * Opens the microphone or line in. Needs RECORD_AUDIO to have been granted.
     *
     * [deviceId] names one of the platform's own inputs - an `AudioDeviceInfo`
     * id - or nought for whatever it would have chosen. Defaulted, because
     * the panels that only want to listen do not care which ear they get.
     * Asking for a different one while open reopens the stream.
     */
    fun startInput(deviceId: Int = 0): Boolean = nativeStartInput(deviceId)
    /** True if closing the ear cut a recording short. */
    fun stopInput(): Boolean = nativeStopInput()
    val inputRunning: Boolean get() = nativeInputRunning()
    /** What the open stream actually is, rather than what was asked for. */
    val inputChannels: Int get() = nativeInputChannels()
    val inputRate: Int get() = nativeInputRate()
    val inputDevice: Int get() = nativeInputDevice()
    /**
     * The loudest thing since anybody looked, decayed rather than cleared, so
     * two meters on screen at once agree with each other.
     */
    fun inputPeak(): Float = nativeInputPeak()

    /** Which pair the swing bends: 0 sixteenths, 1 eighths. Song-wide. */
    fun setSwingUnit(unit: Int) = nativeSetSwingUnit(unit)

    /**
     * The tuner: on only while something is showing it, because while it is
     * on the audio thread copies every input block into its ring.
     */
    fun setTunerOn(on: Boolean) = nativeSetTunerOn(on)

    /**
     * The note in the last half second, in hertz, or 0 for none.
     *
     * **Does the analysis on the calling thread**, which costs about a
     * millisecond, so it is called from a background dispatcher and not from
     * the one drawing the window.
     */
    fun tunerHz(): Float = nativeTunerHz()
    fun setInputGain(gain: Float) = nativeSetInputGain(gain)
    fun setMonitorLevel(level: Float) = nativeSetMonitorLevel(level)

    /** source 0 = what is coming in, 1 = what is going out. Returns "" or an error. */
    fun startCapture(path: String, source: Int): String = nativeStartCapture(path, source)
    fun stopCapture() = nativeStopCapture()
    val capturing: Boolean get() = nativeCapturing()
    val capturedSeconds: Float get() = nativeCapturedSeconds()
    /** The finished file's length, exactly: seconds as a float loses frames. */
    val capturedFrames: Long get() = nativeCapturedFrames()
    val capturedPeak: Float get() = nativeCapturedPeak()
    val captureOverflowed: Boolean get() = nativeCaptureOverflowed()
    /** True once an input capture has run with nothing arriving at all. */
    val captureDeaf: Boolean get() = nativeCaptureDeaf()

    /**
     * Which rack the next recording is being made for, or -1 for none.
     *
     * Only an armed rack has the song's boundaries stamped against the frames
     * being written, and only one can be, because there is one capture. This
     * also resets the marks, so a second take starts with a clean sheet.
     */
    /**
     * Flatten one Bias cell's four lanes into one file: a comp.
     *
     * The medium is left off, because a comp is an edit and a patch is a way
     * of listening - baking the cassette in would make it permanent and leave
     * the patch applying it a second time on top. Offline and faster than real
     * time, with the transport stopped. Returns "" or the reason.
     */
    fun compCell(rack: Int, sceneId: Long, frames: Int, bpm: Float, path: String,
                 peakOut: FloatArray): String =
        nativeCompCell(rack, sceneId, frames, bpm, path, peakOut)

    fun armCapture(rack: Int) = nativeArmCapture(rack)

    /**
     * The boundaries the recording crossed, five longs each:
     * `frame, sceneId, tick, cycleTicks, millibpm`.
     *
     * Returns how many were written, or **-1 if the capture dropped frames** -
     * in which case every index after the drop names the wrong moment and the
     * take must not be split. [MARK_LONGS] is the stride.
     */
    fun captureMarks(out: LongArray): Int = nativeCaptureMarks(out)
    const val MARK_LONGS = 5
    const val MAX_MARKS = 512

    // --- A file on disk, rather than a mounted pad -----------------------

    /**
     * The waveform of a **file**, as min/max pairs, exactly as [sampleShape]
     * gives them for a loaded pad.
     *
     * `sampleShape` asks a Forage for its pad, so it answers nothing for Dice,
     * Pollen, Molt or Mosaic, and nothing at all for a recording that is not
     * mounted anywhere yet. Walks and decodes the file: a worker, never a
     * frame loop.
     */
    fun fileShape(path: String, out: FloatArray, fromFrame: Int = 0, toFrame: Int = 0): Int =
        nativeFileShape(path, out, fromFrame, toFrame)

    /** "name|frames|channels|rate|peak", or "" if it cannot be read. */
    fun fileInfo(path: String): String = nativeFileInfo(path)

    /**
     * [fileInfo] and [fileShape] in one decode: fills [out] with min/max pairs
     * for the whole file and returns the same string [fileInfo] does.
     *
     * Putting a take on a tape lane wants both answers about the same file, and
     * asking separately decodes it twice - which for a five-minute recording is
     * two peaks of well over a hundred megabytes. A worker, never the audio
     * thread and never the main one.
     */
    fun fileSurvey(path: String, out: FloatArray): String = nativeFileSurvey(path, out)

    /**
     * Play a file once, to hear what it is. An empty path stops it.
     *
     * Outside the song entirely: not recorded into a take, not exported, not
     * frozen, and stopped by a panic. Reads and decodes on the calling thread.
     */
    fun auditionFile(path: String): String = nativeAuditionFile(path)
    val auditioning: Boolean get() = nativeAuditioning()

    /** What [editSample] takes, in the order the engine unpacks it. */
    const val EDIT_OPS = 14

    /**
     * Read [src], apply the edit, write [dst]; "" or a reason.
     *
     * [dst] may be [src], which is the overwrite - the engine writes to a
     * temporary and renames, so a failure leaves the original alone. [ops] is
     * a flat array because a dozen arguments of the same type is a dozen
     * chances to swap two, and the order is written out on both sides:
     *
     *   0 from        1 to          2 fadeInMs    3 fadeOutMs
     *   4 gainDb      5 normaliseTo 6 reverse     7 lowCutHz
     *   8 cutoffHz    9 resonance  10 filterType 11 squash
     *  12 squashAttackMs           13 squashReleaseMs
     */
    fun editSample(src: String, dst: String, ops: FloatArray): String =
        nativeEditSample(src, dst, ops)

    private external fun nativeStartInput(deviceId: Int): Boolean
    private external fun nativeStopInput(): Boolean
    private external fun nativeInputChannels(): Int
    private external fun nativeInputRate(): Int
    private external fun nativeInputDevice(): Int
    private external fun nativeInputRunning(): Boolean
    private external fun nativeInputPeak(): Float
    private external fun nativeSetInputGain(gain: Float)
    private external fun nativeSetSwingUnit(unit: Int)
    private external fun nativeSetTunerOn(on: Boolean)
    private external fun nativeTunerHz(): Float
    private external fun nativeSetMonitorLevel(level: Float)
    private external fun nativeStartCapture(path: String, source: Int): String
    private external fun nativeStopCapture()
    private external fun nativeCapturing(): Boolean
    private external fun nativeCapturedSeconds(): Float
    private external fun nativeCapturedPeak(): Float
    private external fun nativeCaptureOverflowed(): Boolean
    private external fun nativeCaptureDeaf(): Boolean
    private external fun nativeCapturedFrames(): Long
    private external fun nativeCompCell(rack: Int, sceneId: Long, frames: Int, bpm: Float,
                                        path: String, peakOut: FloatArray): String
    private external fun nativeArmCapture(rack: Int)
    private external fun nativeCaptureMarks(out: LongArray): Int
    private external fun nativeFileShape(path: String, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    private external fun nativeFileInfo(path: String): String
    private external fun nativeFileSurvey(path: String, out: FloatArray): String
    private external fun nativeAuditionFile(path: String): String
    private external fun nativeAuditioning(): Boolean
    private external fun nativeEditSample(src: String, dst: String, ops: FloatArray): String
    private external fun nativeMidiEvent(rackId: Int, status: Int, data1: Int, data2: Int, channel: Int)
    private external fun nativeSetMpeZone(kind: Int, members: Int, bendSemis: Float)
    private external fun nativeMpeHeldMask(): Int
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLoadUtterance(rack: Int, path: String): String
    private external fun nativeMountMachine(rackId: Int, typeName: String): Boolean
    private external fun nativeUnmountMachine(rackId: Int)
    private external fun nativeRenderSong(
        path: String, tailSeconds: Float, format: Int, bits: Int, startScene: Int, maxSeconds: Float,
    ): String
    private external fun nativeRenderStems(
        paths: Array<String>, racks: IntArray, tailSeconds: Float, format: Int, bits: Int,
        startScene: Int, maxSeconds: Float,
    ): String
    /** Bars of clicks before a start actually starts. 0 is none. */
    fun setCountInBars(bars: Int) = nativeSetCountInBars(bars)

    /** Ticks left of the count, or 0 when the song is simply running. */
    val countInRemaining: Long get() = nativeCountInRemaining()

    private external fun nativeSetCountInBars(bars: Int)
    private external fun nativeCountInRemaining(): Long
    private external fun nativeCancelRender()
    private external fun nativeIsRendering(): Boolean
    private external fun nativeRenderedSeconds(): Float
    private external fun nativeRenderedPeak(): Float
    private external fun nativePrewarm()
    private external fun nativeMountInputMod(rackId: Int, slot: Int, typeName: String): Boolean
    private external fun nativeInputModTypes(): Array<String>
    private external fun nativeInputModParamInfo(type: String): Array<String>
    private external fun nativeMountEffect(rackId: Int, slot: Int, typeName: String): Boolean
    private external fun nativeMountSend(slot: Int, typeName: String): Boolean
    private external fun nativeMountMasterInsert(slot: Int, typeName: String): Boolean
    private external fun nativeMountInputEffect(slot: Int, typeName: String): Boolean
    private external fun nativeEffectTypes(): Array<String>
    private external fun nativeEffectParamInfo(type: String): Array<String>
    private external fun nativeLoadSample(rackId: Int, slot: Int, path: String, maxSeconds: Int): String
    private external fun nativeSoundFontPresets(path: String): String
    private external fun nativeLoadSoundFont(rackId: Int, path: String, presetIndex: Int): String
    private external fun nativeLoadZoneMap(rackId: Int, spec: String, name: String): String
    private external fun nativeSampleMapInfo(rackId: Int): String
    private external fun nativeSampleInfo(rackId: Int, slot: Int): String
    private external fun nativeSlicePoints(path: String, mode: Int, count: Int): String
    private external fun nativeImportAudio(path: String, maxSeconds: Int): String
    private external fun nativeSampleShape(rack: Int, pad: Int, out: FloatArray, fromFrame: Int, toFrame: Int): Int
    private external fun nativeMachineTypes(): Array<String>
    private external fun nativeNoteOn(rackId: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(rackId: Int, note: Int)
    private external fun nativeControlChange(rackId: Int, cc: Int, value: Int, record: Boolean)
    private external fun nativeChannelPressure(rackId: Int, value: Int, record: Boolean)
    private external fun nativeSetParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean): Boolean
    private external fun nativeLoadTake(rack: Int, path: String): String
    private external fun nativeLoadReel(rack: Int, spec: String): String
    private external fun nativeSetCacheRoot(path: String)
    private external fun nativeLoadFormula(rack: Int, formula: String, arp: String, duty: String, vol: String): String
    private external fun nativeBuildCloud(rack: Int, spectrum01: FloatArray): String
    private external fun nativeFreezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float): String
    private external fun nativeLoadFrozen(
        rack: Int,
        sceneIds: LongArray,
        paths: Array<String>,
        bpms: FloatArray,
        ticks: IntArray,
        tails: IntArray,
    ): String
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
    private external fun nativeWorstBlockUs(): Int
    private external fun nativeWorstCallbackUs(): Int
    private external fun nativeWorstPhaseUs(phase: Int): Int
    private external fun nativeWorstRackUs(rack: Int): Int
    private external fun nativeRackPercentileUs(rack: Int): Int
    private external fun nativeResetRackCosts()
    private external fun nativeWorstRackWasFrozen(rack: Int): Boolean
    private external fun nativeInterruptedPercent(): Float
    private external fun nativeHintRunning(): Boolean
    private external fun nativeHintAvailable(): Boolean
    private external fun nativeHintState(): Int
    private external fun nativeRackCostUs(rack: Int): Int
    private external fun nativeRecentCallbackUs(): Int
    private external fun nativeWorstCallbackCpuUs(): Int
    private external fun nativeLateCallbacks(): Long
    private external fun nativeStalledCallbacks(): Long
    private external fun nativeCallbackBudgetUs(): Int
    private external fun nativeReadPeakLevel(): Float
    private external fun nativeReadRackPeak(rackId: Int): Float
    private external fun nativeGetMasterFade(): Float
    private external fun nativeTransportPlay(sceneIdx: Int)
    private external fun nativeTransportStop()
    private external fun nativeTransportRewind()
    private external fun nativeSetStopAtEnd(on: Boolean)
    private external fun nativeIsStopAtEndArmed(): Boolean
    private external fun nativeQueueScene(idx: Int)
    private external fun nativeQueuedScene(): Int
    private external fun nativeSetLauncher(on: Boolean)
    private external fun nativeSetFill(on: Boolean)
    private external fun nativeSetLaunchQuantise(ticks: Int)
    private external fun nativeLaunchClip(rack: Int, sceneId: Long)
    private external fun nativeStopAllClips()
    private external fun nativeCancelLaunch(rack: Int)
    private external fun nativeSetClockOut(on: Boolean)
    private external fun nativeDrainMidiOut(out: LongArray): Int
    private external fun nativeAudioAnchor(out: LongArray)
    private external fun nativeSetExternalSync(on: Boolean)
    private external fun nativeSetLink(on: Boolean)
    private external fun nativeLinkEnabled(): Boolean
    private external fun nativeSetLinkStartStop(on: Boolean)
    private external fun nativeLinkStatus(): Long
    private external fun nativeMidiClockIn(frame: Long, status: Int, d1: Int, d2: Int)
    private external fun nativeSyncState(): Long
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
        handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, seed: Int,
        notes: IntArray, expr: FloatArray,
    ): Boolean
    private external fun nativeSnapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean
    private external fun nativeSnapshotCommit(handle: Long): Boolean
    private external fun nativeMachineParamNames(type: String): Array<String>
    private external fun nativeMachineParamInfo(type: String): Array<String>
    private external fun nativeParamNormalized(rackId: Int, unit: String, name: String): Float
    private external fun nativeSnapshotAbandon(handle: Long)
}
