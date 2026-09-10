package com.rm.acidulous.engine

import android.util.Log
import com.rm.acidulous.model.EngineParams
import com.rm.acidulous.model.Master
import com.rm.acidulous.model.Mixer
import com.rm.acidulous.model.PlayMode
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

    private val mounted = arrayOfNulls<String>(RACKS)

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

    /** Everything the engine needs after any edit: machines, then the snapshot. */
    fun sync(song: Song): Boolean {
        ensureMachines(song)
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
                for ((key, lane) in clip.automation) {
                    val pts = FloatArray(lane.points.size * 2)
                    lane.points.forEachIndexed { i, p -> pts[i * 2] = p.tick.toFloat(); pts[i * 2 + 1] = p.value }
                    NativeEngine.snapshotSetLane(handle, rack, sceneIdx, track.machine.type, laneUnit(key), laneParam(key), lane.linear, pts)
                }
            }
        }

        NativeEngine.tempo = song.tempo
        NativeEngine.setLoopSong(song.loopSong)
        Log.d(TAG, "push: ${song.scenes.size} scenes, $cached clips cached, $marshalled marshalled")
        val ok = NativeEngine.snapshotCommit(handle) // consumes the handle either way
        song.tracks.forEachIndexed { rack, track ->
            if (rack < RACKS) {
                pushChannel(rack, track.mixer)
                pushMachineParams(rack, track.machine.params)
            }
        }
        pushMaster(song.master)
        return ok
    }

    fun pushMachineParams(rack: Int, params: Map<String, Float>) {
        for ((name, v) in params) NativeEngine.setParam(rack, "machine", name, v, record = false)
    }

    // --- Mixer parameters: cheap enough to send whole on every push ------------------

    fun pushChannel(rack: Int, m: Mixer) {
        NativeEngine.setParam(rack, "channel", "gain", EngineParams.volume01(m.volume), record = false)
        NativeEngine.setParam(rack, "channel", "pan", EngineParams.pan01(m.pan), record = false)
        NativeEngine.setParam(rack, "channel", "mute", EngineParams.bool01(m.mute), record = false)
        NativeEngine.setParam(rack, "channel", "solo", EngineParams.bool01(m.solo), record = false)
        NativeEngine.setParam(rack, "channel", "sendreverb", EngineParams.unit01(m.sendReverb), record = false)
        NativeEngine.setParam(rack, "channel", "senddelay", EngineParams.unit01(m.sendDelay), record = false)
    }

    fun pushMaster(m: Master) {
        NativeEngine.setParam(0, "master", "volume", EngineParams.volume01(m.volume), record = false)
        NativeEngine.setParam(0, "master", "reverbon", EngineParams.bool01(m.reverb.on), record = false)
        NativeEngine.setParam(0, "master", "reverbsize", EngineParams.unit01(m.reverb.size), record = false)
        NativeEngine.setParam(0, "master", "reverbdamp", EngineParams.unit01(m.reverb.damp), record = false)
        NativeEngine.setParam(0, "master", "reverbtone", EngineParams.unit01(m.reverb.tone), record = false)
        NativeEngine.setParam(0, "master", "delayon", EngineParams.bool01(m.delay.on), record = false)
        NativeEngine.setParam(0, "master", "delaytime", EngineParams.delayTime01(m.delay.time), record = false)
        NativeEngine.setParam(0, "master", "delayfeedback", EngineParams.unit01(m.delay.feedback), record = false)
        NativeEngine.setParam(0, "master", "delaytone", EngineParams.unit01(m.delay.tone), record = false)
        NativeEngine.setParam(0, "master", "delaypingpong", EngineParams.bool01(m.delay.pingPong), record = false)
        NativeEngine.setParam(0, "master", "limiteron", EngineParams.bool01(m.limiter.on), record = false)
        NativeEngine.setParam(0, "master", "limiterdrive", EngineParams.unit01(m.limiter.drive), record = false)
    }

    /** The metronome lives on the transport, not in the song. */
    fun setMetronome(on: Boolean, volume: Float = 0.5f) {
        NativeEngine.setParam(0, "master", "clickon", EngineParams.bool01(on), record = false)
        NativeEngine.setParam(0, "master", "clickvolume", EngineParams.unit01(volume), record = false)
    }
}
