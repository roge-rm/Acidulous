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

    /**
     * The most ring-out we will store. Not how much we keep: the render stops
     * as soon as the sound has gone, so a closed hat costs nothing and a hall
     * gets what it needs. It was a flat two seconds, which was too little for
     * the one and pure waste for the other.
     */
    private const val TAIL_CAP_SECONDS = 8f

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
        if (kotlin.math.abs(f.bpm - tempo) >= 0.01f) return true
        // A freeze with no tail was written by the old renderer, and that one
        // folded two seconds of the clip *playing again* onto its own opening -
        // so it is not merely missing its ring-out, its first two seconds are
        // doubled. Unlike a changed knob this cannot be heard as a choice, so
        // these are stale and ask to be rendered again. Every new freeze keeps
        // at least one block of tail, so nought only ever means "old".
        if (f.tail == 0) return true
        // And the voice it was rendered through. A freeze written before this
        // field existed carries nought and is left alone.
        if (f.voice == 0) return false
        val track = song.tracks.firstOrNull { it.clips[sceneId] === clip } ?: return false
        return f.voice != voiceOf(track)
    }

    /**
     * A number that changes when anything the freeze baked in changes: the
     * machine, its parameters and settings, and both insert slots.
     *
     * Not the clip - editing one thaws the freeze outright - and not the
     * mixer, because the fader, pan, sends and mute stay live over a frozen
     * track on purpose. That is the line between a freeze and a bounce.
     */
    fun voiceOf(track: Track): Int {
        var h = track.machine.type.hashCode()
        for ((k, v) in track.machine.params.toSortedMap()) h = h * 31 + k.hashCode() * 31 + v.toBits()
        for ((k, v) in track.machine.settings.toSortedMap()) h = h * 31 + k.hashCode() * 31 + v.hashCode()
        for (slot in 0 until EFFECT_SLOTS) {
            val fx = track.effectAt(slot)
            h = h * 31 + fx.type.hashCode() + if (fx.bypass) 7 else 0
            for ((k, v) in fx.params.toSortedMap()) h = h * 31 + k.hashCode() * 31 + v.toBits()
        }
        // Nought means "written before this existed", so never return it.
        return if (h == 0) 1 else h
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

    /** Forget a render and delete it. The clip's notes were never touched. */
    fun discard(context: Context, song: Song, target: Target) {
        val track = song.tracks.getOrNull(target.track) ?: return
        val frozen = track.clips[target.sceneId]?.frozen ?: return
        File(EngineAssets.freezeRoot(context), frozen.file).delete()
    }
}
