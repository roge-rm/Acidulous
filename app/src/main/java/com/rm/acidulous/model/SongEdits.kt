package com.rm.acidulous.model

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
