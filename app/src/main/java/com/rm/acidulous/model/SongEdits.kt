package com.rm.acidulous.model

import java.util.UUID
import kotlin.math.roundToInt

/**
 * Pure edits on the document. Each returns a new [Song]; nothing here touches
 * the engine. The command/undo layer (M3 part 3) wraps these.
 */

fun Song.updateTrack(index: Int, f: (Track) -> Track): Song {
    val track = tracks.getOrNull(index) ?: return this
    return copy(tracks = tracks.toMutableList().also { it[index] = f(track) })
}

/** Edits the clip for ([trackIndex], [sceneId]), creating it with [create] if absent. */
fun Song.updateClip(trackIndex: Int, sceneId: String, create: () -> Clip, f: (Clip) -> Clip): Song =
    updateTrack(trackIndex) { track ->
        val current = track.clips[sceneId] ?: create()
        track.copy(clips = track.clips + (sceneId to f(current)))
    }

/** A clip sized to the scene it lands in - the recorder's default when none exists yet. */
fun Song.emptyClipFor(sceneId: String): Clip {
    val scene = scenes.firstOrNull { it.id == sceneId } ?: return Clip()
    return Clip(bars = barsOf(scene))
}

fun Song.addNote(trackIndex: Int, sceneId: String, note: Note): Song =
    updateClip(trackIndex, sceneId, { emptyClipFor(sceneId) }) { clip ->
        clip.copy(notes = clip.notes + note)
    }

fun Song.clipLengthTicks(sceneId: String, clip: Clip): Int {
    val scene = scenes.firstOrNull { it.id == sceneId } ?: return clip.bars * 4 * PPQN
    return clip.bars * signatureOf(scene).ticksPerBar
}

// --- Structure: scenes ----------------------------------------------------------

fun newId(prefix: String): String = prefix + "-" + UUID.randomUUID().toString().substring(0, 8)

/** Appends a blank scene, or inserts one after [afterIndex]. */
fun Song.addScene(afterIndex: Int? = null, name: String? = null): Song {
    val scene = Scene(id = newId("s"), name = name ?: "Scene ${scenes.size + 1}")
    val at = afterIndex?.let { (it + 1).coerceIn(0, scenes.size) } ?: scenes.size
    return copy(scenes = scenes.toMutableList().also { it.add(at, scene) })
}

/** Inserts a copy after [index]: the scene's settings and every track's clip for it. */
fun Song.duplicateScene(index: Int): Song {
    val source = scenes.getOrNull(index) ?: return this
    val dup = source.copy(id = newId("s"), name = source.name + " copy")
    val newScenes = scenes.toMutableList().also { it.add(index + 1, dup) }
    val newTracks = tracks.map { t ->
        val clip = t.clips[source.id] ?: return@map t
        t.copy(clips = t.clips + (dup.id to clip.copy()))
    }
    return copy(scenes = newScenes, tracks = newTracks)
}

/** Removes the scene and every clip keyed by it. The last scene cannot be removed. */
fun Song.deleteScene(index: Int): Song {
    val scene = scenes.getOrNull(index) ?: return this
    if (scenes.size <= 1) return this
    return copy(
        scenes = scenes - scene,
        tracks = tracks.map { t -> if (scene.id in t.clips) t.copy(clips = t.clips - scene.id) else t },
    )
}

fun Song.moveScene(from: Int, to: Int): Song {
    if (from !in scenes.indices || to !in scenes.indices || from == to) return this
    return copy(scenes = scenes.toMutableList().also { it.add(to, it.removeAt(from)) })
}

fun Song.updateScene(index: Int, f: (Scene) -> Scene): Song {
    val scene = scenes.getOrNull(index) ?: return this
    val next = f(scene)
    if (next === scene) return this
    return copy(scenes = scenes.toMutableList().also { it[index] = next })
}

// --- Structure: tracks ----------------------------------------------------------

const val MAX_TRACKS = 16

fun Song.addTrack(machineType: String, name: String? = null): Song {
    if (tracks.size >= MAX_TRACKS) return this
    val track = Track(id = newId("t"), name = name ?: uniqueTrackName(machineType), machine = Machine(type = machineType))
    return copy(tracks = tracks + track)
}

/**
 * The machine's own name for the first track that uses it, then 2, 3 and so
 * on. One Reflux is just "Reflux"; a number only appears once it means
 * something.
 */
fun Song.uniqueTrackName(base: String): String {
    val taken = tracks.map { it.name }.toSet()
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) ++n
    return "$base $n"
}

fun Song.deleteTrack(index: Int): Song {
    val track = tracks.getOrNull(index) ?: return this
    // Every sidechain names its source by position, so the tracks after this
    // one move up a place - and one that listened to *this* track goes back to
    // its own input, which is what no sidechain means.
    return copy(tracks = tracks - track).remapSidechains { k ->
        when {
            k - 1 == index -> 0
            k - 1 > index -> k - 1
            else -> k
        }
    }
}

fun Song.duplicateTrack(index: Int): Song {
    val source = tracks.getOrNull(index) ?: return this
    if (tracks.size >= MAX_TRACKS) return this
    val dup = source.copy(id = newId("t"), name = source.name + " copy", clips = source.clips.mapValues { it.value.copy() })
    // The copy goes in after its source, so everything past it moves down one.
    return copy(tracks = tracks.toMutableList().also { it.add(index + 1, dup) })
        .remapSidechains { k -> if (k - 1 > index) k + 1 else k }
}

/** The one parameter that holds a track's position: see [remapSidechains]. */
const val SIDECHAIN_PARAM = "sidechain"
/** Its steps: nought for the effect's own input, then the sixteen tracks. */
const val SIDECHAIN_STEPS = 17

/**
 * Re-point every sidechain after the tracks have moved.
 *
 * A compressor, gate or filter that listens to another track names it by
 * position - `sidechain` is 0 for its own input and 1..16 for a track, because
 * that is what the engine, a lane and a controller mapping all address. So an
 * edit that moves tracks has to move what points at them, in every place a
 * value can live: the inserts, the send buses, and any automation lane drawn
 * on it. [f] takes and returns the 1-based track, or 0.
 */
fun Song.remapSidechains(f: (Int) -> Int): Song {
    val last = (SIDECHAIN_STEPS - 1).toFloat()
    fun remap(v01: Float): Float {
        val k = (v01 * last).roundToInt()
        return if (k == 0) v01 else (f(k).coerceIn(0, SIDECHAIN_STEPS - 1) / last)
    }
    fun UnitSlot.remapped(): UnitSlot {
        val v = params[SIDECHAIN_PARAM] ?: return this
        return copy(params = params + (SIDECHAIN_PARAM to remap(v)))
    }
    val lane = ":$SIDECHAIN_PARAM"
    return copy(
        tracks = tracks.map { t ->
            t.copy(
                effects = t.effects.map { it.remapped() },
                clips = t.clips.mapValues { (_, c) ->
                    if (c.automation.keys.none { it.endsWith(lane) }) c
                    else c.copy(automation = c.automation.mapValues { (key, l) ->
                        if (!key.endsWith(lane)) l else l.copy(points = l.points.map { it.copy(value = remap(it.value)) })
                    })
                },
            )
        },
        master = master.copy(
            sends = master.sends.map { it.remapped() },
            inserts = master.inserts.map { it.remapped() },
            groups = master.groups.map { g -> g.copy(inserts = g.inserts.map { it.remapped() }) },
        ),
    )
}

fun Song.changeMachine(index: Int, machineType: String): Song =
    updateTrack(index) { it.copy(machine = Machine(type = machineType)) }

fun Song.renameTrack(index: Int, name: String): Song = updateTrack(index) { it.copy(name = name) }

// --- Machine parameters (document side of a knob) ----------------------------------

/**
 * A mixer value by the name the engine and the lanes use for it.
 *
 * The strip's own fields are in musical units - a gain of 0..1.5, a pan of
 * -1..1 - while everything that addresses it by name speaks normalised, so
 * the conversion belongs here rather than at each caller.
 */
fun Track.withMixerParam(name: String, v01: Float): Track = copy(
    mixer = when (name) {
        "gain" -> mixer.copy(volume = EngineParams.volumeFrom01(v01))
        "pan" -> mixer.copy(pan = EngineParams.panFrom01(v01))
        "sendreverb" -> mixer.copy(sendReverb = v01)
        "senddelay" -> mixer.copy(sendDelay = v01)
        "mute" -> mixer.copy(mute = v01 >= 0.5f)
        "solo" -> mixer.copy(solo = v01 >= 0.5f)
        else -> mixer
    },
)

fun Track.withParam(name: String, v01: Float): Track =
    copy(machine = machine.copy(params = machine.params + (name to v01.coerceIn(0f, 1f))))

fun Track.withPatch(params: Map<String, Float>): Track = copy(machine = machine.copy(params = params))

fun Track.withSetting(name: String, value: String?): Track =
    copy(machine = machine.copy(settings = if (value == null) machine.settings - name else machine.settings + (name to value)))

// --- Insert effects --------------------------------------------------------------------

private fun Track.withEffectSlot(slot: Int, f: (EffectSlot) -> EffectSlot): Track {
    if (slot !in 0 until EFFECT_SLOTS) return this
    val list = List(EFFECT_SLOTS) { effectAt(it) }.toMutableList()
    list[slot] = f(list[slot])
    return copy(effects = list)
}

/** Puts a fresh effect of [type] in [slot]; empty clears it. Parameters start at the effect's defaults. */
/**
 * Put [type] in the slot - and **changing it to what it already is is not a
 * change**, so its settings survive.
 *
 * Without that guard this is a wipe dressed as an assignment: any caller that
 * says "make sure this slot holds an Arp" to a slot that already holds one
 * takes its parameters with it. One did, and the arp lost every setting
 * anybody made.
 */
fun Track.withEffect(slot: Int, type: String): Track =
    withEffectSlot(slot) { if (it.type == type) it else EffectSlot(type = type) }

fun Track.withEffectParam(slot: Int, name: String, v01: Float): Track =
    withEffectSlot(slot) { it.copy(params = it.params + (name to v01.coerceIn(0f, 1f))) }

fun Track.withEffectBypass(slot: Int, bypass: Boolean): Track = withEffectSlot(slot) { it.copy(bypass = bypass) }

/**
 * A whole preset onto one slot, replacing what was there.
 *
 * The same shape as [withPatch] for a machine, and replacing rather than
 * merging for the same reason: what a preset does not mention it wants at the
 * effect's own default, and the panel pushes those defaults itself.
 */
fun Track.withEffectPatch(slot: Int, params: Map<String, Float>): Track =
    withEffectSlot(slot) { it.copy(params = params) }

/** The unit name the engine addresses a slot by: "effect1", "effect2". */
fun effectUnit(slot: Int): String = "effect${slot + 1}"

/** The unit a send bus's parameters are addressed under. */
fun sendUnit(slot: Int): String = "send${slot + 1}"

// --- Groups in the mixer ------------------------------------------------------------

/** The unit a group insert's parameters are addressed under: "group1fx1" .. "group4fx2". */
fun groupInsertUnit(group: Int, slot: Int): String = "group${group + 1}fx${slot + 1}"

private fun Song.withGroup(group: Int, f: (MixGroup) -> MixGroup): Song {
    val list = master.groups
    if (group !in list.indices) return this
    return copy(master = master.copy(groups = list.toMutableList().also { it[group] = f(it[group]) }))
}

/** A new group at the end, if there is room. */
fun Song.addGroup(name: String): Song {
    if (master.groups.size >= MAX_GROUPS) return this
    return copy(master = master.copy(groups = master.groups + MixGroup(name = name)))
}

fun Song.renameGroup(group: Int, name: String): Song = withGroup(group) { it.copy(name = name) }
fun Song.withGroupVolume(group: Int, volume: Float): Song = withGroup(group) { it.copy(volume = volume) }
fun Song.withGroupMute(group: Int, mute: Boolean): Song = withGroup(group) { it.copy(mute = mute) }
fun Song.withGroupSolo(group: Int, solo: Boolean): Song = withGroup(group) { it.copy(solo = solo) }

private fun Song.withGroupInsertSlot(group: Int, slot: Int, f: (UnitSlot) -> UnitSlot): Song {
    if (slot !in 0 until GROUP_INSERT_SLOTS) return this
    return withGroup(group) { g ->
        val list = List(GROUP_INSERT_SLOTS) { g.insertAt(it) }.toMutableList()
        list[slot] = f(list[slot])
        g.copy(inserts = list)
    }
}
fun Song.withGroupInsert(group: Int, slot: Int, type: String): Song =
    withGroupInsertSlot(group, slot) { if (type == it.type) it else UnitSlot(type) }
fun Song.withGroupInsertParam(group: Int, slot: Int, name: String, v01: Float): Song =
    withGroupInsertSlot(group, slot) { it.copy(params = it.params + (name to v01)) }
fun Song.withGroupInsertBypass(group: Int, slot: Int, bypass: Boolean): Song =
    withGroupInsertSlot(group, slot) { it.copy(bypass = bypass) }

/**
 * Remove group [group]. Its tracks go back to the master, and the tracks of
 * the groups after it follow their group down a place.
 */
fun Song.deleteGroup(group: Int): Song {
    if (group !in master.groups.indices) return this
    val n = group + 1
    return copy(
        master = master.copy(groups = master.groups.filterIndexed { i, _ -> i != group }),
        tracks = tracks.map { t ->
            val o = t.mixer.output
            when {
                o == n -> t.copy(mixer = t.mixer.copy(output = 0))
                o > n -> t.copy(mixer = t.mixer.copy(output = o - 1))
                else -> t
            }
        },
    )
}

/**
 * Songs saved in 0.7.0 had groups as tracks whose machine was a Bus. Each of
 * those becomes a mixer group, its tracks routed to it, and the Bus track goes.
 */
fun Song.busTracksToGroups(): Song {
    val buses = tracks.withIndex().filter { it.value.machine.type == "Bus" }.map { it.index }
    if (buses.isEmpty()) return this
    val room = MAX_GROUPS - master.groups.size
    val groupOf = HashMap<Int, Int>() // old 1-based track number -> new group number, 0 for none
    var groups = master.groups
    for ((i, b) in buses.withIndex()) {
        if (i < room) {
            val t = tracks[b]
            groups = groups + MixGroup(t.name, t.mixer.volume, t.mixer.mute, t.mixer.solo, t.effects)
            groupOf[b + 1] = groups.size
        } else {
            groupOf[b + 1] = 0
        }
    }
    var song = copy(
        master = master.copy(groups = groups),
        tracks = tracks.map { t ->
            val to = groupOf[t.mixer.output]
            if (to == null || t.machine.type == "Bus") t else t.copy(mixer = t.mixer.copy(output = to))
        },
    )
    for (b in buses.sortedDescending()) song = song.deleteTrack(b)
    return song
}

/** The unit a master insert's parameters are addressed under: "master1", "master2". */
fun masterInsertUnit(slot: Int): String = "master${slot + 1}"

private fun Song.withMasterInsertSlot(slot: Int, f: (UnitSlot) -> UnitSlot): Song {
    if (slot !in 0 until MASTER_INSERT_SLOTS) return this
    val list = List(MASTER_INSERT_SLOTS) { master.insertAt(it) }.toMutableList()
    list[slot] = f(list[slot])
    return copy(master = master.copy(inserts = list))
}

fun Song.withMasterInsert(slot: Int, type: String): Song =
    withMasterInsertSlot(slot) { if (type == it.type) it else UnitSlot(type) }

fun Song.withMasterInsertParam(slot: Int, name: String, v01: Float): Song =
    withMasterInsertSlot(slot) { it.copy(params = it.params + (name to v01)) }

fun Song.withMasterInsertBypass(slot: Int, bypass: Boolean): Song =
    withMasterInsertSlot(slot) { it.copy(bypass = bypass) }

/** [slot]'s send with [type] on it, keeping nothing of what was there. */
fun Song.withSend(slot: Int, type: String): Song {
    if (slot !in 0 until SEND_SLOTS) return this
    val list = List(SEND_SLOTS) { master.sendAt(it) }.toMutableList()
    list[slot] = if (type == list[slot].type) list[slot] else UnitSlot(type)
    return copy(master = master.copy(sends = list))
}

/** One parameter of [slot]'s send, by the effect's own name for it. */
fun Song.withSendParam(slot: Int, name: String, v01: Float): Song {
    if (slot !in 0 until SEND_SLOTS) return this
    val list = List(SEND_SLOTS) { master.sendAt(it) }.toMutableList()
    val s = list[slot]
    list[slot] = s.copy(params = s.params + (name to v01))
    return copy(master = master.copy(sends = list))
}

fun Song.withSendBypass(slot: Int, bypass: Boolean): Song {
    if (slot !in 0 until SEND_SLOTS) return this
    val list = List(SEND_SLOTS) { master.sendAt(it) }.toMutableList()
    list[slot] = list[slot].copy(bypass = bypass)
    return copy(master = master.copy(sends = list))
}
fun effectSlotOf(unit: String): Int? = when (unit) { "effect1" -> 0; "effect2" -> 1; else -> null }

// --- The input chain ---------------------------------------------------------------

/** The unit an input effect's parameters are addressed under: "input1", "input2". */
fun inputUnit(slot: Int): String = "input${slot + 1}"

/** [slot]'s input effect with [type] on it, keeping nothing of what was there. */
fun Song.withInputFx(slot: Int, type: String): Song {
    if (slot !in 0 until INPUT_SLOTS) return this
    val list = List(INPUT_SLOTS) { inputAt(it) }.toMutableList()
    list[slot] = if (type == list[slot].type) list[slot] else UnitSlot(type)
    return copy(input = list)
}

fun Song.withInputFxParam(slot: Int, name: String, v01: Float): Song {
    if (slot !in 0 until INPUT_SLOTS) return this
    val list = List(INPUT_SLOTS) { inputAt(it) }.toMutableList()
    val s = list[slot]
    list[slot] = s.copy(params = s.params + (name to v01))
    return copy(input = list)
}

fun Song.withInputFxBypass(slot: Int, bypass: Boolean): Song {
    if (slot !in 0 until INPUT_SLOTS) return this
    val list = List(INPUT_SLOTS) { inputAt(it) }.toMutableList()
    list[slot] = list[slot].copy(bypass = bypass)
    return copy(input = list)
}

// --- Modifiers ---------------------------------------------------------------------------

private fun Track.withModifierSlot(slot: Int, f: (UnitSlot) -> UnitSlot): Track {
    if (slot !in 0 until MODIFIER_SLOTS) return this
    val list = List(MODIFIER_SLOTS) { modifierAt(it) }.toMutableList()
    list[slot] = f(list[slot])
    return copy(modifiers = list)
}

/** As [withEffect]: the type it already is is not a change, so the settings stay. */
fun Track.withModifier(slot: Int, type: String): Track =
    withModifierSlot(slot) { if (it.type == type) it else UnitSlot(type = type) }
fun Track.withModifierParam(slot: Int, name: String, v01: Float): Track =
    withModifierSlot(slot) { it.copy(params = it.params + (name to v01.coerceIn(0f, 1f))) }
fun Track.withModifierBypass(slot: Int, bypass: Boolean): Track = withModifierSlot(slot) { it.copy(bypass = bypass) }

fun modifierUnit(slot: Int): String = "mod${slot + 1}"
fun modifierSlotOf(unit: String): Int? = when (unit) { "mod1" -> 0; "mod2" -> 1; "mod3" -> 2; else -> null }

/** How long the song plays once through: per-scene tempo honoured, smooth ramps ignored. */
fun Song.durationSeconds(): Float {
    var total = 0f
    for (scene in scenes) {
        val bpm = scene.tempo?.bpm ?: tempo
        val beats = signatureOf(scene).ticksPerBar.toFloat() / PPQN
        total += barsOf(scene) * scene.repeat * beats * 60f / bpm
    }
    return total
}

/**
 * The master section by parameter name, so a mapped controller reaches it
 * the same way a mapped controller reaches a track.
 *
 * The names are the engine's own ([MasterBus]'s table), which is what makes
 * this the mirror of [Track.withMixerParam] rather than a second vocabulary.
 */
fun Song.withMasterParam(name: String, v01: Float): Song = copy(
    master = when (name) {
        "volume" -> master.copy(volume = EngineParams.volumeFrom01(v01))
        // The reverb and delay names that used to be here are gone: those
        // parameters belong to whatever is on the send now, and are reached
        // under the units `send1` and `send2` by the effect's own names for
        // them - see [Song.withSendParam], which is this function's opposite
        // number for a slot.
        "limiteron" -> master.copy(limiter = master.limiter.copy(on = v01 >= 0.5f))
        "limiterdrive" -> master.copy(limiter = master.limiter.copy(drive = v01))
        else -> master
    },
)
