package com.rm.acidulous.model

import com.rm.acidulous.io.*

import com.rm.acidulous.util.format

import com.rm.acidulous.util.Log
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.NativeEngine

/**
 * Freezing renders a clip to audio, so its rack plays a file instead of
 * running its machine.
 *
 * Heavy machines add up fast on a phone: a big Nexus patch can take a quarter
 * of a core. A frozen clip only costs a memory read and the channel strip.
 * The fader, pan, sends, mute and meters all still work, because only the
 * instrument is rendered and the mix stays live.
 *
 * Everything here works per clip. Freezing a scene or a track just means every
 * clip in that column or row.
 */
object Freeze {
    /** One clip, addressed the way the grid addresses it. */
    data class Target(val track: Int, val sceneId: String)

    private const val TAG = "Acidulous.Freeze"

    /**
     * The longest ring-out we store. The render stops as soon as the sound has
     * died away, so a closed hat costs nothing and a long reverb gets what it
     * needs.
     */
    private const val TAIL_CAP_SECONDS = 8f

    /** A copied render's file name inside the freeze folder. */
    fun nameFor(trackId: String, sceneId: String): String = "${trackId}__$sceneId.wav"

    /**
     * How to copy a song so the copy shares no renders with it: a new id for
     * every track, and a new file name for every render, old to new.
     *
     * Renders are named after the track and scene ids in one folder shared by
     * every song, and thawing deletes the file, so two songs with the same
     * track ids would write over and delete each other's freezes. Nothing
     * saved in a song refers to a track by its id.
     */
    data class CopyPlan(val ids: Map<String, String>, val files: Map<String, String>)

    fun copyPlan(song: Song): CopyPlan {
        val ids = LinkedHashMap<String, String>()
        val files = LinkedHashMap<String, String>()
        for (t in song.tracks) {
            val id = newId("t")
            ids[t.id] = id
            for ((sceneId, clip) in t.clips) clip.frozen?.let { files[it.file] = nameFor(id, sceneId) }
        }
        return CopyPlan(ids, files)
    }

    /**
     * [song] with [plan] applied, once the renders have been copied. [song]
     * can have changed since the plan was made: a track added since gets an
     * id of its own, and a freeze made since is kept only if its file isn't
     * named after one of the old ids. A render in [failed] (new names) didn't
     * copy, so its clip loses its freeze and plays its machine.
     */
    fun applied(song: Song, plan: CopyPlan, failed: Set<String> = emptySet()): Song = song.copy(
        tracks = song.tracks.map { t ->
            val id = plan.ids[t.id] ?: newId("t")
            t.copy(id = id, clips = t.clips.mapValues { (sceneId, clip) ->
                val f = clip.frozen ?: return@mapValues clip
                val target = plan.files[f.file]
                val keep = when {
                    target != null -> target.takeIf { it !in failed && it == nameFor(id, sceneId) }
                    plan.ids.keys.none { f.file.startsWith("${it}__") } -> f.file
                    else -> null
                }
                clip.copy(frozen = keep?.let { f.copy(file = it) })
            })
        },
    )

    /**
     * The renders in [files] (names in the freeze folder) that none of
     * [songs] uses. Only .wav files are considered.
     */
    fun unused(files: Collection<String>, songs: Collection<Song>): List<String> {
        val used = HashSet<String>()
        for (song in songs) for (t in song.tracks) for (clip in t.clips.values) clip.frozen?.let { used += it.file }
        return files.filter { it.endsWith(".wav") && it !in used }
    }

    /** A render that finished, by track id and scene id. */
    data class Rendered(val trackId: String, val sceneId: String, val frozen: Frozen)

    /** [song] with [renders] on their clips, where the track is still there and the clip isn't frozen. */
    fun withRenders(song: Song, renders: List<Rendered>): Song = song.copy(
        tracks = song.tracks.map { t ->
            val mine = renders.filter { it.trackId == t.id }
            if (mine.isEmpty()) return@map t
            t.copy(clips = t.clips.mapValues { (sceneId, clip) ->
                val r = mine.lastOrNull { it.sceneId == sceneId }
                if (r == null || clip.frozen != null) clip else clip.copy(frozen = r.frozen)
            })
        },
    )

    /** Every clip in a scene that is worth freezing. */
    fun scene(song: Song, sceneId: String): List<Target> =
        song.tracks.indices.filter { freezable(song, it, sceneId) }.map { Target(it, sceneId) }

    /** Every clip on a track. */
    fun track(song: Song, track: Int): List<Target> =
        song.scenes.map { it.id }.filter { freezable(song, track, it) }.map { Target(track, it) }

    /** A clip with no notes would render silence, so it isn't freezable. */
    fun freezable(song: Song, track: Int, sceneId: String): Boolean {
        val clip = song.tracks.getOrNull(track)?.clips?.get(sceneId) ?: return false
        return clip.notes.isNotEmpty() && clip.frozen == null
    }

    /**
     * A frozen clip at another tempo can't be used because audio doesn't
     * stretch. The engine plays the machine instead and the clip shows as
     * stale. So does an [outdated] one.
     */
    fun stale(song: Song, sceneId: String, clip: Clip): Boolean {
        val f = clip.frozen ?: return false
        val scene = song.scenes.firstOrNull { it.id == sceneId }
        val tempo = scene?.tempo?.bpm ?: song.tempo
        if (kotlin.math.abs(f.bpm - tempo) >= 0.01f) return true
        val track = song.tracks.firstOrNull { it.clips[sceneId] === clip }
        return outdated(song, track, clip, scene)
    }

    /**
     * Whether a freeze no longer sounds like its clip, whatever the tempo:
     * the sound, the clip or the bar length changed since, or it's from the
     * old renderer.
     * EngineSync only mounts freezes that aren't, and the engine checks the
     * tempo itself, since it stretches a freeze through a tempo ramp.
     */
    fun outdated(song: Song, track: Track?, clip: Clip, scene: Scene?): Boolean {
        val f = clip.frozen ?: return false
        // Rendered at another time signature, so it's the wrong length.
        if (scene != null && f.ticks > 0 && f.ticks != clip.bars * song.signatureOf(scene).ticksPerBar) return true
        // A freeze with no tail came from the old renderer, which doubled
        // the first two seconds of the clip, so it has to be rendered again.
        // Every new freeze has at least one block of tail, so 0 always means
        // old.
        if (f.tail == 0) return true
        if (track == null) return false
        // Edited since it was rendered. 0 is a freeze older than this field.
        if (f.content != 0 && f.content != contentOf(song, track, clip)) return true
        // Also out of date if the sound it was rendered with has changed. A
        // voice of 0 means the freeze is older than this field, so leave it.
        if (f.voice == 0) return false
        return f.voice != voiceOf(track)
    }

    /**
     * Every frozen clip in [song] that plays as audio at its scene's tempo,
     * by track index and scene, in track then scene order.
     */
    fun current(song: Song): List<Pair<Target, Frozen>> {
        val out = ArrayList<Pair<Target, Frozen>>()
        song.tracks.forEachIndexed { i, t ->
            for (scene in song.scenes) {
                val clip = t.clips[scene.id] ?: continue
                val f = clip.frozen ?: continue
                if (!stale(song, scene.id, clip)) out += Target(i, scene.id) to f
            }
        }
        return out
    }

    /**
     * A hash of what a freeze renders from the clip and how the track plays
     * it: the notes with their expression and trigs, the length, play mode
     * and dice, every lane but the mixer's (which stays live over a frozen
     * track), and the track's transpose, velocity, tuning and swing as the
     * engine gets them. Built by hand from stable parts (no enum hashes), so
     * it's the same in every run and can be saved. Never 0, which means "not
     * recorded".
     */
    fun contentOf(song: Song, track: Track, clip: Clip): Int {
        var h = 17
        // Resolved as EngineSync sends them: transpose and tuning only reach
        // melodic machines, and a track without its own follows the song.
        if (MachineUi.takesTranspose(track.machine.type)) h = h * 31 + track.transpose
        h = h * 31 + (track.velocity ?: 0)
        val tuning = if (MachineUi.takesTuning(track.machine.type)) (track.tuning ?: song.tuning)?.takeIf { !it.isEqual } else null
        if (tuning != null) {
            h = h * 31 + (song.key?.root ?: 0)
            for (c in tuning.cents) h = h * 31 + c.toBits()
        }
        val swing = song.swingOf(track)
        h = h * 31 + swing.toBits()
        // The unit only matters once there's swing.
        if (swing != SWING_STRAIGHT) h = h * 31 + song.swingUnit
        h = h * 31 + clip.bars
        h = h * 31 + clip.playMode.ordinal
        h = h * 31 + clip.seed
        h = h * 31 + if (clip.freeRoll) 1 else 0
        fun lane(l: Lane?) {
            if (l == null) { h = h * 31 + 7; return }
            h = h * 31 + if (l.linear) 1 else 2
            for (p in l.points) h = (h * 31 + p.tick) * 31 + p.value.toBits()
        }
        for (n in clip.notes) {
            h = h * 31 + n.tick
            h = h * 31 + n.length
            h = h * 31 + n.pitch
            h = h * 31 + n.velocity
            h = h * 31 + n.chance
            h = h * 31 + n.trig.ordinal
            h = h * 31 + n.ratchet
            h = h * 31 + n.nudge
            h = h * 31 + n.lyric.hashCode()
            lane(n.bend); lane(n.pressure); lane(n.timbre)
        }
        for ((key, l) in clip.automation.entries.sortedBy { it.key }) {
            if (laneUnit(key) == "channel") continue
            h = h * 31 + key.hashCode()
            lane(l)
        }
        return if (h == 0) 1 else h
    }

    /**
     * A hash of everything the freeze baked in: the machine, its parameters
     * and settings, and both insert slots.
     *
     * The clip has a hash of its own ([contentOf]). The mixer isn't included
     * because it stays live over a frozen track.
     */
    fun voiceOf(track: Track): Int {
        var h = track.machine.type.hashCode()
        for ((k, v) in track.machine.params.entries.sortedBy { it.key }) h = h * 31 + k.hashCode() * 31 + v.toBits()
        for ((k, v) in track.machine.settings.entries.sortedBy { it.key }) h = h * 31 + k.hashCode() * 31 + v.hashCode()
        for (slot in 0 until EFFECT_SLOTS) {
            val fx = track.effectAt(slot)
            h = h * 31 + fx.type.hashCode() + if (fx.bypass) 7 else 0
            for ((k, v) in fx.params.entries.sortedBy { it.key }) h = h * 31 + k.hashCode() * 31 + v.toBits()
        }
        // 0 means "written before this field existed", so never return it.
        return if (h == 0) 1 else h
    }

    fun frozenCount(song: Song, targets: List<Target>): Int =
        targets.count { song.tracks.getOrNull(it.track)?.clips?.get(it.sceneId)?.frozen != null }

    /**
     * Renders one clip. It blocks and takes the audio stream down while it
     * runs, so call it from a worker with the transport stopped. Returns the
     * record to store on the clip, or null with the reason logged.
     */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun render(song: Song, target: Target): Frozen? {
        val track = song.tracks.getOrNull(target.track) ?: return null
        val scene = song.scenes.firstOrNull { it.id == target.sceneId } ?: return null
        val clip = track.clips[scene.id] ?: return null
        // A file of its own for every render, since a thawed freeze's file is
        // kept for undo (until the sweep at the next song open) and a fresh
        // render mustn't write over it.
        val stamp = kotlin.uuid.Uuid.random().toString().substring(0, 6)
        val file = File(EngineAssets.freezeRoot(), "${track.id}__${scene.id}__$stamp.wav")
        return when (
            val r = NativeEngine.freezeClip(target.track, scene.engineId, file.absolutePath, TAIL_CAP_SECONDS)
        ) {
            is NativeEngine.FreezeResult.Ok ->
                Frozen(file.name, r.bpm, r.ticks, r.frames, r.peak, voiceOf(track), r.tail, contentOf(song, track, clip)).also {
                    Log.i(
                        TAG,
                        "${track.name} / ${scene.name}: ${r.frames} frames at ${r.bpm} bpm, " +
                            "peak ${r.peak}, ${"%.2f".format(r.tail / 48000f)} s of tail",
                    )
                }
            is NativeEngine.FreezeResult.Failed -> {
                Log.w(TAG, "${track.name} / ${scene.name}: ${r.reason}")
                null
            }
        }
    }
}
