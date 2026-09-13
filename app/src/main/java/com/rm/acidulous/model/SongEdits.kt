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
    val track = Track(id = newId("t"), name = name ?: uniqueTrackName(machineType), machine = Machine(type = machineType))
    return copy(tracks = tracks + track)
}

/**
 * The machine's own name for the first track that uses it, then 2, 3 and so
 * on. One Subvert is just "Subvert"; a number only appears once it means
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
fun Track.withEffect(slot: Int, type: String): Track = withEffectSlot(slot) { EffectSlot(type = type) }

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
fun effectSlotOf(unit: String): Int? = when (unit) { "effect1" -> 0; "effect2" -> 1; else -> null }

// --- Eventors ---------------------------------------------------------------------------

private fun Track.withEventorSlot(slot: Int, f: (UnitSlot) -> UnitSlot): Track {
    if (slot !in 0 until EVENTOR_SLOTS) return this
    val list = List(EVENTOR_SLOTS) { eventorAt(it) }.toMutableList()
    list[slot] = f(list[slot])
    return copy(eventors = list)
}

fun Track.withEventor(slot: Int, type: String): Track = withEventorSlot(slot) { UnitSlot(type = type) }
fun Track.withEventorParam(slot: Int, name: String, v01: Float): Track =
    withEventorSlot(slot) { it.copy(params = it.params + (name to v01.coerceIn(0f, 1f))) }
fun Track.withEventorBypass(slot: Int, bypass: Boolean): Track = withEventorSlot(slot) { it.copy(bypass = bypass) }

fun eventorUnit(slot: Int): String = "eventor${slot + 1}"
fun eventorSlotOf(unit: String): Int? = when (unit) { "eventor1" -> 0; "eventor2" -> 1; "eventor3" -> 2; else -> null }

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
        "reverbon" -> master.copy(reverb = master.reverb.copy(on = v01 >= 0.5f))
        "reverbsize" -> master.copy(reverb = master.reverb.copy(size = v01))
        "reverbdamp" -> master.copy(reverb = master.reverb.copy(damp = v01))
        "reverbtone" -> master.copy(reverb = master.reverb.copy(tone = v01))
        "delayon" -> master.copy(delay = master.delay.copy(on = v01 >= 0.5f))
        "delaytime" -> master.copy(delay = master.delay.copy(
            time = Math.round(v01 * (EngineParams.DELAY_TIMES - 1)).coerceIn(0, EngineParams.DELAY_TIMES - 1)))
        "delayfeedback" -> master.copy(delay = master.delay.copy(feedback = v01))
        "delaytone" -> master.copy(delay = master.delay.copy(tone = v01))
        "delaypingpong" -> master.copy(delay = master.delay.copy(pingPong = v01 >= 0.5f))
        "limiteron" -> master.copy(limiter = master.limiter.copy(on = v01 >= 0.5f))
        "limiterdrive" -> master.copy(limiter = master.limiter.copy(drive = v01))
        else -> master
    },
)
