package com.rm.acidulous.engine

import android.util.Log
import com.rm.acidulous.model.PlayMode
import com.rm.acidulous.model.Song

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

    /** Mounts each track's machine on its rack if it is not already there. */
    fun ensureMachines(song: Song) {
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS && mounted[rack] != track.machine.type) {
                if (NativeEngine.mountMachine(rack, track.machine.type)) {
                    mounted[rack] = track.machine.type
                } else {
                    Log.w(TAG, "could not mount ${track.machine.type} on rack $rack")
                }
            }
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
            }
        }

        NativeEngine.tempo = song.tempo
        NativeEngine.setLoopSong(song.loopSong)
        Log.d(TAG, "push: ${song.scenes.size} scenes, $cached clips cached, $marshalled marshalled")
        return NativeEngine.snapshotCommit(handle) // consumes the handle either way
    }
}
