package com.rm.acidulous.engine

import com.rm.acidulous.io.*

import com.rm.acidulous.model.lyrics.Accent
import com.rm.acidulous.model.lyrics.Lexicon
import com.rm.acidulous.model.lyrics.Lyrics
import kotlin.concurrent.Volatile

import com.rm.acidulous.util.format

import com.rm.acidulous.util.Log
import com.rm.acidulous.model.Locks
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.Clip
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
import com.rm.acidulous.model.isPedalLane
import com.rm.acidulous.model.laneUnit
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * The one place the song meets the engine.
 *
 * The engine plays immutable snapshots and never sees the [Song] itself.
 * [push] builds one through the native builder and commits it as one
 * constructor-queue record, applied at a block boundary. Call it after every
 * edit.
 */
object EngineSync {
    /** The machine that reads a note's words. */
    private const val SINGER = "Diction"


    private const val TAG = "Acidulous.Sync"
    private const val RACKS = 16
    /** Most clips have no per-note expression, so they can all share this. */
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
    private val loadedVoices = arrayOfNulls<String>(RACKS * 2) // the specs a rack's Diction sings and morphs from
    /** Voice specs by setting, and the save they were read at, so an index isn't read on every sync. */
    private val voiceSpecs = mutableMapOf<String, Pair<Int, String>>()

    /**
     * The result of the last formula compile, by rack: empty if it worked,
     * otherwise the reason. The panel shows it so a typed formula never
     * fails silently.
     */
    val formulaErrors = androidx.compose.runtime.mutableStateMapOf<Int, String>()
    // Building a multisample means parsing and decoding, sometimes tens of
    // megabytes, so it never runs on the caller's thread.
    private val mapLoader = com.rm.acidulous.util.SerialWorker("Acidulous.MapLoader")
    /** Set while a map is being built, so the panel can say so. */
    @Volatile var mapStatus: String = ""
        private set

    /** Where relative sample paths in the document resolve. Set once at startup. */
    var sampleRoot: com.rm.acidulous.io.File? = null

    /** Where frozen clips are written and read. Set once, at startup. */
    var freezeRoot: com.rm.acidulous.io.File? = null
    private val loadedPatches = arrayOfNulls<String>(RACKS)
    var patchStatus: String = ""
        private set

    /**
     * A new engine is empty, so forget everything this remembers sending and
     * the next push sends it all.
     *
     * The engine is restarted whenever the activity is (a language change, a
     * keyboard plugged in, Android bringing the app back), but this object
     * lives as long as the process. Without this it would skip mounting
     * machines and the song would come back silent. Called before every
     * start.
     */
    fun forgetEngine() {
        mounted.fill(null)
        mountedEffects.forEach { it.fill(null) }
        mountedModifiers.forEach { it.fill(null) }
        mountedInput.fill(null)
        loadedSamples.clear()
        loadedMaps.fill(null)
        loadedFreezes.fill(null)
        builtClouds.fill(null)
        loadedFormulas.fill(null)
        loadedTakes.fill(null)
        loadedReels.fill(null)
        loadedVoices.fill(null)
        loadedPatches.fill(null)
        mountedSends.fill(null)
        mountedMasterInserts.fill(null)
        mountedGroupInserts.forEach { it.fill(null) }
        pushedTuning.fill(null)
        synced = null
    }

    /**
     * Reports something the user did that didn't work, in words they can act
     * on, like a file that isn't a supported format. Set by the host.
     */
    var onProblem: ((StringResource, Array<out Any>) -> Unit)? = null

    /** A string resource and its arguments, so the host shows it in the user's language. */
    private fun problem(message: StringResource, vararg args: Any) {
        Log.w(TAG, "problem $message: ${args.joinToString(" | ")}")
        onProblem?.invoke(message, args)
    }

    /** A file's own name, which is what the user recognises. */
    private fun shortName(rel: String) = rel.substringAfterLast('/')

    /**
     * Pads reference samples by a path relative to [sampleRoot] in
     * `Machine.settings` ("p03_sample"). Loads what changed and clears what
     * was removed.
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
                val err = NativeEngine.loadSample(rack, pad, if (rel.isEmpty()) "" else com.rm.acidulous.io.File(root, rel).absolutePath)
                // The key is set either way, so a file that won't load is
                // reported once, not on every sync. Changing the setting
                // tries again.
                loadedSamples[key] = rel
                if (err.isNotEmpty()) problem(Res.string.sync_pad_failed, pad + 1, shortName(rel), err)
            }
            // The shared file the pads slice, in the slot above them. One copy
            // for all thirteen pads, instead of decoding a large file thirteen
            // times. It can also be much longer than a pad sample.
            val sliceKey = "$rack:shared"
            val sliceRel = track.machine.settings["slice_sample"] ?: ""
            if (loadedSamples[sliceKey] != sliceRel && mounted[rack] == track.machine.type &&
                !(sliceRel.isEmpty() && loadedSamples[sliceKey] == null)) {
                val err = NativeEngine.loadSample(
                    rack, 13, if (sliceRel.isEmpty()) "" else com.rm.acidulous.io.File(root, sliceRel).absolutePath,
                    // The longer limit: one file for the whole machine, so it
                    // can be a whole track rather than a break.
                    maxSeconds = NativeEngine.SLICE_SECONDS,
                )
                loadedSamples[sliceKey] = sliceRel
                if (err.isNotEmpty()) problem(Res.string.sync_file_failed, shortName(sliceRel), err)
            }
        }
    }

    /**
     * Makes the racks match the tracks: mounts what's missing or changed and
     * unmounts racks whose track is gone. The index is the rack id, so
     * deleting a track shifts the ones after it and their machines remount on
     * their new racks.
     */
    fun ensureMachines(song: Song) {
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS && mounted[rack] != track.machine.type) {
                if (NativeEngine.mountMachine(rack, track.machine.type)) {
                    mounted[rack] = track.machine.type
                    for (pad in 0 until 13) loadedSamples.remove("$rack:$pad") // a new machine starts empty
                    loadedMaps[rack] = null
                    loadedTakes[rack] = null
                    loadedVoices[rack * 2] = null
                    loadedVoices[rack * 2 + 1] = null
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
     * The same for insert slots: a slot whose type changed gets a new effect
     * (or none), so its state starts clean. A slot with the same type keeps
     * its effect and only has its parameters pushed.
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
     * One array rather than sixteen, because sends belong to the song, not a
     * rack.
     */
    private val mountedSends = arrayOfNulls<String>(SEND_SLOTS)

    fun ensureSends(song: Song) {
        for (slot in 0 until SEND_SLOTS) {
            val want = song.master.sendAt(slot).type.ifEmpty { null }
            if (mountedSends[slot] == want) continue
            if (NativeEngine.mountSend(slot, want ?: "")) mountedSends[slot] = want
            else Log.w(TAG, "could not mount send ${want ?: "(none)"} on slot $slot")
        }
        // The master inserts, the same way.
        for (slot in 0 until MASTER_INSERT_SLOTS) {
            val want = song.master.insertAt(slot).type.ifEmpty { null }
            if (mountedMasterInserts[slot] == want) continue
            if (NativeEngine.mountMasterInsert(slot, want ?: "")) mountedMasterInserts[slot] = want
            else Log.w(TAG, "could not mount master insert ${want ?: "(none)"} on slot $slot")
        }
    }
    private val mountedMasterInserts = arrayOfNulls<String>(MASTER_INSERT_SLOTS)
    private val mountedGroupInserts = Array(MAX_GROUPS) { arrayOfNulls<String>(GROUP_INSERT_SLOTS) }

    /** The mixer groups' inserts. A group that doesn't exist has empty slots. */
    fun ensureGroups(song: Song) {
        for (g in 0 until MAX_GROUPS) for (slot in 0 until GROUP_INSERT_SLOTS) {
            val want = song.master.groups.getOrNull(g)?.insertAt(slot)?.type?.ifEmpty { null }
            if (mountedGroupInserts[g][slot] == want) continue
            if (NativeEngine.mountGroupInsert(g, slot, want ?: "")) mountedGroupInserts[g][slot] = want
            else Log.w(TAG, "could not mount group ${g + 1} insert ${want ?: "(none)"} on slot $slot")
        }
    }

    /**
     * Effects on the incoming audio.
     *
     * Same shape as [ensureSends] and mounted the same way. The difference is
     * that the engine runs them before the input is published, so they're
     * recorded into the take rather than applied on playback.
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
     * source string is the identity, so nothing reloads unless it changes.
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
                    NativeEngine.loadSoundFont(rack, com.rm.acidulous.io.File(root, settings["sf2"]!!).absolutePath, preset)
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

    /**
     * The frozen clips a rack should have. The identity is the whole list, so
     * freezing or thawing one clip reloads that rack's set and leaves the
     * other racks alone, and nothing reloads while editing around a freeze.
     */
    fun ensureFrozen(song: Song) {
        val root = freezeRoot ?: return
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val frozen = song.scenes.mapNotNull { scene ->
                val f = track?.clips?.get(scene.id)?.frozen ?: return@mapNotNull null
                Triple(scene.engineId, f, com.rm.acidulous.io.File(root, f.file))
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
     * What an audio track holds: a window into a file per scene, per lane.
     *
     * Unlike other machines, a tape's audio lives in the clips rather than in
     * `Machine.settings`, so [ensureSamples] and [ensureTakes] don't see it.
     * The spec is built from the whole track and is its own identity (see
     * `model/Bias.kt`), so nothing is sent until a take is added, trimmed or
     * moved.
     *
     * Runs off-thread like sample maps: five minutes of audio is the biggest
     * decode in the app.
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
            // Never loaded and nothing to load: don't send an empty spec to
            // every non-tape rack on the first sync.
            if (loadedReels[rack] == null && wanted.isEmpty()) { loadedReels[rack] = ""; continue }
            if (loadedReels[rack] == wanted) continue
            loadedReels[rack] = wanted
            mapLoader.execute {
                mapStatus = if (wanted.isEmpty()) "" else "Reading audio..."
                val error = NativeEngine.loadReel(rack, wanted)
                mapStatus = ""
                if (error.isNotEmpty()) {
                    problem(Res.string.sync_tape_failed, error)
                    loadedReels[rack] = null // let a retry happen
                } else if (wanted.isNotEmpty()) {
                    Log.i(TAG, "rack $rack holds ${wanted.count { it == '\n' }} audio region(s)")
                }
            }
        }
    }

    /**
     * Cumulus's tables. Its spectrum parameters each mean an inverse
     * transform of about a quarter of a million points, so they're watched
     * here and rebuilt off-thread once they settle, rather than smoothed on
     * the audio thread. Everything from `morph` on is live and goes through
     * the normal parameter path.
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
     * Formulate's expression and step tables. Text, like Nexus patches and
     * Mosaic zones: parsed on a worker and handed over as one object.
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
     * A machine's single take: a WAV decoded with its transients found and
     * mounted as one object. Pollen granulates it and Dice cuts it up.
     * Pollen's live buffer is the machine's own and needs nothing from here.
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
            // Molt needs something different from the same file (glottal
            // pulses rather than transients), so it gets its own decode. Both
            // run on a worker.
            val sung = track?.machine?.type == "Molt"
            mapLoader.execute {
                val path = if (wanted.isEmpty()) "" else com.rm.acidulous.io.File(root, wanted).absolutePath
                val error = if (sung) NativeEngine.loadUtterance(rack, path)
                            else NativeEngine.loadTake(rack, path)
                // Reported and not retried: a file that won't decode won't
                // decode the second time either. `loadedTakes` is already set,
                // so changing the setting is what tries again.
                if (error.isNotEmpty()) problem(Res.string.sync_file_failed, shortName(wanted), error)
            }
        }
    }

    /**
     * A Diction's recorded voice: its vowels analysed on a worker and mounted,
     * or the built-in voice when none is chosen. Keyed by what's sent, so a
     * vowel sung again is sent again.
     */
    fun ensureVoices(song: Song) {
        val root = sampleRoot ?: return
        val saves = com.rm.acidulous.model.voice.VoiceBank.saves
        for (at in 0 until RACKS * 2) {
            val rack = at / 2
            val slot = at % 2
            val key = if (slot == 0) com.rm.acidulous.model.voice.VoiceBank.SETTING else com.rm.acidulous.model.voice.VoiceBank.SETTING_MORPH
            val track = song.tracks.getOrNull(rack)
            val setting = track?.machine?.settings?.get(key).orEmpty()
            // Morphing, both voices need their throats as line spectral
            // frequencies, which take a quarter of loading one; asked for so,
            // the voice sung in is loaded again once one to morph to is chosen.
            val morphing = track?.machine?.settings?.get(com.rm.acidulous.model.voice.VoiceBank.SETTING_MORPH).orEmpty().isNotEmpty()
            val wanted = when {
                track == null || track.machine.type != "Diction" -> null
                mounted[rack] != "Diction" -> null // wait for the machine
                setting.isEmpty() -> ""
                else -> (voiceSpecs[setting]?.takeIf { it.first == saves }?.second
                    ?: com.rm.acidulous.model.voice.VoiceBank.engineSpec(root, setting)
                        .also { voiceSpecs[setting] = saves to it }) + if (slot == 1 || morphing) "\nLSF" else ""
            }
            if (loadedVoices[at] == wanted) continue
            loadedVoices[at] = wanted
            if (wanted == null) continue
            mapLoader.execute {
                val error = NativeEngine.loadVoice(rack, slot, wanted)
                if (error.isNotEmpty()) problem(Res.string.sync_file_failed, shortName(setting.substringBeforeLast('/')), error)
            }
        }
    }

    /** A voice was sung into: a Diction singing it hears the new take. */
    fun voicesChanged() {
        synced?.let { ensureVoices(it) }
    }

    /** The song as last synced, for [play] to reset automated values from. */
    private var synced: Song? = null

    /**
     * Play, with every automated parameter reset to the song's value first.
     *
     * A lane leaves its parameter where it finished, and a scene without a
     * lane for it carries on from there, so the second time through the
     * start of the song would sound different. Resetting here matches what
     * an export does (see [pushForRender]). The knob moving back is notice
     * enough, and `PanelKnob` marks the knobs a lane moves.
     *
     * Not in the launcher, where a clip launched mid-set carries on from
     * where the last one left things.
     */
    fun play(sceneIdx: Int = -1, launcher: Boolean = false) {
        if (!launcher) synced?.let { resetAutomated(it) }
        NativeEngine.transportPlay(sceneIdx)
    }

    /** Reset every parameter a lane moves to the song's value or its default. */
    fun resetAutomated(song: Song) {
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS) return@forEachIndexed
            val lanes = track.clips.values.flatMap { it.automation.keys }.toSet()
            if (lanes.isEmpty()) return@forEachIndexed
            var channel = false
            for (key in lanes) {
                val unit = laneUnit(key)
                if (unit == "channel") { channel = true; continue }
                val value = documentValue(track, key)
                if (value != null) NativeEngine.setParam(rack, unit, laneParam(key), value, record = false)
            }
            if (channel) pushChannel(rack, track, song.swingOf(track))
        }
    }

    /**
     * The song's value for the parameter a lane key names: the knob, or the
     * default if the song never set it. Null for ones the song doesn't
     * control: the channel is pushed as a whole, held effects are released by
     * stop, and the wheel and pressure come from the controller.
     */
    fun documentValue(track: Track, key: String): Float? {
        val unit = laneUnit(key)
        val name = laneParam(key)
        val fxSlot = effectSlotOf(unit)
        val modSlot = modifierSlotOf(unit)
        return when {
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
            // A pedal's song value is up. Play starts with it up and its lane
            // puts it down where it says.
            com.rm.acidulous.model.isPedalLane(key) -> 0f
            else -> null
        }
    }

    /**
     * Step locks go back to the knob between steps, and the knob is read when
     * the clip is sent, so if a locked knob moves the clip is different to
     * the engine even though its rev hasn't changed. This folds the knobs'
     * values into the rev the engine caches on. Returns the rev unchanged for
     * a clip with no locks.
     */
    private fun lockedRev(track: Track, clip: Clip): Long {
        var h = 0L
        for ((key, lane) in clip.automation) {
            if (!Locks.isLocks(lane)) continue
            val base = documentValue(track, key) ?: continue
            h = h * 31 + key.hashCode() * 17L + base.toBits()
        }
        return if (h == 0L) clip.rev else clip.rev xor (h shl 24) xor Long.MIN_VALUE
    }

    /** Everything the engine needs after any edit: machines, effects, modifiers, then the snapshot. */
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
        ensureVoices(song)
        ensureReels(song)
        ensureFrozen(song)
        ensureTunings(song)
        return push(song)
    }

    /** What each rack was last told about its tuning, so only changes are pushed. */
    private val pushedTuning = arrayOfNulls<String>(RACKS)

    /**
     * Every melodic track's tuning (its own or the song's), from the song's
     * key. Drums and tape stay in equal temperament: a drum note picks a
     * sound, and a tape's pitch is the recording's.
     */
    private fun ensureTunings(song: Song) {
        val root = song.key?.root ?: 0
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val tuning = if (track == null || !MachineUi.takesTuning(track.machine.type)) null
            else (track.tuning ?: song.tuning)?.takeIf { !it.isEqual }
            val key = tuning?.let { "${it.name}|${it.cents.hashCode()}|$root" } ?: "equal"
            if (pushedTuning[rack] == key) continue
            pushedTuning[rack] = key
            NativeEngine.setTuning(rack, tuning?.let { com.rm.acidulous.model.Tunings.ratios(it, root) })
        }
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
                rampToBpm = scene.ramp?.toBpm ?: 0f,
                rampBars = scene.ramp?.bars ?: 0,
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
            // The pedals this track uses anywhere. A clip that doesn't use
            // one gets a lane holding it up from its first tick, or a pedal
            // left down by the last clip would stay down.
            val pedals = track.clips.values.flatMap { it.automation.keys }.filter { isPedalLane(it) }.toSet()
            song.scenes.forEachIndexed forEachIndexedInner@{ sceneIdx, scene ->
                val clip = track.clips[scene.id] ?: return@forEachIndexedInner
                val implicit = pedals - clip.automation.keys
                // Unchanged since the last push? Then it's one lookup, not a marshal.
                // A singer's words depend on the accent and on whether the
                // dictionary has loaded yet, not only on the clip.
                val sings = track.machine.type == SINGER && clip.notes.any { it.lyric.isNotBlank() }
                val accent = Accent.of(track.machine.params["accent"] ?: 0f)
                val rev = lockedRev(track, clip).let { r ->
                    if (implicit.isEmpty()) r else r xor (implicit.sorted().hashCode().toLong() shl 20) xor 0x5a5a
                }.let { r ->
                    if (!sings) r else r xor ((accent.ordinal + 1L) shl 40) xor (if (Lexicon.dictionary != null) 1L shl 44 else 0L)
                }
                if (NativeEngine.snapshotSetClipCached(handle, rack, sceneIdx, rev)) {
                    cached++
                    return@forEachIndexedInner
                }
                marshalled++
                val flat = IntArray(clip.notes.size * 6)
                // The curves of every note end to end, with each note saying
                // how many are its own. One array instead of a call per curve,
                // which for expressive chords would be hundreds of JNI calls.
                val expr = ArrayList<Float>()
                clip.notes.forEachIndexed { i, n ->
                    // The nudge is applied here and nowhere else, so
                    // micro-timing needs no extra engine property. The host
                    // re-sorts by tick afterwards, keeping notes sorted. A note
                    // nudged before the start of the clip lands on the downbeat
                    // rather than wrapping (the host's clamp decides that).
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
                    handle, rack, sceneIdx, rev, clip.bars,
                    // Bit 0 is the play mode, bit 1 is whether the dice roll
                    // freely. One word rather than a tenth argument for a bool.
                    playMode = (if (clip.playMode == PlayMode.OneShot) 1 else 0) or
                        (if (clip.freeRoll) 2 else 0),
                    mute = clip.mute,
                    seed = clip.seed,
                    notes = flat,
                    expr = if (expr.isEmpty()) EMPTY_FLOATS else expr.toFloatArray(),
                    lyrics = if (sings) Lyrics.forNotes(clip.notes, accent, Lexicon.dictionary)?.joinToString("|") else null,
                )
                for ((key, stored) in clip.automation) {
                    // Step locks say "back to the knob" between steps, so tell
                    // the engine where the knob is.
                    val lane = if (Locks.isLocks(stored)) {
                        Locks.resolve(stored, documentValue(track, key) ?: continue)
                    } else {
                        stored
                    }
                    val pts = FloatArray(lane.points.size * 2)
                    lane.points.forEachIndexed { i, p -> pts[i * 2] = p.tick.toFloat(); pts[i * 2 + 1] = p.value }
                    // The type the lane's parameter belongs to: the machine's, or the effect's in that slot.
                    val unit = laneUnit(key)
                    val ownerType = effectSlotOf(unit)?.let { track.effectAt(it).type }
                        ?: modifierSlotOf(unit)?.let { track.modifierAt(it).type }
                        ?: track.machine.type
                    NativeEngine.snapshotSetLane(handle, rack, sceneIdx, ownerType, unit, laneParam(key), lane.linear, pts)
                }
                for (key in implicit) {
                    NativeEngine.snapshotSetLane(
                        handle, rack, sceneIdx, track.machine.type, laneUnit(key), laneParam(key), false, floatArrayOf(0f, 0f),
                    )
                }
            }
        }

        NativeEngine.tempo = song.tempo
        // The swing unit is the song's. The amount is per track and goes with
        // the channel, resolved here so the engine never sees "follow the song".
        NativeEngine.setSwingUnit(song.swingUnit)
        NativeEngine.setLoopSong(song.loopSong)
        Log.d(TAG, "push: ${song.scenes.size} scenes, $cached clips cached, $marshalled marshalled")
        val ok = NativeEngine.snapshotCommit(handle) // consumes the handle either way
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS) {
                pushChannel(rack, track, song.swingOf(track))
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

    /** Each unit type's parameter table, fetched once. */
    private val tables = HashMap<String, List<ParamInfo>>()
    private fun machineTable(type: String) = tables.getOrPut("machine:$type") { NativeEngine.machineParamInfo(type) }
    private fun effectTable(type: String) = tables.getOrPut("effect:$type") { NativeEngine.effectParamInfo(type) }
    private fun modifierTable(type: String) = tables.getOrPut("mod:$type") { NativeEngine.inputModParamInfo(type) }

    /**
     * Sets every parameter a song's units have but the song doesn't name to
     * its default.
     *
     * A patch only names what it changes (Rimshot names 11 of Genesis's 49
     * parameters). A rack that keeps the same machine type keeps its instance,
     * so without this the unnamed parameters would keep the previous song's
     * values, and a song and its export would sound different depending on
     * what was open before.
     *
     * Main thread only, like every other parameter push, since the engine's
     * queue has one producer. It pauses every couple of hundred messages so
     * the queue (512 entries) can drain.
     */
    fun pushUnnamedDefaults(song: Song) {
        var sent = 0
        fun send(rack: Int, unit: String, name: String, v: Float) {
            NativeEngine.setParam(rack, unit, name, v, record = false)
            if (++sent % 200 == 0) com.rm.acidulous.util.sleepMs(15)
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
     * Resets every parameter to what the song says before a render.
     *
     * Automation lanes move parameters and leave them where they finished,
     * without the song knowing. So without this a render would start from
     * wherever the last playback left things, and two exports of the same
     * song could differ. Each render starts from the song: every named value,
     * and the default for everything else (see [pushUnnamedDefaults]).
     *
     * Main thread only, for the reason given there.
     */
    fun pushForRender(song: Song) {
        pushUnnamedDefaults(song)
        var sent = 0
        song.tracks.forEachIndexed { rack, track ->
            if (rack >= RACKS) return@forEachIndexed
            pushChannel(rack, track, song.swingOf(track))
            for ((name, v) in track.machine.params) {
                NativeEngine.setParam(rack, "machine", name, v, record = false)
                if (++sent % 200 == 0) com.rm.acidulous.util.sleepMs(15)
            }
            for (slot in 0 until EFFECT_SLOTS) pushSlot(rack, effectUnit(slot), track.effectAt(slot))
            for (slot in 0 until MODIFIER_SLOTS) pushSlot(rack, modifierUnit(slot), track.modifierAt(slot))
            com.rm.acidulous.util.sleepMs(2)
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

    // --- Mixer parameters: cheap enough to send in full on every push --------------

    /** The channel, and what the track itself does to its notes on the way to the machine. */
    fun pushChannel(rack: Int, track: com.rm.acidulous.model.Track, swing: Float) {
        pushChannel(rack, track.mixer, swing)
        val shift = if (MachineUi.takesTranspose(track.machine.type)) track.transpose.coerceIn(-48, 48) else 0
        NativeEngine.setParam(rack, "channel", "transpose", (shift + 48) / 96f, record = false)
        NativeEngine.setParam(rack, "channel", "velocity", (track.velocity ?: 0).coerceIn(0, 127) / 127f, record = false)
    }

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
        // 50..75 as 0..1, the range the engine's own table uses.
        NativeEngine.setParam(
            rack, "channel", "swing",
            ((swing - SWING_STRAIGHT) / (SWING_MAX - SWING_STRAIGHT)).coerceIn(0f, 1f), record = false,
        )
    }

    fun pushMaster(m: Master) {
        NativeEngine.setParam(0, "master", "volume", EngineParams.volume01(m.volume), record = false)
        // Every group's fader, and unity for groups that don't exist.
        for (g in 0 until MAX_GROUPS) {
            val group = m.groups.getOrNull(g) ?: MixGroup()
            NativeEngine.setParam(0, "master", "g${g + 1}gain", EngineParams.volume01(group.volume), record = false)
            NativeEngine.setParam(0, "master", "g${g + 1}mute", EngineParams.bool01(group.mute), record = false)
            NativeEngine.setParam(0, "master", "g${g + 1}solo", EngineParams.bool01(group.solo), record = false)
            NativeEngine.setParam(0, "master", "g${g + 1}pan", EngineParams.pan01(group.pan), record = false)
        }
        NativeEngine.setParam(0, "master", "limiteron", EngineParams.bool01(m.limiter.on), record = false)
        NativeEngine.setParam(0, "master", "limiterdrive", EngineParams.unit01(m.limiter.drive), record = false)
        // The held performance effects' settings. Addressed to a rack like
        // everything on this unit, though they belong to the master.
        NativeEngine.setParam(0, "perform", "stoplen", m.perform.stopLen / (STOP_LENGTHS - 1f), record = false)
        NativeEngine.setParam(0, "perform", "throwtime", m.perform.throwTime / (THROW_TIMES.size - 1f), record = false)
        NativeEngine.setParam(0, "perform", "feedback", (m.perform.feedback / 0.9f).coerceIn(0f, 1f), record = false)
        NativeEngine.setParam(0, "perform", "riserlen", m.perform.riserLen / (RISER_LENGTHS - 1f), record = false)
        NativeEngine.setParam(0, "perform", "xmode", m.perform.xMode.toFloat(), record = false)
        NativeEngine.setParam(0, "perform", "ymode", m.perform.yMode.toFloat(), record = false)
        // A target past the existing groups means the whole mix. The engine
        // falls back too, but only for a group with nothing in it.
        val target = m.perform.target.takeIf { it in 1..m.groups.size } ?: 0
        NativeEngine.setParam(0, "perform", "target", target / MAX_GROUPS.toFloat(), record = false)
    }

    /** The input effects' parameters. */
    fun pushInputFx(song: Song) {
        for (slot in 0 until INPUT_SLOTS) pushSlot(0, inputUnit(slot), song.inputAt(slot))
    }

    /**
     * The parameters of whatever is on each send and master insert.
     *
     * [ensureSends] has already mounted them by now, as for every other slot,
     * since an effect's parameter names can't be resolved until it's there.
     */
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
        // Both are stepped, and the engine reads them as an index from the
        // normalised value: three voices and five divisions.
        NativeEngine.setParam(0, "master", "clickvoice", EngineParams.unit01(voice / 2f), record = false)
        NativeEngine.setParam(0, "master", "clickdiv", EngineParams.unit01(division / 4f), record = false)
        NativeEngine.setParam(0, "master", "clickwhen", EngineParams.unit01(whenOn / 2f), record = false)
    }

    /**
     * The click's settings, without changing whether it's on.
     *
     * A separate call because the settings and the on/off switch are in
     * different places, and a changed voice should apply straight away.
     */
    fun setClickSettings(voice: Int, division: Int, whenOn: Int, volume: Float) {
        NativeEngine.setParam(0, "master", "clickvoice", EngineParams.unit01(voice / 2f), record = false)
        NativeEngine.setParam(0, "master", "clickdiv", EngineParams.unit01(division / 4f), record = false)
        NativeEngine.setParam(0, "master", "clickwhen", EngineParams.unit01(whenOn / 2f), record = false)
        NativeEngine.setParam(0, "master", "clickvolume", EngineParams.unit01(volume), record = false)
    }

    /**
     * Nexus patches. Only rebuilt when the topology changes: dragging a node
     * around the canvas changes the saved patch several times a second, and
     * rebuilding the graph for that would cut off every delay tail in it.
     */
    fun ensureNexusPatches(song: Song) {
        for (rack in 0 until RACKS) {
            val track = song.tracks.getOrNull(rack)
            val wanted = when {
                track == null || track.machine.type != "Nexus" -> null
                mounted[rack] != "Nexus" -> null // wait for the machine
                else -> {
                    // An empty Nexus has nothing to build, and asking the
                    // engine to parse it would only log a useless warning.
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
     * The parameters that decide what's in the tables. Mirrors the block at
     * the top of Cumulus::P, so keep the two in step.
     */
    private val CLOUD_PARAMS = listOf(
        "partials", "tilt", "odd", "comb", "combperiod", "vowel", "vowelamount",
        "bandwidth", "bwscale", "stretch", "seed",
        "btilt", "bbandwidth", "bstretch", "bcomb", "bvowel", "bodd",
    )
}