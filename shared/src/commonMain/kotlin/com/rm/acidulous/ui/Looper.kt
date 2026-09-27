package com.rm.acidulous.ui

import com.rm.acidulous.util.System

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.LaunchState
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor

/**
 * The launcher as a looper: tap an empty cell and play into it.
 *
 * The cell becomes a clip that launches on the next line like any other, and
 * recording arms as it starts. With a length set in the q: window it closes
 * itself after that many bars, otherwise the next tap closes it on the nearest
 * bar line. After that it overdubs each time round until the cell is tapped,
 * and another tap starts overdubbing again. The result is an ordinary clip.
 *
 * Every tap returns how to undo itself, because a double tap on a cell is a tap
 * followed by its undo. That's how a double tap opens the editor without the
 * first tap doing anything.
 */
class Looper(
    private val editor: SongEditor,
    /** Arm or disarm recording, like the ○ button. */
    private val arm: (Boolean) -> Unit,
    private val armed: () -> Boolean,
    /** Point MIDI in and the editor's keys at this track. */
    private val focus: (Int) -> Unit,
) {
    enum class Phase {
        /** Tapped, waiting for its line. */
        Waiting,
        /** Recording its first pass, length not yet decided. */
        Open,
        /** Recording on top of itself every time round. */
        Overdub,
        /** Just playing, a tap overdubs again. */
        Playing,
    }

    class Loop(val sceneId: String, val sceneIndex: Int, val fixed: Boolean, phase: Phase) {
        var phase by mutableStateOf(phase)
        /**
         * When it was tapped. For the first moments the engine may not report
         * the transport running or the clip queued yet (the request lands on
         * the next block and is read back on the next poll), so the loop
         * mustn't be dropped then.
         */
        val since = System.currentTimeMillis()
        val young: Boolean get() = System.currentTimeMillis() - since < GRACE_MS
    }

    /** By track. Observed, so the grid redraws a cell as its loop changes. */
    val loops = mutableStateMapOf<Int, Loop>()
    private var armedHere = false

    fun phaseOf(track: Int, sceneId: String): Phase? = loops[track]?.takeIf { it.sceneId == sceneId }?.phase

    /**
     * A tap on [track]'s cell in [scene]. Returns how to undo it, or null if
     * it's not a looper tap and the cell should behave as usual.
     */
    fun tap(song: Song, track: Int, scene: Scene, sceneIndex: Int, launch: LaunchState, playing: Boolean): (() -> Unit)? {
        val loop = loops[track]?.takeIf { it.sceneId == scene.id }
        if (loop == null) {
            if (song.tracks.getOrNull(track)?.clips?.get(scene.id) != null) return null
            return start(song, track, scene, sceneIndex, playing)
        }
        val was = loop.phase
        when (was) {
            Phase.Waiting -> {
                val undo = cancel(track, scene.id)
                return undo
            }
            Phase.Open -> {
                val before = song.tracks[track].clips[scene.id]?.bars ?: PROVISIONAL_BARS
                close(song, track, scene, launch)
                loop.phase = Phase.Overdub
                refreshArm()
                return {
                    editor.editClip(track, scene.id) { it.copy(bars = before) }
                    loop.phase = was
                    refreshArm()
                }
            }
            Phase.Overdub, Phase.Playing -> {
                loop.phase = if (was == Phase.Overdub) Phase.Playing else Phase.Overdub
                refreshArm()
                return { loop.phase = was; refreshArm() }
            }
        }
    }

    /** Set a free loop's length to the nearest bar line from now. */
    private fun close(song: Song, track: Int, scene: Scene, launch: LaunchState) {
        val tpb = song.signatureOf(scene).ticksPerBar.toLong()
        // Within the clip's first pass. A launcher cycle is the clip times the
        // scene's repeats, and the loop is only the clip.
        val t = launch.tickInCycle % (PROVISIONAL_BARS * tpb)
        // A tap just after a downbeat means that downbeat, anything later means
        // the next one.
        val whole = (t / tpb).toInt()
        val bars = (if (t % tpb < tpb / 8 && whole >= 1) whole else whole + 1).coerceIn(1, PROVISIONAL_BARS)
        editor.editClip(track, scene.id) { it.copy(bars = bars) }
    }

    private fun start(song: Song, track: Int, scene: Scene, sceneIndex: Int, playing: Boolean): () -> Unit {
        val fixed = UiPrefs.loopBars > 0
        val bars = if (fixed) UiPrefs.loopBars else PROVISIONAL_BARS
        editor.editClip(track, scene.id) { Clip(bars = bars) }
        loops[track] = Loop(scene.id, sceneIndex, fixed, Phase.Waiting)
        lastTick[track] = 0L
        focus(track)
        NativeEngine.launchClip(track, scene.engineId)
        if (!playing) EngineSync.play(0, launcher = true)
        // Undo for a double tap, which opens the editor. It must not leave the
        // song playing just because the first tap started it.
        return {
            cancel(track, scene.id)
            if (!playing) NativeEngine.transportStop()
        }
    }

    /** Undo a loop before it has started: no clip, no launch. */
    private fun cancel(track: Int, sceneId: String): () -> Unit {
        loops.remove(track)
        NativeEngine.cancelLaunch(track)
        editor.edit(track) { t -> t.copy(clips = t.clips - sceneId) }
        refreshArm()
        return {}
    }

    /**
     * Called every poll. Starts recording as a loop's clip begins, closes a
     * free loop that has reached the maximum length, and drops loops whose
     * track has moved on or stopped.
     */
    fun poll(song: Song, launchStates: List<LaunchState>, playing: Boolean) {
        if (loops.isEmpty()) return
        // Recording was turned off with ○ while a loop recorded. Treat it as a
        // tap on every recording loop instead of arming again.
        if (armedHere && !armed()) {
            armedHere = false
            for ((track, loop) in loops) {
                val scene = song.scenes.getOrNull(loop.sceneIndex) ?: continue
                if (loop.phase == Phase.Open) close(song, track, scene, launchStates.getOrNull(track) ?: continue)
                if (loop.phase == Phase.Open || loop.phase == Phase.Overdub) loop.phase = Phase.Playing
            }
        }
        if (!playing) {
            loops.entries.removeAll { !it.value.young }
            refreshArm()
            return
        }
        for ((track, loop) in loops.entries.toList()) {
            val launch = launchStates.getOrNull(track) ?: continue
            val sounding = launch.playing && launch.scene == loop.sceneIndex
            when {
                loop.phase == Phase.Waiting && sounding -> {
                    loop.phase = if (loop.fixed) Phase.Overdub else Phase.Open
                    lastTick[track] = launch.tickInCycle
                }
                loop.phase == Phase.Waiting -> if (!launch.queued && !loop.young) loops.remove(track)
                !sounding -> loops.remove(track)
                // Nobody closed it, so it becomes a loop of the maximum length.
                // Counted against the clip, not the cycle (clip times the
                // scene's repeats).
                loop.phase == Phase.Open && launch.tickInCycle >=
                    PROVISIONAL_BARS.toLong() * (song.scenes.getOrNull(loop.sceneIndex)?.let { song.signatureOf(it).ticksPerBar } ?: 960) ->
                    loop.phase = Phase.Overdub
            }
            lastTick[track] = launch.tickInCycle
        }
        refreshArm()
    }

    private val lastTick = LongArray(16)

    /** Recording is on while any loop records. Only turns it off if we turned it on. */
    private fun refreshArm() {
        val wanted = loops.values.any { it.phase == Phase.Open || it.phase == Phase.Overdub }
        if (wanted && !armed()) {
            arm(true)
            armedHere = true
        } else if (!wanted && armedHere) {
            if (armed()) arm(false)
            armedHere = false
        }
    }

    companion object {
        /** How long a free loop can run before it closes itself. */
        const val PROVISIONAL_BARS = 16
        private const val GRACE_MS = 1500L
    }
}
