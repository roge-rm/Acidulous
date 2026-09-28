package com.rm.acidulous.engine

/**
 * The only way into the native audio engine.
 *
 * Nothing here blocks the caller: note and parameter calls go onto lock-free
 * queues the audio thread drains, and objects are built on the calling
 * thread before being handed over.
 */
object NativeEngine {

    fun start(): Boolean = EngineNative.nativeStart()

    fun stop() = EngineNative.nativeStop()

    val isRunning: Boolean get() = EngineNative.nativeIsRunning()

    /** Queues a machine of [typeName] to be mounted on [rackId]. Applied at a block boundary. */
    fun mountMachine(rackId: Int, typeName: String): Boolean = EngineNative.nativeMountMachine(rackId, typeName)
    fun unmountMachine(rackId: Int) = EngineNative.nativeUnmountMachine(rackId)
    /** Mounts an insert effect on one of a rack's two slots. An empty [typeName] clears it. */
    fun mountEffect(rackId: Int, slot: Int, typeName: String): Boolean = EngineNative.nativeMountEffect(rackId, slot, typeName)

    /**
     * Put an effect on one of the two send buses, or empty it with "".
     *
     * No rack, because sends belong to the song. Their parameters use the
     * units `send1` and `send2`, the same way inserts use `effect1` and
     * `effect2`.
     */
    fun mountSend(slot: Int, typeName: String): Boolean = EngineNative.nativeMountSend(slot, typeName)
    fun mountMasterInsert(slot: Int, typeName: String): Boolean = EngineNative.nativeMountMasterInsert(slot, typeName)
    fun mountGroupInsert(group: Int, slot: Int, typeName: String): Boolean = EngineNative.nativeMountGroupInsert(group, slot, typeName)
    /** Group [group]'s peak since the last read. */
    fun groupPeak(group: Int): Float = EngineNative.nativeGroupPeak(group)

    /**
     * An effect on the incoming audio, before anything hears it.
     *
     * Unlike a track insert, it runs before the capture sees the block, so
     * it's recorded into the take. Its parameters use the units `input1` and
     * `input2`, like sends use `send1` and `send2`.
     */
    fun mountInputEffect(slot: Int, typeName: String): Boolean =
        EngineNative.nativeMountInputEffect(slot, typeName)
    /**
     * Builds what machines need before they can be mounted (Trinity's
     * wavetables, ~0.1 s). Call once on a worker at startup. Blocks.
     */
    fun prewarm() = EngineNative.nativePrewarm()

    /** Mounts a modifier (Scale, Chord, Arp) before the machine. An empty [typeName] clears it. */
    fun mountInputMod(rackId: Int, slot: Int, typeName: String): Boolean = EngineNative.nativeMountInputMod(rackId, slot, typeName)

    /**
     * The longest a sample may be, in seconds.
     *
     * Two limits because they cost differently. [PAD_SECONDS] is a pad's own
     * sample: one of thirteen, so 30 seconds each is already 150 MB for a
     * kit. [SLICE_SECONDS] is the one file a Forage slices. It's mounted once
     * and every pad reads part of it, so it can be a whole track: ten minutes
     * is 230 MB. Must match kMaxDecodeSeconds and kMaxSliceSeconds in
     * engine/format/Decoded.h.
     */
    const val PAD_SECONDS = 30
    const val SLICE_SECONDS = 600

    /** Decodes a WAV and mounts it on a pad. An empty path clears it. Returns an error message, or "" on success. */
    fun loadSample(rackId: Int, slot: Int, absolutePath: String, maxSeconds: Int = PAD_SECONDS): String =
        EngineNative.nativeLoadSample(rackId, slot, absolutePath, maxSeconds)
    /**
     * Multisample maps for Mosaic. All three block while the instrument is
     * built and decoded, so call them from a worker.
     */
    suspend fun soundFontPresets(path: String): List<String> {
        val raw = EngineNative.nativeSoundFontPresets(path)
        if (raw.startsWith("!")) return emptyList()
        return raw.trim().lines().filter { it.isNotBlank() }
    }
    suspend fun soundFontError(path: String): String = EngineNative.nativeSoundFontPresets(path).let { if (it.startsWith("!")) it.drop(1) else "" }
    suspend fun loadSoundFont(rackId: Int, path: String, presetIndex: Int): String = EngineNative.nativeLoadSoundFont(rackId, path, presetIndex)
    /** One zone per line: path|lowKey|highKey|rootKey|lowVel|highVel|cents|gain|pan|loop */
    suspend fun loadZoneMap(rackId: Int, spec: String, name: String): String = EngineNative.nativeLoadZoneMap(rackId, spec, name)
    /** "name|zones|samples|seconds" for the mounted map, or "". */
    fun sampleMapInfo(rackId: Int): String = EngineNative.nativeSampleMapInfo(rackId)

    /** "name|frames|stereo" for a loaded pad, or "". */
    fun sampleInfo(rackId: Int, slot: Int): String = EngineNative.nativeSampleInfo(rackId, slot)

    /**
     * A pad's sample as [out].size / 2 pairs of min and max, for drawing,
     * from [fromFrame] until [toFrame], or the whole sample when they're
     * equal. A window, so zooming in shows real detail.
     *
     * Returns how many columns were filled, or 0 if the pad is empty. Fine
     * on a resize but too slow to call every frame, since it walks the
     * whole range.
     */
    fun sampleShape(rack: Int, pad: Int, out: FloatArray, fromFrame: Int = 0, toFrame: Int = 0): Int =
        EngineNative.nativeSampleShape(rack, pad, out, fromFrame, toFrame)

    /** What an import produced: where it went, and whether all of it made it. */
    data class Imported(val path: String, val truncated: Boolean)

    /**
     * Make an imported file usable: where to find it, or a failure with the
     * reason.
     *
     * A WAV comes back unchanged. Anything else is decoded and written next
     * to itself as a WAV, and the original removed, so nothing downstream
     * sees another format. Decodes the file, so call it off the main thread.
     *
     * [Imported.truncated] means the file was longer than [maxSeconds] and
     * was cut short, which the user needs to be told.
     */
    suspend fun importAudio(absolutePath: String, maxSeconds: Int = PAD_SECONDS): Result<Imported> {
        val out = EngineNative.nativeImportAudio(absolutePath, maxSeconds)
        val word = out.substringBefore('\n')
        val rest = out.substringAfter('\n', "")
        return when (word) {
            "ok" -> Result.success(Imported(rest, truncated = false))
            "cut" -> Result.success(Imported(rest, truncated = true))
            else -> Result.failure(IllegalArgumentException(rest.ifEmpty { "it didn't load" }))
        }
    }

    /**
     * What a loop file looks like to a machine that follows the song: how
     * many bars it probably is (0 if no whole number fits) and how long it is
     * in seconds. Null if the file can't be read. Decodes the file, so call
     * it off the main thread.
     */
    suspend fun loopShape(absolutePath: String): Pair<Float, Float>? =
        EngineNative.nativeLoopShape(absolutePath).split('|').takeIf { it.size == 2 }?.let { (b, s) ->
            val bars = b.toFloatOrNull() ?: return@let null
            val seconds = s.toFloatOrNull() ?: return@let null
            bars to seconds
        }

    /**
     * Where [count] slices fall in a file, as fractions of its length.
     *
     * [mode] 0 finds transients, 1 divides evenly. Returns count+1 boundaries
     * so slice n is points[n]..points[n+1], or empty if the file can't be
     * read. Decodes the file, so call it off the main thread.
     */
    suspend fun slicePoints(absolutePath: String, mode: Int, count: Int): List<Float> =
        EngineNative.nativeSlicePoints(absolutePath, mode, count)
            .split(',').mapNotNull { it.trim().toFloatOrNull() }

    /**
     * Renders the whole song to [path]. [format] indexes AudioFormat in the
     * engine: 0 wav, 1 aiff, 2 flac. Blocks, so use a worker. Returns "" on
     * success or an error, and "cancelled" after [cancelRender].
     */
    suspend fun renderSong(
        path: String, tailSeconds: Float = 2f, format: Int = 0, bits: Int = 24,
        startScene: Int = 0, maxSeconds: Float = 0f,
    ): String = EngineNative.nativeRenderSong(path, tailSeconds, format, bits, startScene, maxSeconds)

    /** One pass, one file per entry. A rack of -1 is the master mix. */
    suspend fun renderStems(
        paths: Array<String>, racks: IntArray, tailSeconds: Float = 2f, format: Int = 0, bits: Int = 24,
        startScene: Int = 0, maxSeconds: Float = 0f,
    ): String = EngineNative.nativeRenderStems(paths, racks, tailSeconds, format, bits, startScene, maxSeconds)
    fun cancelRender() = EngineNative.nativeCancelRender()
    /** [integrated LUFS, true peak dBTP] of the song as an export would render it, or null. */
    suspend fun measureLoudness(tailSeconds: Float, startScene: Int, maxSeconds: Float): FloatArray? =
        EngineNative.nativeMeasureLoudness(tailSeconds, startScene, maxSeconds)
    fun setRenderGain(db: Float) = EngineNative.nativeSetRenderGain(db)
    /** Momentary, short-term and integrated LUFS and true peak dBTP of the master. Reading keeps it measuring. */
    fun loudness(): FloatArray = EngineNative.nativeLoudness()
    fun resetLoudness() = EngineNative.nativeResetLoudness()
    val isRendering: Boolean get() = EngineNative.nativeIsRendering()
    val renderedSeconds: Float get() = EngineNative.nativeRenderedSeconds()
    val renderedPeak: Float get() = EngineNative.nativeRenderedPeak()

    /** Every machine type the engine can build, from its registry. */
    val machineTypes: List<String> get() = EngineNative.nativeMachineTypes().toList()
    fun machineParamNames(type: String): List<String> = EngineNative.nativeMachineParamNames(type).toList()
    fun machineParamInfo(type: String): List<ParamInfo> = EngineNative.nativeMachineParamInfo(type).map { ParamInfo.parse(it) }
    /** Every insert effect type, from the effect registry. */
    val effectTypes: List<String> get() = EngineNative.nativeEffectTypes().toList()
    fun effectParamInfo(type: String): List<ParamInfo> = EngineNative.nativeEffectParamInfo(type).map { ParamInfo.parse(it) }
    val inputModTypes: List<String> get() = EngineNative.nativeInputModTypes().toList()
    fun inputModParamInfo(type: String): List<ParamInfo> = EngineNative.nativeInputModParamInfo(type).map { ParamInfo.parse(it) }
    /** Normalised 0..1 value of a mounted unit's parameter, or -1. */
    fun paramNormalized(rackId: Int, unit: String, name: String): Float = EngineNative.nativeParamNormalized(rackId, unit, name)

    fun noteOn(rackId: Int, note: Int, velocity: Int = 100) = EngineNative.nativeNoteOn(rackId, note, velocity)

    fun noteOff(rackId: Int, note: Int) = EngineNative.nativeNoteOff(rackId, note)

    /**
     * A channel message from a MIDI port, addressed to a rack. Everything a
     * keyboard sends (notes, wheel, pressure, bend) comes this way, so
     * playing hardware takes the same path as playing on screen, recording
     * included.
     */
    fun midiEvent(rackId: Int, status: Int, data1: Int, data2: Int, channel: Int = NO_CHANNEL) =
        EngineNative.nativeMidiEvent(rackId, status, data1, data2, channel)

    /**
     * An MPE zone on one rack. [kind] is 0 off, 1 lower, 2 upper.
     *
     * The rack needs it to know which incoming channels are fingers, and how
     * far a per-note bend goes: 48 semitones by the spec, where a normal bend
     * is 2.
     */
    fun setMpeZone(kind: Int, members: Int, bendSemis: Float) =
        EngineNative.nativeSetMpeZone(kind, members, bendSemis)

    /** Which member channels are holding a note, one bit per channel. */
    val mpeHeldMask: Int get() = EngineNative.nativeMpeHeldMask()

    /** The channel for the app's own notes: none, since they didn't come from a cable. */
    const val NO_CHANNEL = 0xff

    /**
     * Mod wheel is CC 1, pressure is channel aftertouch. Both 0..127.
     *
     * [record] says this was played. Mod and pressure are recorded into a
     * lane while armed, so re-sending a control's current value (which the
     * editor does when you change track) must pass false, or opening a track
     * would write a point nobody played.
     */
    fun controlChange(rackId: Int, cc: Int, value: Int, record: Boolean = true) =
        EngineNative.nativeControlChange(rackId, cc, value, record)

    fun channelPressure(rackId: Int, value: Int, record: Boolean = true) =
        EngineNative.nativeChannelPressure(rackId, value, record)

    /**
     * [unit] is "machine", "effect1", "effect2", "mod1", "mod2", "mod3" or "channel".
     * [value] is normalised 0..1. [quantise], in ticks: while playing, the
     * value waits for the rack's next multiple of it (the next bar, say) and
     * lands there. 0 means now. Returns false if the name is unknown for
     * what's mounted.
     */
    fun setParam(rackId: Int, unit: String, name: String, value: Float, record: Boolean = true, quantise: Int = 0): Boolean =
        EngineNative.nativeSetParam(rackId, unit, name, value, record, quantise)

    // --- Transport -----------------------------------------------------------
    /** Play from the top of [sceneIdx]; -1 restarts the current scene. */
    fun transportPlay(sceneIdx: Int = -1) = EngineNative.nativeTransportPlay(sceneIdx)
    fun transportStop() = EngineNative.nativeTransportStop()

    /** Back to the start of the song without playing. See Transport.h. */
    fun transportRewind() = EngineNative.nativeTransportRewind()

    /** The scene queued to follow this one, or -1. Setting it cancels a pending stop. */
    var queuedScene: Int
        get() = EngineNative.nativeQueuedScene()
        set(value) = EngineNative.nativeQueueScene(value)

    /** Let the playing scene finish its repeats and then stop. */
    var stopAtEnd: Boolean
        get() = EngineNative.nativeIsStopAtEndArmed()
        set(value) = EngineNative.nativeSetStopAtEnd(value)
    val isPlaying: Boolean get() = EngineNative.nativeIsPlaying()
    fun setLoopScene(on: Boolean) = EngineNative.nativeSetLoopScene(on)
    fun setLoopSong(on: Boolean) = EngineNative.nativeSetLoopSong(on)

    /** REC arm: while armed, playing records. */
    var recordArmed: Boolean
        get() = EngineNative.nativeIsRecordArmed()
        set(on) = EngineNative.nativeSetRecordArmed(on)

    /**
     * Drains live events timestamped by the audio thread while recording.
     * Fills [out] with 5 longs per event: absTick, sceneId, tickInIteration,
     * packed (rack shl 24 or cmd shl 16 or p1 shl 8 or p2), and for parameter
     * events (cmd 0xf0, p1 = unit ordinal) (index shl 32 or float bits).
     * Returns the count.
     */
    fun drainRecorded(out: LongArray): Int = EngineNative.nativeDrainRecorded(out)
    val recordedDropped: Int get() = EngineNative.nativeGetRecordedDropped()

    /** The song tempo. Reading returns the effective tempo, which a scene may override. */
    var tempo: Float
        get() = EngineNative.nativeGetTempo()
        set(bpm) = EngineNative.nativeSetTempo(bpm)

    /** Packed scene | repeat | tick-in-iteration. Decode with [Position.unpack]. */
    val positionPacked: Long get() = EngineNative.nativeGetPositionPacked()

    // --- Clip mode -----------------------------------------------------------
    /** Turns the grid into a launcher: every rack plays whichever clip it was given. */
    fun setLauncher(on: Boolean) = EngineNative.nativeSetLauncher(on)

    /**
     * Whether Fill trigs may sound.
     *
     * Held rather than latched, so it's a plain flag. An offline render never
     * has it held, so songs with fill trigs export the same way every time.
     */
    fun setFill(on: Boolean) = EngineNative.nativeSetFill(on)

    /** 0 swaps at the end of the playing clip's cycle; otherwise a tick grid. */
    fun setLaunchQuantise(ticks: Int) = EngineNative.nativeSetLaunchQuantise(ticks)

    /**
     * A cell was tapped. Whether that starts, cancels or stops a clip is
     * decided on the audio thread against what's actually playing, so a tap
     * is never read against stale state.
     */
    fun launchClip(rack: Int, sceneId: Long) = EngineNative.nativeLaunchClip(rack, sceneId)

    /**
     * A scene's header in clip mode: its clips start and every track without
     * a clip in it stops, all on one tick (the next grid line, or the end of
     * the longest clip playing). A track already playing its clip carries on.
     */
    fun launchScene(sceneId: Long) = EngineNative.nativeLaunchScene(sceneId)
    fun stopAllClips() = EngineNative.nativeStopAllClips()

    /** Forget what this track had queued, whatever has happened since. */
    fun cancelLaunch(rack: Int) = EngineNative.nativeCancelLaunch(rack)

    // --- MIDI out ------------------------------------------------------------
    /** 24 pulses per quarter note, plus start, stop and song position. */
    fun setClockOut(on: Boolean) = EngineNative.nativeSetClockOut(on)

    /** Fills [out] with two longs per event: frame, then rack|status|d1|d2. */
    fun drainMidiOut(out: LongArray): Int = EngineNative.nativeDrainMidiOut(out)

    /**
     * Ties the engine's frame count to the wall clock: [out] becomes frame,
     * nanoseconds, sample rate. The frame is -1 until the audio stream has
     * run long enough to know.
     */
    fun audioAnchor(out: LongArray) = EngineNative.nativeAudioAnchor(out)

    // --- Clock in ------------------------------------------------------------
    /** Follow an incoming clock rather than the song's own tempo. */
    fun setExternalSync(on: Boolean) = EngineNative.nativeSetExternalSync(on)
    /** A track's tuning: 128 ratios to equal temperament, or null for equal temperament. */
    fun setTuning(rack: Int, ratios: FloatArray?) = EngineNative.nativeSetTuning(rack, ratios)

    // --- Ableton Link -------------------------------------------------------
    fun setLink(on: Boolean) = EngineNative.nativeSetLink(on)
    fun linkEnabled(): Boolean = EngineNative.nativeLinkEnabled()
    fun setLinkStartStop(on: Boolean) = EngineNative.nativeSetLinkStartStop(on)
    /** Peers in the top word, the session tempo in hundredths in the bottom. */
    fun linkStatus(): Long = EngineNative.nativeLinkStatus()

    /** A real-time MIDI byte, on the frame it arrived. */
    fun midiClockIn(frame: Long, status: Int, d1: Int, d2: Int) = EngineNative.nativeMidiClockIn(frame, status, d1, d2)

    /** Packed locked | bpm x100 | phase error in microseconds. */
    fun syncState(): Long = EngineNative.nativeSyncState()

    /** Fills [out] (one per rack) with packed scene | pending | tick-in-cycle. */
    fun launchStates(out: LongArray) = EngineNative.nativeLaunchStates(out)

    fun notesOn(rackId: Int): Int = EngineNative.nativeGetNotesOn(rackId)
    fun debugParam(rackId: Int, name: String): Float = EngineNative.nativeDebugParam(rackId, name)
    fun notesOff(rackId: Int): Int = EngineNative.nativeGetNotesOff(rackId)

    // --- Song snapshot builder (see EngineSync) ------------------------------
    fun snapshotBegin(): Long = EngineNative.nativeSnapshotBegin()
    fun snapshotAddScene(
        handle: Long, sceneId: Long, ticksPerBar: Int, repeat: Int, bpmOverride: Float,
        smooth: Boolean, fadeIn: Boolean, fadeOut: Boolean,
        /** A tempo change over the scene's last [rampBars] bars. 0 for none. */
        rampToBpm: Float = 0f, rampBars: Int = 0,
    ): Boolean = EngineNative.nativeSnapshotAddScene(handle, sceneId, ticksPerBar, repeat, bpmOverride, rampToBpm, rampBars, smooth, fadeIn, fadeOut)
    /** True if the engine still has this clip rev and reused it. False means send it in full. */
    fun snapshotSetClipCached(handle: Long, rack: Int, scene: Int, rev: Long): Boolean =
        EngineNative.nativeSnapshotSetClipCached(handle, rack, scene, rev)
    /**
     * @param notes flat [tick, length, pitch, velocity, curvePointCount, trig] x count,
     * where `trig` is `Note.trigWord` (chance, condition and ratchet in one
     * int) and `tick` already includes the note's nudge
     * @param playMode bit 0 one-shot, bit 1 the dice roll freely
     * @param seed the clip's own dice, so a probability repeats
     * @param expr flat [kind, tick, value] x point, in note order. Each note
     * takes the number of points it declared
     * @param lyrics each note's sounds for a singer, in note order, split by
     * `|`, e.g. `HH AX|L OW|`. Null when no note has words
     */
    fun snapshotSetClip(
        handle: Long, rack: Int, scene: Int, rev: Long, bars: Int, playMode: Int, mute: Boolean, seed: Int,
        notes: IntArray, expr: FloatArray, lyrics: String? = null,
    ): Boolean = EngineNative.nativeSnapshotSetClip(handle, rack, scene, rev, bars, playMode, mute, seed, notes, expr, lyrics)
    fun snapshotSetLane(handle: Long, rack: Int, scene: Int, machineType: String, unit: String, name: String, linear: Boolean, points: FloatArray): Boolean =
        EngineNative.nativeSnapshotSetLane(handle, rack, scene, machineType, unit, name, linear, points)
    fun snapshotCommit(handle: Long): Boolean = EngineNative.nativeSnapshotCommit(handle)
    fun snapshotAbandon(handle: Long) = EngineNative.nativeSnapshotAbandon(handle)

    /** Decode a WAV and mount it with its transients. Worker only. */
    suspend fun loadTake(rack: Int, path: String): String = EngineNative.nativeLoadTake(rack, path)

    /**
     * What an audio track holds, one line per region:
     *
     *     sceneId|lane|absPath|offset|frames|startTick|ticks|bpm|loop
     *
     * One line per lane per cell, so a take across four scenes is four lines
     * naming one file. The engine decodes each distinct file once however
     * many lines mention it. An empty spec clears the reel.
     */
    suspend fun loadReel(rack: Int, spec: String): String = EngineNative.nativeLoadReel(rack, spec)

    /**
     * Where takes too long to hold in memory are converted to. Set once at
     * startup.
     *
     * If unset, long takes are held in memory up to the resident limit
     * instead of being mapped from a file.
     */
    fun setCacheRoot(path: String) = EngineNative.nativeSetCacheRoot(path)

    /** The longest a take may be, in seconds. The engine enforces it. */
    const val REEL_SECONDS = 300

    /**
     * A sung take for a Molt. Decoding and pitch-marking happen on the
     * caller's thread, so only call this from a worker. An empty path clears
     * what's mounted.
     */
    suspend fun loadUtterance(rack: Int, path: String): String = EngineNative.nativeLoadUtterance(rack, path)

    /** Compile and mount Formulate's expression and tables. Returns "" or the reason. */
    suspend fun loadFormula(rack: Int, formula: String, arp: String, duty: String, vol: String): String =
        EngineNative.nativeLoadFormula(rack, formula, arp, duty, vol)

    /**
     * Build and mount Cumulus's tables for a rack. Slow, so worker only.
     * [spectrum01] is the spectrum parameters in table order, NaN for any
     * the song never set. Passed in rather than read from the engine, which
     * may not have them yet.
     */
    suspend fun buildCloud(rack: Int, spectrum01: FloatArray): String = EngineNative.nativeBuildCloud(rack, spectrum01)

    // --- Freeze --------------------------------------------------------------
    /** What a freeze produced, or why it didn't happen. */
    sealed class FreezeResult {
        data class Ok(val frames: Int, val ticks: Int, val bpm: Float, val peak: Float, val tail: Int) :
            FreezeResult()
        data class Failed(val reason: String) : FreezeResult()
    }

    /**
     * Render one clip to [path]. Stops the audio stream while it runs, so
     * call it on a worker and not while the transport is running.
     */
    suspend fun freezeClip(rack: Int, sceneId: Long, path: String, tailSeconds: Float = 8f): FreezeResult {
        val out = EngineNative.nativeFreezeClip(rack, sceneId, path, tailSeconds)
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
    suspend fun loadFrozen(
        rack: Int,
        sceneIds: LongArray,
        paths: Array<String>,
        bpms: FloatArray,
        ticks: IntArray,
        tails: IntArray,
    ): String = EngineNative.nativeLoadFrozen(rack, sceneIds, paths, bpms, ticks, tails)

    // --- Settings that belong to the device ----------------------------------
    /** Output buffer size in bursts: 1 tight, 2 default, 4 safe. */
    fun setBufferBursts(bursts: Int) = EngineNative.nativeSetBufferBursts(bursts)
    /** What the stream actually buffers, which the device may round. */
    val bufferFrames: Int get() = EngineNative.nativeBufferFrames()
    /** Held notes per rack, 0 for no limit. */
    fun setVoiceLimit(notes: Int) = EngineNative.nativeSetVoiceLimit(notes)
    /** 1 full, 0 lean. */
    fun setQuality(level: Int) = EngineNative.nativeSetQuality(level)
    /** Bits in a recorded or exported WAV: 24 or 16. */
    fun setRecordBits(bits: Int) = EngineNative.nativeSetRecordBits(bits)

    // --- Diagnostics ---------------------------------------------------------
    val sampleRate: Int get() = EngineNative.nativeGetSampleRate()
    val framesPerBurst: Int get() = EngineNative.nativeGetFramesPerBurst()
    val isLowLatency: Boolean get() = EngineNative.nativeIsLowLatency()
    val xRunCount: Long get() = EngineNative.nativeGetXRunCount()
    val loadAvg: Float get() = EngineNative.nativeGetLoadAvg()

    /**
     * The worst block and the worst callback since the last read, in
     * microseconds, and how many callbacks have overrun.
     *
     * Reading clears the two peaks, so only one thing should poll them (the
     * diagnostics loop). [loadAvg] is a smoothed average and isn't cleared:
     * it shows how hard the engine is working, while these show how close it
     * came to a dropout.
     */
    val worstBlockUs: Int get() = EngineNative.nativeWorstBlockUs()
    val worstCallbackUs: Int get() = EngineNative.nativeWorstCallbackUs()
    val worstCallbackCpuUs: Int get() = EngineNative.nativeWorstCallbackCpuUs()
    val lateCallbacks: Long get() = EngineNative.nativeLateCallbacks()

    /**
     * Late callbacks that were late without doing the work, meaning they
     * were descheduled rather than slow. If this tracks [lateCallbacks] the
     * device isn't short of CPU but of priority, and cheaper DSP won't help.
     */
    val stalledCallbacks: Long get() = EngineNative.nativeStalledCallbacks()

    /** One callback's worth of audio in microseconds, from the stream's own rate. */
    val callbackBudgetUs: Int get() = EngineNative.nativeCallbackBudgetUs()

    /** Mirrors `Engine::Phase`, in order. */
    enum class Phase { Input, Sequencer, Racks, Master, Capture }

    fun worstPhaseUs(phase: Phase): Int = EngineNative.nativeWorstPhaseUs(phase.ordinal)

    /** The most this rack has cost since last read, in microseconds. Reading clears it. */
    fun worstRackUs(rack: Int): Int = EngineNative.nativeWorstRackUs(rack)

    /**
     * What this rack costs in its worst block out of a hundred, in
     * microseconds.
     *
     * The peak above is one block out of a whole song, so a single interrupt
     * sets it and nothing brings it back down. This needs one block in a
     * hundred to agree before it moves, so it's more repeatable. Not cleared
     * by reading.
     */
    fun rackPercentileUs(rack: Int): Int = EngineNative.nativeRackPercentileUs(rack)

    /** Restart the measurements above. The reset button. */
    fun resetRackCosts() = EngineNative.nativeResetRackCosts()
    /** Whether that rack was playing frozen audio when it set its peak. Read before [worstRackUs]. */
    fun worstRackWasFrozen(rack: Int): Boolean = EngineNative.nativeWorstRackWasFrozen(rack)
    /**
     * How often a block is interrupted rather than slow, 0 to 100.
     *
     * The worst-block, per-phase and per-track figures only count blocks
     * that ran uninterrupted, so this is also how much they miss. High means
     * the fix is scheduling, not DSP.
     */
    val interruptedPercent: Float get() = EngineNative.nativeInterruptedPercent()

    /** Whether the scheduler is being told our deadline, and whether it could be. */
    val hintRunning: Boolean get() = EngineNative.nativeHintRunning()
    val hintAvailable: Boolean get() = EngineNative.nativeHintAvailable()
    /** 0 no API, 1 waiting, 2 the audio thread never named itself, 3 refused, 4 on. */
    val hintState: Int get() = EngineNative.nativeHintState()

    /** What this rack has cost recently, in microseconds. Decays by itself and isn't cleared by reading. */
    fun rackCostUs(rack: Int): Int = EngineNative.nativeRackCostUs(rack)

    /** The worst recent callback, decaying. Safe for any number of readers. */
    val recentCallbackUs: Int get() = EngineNative.nativeRecentCallbackUs()

    /** Peak absolute sample since the last call, then reset. 0.0 means silence. */
    fun readPeakLevel(): Float = EngineNative.nativeReadPeakLevel()
    fun readRackPeak(rackId: Int): Float = EngineNative.nativeReadRackPeak(rackId)
    /** The scene fade multiplier the master is applying right now (1 = none). */
    val masterFade: Float get() = EngineNative.nativeGetMasterFade()

    /**
     * Stop everything and silence every tail right away. For when a patch
     * runs away, which is easy with a modular and painful on headphones.
     */
    fun panic() = EngineNative.nativePanic()


    // --- Nexus -----------------------------------------------------------

    /** Build a patch on this thread and hand it to the rack. Returns "" or an error. */
    suspend fun loadNexusPatch(rack: Int, spec: String): String = EngineNative.nativeLoadNexusPatch(rack, spec)

    /** The module palette from the engine, one line per type. */
    fun nexusPalette(): String = EngineNative.nativeNexusPalette()

    /** Fills [out] with the scope trace and returns how many points were written. */
    fun nexusScope(rack: Int, out: FloatArray): Int = EngineNative.nativeNexusScope(rack, out)

    /**
     * How much is moving in the patch: [NEXUS_SLOTS] module levels followed
     * by [NEXUS_CABLES] cable levels, so [out] should be that long.
     *
     * Slots are indexed by slot number and cables by their order in the
     * patch text, the same index their depth knobs use, so the editor can
     * read straight into what it's drawing. Levels are decaying peaks, not
     * instantaneous samples.
     */
    fun nexusActivity(rack: Int, out: FloatArray): Int = EngineNative.nativeNexusActivity(rack, out)


    // --- Audio in --------------------------------------------------------

    /**
     * Opens the microphone or line in. Needs RECORD_AUDIO to be granted.
     *
     * [deviceId] is one of the platform's inputs (an `AudioDeviceInfo` id),
     * or 0 for the platform's default. It defaults to 0 because panels that
     * only listen don't care which input they get. Asking for a different
     * one while open reopens the stream.
     */
    fun startInput(deviceId: Int = 0): Boolean =
        EngineNative.nativeStartInput(deviceId).also { com.rm.acidulous.AppHost.current.inputSession(EngineNative.nativeInputSession()) }
    /** True if closing the input cut a recording short. */
    fun stopInput(): Boolean = EngineNative.nativeStopInput().also { com.rm.acidulous.AppHost.current.inputSession(0) }
    /**
     * The microphone raw, for instruments, or clean, with the platform's
     * noise suppression and level control, for voices. An open input
     * reopens. See AppHost.cleansInput.
     */
    fun setInputClean(on: Boolean) {
        EngineNative.nativeSetInputClean(on)
        com.rm.acidulous.AppHost.current.inputSession(EngineNative.nativeInputSession())
    }
    val inputRunning: Boolean get() = EngineNative.nativeInputRunning()
    /** What the open stream actually is, rather than what was asked for. */
    val inputChannels: Int get() = EngineNative.nativeInputChannels()
    val inputRate: Int get() = EngineNative.nativeInputRate()
    val inputDevice: Int get() = EngineNative.nativeInputDevice()
    /**
     * The loudest level since the last look, decayed rather than cleared, so
     * two meters on screen at once agree.
     */
    fun inputPeak(): Float = EngineNative.nativeInputPeak()

    /** Which pair the swing bends: 0 sixteenths, 1 eighths. For the whole song. */
    fun setSwingUnit(unit: Int) = EngineNative.nativeSetSwingUnit(unit)

    /**
     * The tuner. Only on while something shows it, because while it's on the
     * audio thread copies every input block into its buffer.
     */
    fun setTunerOn(on: Boolean) = EngineNative.nativeSetTunerOn(on)

    /**
     * The note in the last half second, in hertz, or 0 for none.
     *
     * Does the analysis on the calling thread, which takes about a
     * millisecond, so call it from a background dispatcher and not the UI
     * thread.
     */
    fun tunerHz(): Float = EngineNative.nativeTunerHz()
    fun setInputGain(gain: Float) = EngineNative.nativeSetInputGain(gain)
    fun setMonitorLevel(level: Float) = EngineNative.nativeSetMonitorLevel(level)

    /** [source] 0 is the input, 1 is the output. Returns "" or an error. */
    fun startCapture(path: String, source: Int): String = EngineNative.nativeStartCapture(path, source)
    fun stopCapture() = EngineNative.nativeStopCapture()
    val capturing: Boolean get() = EngineNative.nativeCapturing()
    val capturedSeconds: Float get() = EngineNative.nativeCapturedSeconds()
    /** The finished file's exact length. Seconds as a float lose frames. */
    val capturedFrames: Long get() = EngineNative.nativeCapturedFrames()
    val capturedPeak: Float get() = EngineNative.nativeCapturedPeak()
    val captureOverflowed: Boolean get() = EngineNative.nativeCaptureOverflowed()
    /** True once an input capture has run with nothing arriving at all. */
    val captureDeaf: Boolean get() = EngineNative.nativeCaptureDeaf()

    /**
     * Flatten one Bias cell's four lanes into one file: a comp.
     *
     * The tape medium isn't applied, because a comp is an edit and the patch
     * applies the medium on playback. Baking it in would apply it twice.
     * Runs offline, faster than real time, with the transport stopped.
     * Returns "" or the reason.
     */
    suspend fun compCell(rack: Int, sceneId: Long, frames: Int, bpm: Float, path: String,
                 peakOut: FloatArray): String =
        EngineNative.nativeCompCell(rack, sceneId, frames, bpm, path, peakOut)

    /**
     * Which rack the next recording is for, or -1 for none.
     *
     * Only an armed rack gets the song's boundaries marked against the frames
     * being written, and only one can be armed since there's one capture.
     * Also resets the marks, so a new take starts clean.
     */
    fun armCapture(rack: Int) = EngineNative.nativeArmCapture(rack)

    /**
     * The boundaries the recording crossed, five longs each:
     * `frame, sceneId, tick, cycleTicks, millibpm`.
     *
     * Returns how many were written, or -1 if the capture dropped frames. In
     * that case every index after the drop is wrong and the take must not be
     * split. [MARK_LONGS] is the stride.
     */
    fun captureMarks(out: LongArray): Int = EngineNative.nativeCaptureMarks(out)
    const val MARK_LONGS = 5
    const val MAX_MARKS = 512

    // --- A file on disk, rather than a mounted pad -----------------------

    /**
     * The waveform of a file, as min/max pairs, the same as [sampleShape]
     * gives for a loaded pad.
     *
     * `sampleShape` only works for Forage pads, so it has nothing for Dice,
     * Pollen, Molt or Mosaic, or for a recording that isn't mounted yet.
     * Reads and decodes the file, so use a worker, never a frame loop.
     */
    suspend fun fileShape(path: String, out: FloatArray, fromFrame: Int = 0, toFrame: Int = 0): Int =
        EngineNative.nativeFileShape(path, out, fromFrame, toFrame)

    /** "name|frames|channels|rate|peak", or "" if it can't be read. */
    suspend fun fileInfo(path: String): String = EngineNative.nativeFileInfo(path)

    /**
     * [fileInfo] and [fileShape] in one decode: fills [out] with min/max
     * pairs for the whole file and returns the same string as [fileInfo].
     *
     * Putting a take on a tape lane needs both for the same file, and asking
     * separately decodes it twice, which for a five-minute recording is two
     * peaks of well over 100 MB. Use a worker, never the audio or main
     * thread.
     */
    suspend fun fileSurvey(path: String, out: FloatArray): String = EngineNative.nativeFileSurvey(path, out)

    /**
     * Play a file once, to hear it. An empty path stops it.
     *
     * Completely outside the song: not recorded, exported or frozen, and
     * stopped by a panic. Reads and decodes on the calling thread.
     */
    fun auditionFile(path: String): String = EngineNative.nativeAuditionFile(path)
    val auditioning: Boolean get() = EngineNative.nativeAuditioning()
    /** How far through the auditioned file, 0..1, or -1 when there isn't one. For a playhead. */
    val auditionProgress: Float get() = EngineNative.nativeAuditionProgress()

    /** How many values [editSample] takes, in the order the engine unpacks them. */
    const val EDIT_OPS = 14

    /**
     * Read [src], apply the edit and write [dst]. Returns "" or the reason.
     *
     * [dst] may be [src] to overwrite. The engine writes to a temporary file
     * and renames it, so a failure leaves the original alone. [ops] is a flat
     * array because a dozen arguments of the same type make it easy to swap
     * two, and the order is written out on both sides:
     *
     *   0 from        1 to          2 fadeInMs    3 fadeOutMs
     *   4 gainDb      5 normaliseTo 6 reverse     7 lowCutHz
     *   8 cutoffHz    9 resonance  10 filterType 11 squash
     *  12 squashAttackMs           13 squashReleaseMs
     */
    fun editSample(src: String, dst: String, ops: FloatArray): String =
        EngineNative.nativeEditSample(src, dst, ops)

    /**
     * The edit [editSample] would make, in memory only: fills [out] like
     * [fileShape] with the kept part edited and the rest as it is, so it
     * lines up with the file. If [auditionPreview] is playing, what it plays
     * changes to match without starting over. Decodes on the first call for
     * a file, so use a worker.
     */
    suspend fun editPreview(src: String, ops: FloatArray, out: FloatArray, fromFrame: Int = 0, toFrame: Int = 0): Int =
        EngineNative.nativeEditPreview(src, ops, out, fromFrame, toFrame)

    /** Plays the last [editPreview] from the start. [auditionFile] with "" stops it. */
    fun auditionPreview(): String = EngineNative.nativeAuditionPreview()

    /** Bars of clicks before playback actually starts. 0 is none. */
    fun setCountInBars(bars: Int) = EngineNative.nativeSetCountInBars(bars)

    /** Ticks left of the count-in, or 0 when not counting in. */
    val countInRemaining: Long get() = EngineNative.nativeCountInRemaining()

}
