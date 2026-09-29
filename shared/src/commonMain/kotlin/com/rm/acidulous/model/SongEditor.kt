package com.rm.acidulous.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Every change to the song goes through here.
 *
 * Undo is per track for note edits. Each track has its own undo/redo history
 * of whole Track values, which are immutable, so an entry is just a
 * reference. Structure edits (scenes, tracks, song settings) have a separate
 * song-level history, which the main screen's undo uses.
 *
 * Gestures like dragging a note are merged: [beginGesture] captures the track
 * once, [updateGesture] works from that base so drags are absolute rather
 * than cumulative, and only [endGesture] writes an undo step. [onChange] says
 * whether the caller should push to the engine now (taps, gesture ends) or
 * may throttle (mid-gesture).
 */
class SongEditor(
    initial: Song,
    private val onChange: (song: Song, pushNow: Boolean) -> Unit,
) {
    var song: Song = initial
        private set

    private class History {
        val undo = ArrayDeque<Track>()
        val redo = ArrayDeque<Track>()
    }

    private val histories = HashMap<String, History>()

    /**
     * Counts changes to any history, so a button showing [canUndo] or
     * [canUndoSong] updates when it changes. The histories themselves aren't
     * state, and a screen that isn't rebuilt otherwise (the editor, since it
     * stopped rebuilding for every meter reading) showed a greyed undo after
     * an edit, and ignored the tap.
     */
    private var historyChanges by androidx.compose.runtime.mutableIntStateOf(0)
    private val songUndo = ArrayDeque<Song>()
    private val songRedo = ArrayDeque<Song>()
    private var gesture: Gesture? = null
    /** Each track as the current take last left it, by track id; see [recorded]. */
    private val takes = HashMap<String, Track>()

    private class Gesture(val trackIndex: Int, val base: Track)

    /** Replaces the whole song (load, or a structural edit from elsewhere). Clears histories. */
    fun replace(newSong: Song, push: Boolean = true) {
        song = newSong
        histories.clear()
        songUndo.clear()
        songRedo.clear()
        gesture = null
        takes.clear()
        historyChanges++
        onChange(song, push)
    }

    /**
     * A mapped controller moved a parameter.
     *
     * Does the same three things a knob does: the engine hears it, the lane
     * records it while armed, and the song stores it so the on-screen knob
     * matches and the patch saves.
     *
     * No undo entry, on purpose. One turn of a controller knob is a hundred
     * CC messages, and a controller has no begin and end like an on-screen
     * knob does, so there's no good place to close an undo step.
     */
    fun applyMapped(trackIndex: Int, unit: String, name: String, v01: Float) {
        com.rm.acidulous.engine.NativeEngine.setParam(trackIndex, unit, name, v01, record = true)
        // The master is shared by all tracks, so it's a song edit, same as
        // the mixer's own faders.
        if (unit == "master") {
            song = song.withMasterParam(name, v01)
            onChange(song, false)
            return
        }
        val before = song.tracks.getOrNull(trackIndex) ?: return
        val slot = effectSlotOf(unit) ?: modifierSlotOf(unit)
        val after = when {
            unit == "machine" -> before.withParam(name, v01)
            unit.startsWith("effect") && slot != null -> before.withEffectParam(slot, name, v01)
            unit.startsWith("mod") && slot != null -> before.withModifierParam(slot, name, v01)
            unit == "channel" -> before.withMixerParam(name, v01)
            else -> before
        }
        if (after === before) return
        commit(trackIndex, after, pushNow = false)
    }

    // --- Track-scoped edits (undoable) ---------------------------------------------

    fun edit(trackIndex: Int, push: Boolean = true, f: (Track) -> Track) {
        val before = song.tracks.getOrNull(trackIndex) ?: return
        val after = f(before)
        if (after === before) return
        val h = historyFor(before.id)
        h.undo.addLast(before)
        if (h.undo.size > MAX_HISTORY) h.undo.removeFirst()
        h.redo.clear()
        historyChanges++
        commit(trackIndex, after, pushNow = push)
    }

    /**
     * A recording put notes on a track. A whole take is one undo step: the
     * first notes write the step and the rest join it, but only while the
     * track is still as the take left it. If anything else touched the track
     * in between (an edit, an undo), a new step starts, so someone else's
     * change never gets folded into the take.
     */
    fun recorded(trackIndex: Int, track: Track, push: Boolean = false) {
        val before = song.tracks.getOrNull(trackIndex) ?: return
        if (track === before) return
        if (takes[before.id] === before) commit(trackIndex, track, pushNow = push)
        else edit(trackIndex, push) { track }
        takes[track.id] = track
    }

    /** The take is over, so the next recorded notes start a new undo step. */
    fun endTake() = takes.clear()

    fun editClip(trackIndex: Int, sceneId: String, push: Boolean = true, f: (Clip) -> Clip) = edit(trackIndex, push) { track ->
        val current = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        val next = f(current)
        if (next === current) track else track.copy(clips = track.clips + (sceneId to next))
    }

    // Each reads historyChanges first, which is always true, so a caller in
    // composition is told when a history changes.
    fun canUndo(trackIndex: Int): Boolean = historyChanges >= 0 && song.tracks.getOrNull(trackIndex)?.let { histories[it.id]?.undo?.isNotEmpty() } == true
    fun canRedo(trackIndex: Int): Boolean = historyChanges >= 0 && song.tracks.getOrNull(trackIndex)?.let { histories[it.id]?.redo?.isNotEmpty() } == true

    fun undo(trackIndex: Int) {
        val current = song.tracks.getOrNull(trackIndex) ?: return
        val h = histories[current.id] ?: return
        val previous = h.undo.removeLastOrNull() ?: return
        h.redo.addLast(current)
        historyChanges++
        commit(trackIndex, previous, pushNow = true)
    }

    fun redo(trackIndex: Int) {
        val current = song.tracks.getOrNull(trackIndex) ?: return
        val h = histories[current.id] ?: return
        val next = h.redo.removeLastOrNull() ?: return
        h.undo.addLast(current)
        historyChanges++
        commit(trackIndex, next, pushNow = true)
    }

    // --- Gestures ---------------------------------------------------------------------

    fun beginGesture(trackIndex: Int) {
        val base = song.tracks.getOrNull(trackIndex) ?: return
        gesture = Gesture(trackIndex, base)
    }

    /** [f] is applied to the gesture's base track, so it must describe the total change so far. */
    fun updateGesture(pushNow: Boolean = false, f: (Track) -> Track) {
        val g = gesture ?: return
        commit(g.trackIndex, f(g.base), pushNow)
    }

    /**
     * [pushNow] is for gestures made of separate steps rather than one drag.
     * Otherwise the drag throttle would hold back the last step until the
     * gesture ends, and that's the step being listened to.
     */
    fun updateGestureClip(sceneId: String, pushNow: Boolean = false, f: (Clip) -> Clip) = updateGesture(pushNow) { track ->
        val current = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        track.copy(clips = track.clips + (sceneId to f(current)))
    }

    fun endGesture() {
        val g = gesture ?: return
        gesture = null
        val after = song.tracks.getOrNull(g.trackIndex) ?: return
        if (after === g.base) return
        val h = historyFor(g.base.id)
        h.undo.addLast(g.base)
        if (h.undo.size > MAX_HISTORY) h.undo.removeFirst()
        h.redo.clear()
        historyChanges++
        onChange(song, true)
    }

    fun cancelGesture() {
        val g = gesture ?: return
        gesture = null
        commit(g.trackIndex, g.base, pushNow = true)
    }

    // --- Song-scoped edits (structure), with their own history -------------------------

    fun editSong(f: (Song) -> Song) {
        val next = f(song)
        if (next === song) return
        songUndo.addLast(song)
        if (songUndo.size > MAX_HISTORY) songUndo.removeFirst()
        songRedo.clear()
        historyChanges++
        song = next
        onChange(song, true)
    }

    // A continuous song-level edit (a master fader drag): one undo step at the end.
    private var songGesture: Song? = null

    fun beginSongGesture() { songGesture = song }

    /** [f] is applied to the gesture's base song, so describe the total change so far. */
    fun updateSongGesture(f: (Song) -> Song) {
        val base = songGesture ?: return
        song = f(base)
        onChange(song, false)
    }

    fun endSongGesture() {
        val base = songGesture ?: return
        songGesture = null
        if (song === base) return
        songUndo.addLast(base)
        if (songUndo.size > MAX_HISTORY) songUndo.removeFirst()
        songRedo.clear()
        historyChanges++
        onChange(song, true)
    }

    fun canUndoSong(): Boolean = historyChanges >= 0 && songUndo.isNotEmpty()
    fun canRedoSong(): Boolean = historyChanges >= 0 && songRedo.isNotEmpty()

    fun undoSong() {
        val previous = songUndo.removeLastOrNull() ?: return
        songRedo.addLast(song)
        historyChanges++
        song = previous
        onChange(song, true)
    }

    fun redoSong() {
        val next = songRedo.removeLastOrNull() ?: return
        songUndo.addLast(song)
        historyChanges++
        song = next
        onChange(song, true)
    }

    private fun commit(trackIndex: Int, track: Track, pushNow: Boolean) {
        song = song.copy(tracks = song.tracks.toMutableList().also { it[trackIndex] = track })
        onChange(song, pushNow)
    }

    private fun historyFor(trackId: String) = histories.getOrPut(trackId) { History() }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
