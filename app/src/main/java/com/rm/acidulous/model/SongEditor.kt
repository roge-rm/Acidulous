package com.rm.acidulous.model

/**
 * The editing surface over the document: every change goes through here.
 *
 * The reference sequencer's undo is per track, so that is the unit for note edits: each track
 * has its own undo/redo history of *whole Track values* (they are immutable, so
 * a history entry is just a reference). Structure edits - scenes, tracks, song
 * settings - have a separate song-level history, which is what the main
 * screen's undo drives.
 *
 * Gestures - a note being dragged - are coalesced: [beginGesture] captures the
 * track once, [updateGesture] re-derives from that base so drags are absolute
 * rather than cumulative, and only [endGesture] writes an undo step. The
 * [onChange] callback says whether the caller should push to the engine now
 * (taps, gesture ends) or may throttle (mid-gesture).
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
    private val songUndo = ArrayDeque<Song>()
    private val songRedo = ArrayDeque<Song>()
    private var gesture: Gesture? = null

    private class Gesture(val trackIndex: Int, val base: Track)

    /** Replace the whole document (load, structural edit from elsewhere). Clears histories. */
    fun replace(newSong: Song, push: Boolean = true) {
        song = newSong
        histories.clear()
        songUndo.clear()
        songRedo.clear()
        gesture = null
        onChange(song, push)
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
        commit(trackIndex, after, pushNow = push)
    }

    fun editClip(trackIndex: Int, sceneId: String, push: Boolean = true, f: (Clip) -> Clip) = edit(trackIndex, push) { track ->
        val current = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        val next = f(current)
        if (next === current) track else track.copy(clips = track.clips + (sceneId to next))
    }

    fun canUndo(trackIndex: Int): Boolean = song.tracks.getOrNull(trackIndex)?.let { histories[it.id]?.undo?.isNotEmpty() } == true
    fun canRedo(trackIndex: Int): Boolean = song.tracks.getOrNull(trackIndex)?.let { histories[it.id]?.redo?.isNotEmpty() } == true

    fun undo(trackIndex: Int) {
        val current = song.tracks.getOrNull(trackIndex) ?: return
        val h = histories[current.id] ?: return
        val previous = h.undo.removeLastOrNull() ?: return
        h.redo.addLast(current)
        commit(trackIndex, previous, pushNow = true)
    }

    fun redo(trackIndex: Int) {
        val current = song.tracks.getOrNull(trackIndex) ?: return
        val h = histories[current.id] ?: return
        val next = h.redo.removeLastOrNull() ?: return
        h.undo.addLast(current)
        commit(trackIndex, next, pushNow = true)
    }

    // --- Gestures ---------------------------------------------------------------------

    fun beginGesture(trackIndex: Int) {
        val base = song.tracks.getOrNull(trackIndex) ?: return
        gesture = Gesture(trackIndex, base)
    }

    /** [f] is applied to the gesture's *base* track, so it must describe the total change so far. */
    fun updateGesture(f: (Track) -> Track) {
        val g = gesture ?: return
        commit(g.trackIndex, f(g.base), pushNow = false)
    }

    fun updateGestureClip(sceneId: String, f: (Clip) -> Clip) = updateGesture { track ->
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
        song = next
        onChange(song, true)
    }

    fun canUndoSong(): Boolean = songUndo.isNotEmpty()
    fun canRedoSong(): Boolean = songRedo.isNotEmpty()

    fun undoSong() {
        val previous = songUndo.removeLastOrNull() ?: return
        songRedo.addLast(song)
        song = previous
        onChange(song, true)
    }

    fun redoSong() {
        val next = songRedo.removeLastOrNull() ?: return
        songUndo.addLast(song)
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
