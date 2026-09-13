package com.rm.acidulous.engine

import android.util.Log
import com.rm.acidulous.model.CurveBuilder
import com.rm.acidulous.model.Lane
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.trimmedTo
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.laneKey
import com.rm.acidulous.model.updateClip
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

    private val buffer = LongArray(128 * 5)
    private val paramNames = HashMap<String, List<String>>()
    private val effectParamNames = HashMap<String, List<String>>()
    private val eventorParamNames = HashMap<String, List<String>>()
    private val open = HashMap<Int, OpenNote>() // key: rack shl 8 or pitch
    private var dirty = false
    private var lastScene = -1
    private var lastTick = 0L

    var notesRecorded: Int = 0
        private set

    /**
     * A note being held, and what the finger holding it is doing.
     *
     * The three builders are made up front rather than on first use: an MPE
     * controller sends a note's pressure before its note-on in some
     * firmwares, and a curve that only exists once something has moved would
     * lose the value the note started at.
     */
    private class OpenNote(val absTick: Long, val sceneId: Long, val tickInIteration: Long, val velocity: Int) {
        val curves = listOf(
            CurveBuilder(neutral = 0.5f), // bend, centred
            CurveBuilder(neutral = 0f),   // pressure
            CurveBuilder(neutral = 0f),   // slide
        )
        var moved = false
    }

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
            val absTick = buffer[i * 5]
            val sceneId = buffer[i * 5 + 1]
            val tickInIteration = buffer[i * 5 + 2]
            val packed = buffer[i * 5 + 3]
            val extra = buffer[i * 5 + 4]
            val rack = ((packed shr 24) and 0xff).toInt()
            val cmd = ((packed shr 16) and 0xff).toInt()
            val p1 = ((packed shr 8) and 0xff).toInt()
            val p2 = (packed and 0xff).toInt()

            if (cmd == CMD_EXPRESSION) {
                // A curve point for a note already down. One that is not -
                // a finger's tail after its note-off, or the count-in - has
                // nowhere to go, and is dropped rather than guessed at.
                val on = open[(rack shl 8) or p1] ?: continue
                val kind = p2
                if (kind in on.curves.indices) {
                    val value = java.lang.Float.intBitsToFloat((extra and 0xffffffffL).toInt())
                    on.curves[kind].add((absTick - on.absTick).toInt(), value)
                    on.moved = true
                }
                continue
            }
            if (cmd == CMD_PARAM) {
                val index = (extra shr 32).toInt()
                val value = java.lang.Float.intBitsToFloat((extra and 0xffffffffL).toInt())
                doc = commitParam(doc, rack, p1, index, value, sceneId, tickInIteration, sceneIdOf) ?: doc
                continue
            }
            val key = (rack shl 8) or p1
            val isOn = cmd == 0x90 && p2 > 0
            val isOff = cmd == 0x80 || (cmd == 0x90 && p2 == 0)
            when {
                isOn -> open[key] = OpenNote(absTick, sceneId, tickInIteration, p2)
                isOff -> open.remove(key)?.let { on ->
                    doc = commit(doc, rack, p1, on, absTick, sceneIdOf) ?: doc
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

        // Curves are the note's own, so trimming them to its length is the
        // last thing done and needs no reference to where the note ended up.
        val curves = if (on.moved) on.curves.map { it.build()?.trimmedTo(length) } else NO_CURVES
        notesRecorded++
        dirty = true
        Log.d(TAG, "recorded pitch $pitch at $tick (raw $raw) len $length into $sceneId" +
            if (on.moved) " with ${curves.count { it != null }} curves" else "")
        return song.addNote(
            rack, sceneId,
            Note(
                tick = tick, length = length, pitch = pitch, velocity = on.velocity, rawTick = raw,
                bend = curves[0], pressure = curves[1], timbre = curves[2],
            ),
        )
    }

    /** A knob move becomes a lane point at the quantised tick; same tick replaces. */
    private fun commitParam(
        song: Song, rack: Int, unitOrdinal: Int, index: Int, value: Float,
        sceneIdRaw: Long, tickInIteration: Long, sceneIdOf: (Long) -> String?,
    ): Song? {
        val sceneId = sceneIdOf(sceneIdRaw) ?: return null
        val track = song.tracks.getOrNull(rack) ?: return null
        val unit = UNITS.getOrNull(unitOrdinal) ?: return null
        val name = when (unit) {
            "machine" -> paramNames.getOrPut(track.machine.type) { NativeEngine.machineParamNames(track.machine.type) }.getOrNull(index)
            "channel" -> CHANNEL_PARAMS.getOrNull(index)
            "performance" -> PERF_PARAMS.getOrNull(index)
            "effect1", "effect2" -> {
                val type = track.effectAt(if (unit == "effect1") 0 else 1).type
                if (index == EFFECT_BYPASS_INDEX) "bypass"
                else if (type.isEmpty()) null
                else effectParamNames.getOrPut(type) { NativeEngine.effectParamInfo(type).map { it.name } }.getOrNull(index)
            }
            "eventor1", "eventor2", "eventor3" -> {
                val type = track.eventorAt(if (unit == "eventor1") 0 else if (unit == "eventor2") 1 else 2).type
                if (index == EFFECT_BYPASS_INDEX) "bypass"
                else if (type.isEmpty()) null
                else eventorParamNames.getOrPut(type) { NativeEngine.eventorParamInfo(type).map { it.name } }.getOrNull(index)
            }
            else -> null
        } ?: return null
        val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        val len = song.clipLengthTicks(sceneId, clip)
        if (len <= 0) return null
        val raw = (tickInIteration % len).toInt()
        val g = clip.grid.coerceAtLeast(1)
        val tick = if (quantise) (((raw + g / 2) / g) * g) % len else raw
        val key = laneKey(unit, name)
        dirty = true
        return song.updateClip(rack, sceneId, { clip }) { c ->
            val lane = c.automation[key] ?: Lane()
            c.copy(automation = c.automation + (key to lane.withPoint(tick, value)))
        }
    }

    data class Result(val song: Song, val push: Boolean)

    private companion object {
        const val TAG = "Acidulous.Rec"
        /** Mirrors seq::kRecParam and seq::kRecNoteExpression. */
        const val CMD_PARAM = 0xf0
        const val CMD_EXPRESSION = 0xf1
        val NO_CURVES = listOf<Lane?>(null, null, null)
        // Mirrors acidulous::Unit
        // Order matters: this is the Unit enum's ordinal, read off events the
        // audio thread stamped. Keep it in step with Messages.h.
        val UNITS = listOf(
            "machine", "effect1", "effect2", "eventor1", "eventor2", "eventor3", "channel", "master",
            "performance",
        )
        /** Unit::Performance's two indices; see kPerfMod in Messages.h. */
        val PERF_PARAMS = listOf("mod", "pressure")
        val CHANNEL_PARAMS = listOf("gain", "pan", "mute", "solo", "sendreverb", "senddelay")
        const val EFFECT_BYPASS_INDEX = -2 // mirrors kEffectBypassIndex
    }
}
