package com.rm.acidulous.engine

import android.util.Log
import com.rm.acidulous.model.RISER_LENGTHS
import com.rm.acidulous.model.STOP_LENGTHS
import com.rm.acidulous.model.SWING_MAX
import com.rm.acidulous.model.THROW_TIMES
import com.rm.acidulous.model.SWING_STRAIGHT
import com.rm.acidulous.model.swingOf
import com.rm.acidulous.model.EFFECT_SLOTS
import com.rm.acidulous.model.EngineParams
import com.rm.acidulous.model.effectSlotOf
import com.rm.acidulous.model.effectUnit
import com.rm.acidulous.model.MODIFIER_SLOTS
import com.rm.acidulous.model.modifierSlotOf
import com.rm.acidulous.model.modifierUnit
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Master
import com.rm.acidulous.model.Mixer
import com.rm.acidulous.model.PlayMode
import com.rm.acidulous.model.INPUT_SLOTS
import com.rm.acidulous.model.inputUnit
import com.rm.acidulous.model.GROUP_INSERT_SLOTS
import com.rm.acidulous.model.MASTER_INSERT_SLOTS
import com.rm.acidulous.model.MAX_GROUPS
import com.rm.acidulous.model.MixGroup
import com.rm.acidulous.model.groupInsertUnit
import com.rm.acidulous.model.SEND_SLOTS
import com.rm.acidulous.model.masterInsertUnit
import com.rm.acidulous.model.BIAS_MACHINE
import com.rm.acidulous.model.reelSpec
import com.rm.acidulous.model.sendUnit
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
    /** Most clips carry no per-note expression; they can all share this. */
    private val EMPTY_FLOATS = FloatArray(0)

    private val mounted = arrayOfNulls<String>(RACKS)
    private val mountedEffects = Array(RACKS) { arrayOfNulls<String>(EFFECT_SLOTS) }
    private val mountedModifiers = Array(RACKS) { arrayOfNulls<String>(MODIFIER_SLOTS) }
    private val mountedInput = arrayOfNulls<String>(INPUT_SLOTS)
    private val loadedSamples = HashMap<String, String>() // "rack:slot" -> relative path
    private val loadedMaps = arrayOfNulls<String>(RACKS)   // the source string a rack's map was built from
    private val loadedFreezes = arrayOfNulls<String>(RACKS) // which frozen clips a rack has, as one identity string
    private val builtClouds = arrayOfNulls<String>(RACKS)   // the spectrum a rack's Cumulus tables were built from
    private val loadedFormulas = arrayOfNulls<String>(RACKS) // the text a rack's Formulate was compiled from
    private val loadedTakes = arrayOfNulls<String>(RACKS)    // the file a rack's Pollen is granulating
    private val loadedReels = arrayOfNulls<String>(RACKS)    // the spec a rack's Bias was built from

    /**
     * What the last compile said, by rack: empty when it read, the reason
     * when it did not. The panel shows it - a typed formula that fails
     * quietly is a trap.
     */
    val formulaErrors = androidx.compose.runtime.mutableStateMapOf<Int, String>()
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
    /**
     * Something the player did that did not work, in words they can act on.
     *
     * Everything here used to end at `Log.w`, which is the right place for a
     * mount queue being full and the wrong place for "that file is an mp3".
     * Dan: "loading anything other than a wav silently fails, we need some
     * kind of error message on an error." The host sets this; nothing else
     * reads it.
     */
    var onProblem: ((String) -> Unit)? = null

    private fun problem(message: String) {
        Log.w(TAG, message)
        onProblem?.invoke(message)
    }

    /** A file's own name, which is what the player recognises. */
    private fun shortName(rel: String) = rel.substringAfterLast('/')

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
                // The key is set either way, so a file that will not read is
                // reported once rather than on every sync for the rest of the
                // session. Changing the setting is what asks again.
                loadedSamples[key] = rel
                if (err.isNotEmpty()) problem("Pad ${pad + 1}: ${shortName(rel)} would not load - $err.")
            }
            // And the shared file the pads slice, in the slot above them. One
            // copy for all thirteen: mounting it per pad would decode an
            // eleven megabyte file thirteen times - and since there is only
            // ever one of it, it may be far longer than a pad sample.
            val sliceKey = "$rack:shared"
            val sliceRel = track.machine.settings["slice_sample"] ?: ""
            if (loadedSamples[sliceKey] != sliceRel && mounted[rack] == track.machine.type &&
                !(sliceRel.isEmpty() && loadedSamples[sliceKey] == null)) {
                val err = NativeEngine.loadSample(
                    rack, 13, if (sliceRel.isEmpty()) "" else java.io.File(root, sliceRel).absolutePath,
                    // The long ceiling: one file for the whole machine, so it
                    // is allowed to be a whole track rather than a break.
                    maxSeconds = NativeEngine.SLICE_SECONDS,
                )
                loadedSamples[sliceKey] = sliceRel
                if (err.isNotEmpty()) problem("${shortName(sliceRel)} would not load - $err.")
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
                    loadedTakes[rack] = null
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
     * The two send buses, the same way.
     *
     * One array rather than sixteen, because a send belongs to the song and
     * not to a rack - which is also why nothing here takes a rack index.
     */
    private val mountedSends = arrayOfNulls<String>(SEND_SLOTS)

    fun ensureSends(song: Song) {
        for (slot in 0 until SEND_SLOTS) {
            val want = song.master.sendAt(slot).type.ifEmpty { null }
            if (mountedSends[slot] == want) continue
            if (NativeEngine.mountSend(slot, want ?: "")) mountedSends[slot] = want
            else Log.w(TAG, "could not mount send ${want ?: "(none)"} on slot $slot")
        }
        // The master's inserts, the same way.
        for (slot in 0 until MASTER_INSERT_SLOTS) {
            val want = song.master.insertAt(slot).type.ifEmpty { null }
            if (mountedMasterInserts[slot] == want) continue
            if (NativeEngine.mountMasterInsert(slot, want ?: "")) mountedMasterInserts[slot] = want
            else Log.w(TAG, "could not mount master insert ${want ?: "(none)"} on slot $slot")
        }
    }
    private val mountedMasterInserts = arrayOfNulls<String>(MASTER_INSERT_SLOTS)
    private val mountedGroupInserts = Array(MAX_GROUPS) { arrayOfNulls<String>(GROUP_INSERT_SLOTS) }

    /** The mixer groups' inserts; a group that is not there has empty slots. */
    fun ensureGroups(song: Song) {
        for (g in 0 until MAX_GROUPS) for (slot in 0 until GROUP_INSERT_SLOTS) {
            val want = song.master.groups.getOrNull(g)?.insertAt(slot)?.type?.ifEmpty { null }
            if (mountedGroupInserts[g][slot] == want) continue
            if (NativeEngine.mountGroupInsert(g, slot, want ?: "")) mountedGroupInserts[g][slot] = want
            else Log.w(TAG, "could not mount group ${g + 1} insert ${want ?: "(none)"} on slot $slot")
        }
    }

    /**
     * What the incoming audio goes through before anything hears it.
     *
     * The same shape as [ensureSends] and mounted the same way; what differs
     * is only where the engine runs it, which is before the input is
     * published - so an effect here is **printed into a recording** rather
     * than applied to a playback.
     */
    fun ensureInputFx(song: Song) {
        for (slot in 0 until INPUT_SLOTS) {
            val want = song.inputAt(slot).type.ifEmpty { null }
            if (mountedInput[slot] == want) continue
            if (NativeEngine.mountInputEffect(slot, want ?: "")) mountedInput[slot] = want
            else Log.w(TAG, "could not mount input effect ${want ?: "(none)"} on slot $slot")
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

    /** Modifiers follow the same rule as effects: remount on type change only. */
    fun ensureModifiers(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            for (slot in 0 until MODIFIER_SLOTS) {
                val want = track?.modifierAt(slot)?.type?.ifEmpty { null }
                if (mountedModifiers[rack][slot] == want) continue
                if (NativeEngine.mountInputMod(rack, slot, want ?: "")) mountedModifiers[rack][slot] = want
                else Log.w(TAG, "could not mount modifier ${want ?: "(none)"} on rack $rack slot $slot")
            }
        }
    }

    /** Everything the engine needs after any edit: machines, effects, modifiers, then the snapshot. */
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
            val identity = frozen.joinToString(";") {
                "${it.first}:${it.second.file}:${it.second.bpm}:${it.second.ticks}:${it.second.tail}"
            }
            if (loadedFreezes[rack] == identity) continue
            loadedFreezes[rack] = identity
            mapLoader.execute {
                val error = NativeEngine.loadFrozen(
                    rack,
                    frozen.map { it.first }.toLongArray(),
                    frozen.map { it.third.absolutePath }.toTypedArray(),
                    frozen.map { it.second.bpm }.toFloatArray(),
                    frozen.map { it.second.ticks }.toIntArray(),
                    frozen.map { it.second.tail }.toIntArray(),
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

    /**
     * What an audio track is holding: a window into a file per scene, per lane.
     *
     * Unlike every other machine's sample, a tape's material lives in the
     * *clips* rather than in `Machine.settings`, so there is nothing for
     * [ensureSamples] or [ensureTakes] to find. The spec is built from the whole
     * track and is its own identity - see `model/Bias.kt` for why - so this
     * sends nothing at all until a take is added, trimmed or moved.
     *
     * Off-thread for the same reason as a sample map, and more so: five minutes
     * of audio is the largest decode in the app.
     */
    fun ensureReels(song: Song) {
        val root = sampleRoot ?: return
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val wanted = when {
                track == null || track.machine.type != BIAS_MACHINE -> ""
                mounted[rack] != BIAS_MACHINE -> continue // wait for the machine
                else -> reelSpec(song, track, root)
            }
            // Never loaded and nothing to load: the fifteen racks that are not
            // tapes must not each send an empty spec on the first sync.
            if (loadedReels[rack] == null && wanted.isEmpty()) { loadedReels[rack] = ""; continue }
            if (loadedReels[rack] == wanted) continue
            loadedReels[rack] = wanted
            mapLoader.execute {
                mapStatus = if (wanted.isEmpty()) "" else "Reading audio..."
                val error = NativeEngine.loadReel(rack, wanted)
                mapStatus = ""
                if (error.isNotEmpty()) {
                    problem("The audio on this track would not load - $error.")
                    loadedReels[rack] = null // let a retry happen
                } else if (wanted.isNotEmpty()) {
                    Log.i(TAG, "rack $rack holds ${wanted.count { it == '\n' }} audio region(s)")
                }
            }
        }
    }

    /**
     * Cumulus's tables. Its spectrum parameters are not knobs in the usual
     * sense - each one means an inverse transform of a quarter of a million
     * points - so they are watched here and rebuilt off-thread when they
     * settle, rather than being smoothed on the audio thread like everything
     * else. Everything from `morph` on is live and goes through the ordinary
     * parameter path.
     */
    fun ensureClouds(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val wanted = if (track?.machine?.type != "Cumulus" || mounted[rack] != "Cumulus") {
                null
            } else {
                CLOUD_PARAMS.joinToString(",") { "%s=%.5f".format(it, track.machine.params[it] ?: -1f) }
            }
            if (builtClouds[rack] == wanted) continue
            builtClouds[rack] = wanted
            if (wanted == null) continue
            mapLoader.execute {
                val values = FloatArray(CLOUD_PARAMS.size) { i ->
                    track!!.machine.params[CLOUD_PARAMS[i]] ?: Float.NaN
                }
                val error = NativeEngine.buildCloud(rack, values)
                if (error.isNotEmpty()) {
                    Log.w(TAG, "cumulus rack $rack: $error")
                    builtClouds[rack] = null // let a retry happen
                }
            }
        }
    }

    /**
     * Formulate's expression and step tables. Text, like Nexus's patch and
     * Mosaic's zones: parsed on a worker, handed over as one object.
     */
    fun ensureFormulas(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val settings = track?.machine?.settings
            val wanted = if (track?.machine?.type != "Formulate" || mounted[rack] != "Formulate") {
                null
            } else {
                listOf("formula", "arp", "duty", "vol").joinToString("\u0001") { settings?.get(it).orEmpty() }
            }
            if (loadedFormulas[rack] == wanted) continue
            loadedFormulas[rack] = wanted
            if (wanted == null) { formulaErrors.remove(rack); continue }
            val parts = wanted.split("\u0001")
            mapLoader.execute {
                val error = NativeEngine.loadFormula(rack, parts[0], parts[1], parts[2], parts[3])
                formulaErrors[rack] = error
                if (error.isNotEmpty()) Log.w(TAG, "formulate rack $rack: $error")
            }
        }
    }

    /**
     * A machine's one take: a WAV decoded with its transients found, mounted
     * as one object - Pollen granulates it, Dice cuts it up. Pollen's live
     * ring is the machine's own and needs nothing from here.
     */
    fun ensureTakes(song: Song) {
        val root = sampleRoot ?: return
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val wanted = when {
                track == null || !MachineUi.acceptsOneSample(track.machine.type) -> null
                mounted[rack] != track.machine.type -> null // wait for the machine
                else -> track.machine.settings["sample"].orEmpty()
            }
            if (loadedTakes[rack] == wanted) continue
            loadedTakes[rack] = wanted
            if (wanted == null) continue
            // Molt asks a different question of the same file - where the
            // glottal pulses are rather than where the transients are - so it
            // gets its own decode. Both are a worker's job either way.
            val sung = track?.machine?.type == "Molt"
            mapLoader.execute {
                val path = if (wanted.isEmpty()) "" else java.io.File(root, wanted).absolutePath
                val error = if (sung) NativeEngine.loadUtterance(rack, path)
                            else NativeEngine.loadTake(rack, path)
                // Reported and not retried: a file that will not decode will
                // not decode the second time either, and `loadedTakes` is
                // already set, so asking again means changing the setting.
                if (error.isNotEmpty()) problem("${shortName(wanted)} would not load - $error.")
            }
        }
    }

    /** The song as last synced, for [play] to put its automated values back from. */
    private var synced: Song? = null

    /**
     * Play, with every automated parameter back where the song says first.
     *
     * A lane leaves its parameter where it finished, and a scene with no lane
     * of its own for it plays on from there - so the second time through, the
     * top of the song did not sound like the first. Starting the arranger now
     * puts them back, the way an export starts (see [pushForRender]). Nothing
     * is said on screen: the knob moves back, which is its own notice, and
     * `PanelKnob` marks the ones a lane moves.
     *
     * Not in the launcher: a clip launched mid-set carries on from where the
     * last one left things, which is what launching live is for.
     */
    fun play(sceneIdx: Int = -1, launcher: Boolean = false) {
        if (!launcher) synced?.let { resetAutomated(it) }
        NativeEngine.transportPlay(sceneIdx)
    }

    /** Every parameter a lane moves, back to the document's value or its default. */
    fun resetAutomated(song: Song) {
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS) return@forEachIndexed
            val lanes = track.clips.values.flatMap { it.automation.keys }.toSet()
            if (lanes.isEmpty()) return@forEachIndexed
            var channel = false
            for (key in lanes) {
                val unit = laneUnit(key)
                val name = laneParam(key)
                val fxSlot = effectSlotOf(unit)
                val modSlot = modifierSlotOf(unit)
                val value: Float? = when {
                    unit == "machine" -> track.machine.params[name]
                        ?: machineTable(track.machine.type).firstOrNull { it.name == name }?.defaultNormalized
                    fxSlot != null -> track.effectAt(fxSlot).takeIf { !it.isEmpty }?.let { fx ->
                        if (name == "bypass") EngineParams.bool01(fx.bypass)
                        else fx.params[name] ?: effectTable(fx.type).firstOrNull { it.name == name }?.defaultNormalized
                    }
                    modSlot != null -> track.modifierAt(modSlot).takeIf { !it.isEmpty }?.let { mod ->
                        if (name == "bypass") EngineParams.bool01(mod.bypass)
                        else mod.params[name] ?: modifierTable(mod.type).firstOrNull { it.name == name }?.defaultNormalized
                    }
                    unit == "channel" -> { channel = true; null }
                    // The held effects are let go by a stop, and the wheel
                    // and pressure are a controller's, not the song's.
                    else -> null
                }
                if (value != null) NativeEngine.setParam(rack, unit, name, value, record = false)
            }
            if (channel) pushChannel(rack, track.mixer, song.swingOf(track))
        }
    }

    fun sync(song: Song): Boolean {
        synced = song
        ensureMachines(song)
        ensureSamples(song)
        ensureEffects(song)
        ensureSends(song)
        ensureGroups(song)
        ensureInputFx(song)
        ensureModifiers(song)
        ensureSampleMaps(song)
        ensureNexusPatches(song)
        ensureClouds(song)
        ensureFormulas(song)
        ensureTakes(song)
        ensureReels(song)
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
                val flat = IntArray(clip.notes.size * 6)
                // The curves of every note end to end, each note saying how
                // many of them are its own. One array rather than a call per
                // curve: a clip of expressive chords would otherwise be
                // hundreds of JNI crossings where it is now one.
                val expr = ArrayList<Float>()
                clip.notes.forEachIndexed { i, n ->
                    // The nudge is applied here and nowhere else. "When does
                    // this note play" is a field the engine already has, so
                    // micro-timing costs it no property, no wire slot and no
                    // gate; the host re-sorts by tick afterwards, which is
                    // what keeps the sorted-notes contract true. A note nudged
                    // off the front of the clip lands on the downbeat rather
                    // than wrapping, which the host's own clamp decides.
                    flat[i * 6] = n.tick + n.nudge
                    flat[i * 6 + 1] = n.length
                    flat[i * 6 + 2] = n.pitch
                    flat[i * 6 + 3] = n.velocity
                    flat[i * 6 + 5] = n.trigWord
                    var count = 0
                    if (n.hasExpression) {
                        n.curves.forEachIndexed { kind, curve ->
                            curve?.points?.forEach { p ->
                                expr.add(kind.toFloat()); expr.add(p.tick.toFloat()); expr.add(p.value)
                                count++
                            }
                        }
                    }
                    flat[i * 6 + 4] = count
                }
                NativeEngine.snapshotSetClip(
                    handle, rack, sceneIdx, clip.rev, clip.bars,
                    // Bit 0 is the play mode, bit 1 is whether the dice roll
                    // free: one word rather than a tenth argument for one bool.
                    playMode = (if (clip.playMode == PlayMode.OneShot) 1 else 0) or
                        (if (clip.freeRoll) 2 else 0),
                    mute = clip.mute,
                    seed = clip.seed,
                    notes = flat,
                    expr = if (expr.isEmpty()) EMPTY_FLOATS else expr.toFloatArray(),
                )
                for ((key, lane) in clip.automation) {
                    val pts = FloatArray(lane.points.size * 2)
                    lane.points.forEachIndexed { i, p -> pts[i * 2] = p.tick.toFloat(); pts[i * 2 + 1] = p.value }
                    // The type the lane's parameter belongs to: the machine's, or the effect's in that slot.
                    val unit = laneUnit(key)
                    val ownerType = effectSlotOf(unit)?.let { track.effectAt(it).type }
                        ?: modifierSlotOf(unit)?.let { track.modifierAt(it).type }
                        ?: track.machine.type
                    NativeEngine.snapshotSetLane(handle, rack, sceneIdx, ownerType, unit, laneParam(key), lane.linear, pts)
                }
            }
        }

        NativeEngine.tempo = song.tempo
        // The unit is the song's; the amount is per track and rides the
        // channel, resolved here so the engine never sees "follow the song".
        NativeEngine.setSwingUnit(song.swingUnit)
        NativeEngine.setLoopSong(song.loopSong)
        Log.d(TAG, "push: ${song.scenes.size} scenes, $cached clips cached, $marshalled marshalled")
        val ok = NativeEngine.snapshotCommit(handle) // consumes the handle either way
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS) {
                pushChannel(rack, track.mixer, song.swingOf(track))
                pushMachineParams(rack, track.machine.params)
                for (slot in 0 until EFFECT_SLOTS) pushSlot(rack, effectUnit(slot), track.effectAt(slot))
                for (slot in 0 until MODIFIER_SLOTS) pushSlot(rack, modifierUnit(slot), track.modifierAt(slot))
            }
        }
        pushMaster(song.master)
        pushSends(song.master)
        pushInputFx(song)
        return ok
    }

    /** Each unit type's parameter table, asked for once. */
    private val tables = HashMap<String, List<ParamInfo>>()
    private fun machineTable(type: String) = tables.getOrPut("machine:$type") { NativeEngine.machineParamInfo(type) }
    private fun effectTable(type: String) = tables.getOrPut("effect:$type") { NativeEngine.effectParamInfo(type) }
    private fun modifierTable(type: String) = tables.getOrPut("mod:$type") { NativeEngine.inputModParamInfo(type) }

    /**
     * Every parameter a song's units have and the document does not name, set
     * to its default.
     *
     * A patch names only what it changes - Rimshot names eleven of Genesis's
     * forty-nine - and a song only ever sent what was named. A rack whose
     * machine is the same type as in the song open before keeps its instance,
     * so the other thirty-eight kept that song's values: a song sounded
     * different depending on what had been open first, and so did its export.
     * Loading a patch already fills the rest in; opening a song did not.
     *
     * Main thread only, like every other parameter push: the engine's queue
     * has one producer. It pauses every couple of hundred messages so that
     * the queue, which holds five hundred and twelve, can drain.
     */
    fun pushUnnamedDefaults(song: Song) {
        var sent = 0
        fun send(rack: Int, unit: String, name: String, v: Float) {
            NativeEngine.setParam(rack, unit, name, v, record = false)
            if (++sent % 200 == 0) Thread.sleep(15)
        }
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS || track.machine.type.isEmpty()) return@forEachIndexed
            for (p in machineTable(track.machine.type)) {
                if (p.name !in track.machine.params) send(rack, "machine", p.name, p.defaultNormalized)
            }
            for (slot in 0 until EFFECT_SLOTS) {
                val fx = track.effectAt(slot)
                if (fx.isEmpty) continue
                for (p in effectTable(fx.type)) if (p.name !in fx.params) send(rack, effectUnit(slot), p.name, p.defaultNormalized)
            }
            for (slot in 0 until MODIFIER_SLOTS) {
                val mod = track.modifierAt(slot)
                if (mod.isEmpty) continue
                for (p in modifierTable(mod.type)) if (p.name !in mod.params) send(rack, modifierUnit(slot), p.name, p.defaultNormalized)
            }
        }
        val slots = song.master.sends.withIndex().map { sendUnit(it.index) to it.value } +
            song.master.inserts.withIndex().map { masterInsertUnit(it.index) to it.value } +
            song.master.groups.withIndex().flatMap { (g, group) ->
                group.inserts.withIndex().map { groupInsertUnit(g, it.index) to it.value }
            }
        for ((unit, slot) in slots) {
            if (slot.isEmpty) continue
            for (p in effectTable(slot.type)) if (p.name !in slot.params) send(0, unit, p.name, p.defaultNormalized)
        }
    }

    /**
     * Every parameter back to what the song says, before a render.
     *
     * An automation lane moves a parameter and leaves it where it finished:
     * the document never hears of it. So a render started from wherever the
     * last playing had left things - the demo's bass filter ends its Dub scene
     * at a different cutoff from the patch's, its first scene has no lane to
     * put it back, and the first export after opening the song and every one
     * after that differed on the bass. Two exports of one song must be the same
     * file, so each render starts from the document: every named value, and
     * every default for the rest (see [pushUnnamedDefaults]).
     *
     * Main thread only, for the reason given there.
     */
    fun pushForRender(song: Song) {
        pushUnnamedDefaults(song)
        var sent = 0
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS) return@forEachIndexed
            pushChannel(rack, track.mixer, song.swingOf(track))
            for ((name, v) in track.machine.params) {
                NativeEngine.setParam(rack, "machine", name, v, record = false)
                if (++sent % 200 == 0) Thread.sleep(15)
            }
            for (slot in 0 until EFFECT_SLOTS) pushSlot(rack, effectUnit(slot), track.effectAt(slot))
            for (slot in 0 until MODIFIER_SLOTS) pushSlot(rack, modifierUnit(slot), track.modifierAt(slot))
            Thread.sleep(2)
        }
        pushMaster(song.master)
        pushSends(song.master)
        pushInputFx(song)
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

    fun pushChannel(rack: Int, m: Mixer, swing: Float = SWING_STRAIGHT) {
        NativeEngine.setParam(rack, "channel", "gain", EngineParams.volume01(m.volume), record = false)
        NativeEngine.setParam(rack, "channel", "pan", EngineParams.pan01(m.pan), record = false)
        NativeEngine.setParam(rack, "channel", "mute", EngineParams.bool01(m.mute), record = false)
        NativeEngine.setParam(rack, "channel", "midimode", m.midiMode / 2f, record = false)
        NativeEngine.setParam(rack, "channel", "midichannel", m.midiChannel / 15f, record = false)
        NativeEngine.setParam(rack, "channel", "output", m.output / 16f, record = false)
        NativeEngine.setParam(rack, "channel", "solo", EngineParams.bool01(m.solo), record = false)
        NativeEngine.setParam(rack, "channel", "sendreverb", EngineParams.unit01(m.sendReverb), record = false)
        NativeEngine.setParam(rack, "channel", "senddelay", EngineParams.unit01(m.sendDelay), record = false)
        // 50..75 as 0..1, the range the engine's own table states.
        NativeEngine.setParam(
            rack, "channel", "swing",
            ((swing - SWING_STRAIGHT) / (SWING_MAX - SWING_STRAIGHT)).coerceIn(0f, 1f), record = false,
        )
    }

    fun pushMaster(m: Master) {
        NativeEngine.setParam(0, "master", "volume", EngineParams.volume01(m.volume), record = false)
        // Every group's fader, and unity for the ones that are not there.
        for (g in 0 until MAX_GROUPS) {
            val group = m.groups.getOrNull(g) ?: MixGroup()
            NativeEngine.setParam(0, "master", "g${g + 1}gain", EngineParams.volume01(group.volume), record = false)
            NativeEngine.setParam(0, "master", "g${g + 1}mute", EngineParams.bool01(group.mute), record = false)
            NativeEngine.setParam(0, "master", "g${g + 1}solo", EngineParams.bool01(group.solo), record = false)
            NativeEngine.setParam(0, "master", "g${g + 1}pan", EngineParams.pan01(group.pan), record = false)
        }
        NativeEngine.setParam(0, "master", "limiteron", EngineParams.bool01(m.limiter.on), record = false)
        NativeEngine.setParam(0, "master", "limiterdrive", EngineParams.unit01(m.limiter.drive), record = false)
        // The held effects' settings. Addressed at a rack like everything on
        // this unit, though they belong to the master.
        NativeEngine.setParam(0, "perform", "stoplen", m.perform.stopLen / (STOP_LENGTHS.size - 1f), record = false)
        NativeEngine.setParam(0, "perform", "throwtime", m.perform.throwTime / (THROW_TIMES.size - 1f), record = false)
        NativeEngine.setParam(0, "perform", "feedback", (m.perform.feedback / 0.9f).coerceIn(0f, 1f), record = false)
        NativeEngine.setParam(0, "perform", "riserlen", m.perform.riserLen / (RISER_LENGTHS.size - 1f), record = false)
        NativeEngine.setParam(0, "perform", "xmode", m.perform.xMode.toFloat(), record = false)
        NativeEngine.setParam(0, "perform", "ymode", m.perform.yMode.toFloat(), record = false)
        // A target past the groups there are is the whole mix; the engine falls
        // back too, but only for a group with nothing in it.
        val target = m.perform.target.takeIf { it in 1..m.groups.size } ?: 0
        NativeEngine.setParam(0, "perform", "target", target / MAX_GROUPS.toFloat(), record = false)
    }

    /**
     * The parameters of whatever is on each send.
     *
     * Mounting is [ensureSends]' job and has already happened by here: until
     * the effect is there its parameter names have nothing to resolve against,
     * which is the same order every other slot is pushed in.
     */
    fun pushInputFx(song: Song) {
        for (slot in 0 until INPUT_SLOTS) pushSlot(0, inputUnit(slot), song.inputAt(slot))
    }

    fun pushSends(m: Master) {
        for (slot in 0 until SEND_SLOTS) pushSlot(0, sendUnit(slot), m.sendAt(slot))
        for (slot in 0 until MASTER_INSERT_SLOTS) pushSlot(0, masterInsertUnit(slot), m.insertAt(slot))
        for ((g, group) in m.groups.withIndex()) {
            for (slot in 0 until GROUP_INSERT_SLOTS) pushSlot(0, groupInsertUnit(g, slot), group.insertAt(slot))
        }
    }

    /** The metronome lives on the transport, not in the song. */
    fun setMetronome(on: Boolean, volume: Float = 0.5f, voice: Int = 0, division: Int = 1, whenOn: Int = 0) {
        NativeEngine.setParam(0, "master", "clickon", EngineParams.bool01(on), record = false)
        NativeEngine.setParam(0, "master", "clickvolume", EngineParams.unit01(volume), record = false)
        // Both are stepped, and the engine reads them as an index off the
        // normalised value: three voices and five divisions.
        NativeEngine.setParam(0, "master", "clickvoice", EngineParams.unit01(voice / 2f), record = false)
        NativeEngine.setParam(0, "master", "clickdiv", EngineParams.unit01(division / 4f), record = false)
        NativeEngine.setParam(0, "master", "clickwhen", EngineParams.unit01(whenOn / 2f), record = false)
    }

    /**
     * The click's shape, without touching whether it is on.
     *
     * Its own call because the settings and the on/off switch are in
     * different places: changing the voice used to reach the engine only
     * when the metronome was next toggled, so a chosen voice sat there
     * doing nothing until you switched the click off and on again.
     */
    fun setClickSettings(voice: Int, division: Int, whenOn: Int, volume: Float) {
        NativeEngine.setParam(0, "master", "clickvoice", EngineParams.unit01(voice / 2f), record = false)
        NativeEngine.setParam(0, "master", "clickdiv", EngineParams.unit01(division / 4f), record = false)
        NativeEngine.setParam(0, "master", "clickwhen", EngineParams.unit01(whenOn / 2f), record = false)
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

    /**
     * The parameters that decide what is in the tables. Mirrors the block at
     * the top of Cumulus::P - if one moves there, it moves here.
     */
    private val CLOUD_PARAMS = listOf(
        "partials", "tilt", "odd", "comb", "combperiod", "vowel", "vowelamount",
        "bandwidth", "bwscale", "stretch", "seed",
        "btilt", "bbandwidth", "bstretch", "bcomb", "bvowel", "bodd",
    )
}