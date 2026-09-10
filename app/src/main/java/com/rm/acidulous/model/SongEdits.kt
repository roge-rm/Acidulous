package com.rm.acidulous.model

import java.util.UUID

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
    val track = Track(id = newId("t"), name = name ?: "$machineType ${tracks.size + 1}", machine = Machine(type = machineType))
    return copy(tracks = tracks + track)
}

fun Song.deleteTrack(index: Int): Song {
    val track = tracks.getOrNull(index) ?: return this
    return copy(tracks = tracks - track)
}

fun Song.duplicateTrack(index: Int): Song {
    val source = tracks.getOrNull(index) ?: return this
    if (tracks.size >= MAX_TRACKS) return this
    val dup = source.copy(id = newId("t"), name = source.name + " copy", clips = source.clips.mapValues { it.value.copy() })
    return copy(tracks = tracks.toMutableList().also { it.add(index + 1, dup) })
}

fun Song.changeMachine(index: Int, machineType: String): Song =
    updateTrack(index) { it.copy(machine = Machine(type = machineType)) }

fun Song.renameTrack(index: Int, name: String): Song = updateTrack(index) { it.copy(name = name) }

// --- Machine parameters (document side of a knob) ----------------------------------

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
fun Track.withEffect(slot: Int, type: String): Track = withEffectSlot(slot) { EffectSlot(type = type) }

fun Track.withEffectParam(slot: Int, name: String, v01: Float): Track =
    withEffectSlot(slot) { it.copy(params = it.params + (name to v01.coerceIn(0f, 1f))) }

fun Track.withEffectBypass(slot: Int, bypass: Boolean): Track = withEffectSlot(slot) { it.copy(bypass = bypass) }

/** The unit name the engine addresses a slot by: "effect1", "effect2". */
fun effectUnit(slot: Int): String = "effect${slot + 1}"
fun effectSlotOf(unit: String): Int? = when (unit) { "effect1" -> 0; "effect2" -> 1; else -> null }
