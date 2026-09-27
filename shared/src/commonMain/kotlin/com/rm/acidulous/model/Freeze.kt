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

    fun fileFor(trackId: String, sceneId: String): File =
        File(EngineAssets.freezeRoot(), "${trackId}__$sceneId.wav")

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
     * stale.
     */
    fun stale(song: Song, sceneId: String, clip: Clip): Boolean {
        val f = clip.frozen ?: return false
        val scene = song.scenes.firstOrNull { it.id == sceneId }
        val tempo = scene?.tempo?.bpm ?: song.tempo
        if (kotlin.math.abs(f.bpm - tempo) >= 0.01f) return true
        // A freeze with no tail came from the old renderer, which doubled
        // the first two seconds of the clip, so it has to be rendered again.
        // Every new freeze has at least one block of tail, so 0 always means
        // old.
        if (f.tail == 0) return true
        // Also stale if the sound it was rendered with has changed. A voice
        // of 0 means the freeze is older than this field, so leave it.
        if (f.voice == 0) return false
        val track = song.tracks.firstOrNull { it.clips[sceneId] === clip } ?: return false
        return f.voice != voiceOf(track)
    }

    /**
     * A hash of everything the freeze baked in: the machine, its parameters
     * and settings, and both insert slots.
     *
     * The clip isn't included because editing it thaws the freeze anyway. The
     * mixer isn't included because it stays live over a frozen track.
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
    suspend fun render(song: Song, target: Target): Frozen? {
        val track = song.tracks.getOrNull(target.track) ?: return null
        val scene = song.scenes.firstOrNull { it.id == target.sceneId } ?: return null
        val file = fileFor(track.id, scene.id)
        return when (
            val r = NativeEngine.freezeClip(target.track, scene.engineId, file.absolutePath, TAIL_CAP_SECONDS)
        ) {
            is NativeEngine.FreezeResult.Ok ->
                Frozen(file.name, r.bpm, r.ticks, r.frames, r.peak, voiceOf(track), r.tail).also {
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

    /** Deletes a render. The clip's notes are untouched. */
    fun discard(song: Song, target: Target) {
        val track = song.tracks.getOrNull(target.track) ?: return
        val frozen = track.clips[target.sceneId]?.frozen ?: return
        File(EngineAssets.freezeRoot(), frozen.file).delete()
    }
}
