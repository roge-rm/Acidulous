package com.rm.acidulous.model

import android.content.Context
import android.util.Log
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.NativeEngine
import java.io.File

/**
 * Freezing: a clip rendered to audio, so its rack plays a file instead of
 * running its machine.
 *
 * Sixteen tracks is the promise; a phone is the problem. Filament is about
 * 6% of a core for six voices and a heavy Nexus patch a quarter of one, so
 * four or five of those and a mid-range phone is out of room. A frozen clip
 * costs a memory read and the channel strip - which is why the fader, pan,
 * sends, mute and meters all still work over it, and why freezing is not
 * bouncing: the mix stays live, only the instrument stops running.
 *
 * The unit is the clip, and a scene or a track is just every clip in that
 * column or row. Nothing here knows about scenes or tracks as such.
 */
object Freeze {
    /** One clip, addressed the way the grid addresses it. */
    data class Target(val track: Int, val sceneId: String)

    private const val TAG = "Acidulous.Freeze"

    /** Two seconds of tail, wrapped back into the head so the loop joins. */
    private const val TAIL_SECONDS = 2f

    fun fileFor(context: Context, trackId: String, sceneId: String): File =
        File(EngineAssets.freezeRoot(context), "${trackId}__$sceneId.wav")

    /** Every clip in a scene that is worth freezing. */
    fun scene(song: Song, sceneId: String): List<Target> =
        song.tracks.indices.filter { freezable(song, it, sceneId) }.map { Target(it, sceneId) }

    /** Every clip on a track. */
    fun track(song: Song, track: Int): List<Target> =
        song.scenes.map { it.id }.filter { freezable(song, track, it) }.map { Target(track, it) }

    /** A clip with nothing in it renders silence; there is no point. */
    fun freezable(song: Song, track: Int, sceneId: String): Boolean {
        val clip = song.tracks.getOrNull(track)?.clips?.get(sceneId) ?: return false
        return clip.notes.isNotEmpty() && clip.frozen == null
    }

    /**
     * A frozen clip at another tempo cannot be used: audio does not stretch,
     * so the engine plays the machine instead and the clip says it is stale
     * rather than quietly sounding wrong.
     */
    fun stale(song: Song, sceneId: String, clip: Clip): Boolean {
        val f = clip.frozen ?: return false
        val scene = song.scenes.firstOrNull { it.id == sceneId }
        val tempo = scene?.tempo?.bpm ?: song.tempo
        return kotlin.math.abs(f.bpm - tempo) >= 0.01f
    }

    fun frozenCount(song: Song, targets: List<Target>): Int =
        targets.count { song.tracks.getOrNull(it.track)?.clips?.get(it.sceneId)?.frozen != null }

    /**
     * Render one clip. Blocking, and it takes the audio stream down while it
     * runs, so it belongs on a worker with the transport stopped. Returns the
     * record to store on the clip, or null with the reason logged.
     */
    fun render(context: Context, song: Song, target: Target): Frozen? {
        val track = song.tracks.getOrNull(target.track) ?: return null
        val scene = song.scenes.firstOrNull { it.id == target.sceneId } ?: return null
        val file = fileFor(context, track.id, scene.id)
        return when (val r = NativeEngine.freezeClip(target.track, scene.engineId, file.absolutePath, TAIL_SECONDS)) {
            is NativeEngine.FreezeResult.Ok ->
                Frozen(file.name, r.bpm, r.ticks, r.frames, r.peak).also {
                    Log.i(TAG, "${track.name} / ${scene.name}: ${r.frames} frames at ${r.bpm} bpm, peak ${r.peak}")
                }
            is NativeEngine.FreezeResult.Failed -> {
                Log.w(TAG, "${track.name} / ${scene.name}: ${r.reason}")
                null
            }
        }
    }

    /** Forget a render and delete it. The clip's notes were never touched. */
    fun discard(context: Context, song: Song, target: Target) {
        val track = song.tracks.getOrNull(target.track) ?: return
        val frozen = track.clips[target.sceneId]?.frozen ?: return
        File(EngineAssets.freezeRoot(context), frozen.file).delete()
    }
}
