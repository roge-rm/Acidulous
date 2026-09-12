package com.rm.acidulous.engine

import android.util.Log
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.addNote
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.emptyClipFor

/**
 * Turns stamped live MIDI into notes in the document.
 *
 * Three rules:
 *  - the document updates the moment a note completes (the piano roll shows it);
 *  - quantise happens here, at merge time, and the raw tick is kept, so it can
 *    be undone later;
 *  - the engine gets the new notes at the next iteration boundary (or on stop),
 *    never mid-pass - otherwise a note quantised *forward* of where it was
 *    played would sound twice in the pass it was recorded in.
 *
 * Drive [poll] from the UI's polling loop. Not thread-safe; UI thread only.
 */
class Recorder {

    var quantise: Boolean = true

    private val buffer = LongArray(128 * 4)
    private val open = HashMap<Int, OpenNote>() // key: rack shl 8 or pitch
    private var dirty = false
    private var lastScene = -1
    private var lastTick = 0L

    var notesRecorded: Int = 0
        private set

    private class OpenNote(val absTick: Long, val sceneId: Long, val tickInIteration: Long, val velocity: Int)

    /**
     * @param song the current document
     * @param position where the transport is now
     * @param sceneIdOf maps the engine's 64-bit scene id back to the document's
     * @return the updated document, and whether the caller should push it now
     */
    fun poll(song: Song, position: Position, playing: Boolean, sceneIdOf: (Long) -> String?): Result {
        var doc = song
        val n = NativeEngine.drainRecorded(buffer)
        for (i in 0 until n) {
            val absTick = buffer[i * 4]
            val sceneId = buffer[i * 4 + 1]
            val tickInIteration = buffer[i * 4 + 2]
            val packed = buffer[i * 4 + 3]
            val rack = ((packed shr 24) and 0xff).toInt()
            val cmd = ((packed shr 16) and 0xff).toInt()
            val pitch = ((packed shr 8) and 0xff).toInt()
            val vel = (packed and 0xff).toInt()
            val key = (rack shl 8) or pitch

            val isOn = cmd == 0x90 && vel > 0
            val isOff = cmd == 0x80 || (cmd == 0x90 && vel == 0)
            when {
                isOn -> open[key] = OpenNote(absTick, sceneId, tickInIteration, vel)
                isOff -> open.remove(key)?.let { on ->
                    doc = commit(doc, rack, pitch, on, absTick, sceneIdOf) ?: doc
                }
            }
        }

        // Iteration boundary: the scene changed, or the tick wrapped.
        val wrapped = position.scene != lastScene || position.tickInIteration < lastTick
        lastScene = position.scene
        lastTick = position.tickInIteration

        val pushNow = dirty && (wrapped || !playing)
        if (pushNow) dirty = false
        return Result(doc, pushNow)
    }

    /** Stop or disarm: close anything still held and hand everything over. */
    fun flush(song: Song, sceneIdOf: (Long) -> String?): Result {
        var doc = song
        for ((key, on) in open) {
            val rack = key shr 8
            val pitch = key and 0xff
            doc = commit(doc, rack, pitch, on, on.absTick + 1, sceneIdOf) ?: doc
        }
        open.clear()
        val push = dirty
        dirty = false
        return Result(doc, push)
    }

    private fun commit(
        song: Song, rack: Int, pitch: Int, on: OpenNote, offAbsTick: Long, sceneIdOf: (Long) -> String?,
    ): Song? {
        val sceneId = sceneIdOf(on.sceneId) ?: return null
        if (rack !in song.tracks.indices) return null
        val track = song.tracks[rack]
        val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        val len = song.clipLengthTicks(sceneId, clip)
        if (len <= 0) return null

        val raw = (on.tickInIteration % len).toInt()
        val tick = if (quantise) {
            val g = clip.grid.coerceAtLeast(1)
            (((raw + g / 2) / g) * g) % len // rounding past the end lands at the top of the loop
        } else raw
        val length = (offAbsTick - on.absTick).toInt().coerceAtLeast(1)

        notesRecorded++
        dirty = true
        Log.d(TAG, "recorded pitch $pitch at $tick (raw $raw) len $length into $sceneId")
        return song.addNote(rack, sceneId, Note(tick = tick, length = length, pitch = pitch, velocity = on.velocity, rawTick = raw))
    }

    data class Result(val song: Song, val push: Boolean)

    private companion object {
        const val TAG = "Acidulous.Rec"
    }
}
