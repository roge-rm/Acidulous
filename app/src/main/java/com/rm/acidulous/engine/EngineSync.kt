package com.rm.acidulous.engine

import android.util.Log
import com.rm.acidulous.model.EFFECT_SLOTS
import com.rm.acidulous.model.EngineParams
import com.rm.acidulous.model.effectSlotOf
import com.rm.acidulous.model.effectUnit
import com.rm.acidulous.model.EVENTOR_SLOTS
import com.rm.acidulous.model.eventorSlotOf
import com.rm.acidulous.model.eventorUnit
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Master
import com.rm.acidulous.model.Mixer
import com.rm.acidulous.model.PlayMode
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.laneParam
import com.rm.acidulous.model.laneUnit

/**
 * The one place the document meets the engine.
 *
 * The engine plays immutable snapshots; it never sees the [Song] itself. [push]
 * builds one through the native builder and commits it - one constructor-queue
 * record, applied at a block boundary. Call it after every edit.
 */
object EngineSync {

    private const val TAG = "Acidulous.Sync"
    private const val RACKS = 16

    private val mounted = arrayOfNulls<String>(RACKS)
    private val mountedEffects = Array(RACKS) { arrayOfNulls<String>(EFFECT_SLOTS) }
    private val mountedEventors = Array(RACKS) { arrayOfNulls<String>(EVENTOR_SLOTS) }
    private val loadedSamples = HashMap<String, String>() // "rack:slot" -> relative path
    private val loadedMaps = arrayOfNulls<String>(RACKS)   // the source string a rack's map was built from
    private val loadedFreezes = arrayOfNulls<String>(RACKS) // which frozen clips a rack has, as one identity string
    // Building a multisample means parsing and decoding, sometimes tens of
    // megabytes, so it never runs on the caller's thread.
    private val mapLoader = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Acidulous.MapLoader").apply { isDaemon = true }
    }
    /** Set while a map is being built, so the panel can say so. */
    @Volatile var mapStatus: String = ""
        private set

    /** Where relative sample paths in the document resolve. Set once at startup. */
    var sampleRoot: java.io.File? = null

    /** Where frozen clips are written and read. Set once, at startup. */
    var freezeRoot: java.io.File? = null
    private val loadedPatches = arrayOfNulls<String>(RACKS)
    var patchStatus: String = ""
        private set

    /**
     * Pads reference samples by a path relative to [sampleRoot] in
     * `Machine.settings` ("p03_sample"). Loads what changed, clears what went.
     */
    fun ensureSamples(song: Song) {
        val root = sampleRoot ?: return
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS || !MachineUi.acceptsSamples(track.machine.type)) return@forEachIndexed
            for (pad in 0 until 13) {
                val key = "$rack:$pad"
                val rel = track.machine.settings["p%02d_sample".format(pad)] ?: ""
                if (loadedSamples[key] == rel) continue
                if (mounted[rack] != track.machine.type) continue // machine not mounted yet
                if (rel.isEmpty() && loadedSamples[key] == null) { loadedSamples[key] = ""; continue } // never loaded: nothing to clear
                val err = NativeEngine.loadSample(rack, pad, if (rel.isEmpty()) "" else java.io.File(root, rel).absolutePath)
                if (err.isEmpty()) loadedSamples[key] = rel else Log.w(TAG, "sample '$rel' on rack $rack pad $pad: $err")
            }
        }
    }

    /**
     * Makes the racks match the tracks: mounts what is missing or changed,
     * unmounts racks whose track is gone. Index is rack id, so deleting a track
     * shifts the ones after it - their machines remount on their new racks.
     */
    fun ensureMachines(song: Song) {
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS && mounted[rack] != track.machine.type) {
                if (NativeEngine.mountMachine(rack, track.machine.type)) {
                    mounted[rack] = track.machine.type
                    for (pad in 0 until 13) loadedSamples.remove("$rack:$pad") // a new machine starts empty
                    loadedMaps[rack] = null
                } else {
                    Log.w(TAG, "could not mount ${track.machine.type} on rack $rack")
                }
            }
        }
        for (rack in song.tracks.size until RACKS) {
            if (mounted[rack] != null) {
                NativeEngine.unmountMachine(rack)
                mounted[rack] = null
            }
        }
    }

    /**
     * Same for the insert slots: a slot whose type changed gets a fresh effect
     * (or nothing), so its state starts clean; a slot whose type is unchanged
     * keeps its effect and only has its parameters pushed.
     */
    fun ensureEffects(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            for (slot in 0 until EFFECT_SLOTS) {
                val want = track?.effectAt(slot)?.type?.ifEmpty { null }
                if (mountedEffects[rack][slot] == want) continue
                if (NativeEngine.mountEffect(rack, slot, want ?: "")) mountedEffects[rack][slot] = want
                else Log.w(TAG, "could not mount effect ${want ?: "(none)"} on rack $rack slot $slot")
            }
        }
    }

    /**
     * Mosaic's instrument: a SoundFont preset or a list of WAV zones. The
     * source string is the identity, so nothing reloads unless it changed.
     */
    fun ensureSampleMaps(song: Song) {
        val root = sampleRoot ?: return
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val wanted = when {
                track == null || !MachineUi.acceptsSampleMap(track.machine.type) -> null
                mounted[rack] != track.machine.type -> null // wait for the machine
                else -> {
                    val sf2 = track.machine.settings["sf2"].orEmpty()
                    if (sf2.isNotEmpty()) "sf2:${track.machine.settings["sf2preset"] ?: "0"}:$sf2"
                    else track.machine.settings["zones"].orEmpty().ifEmpty { null }?.let { "zones:$it" }
                }
            }
            if (loadedMaps[rack] == wanted) continue
            loadedMaps[rack] = wanted
            if (wanted == null) continue
            val settings = track!!.machine.settings
            mapLoader.execute {
                mapStatus = "loading…"
                val error = if (wanted.startsWith("sf2:")) {
                    val preset = settings["sf2preset"]?.toIntOrNull() ?: 0
                    NativeEngine.loadSoundFont(rack, java.io.File(root, settings["sf2"]!!).absolutePath, preset)
                } else {
                    val zones = com.rm.acidulous.model.Zones.decode(settings["zones"])
                    NativeEngine.loadZoneMap(rack, com.rm.acidulous.model.Zones.spec(zones, root), track.name)
                }
                mapStatus = if (error.isEmpty()) "" else error
                if (error.isNotEmpty()) {
                    Log.w(TAG, "rack $rack map: $error")
                    loadedMaps[rack] = null // let a retry happen
                }
            }
        }
    }

    /** Eventors follow the same rule as effects: remount on type change only. */
    fun ensureEventors(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            for (slot in 0 until EVENTOR_SLOTS) {
                val want = track?.eventorAt(slot)?.type?.ifEmpty { null }
                if (mountedEventors[rack][slot] == want) continue
                if (NativeEngine.mountEventor(rack, slot, want ?: "")) mountedEventors[rack][slot] = want
                else Log.w(TAG, "could not mount eventor ${want ?: "(none)"} on rack $rack slot $slot")
            }
        }
    }

    /** Everything the engine needs after any edit: machines, effects, eventors, then the snapshot. */
    /**
     * The frozen clips a rack should be holding. The identity is the whole
     * list, so adding or thawing one clip reloads that rack's set and leaves
     * the other fifteen alone - and nothing reloads when a song is merely
     * being edited around a freeze.
     */
    fun ensureFrozen(song: Song) {
        val root = freezeRoot ?: return
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val frozen = song.scenes.mapNotNull { scene ->
                val f = track?.clips?.get(scene.id)?.frozen ?: return@mapNotNull null
                Triple(scene.engineId, f, java.io.File(root, f.file))
            }.filter { it.third.isFile }
            val identity = frozen.joinToString(";") { "${it.first}:${it.second.file}:${it.second.bpm}:${it.second.ticks}" }
            if (loadedFreezes[rack] == identity) continue
            loadedFreezes[rack] = identity
            mapLoader.execute {
                val error = NativeEngine.loadFrozen(
                    rack,
                    frozen.map { it.first }.toLongArray(),
                    frozen.map { it.third.absolutePath }.toTypedArray(),
                    frozen.map { it.second.bpm }.toFloatArray(),
                    frozen.map { it.second.ticks }.toIntArray(),
                )
                if (error.isNotEmpty()) {
                    Log.w(TAG, "frozen clips for rack $rack: $error")
                    loadedFreezes[rack] = null // let a retry happen
                } else if (frozen.isNotEmpty()) {
                    Log.i(TAG, "rack $rack holds ${frozen.size} frozen clip(s)")
                }
            }
        }
    }

    fun sync(song: Song): Boolean {
        ensureMachines(song)
        ensureSamples(song)
        ensureEffects(song)
        ensureEventors(song)
        ensureSampleMaps(song)
        ensureNexusPatches(song)
        ensureFrozen(song)
        return push(song)
    }

    fun push(song: Song): Boolean {
        val handle = NativeEngine.snapshotBegin()
        if (handle == 0L) return false

        for (scene in song.scenes) {
            val ok = NativeEngine.snapshotAddScene(
                handle,
                sceneId = scene.engineId,
                ticksPerBar = song.signatureOf(scene).ticksPerBar,
                repeat = scene.repeat,
                bpmOverride = scene.tempo?.bpm ?: 0f,
                smooth = scene.tempo?.smooth ?: false,
                fadeIn = scene.fadeIn,
                fadeOut = scene.fadeOut,
            )
            if (!ok) {
                NativeEngine.snapshotAbandon(handle)
                return false
            }
        }

        var cached = 0
        var marshalled = 0
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS) return@forEachIndexed
            song.scenes.forEachIndexed forEachIndexedInner@{ sceneIdx, scene ->
                val clip = track.clips[scene.id] ?: return@forEachIndexedInner
                // Unchanged since the last push? Then it is one lookup, not a marshal.
                if (NativeEngine.snapshotSetClipCached(handle, rack, sceneIdx, clip.rev)) {
                    cached++
                    return@forEachIndexedInner
                }
                marshalled++
                val flat = IntArray(clip.notes.size * 4)
                clip.notes.forEachIndexed { i, n ->
                    flat[i * 4] = n.tick
                    flat[i * 4 + 1] = n.length
                    flat[i * 4 + 2] = n.pitch
                    flat[i * 4 + 3] = n.velocity
                }
                NativeEngine.snapshotSetClip(
                    handle, rack, sceneIdx, clip.rev, clip.bars,
                    playMode = if (clip.playMode == PlayMode.OneShot) 1 else 0,
                    mute = clip.mute,
                    notes = flat,
                )
                for ((key, lane) in clip.automation) {
                    val pts = FloatArray(lane.points.size * 2)
                    lane.points.forEachIndexed { i, p -> pts[i * 2] = p.tick.toFloat(); pts[i * 2 + 1] = p.value }
                    // The type the lane's parameter belongs to: the machine's, or the effect's in that slot.
                    val unit = laneUnit(key)
                    val ownerType = effectSlotOf(unit)?.let { track.effectAt(it).type }
                        ?: eventorSlotOf(unit)?.let { track.eventorAt(it).type }
                        ?: track.machine.type
                    NativeEngine.snapshotSetLane(handle, rack, sceneIdx, ownerType, unit, laneParam(key), lane.linear, pts)
                }
            }
        }

        NativeEngine.tempo = song.tempo
        NativeEngine.setLoopSong(song.loopSong)
        Log.d(TAG, "push: ${song.scenes.size} scenes, $cached clips cached, $marshalled marshalled")
        val ok = NativeEngine.snapshotCommit(handle) // consumes the handle either way
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS) {
                pushChannel(rack, track.mixer)
                pushMachineParams(rack, track.machine.params)
                for (slot in 0 until EFFECT_SLOTS) pushSlot(rack, effectUnit(slot), track.effectAt(slot))
                for (slot in 0 until EVENTOR_SLOTS) pushSlot(rack, eventorUnit(slot), track.eventorAt(slot))
            }
        }
        pushMaster(song.master)
        return ok
    }

    fun pushMachineParams(rack: Int, params: Map<String, Float>) {
        var rejected = 0
        for ((name, v) in params) if (!NativeEngine.setParam(rack, "machine", name, v, record = false)) rejected++
        if (rejected > 0) Log.w(TAG, "rack $rack: $rejected of ${params.size} machine parameters were not accepted")
    }

    fun pushSlot(rack: Int, unit: String, slot: com.rm.acidulous.model.UnitSlot) {
        if (slot.isEmpty) return
        for ((name, v) in slot.params) NativeEngine.setParam(rack, unit, name, v, record = false)
        NativeEngine.setParam(rack, unit, "bypass", EngineParams.bool01(slot.bypass), record = false)
    }

    // --- Mixer parameters: cheap enough to send whole on every push ------------------

    fun pushChannel(rack: Int, m: Mixer) {
        NativeEngine.setParam(rack, "channel", "gain", EngineParams.volume01(m.volume), record = false)
        NativeEngine.setParam(rack, "channel", "pan", EngineParams.pan01(m.pan), record = false)
        NativeEngine.setParam(rack, "channel", "mute", EngineParams.bool01(m.mute), record = false)
        NativeEngine.setParam(rack, "channel", "solo", EngineParams.bool01(m.solo), record = false)
        NativeEngine.setParam(rack, "channel", "sendreverb", EngineParams.unit01(m.sendReverb), record = false)
        NativeEngine.setParam(rack, "channel", "senddelay", EngineParams.unit01(m.sendDelay), record = false)
    }

    fun pushMaster(m: Master) {
        NativeEngine.setParam(0, "master", "volume", EngineParams.volume01(m.volume), record = false)
        NativeEngine.setParam(0, "master", "reverbon", EngineParams.bool01(m.reverb.on), record = false)
        NativeEngine.setParam(0, "master", "reverbsize", EngineParams.unit01(m.reverb.size), record = false)
        NativeEngine.setParam(0, "master", "reverbdamp", EngineParams.unit01(m.reverb.damp), record = false)
        NativeEngine.setParam(0, "master", "reverbtone", EngineParams.unit01(m.reverb.tone), record = false)
        NativeEngine.setParam(0, "master", "delayon", EngineParams.bool01(m.delay.on), record = false)
        NativeEngine.setParam(0, "master", "delaytime", EngineParams.delayTime01(m.delay.time), record = false)
        NativeEngine.setParam(0, "master", "delayfeedback", EngineParams.unit01(m.delay.feedback), record = false)
        NativeEngine.setParam(0, "master", "delaytone", EngineParams.unit01(m.delay.tone), record = false)
        NativeEngine.setParam(0, "master", "delaypingpong", EngineParams.bool01(m.delay.pingPong), record = false)
        NativeEngine.setParam(0, "master", "limiteron", EngineParams.bool01(m.limiter.on), record = false)
        NativeEngine.setParam(0, "master", "limiterdrive", EngineParams.unit01(m.limiter.drive), record = false)
    }

    /** The metronome lives on the transport, not in the song. */
    fun setMetronome(on: Boolean, volume: Float = 0.5f) {
        NativeEngine.setParam(0, "master", "clickon", EngineParams.bool01(on), record = false)
        NativeEngine.setParam(0, "master", "clickvolume", EngineParams.unit01(volume), record = false)
    }

    /**
     * Nexus patches. Gated on the *topology* only - dragging a node around
     * the canvas changes the saved patch several times a second, and
     * rebuilding the graph for that would cut every delay tail in it.
     */
    fun ensureNexusPatches(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val wanted = when {
                track == null || track.machine.type != "Nexus" -> null
                mounted[rack] != "Nexus" -> null // wait for the machine
                else -> {
                    // An empty rack is not an error: a Nexus with nothing in
                    // it has nothing to build, and asking the engine to parse
                    // that only produces a warning nobody can act on.
                    val p = com.rm.acidulous.model.NexusPatch.decode(track.machine.settings["nexus"])
                    if (p.modules.isEmpty()) null else p.topology()
                }
            }
            if (loadedPatches[rack] == wanted) continue
            loadedPatches[rack] = wanted
            if (wanted == null) continue
            val spec = com.rm.acidulous.model.NexusPatch
                .decode(track!!.machine.settings["nexus"]).encode()
            mapLoader.execute {
                val error = NativeEngine.loadNexusPatch(rack, spec)
                patchStatus = error
                if (error.isNotEmpty()) Log.w(TAG, "rack $rack patch: $error")
            }
        }
    }
}
