package com.rm.acidulous.engine

import com.rm.acidulous.util.Math

import com.rm.acidulous.util.Log
import com.rm.acidulous.model.CurveBuilder
import com.rm.acidulous.model.Lane
import com.rm.acidulous.model.LanePoint
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.trimmedTo
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.Swing
import com.rm.acidulous.model.swingOf
import com.rm.acidulous.model.swingPair
import com.rm.acidulous.model.laneKey
import com.rm.acidulous.model.updateClip
import com.rm.acidulous.model.addNote
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.emptyClipFor

/**
 * Turns timestamped live MIDI into notes in the song.
 *
 * Three rules:
 *  - the song updates as soon as a note ends, so the piano roll shows it;
 *  - quantising happens here, when the note is added, and the raw tick is
 *    kept so it can be undone later;
 *  - the engine gets the new notes at the next iteration boundary (or on
 *    stop), never mid-pass. Otherwise a note quantised forward of where it
 *    was played would sound twice in the pass it was recorded in.
 *
 * Call [poll] from the UI's polling loop. Not thread-safe: UI thread only.
 */
class Recorder {

    var quantise: Boolean = true
    /** How far a note moves towards its grid line when [quantise] is on. 1 is all the way. */
    var strength: Float = 1f
    /** A take replaces the notes it plays over: see [Replacing]. */
    var replace: Boolean = false

    /**
     * A track replacing what it plays over: from its first note in a take, the
     * notes its clip had then are taken out as the playhead passes them, so
     * what's left is the take. [from] is where the playhead had got to, in the
     * clip's straight ticks.
     */
    private class Replacing(val sceneId: String, val old: List<Note>, var from: Int)
    private val replacing = HashMap<Int, Replacing>()

    private val buffer = LongArray(128 * 5)
    private val paramNames = HashMap<String, List<String>>()
    private val effectParamNames = HashMap<String, List<String>>()
    private val modifierParamNames = HashMap<String, List<String>>()
    private val open = HashMap<Int, OpenNote>() // key: rack shl 8 or pitch
    private var dirty = false
    private var lastScene = -1
    private var lastTick = 0L

    var notesRecorded: Int = 0
        private set

    /**
     * A held note and what the finger holding it is doing.
     *
     * The three curve builders are made up front because some MPE firmwares
     * send a note's pressure before its note-on, and a curve created on first
     * use would lose the starting value.
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
     * @param song the current song
     * @param position where the transport is now
     * @param sceneIdOf maps the engine's 64-bit scene id back to the song's
     * @param cycleWrapped a launcher track has just looped, which [position]
     *   can't show in clip mode. The looper needs its last pass pushed then to
     *   hear it on the next one.
     * @return the updated song, and whether the caller should push it now
     */
    fun poll(
        song: Song, position: Position, playing: Boolean, sceneIdOf: (Long) -> String?, cycleWrapped: Boolean = false,
        /** Where a track's playhead is: its clip's scene id and how far in, as heard. For [replace]. */
        playhead: (Int) -> Pair<String, Long>? = { null },
    ): Result {
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
                // A curve point for a note that's held. Points with no held
                // note (after its note-off, or during the count-in) are
                // dropped.
                val on = open[(rack shl 8) or p1] ?: continue
                val kind = p2
                if (kind in on.curves.indices) {
                    val value = Float.fromBits((extra and 0xffffffffL).toInt())
                    on.curves[kind].add((absTick - on.absTick).toInt(), value)
                    on.moved = true
                }
                continue
            }
            if (cmd == CMD_PARAM) {
                val index = (extra shr 32).toInt()
                val value = Float.fromBits((extra and 0xffffffffL).toInt())
                doc = commitParam(doc, rack, p1, index, value, sceneId, tickInIteration, sceneIdOf) ?: doc
                continue
            }
            val key = (rack shl 8) or p1
            val isOn = cmd == 0x90 && p2 > 0
            val isOff = cmd == 0x80 || (cmd == 0x90 && p2 == 0)
            when {
                isOn -> {
                    open[key] = OpenNote(absTick, sceneId, tickInIteration, p2)
                    if (replace && rack !in replacing) startReplacing(doc, rack, sceneIdOf(sceneId), tickInIteration)
                }
                isOff -> open.remove(key)?.let { on ->
                    doc = commit(doc, rack, p1, on, absTick, sceneIdOf) ?: doc
                }
            }
        }

        if (!playing) replacing.clear()
        if (replace) doc = takeOutPassed(doc, playhead)

        // Iteration boundary: the scene changed, or the tick wrapped.
        val wrapped = position.scene != lastScene || position.tickInIteration < lastTick
        lastScene = position.scene
        lastTick = position.tickInIteration

        val pushNow = dirty && (wrapped || cycleWrapped || !playing)
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
        replacing.clear()
        val push = dirty
        dirty = false
        return Result(doc, push)
    }

    /** Where in [sceneId]'s clip on [rack] a heard tick is, in straight ticks, and the clip's length; null if it has none. */
    private fun straightTick(song: Song, rack: Int, sceneId: String, heard: Long): Pair<Int, Int>? {
        val track = song.tracks.getOrNull(rack) ?: return null
        val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        val len = song.clipLengthTicks(sceneId, clip)
        if (len <= 0) return null
        return Swing.from((heard % len).toInt(), song.swingOf(track), song.swingPair) to len
    }

    internal fun startReplacing(song: Song, rack: Int, sceneId: String?, heard: Long) {
        if (sceneId == null) return
        val (tick, _) = straightTick(song, rack, sceneId, heard) ?: return
        replacing[rack] = Replacing(sceneId, song.tracks[rack].clips[sceneId]?.notes ?: emptyList(), tick)
    }

    /**
     * Takes out each replacing track's old notes the playhead has passed since
     * the last poll. A track that's moved on to another clip loses the rest of
     * the one it left, and replaces in the new one from its start.
     */
    internal fun takeOutPassed(song: Song, playhead: (Int) -> Pair<String, Long>?): Song {
        var doc = song
        for ((rack, r) in replacing.entries.toList()) {
            val (sceneId, heard) = playhead(rack) ?: continue
            if (sceneId != r.sceneId) {
                doc = takeOut(doc, rack, r, r.from, Int.MAX_VALUE)
                replacing[rack] = Replacing(sceneId, doc.tracks.getOrNull(rack)?.clips?.get(sceneId)?.notes ?: emptyList(), 0)
                continue
            }
            val (now, len) = straightTick(doc, rack, sceneId, heard) ?: continue
            doc = if (now >= r.from) {
                takeOut(doc, rack, r, r.from, now)
            } else {
                takeOut(takeOut(doc, rack, r, r.from, len), rack, r, 0, now)
            }
            r.from = now
        }
        return doc
    }

    /** [r]'s old notes starting in [from, until) out of its clip. */
    private fun takeOut(song: Song, rack: Int, r: Replacing, from: Int, until: Int): Song {
        val clip = song.tracks.getOrNull(rack)?.clips?.get(r.sceneId) ?: return song
        val gone = clip.notes.filter { n -> n.tick in from until until && r.old.any { it === n } }
        if (gone.isEmpty()) return song
        dirty = true
        return song.updateClip(rack, r.sceneId, { clip }) { c -> c.copy(notes = c.notes.filterNot { n -> gone.any { it === n } }) }
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

        // Convert the performance back to straight time first.
        //
        // The player hears swung playback, so the times that arrive are in
        // swung time. Stored as they are, they'd be swung again on playback.
        // Songs always hold straight time.
        //
        // This has to happen before quantising, because a swung offbeat is
        // nearer the next grid line than the one it was played on.
        val heard = (on.tickInIteration % len).toInt()
        val raw = Swing.from(heard, song.swingOf(track), song.swingPair)
        val tick = if (quantise) {
            val g = clip.grid.coerceAtLeast(1)
            val snapped = ((raw + g / 2) / g) * g
            // Partial move at less than full strength. Rounding past the end
            // wraps to the start of the loop.
            (raw + Math.round((snapped - raw) * strength.coerceIn(0f, 1f))) % len
        } else raw
        val length = (offAbsTick - on.absTick).toInt().coerceAtLeast(1)

        // Curves are relative to the note, so trimming them to its length
        // doesn't depend on where the note ends up.
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

    /** A knob move becomes a lane point at the quantised tick. A point at the same tick is replaced. */
    private fun commitParam(
        song: Song, rack: Int, unitOrdinal: Int, index: Int, value: Float,
        sceneIdRaw: Long, tickInIteration: Long, sceneIdOf: (Long) -> String?,
    ): Song? {
        val sceneId = sceneIdOf(sceneIdRaw) ?: return null
        val track = song.tracks.getOrNull(rack) ?: return null
        val unit = RECORD_UNITS.getOrNull(unitOrdinal) ?: return null
        val name = when (unit) {
            "machine" -> paramNames.getOrPut(track.machine.type) { NativeEngine.machineParamNames(track.machine.type) }.getOrNull(index)
            "channel" -> CHANNEL_PARAMS.getOrNull(index)
            "performance" -> PERF_PARAMS.getOrNull(index)
            "perform" -> PERFORM_PARAMS.getOrNull(index)
            "effect1", "effect2" -> {
                val type = track.effectAt(if (unit == "effect1") 0 else 1).type
                if (index == EFFECT_BYPASS_INDEX) "bypass"
                else if (type.isEmpty()) null
                else effectParamNames.getOrPut(type) { NativeEngine.effectParamInfo(type).map { it.name } }.getOrNull(index)
            }
            "mod1", "mod2", "mod3" -> {
                val type = track.modifierAt(if (unit == "mod1") 0 else if (unit == "mod2") 1 else 2).type
                if (index == EFFECT_BYPASS_INDEX) "bypass"
                else if (type.isEmpty()) null
                else modifierParamNames.getOrPut(type) { NativeEngine.inputModParamInfo(type).map { it.name } }.getOrNull(index)
            }
            else -> null
        } ?: return null
        val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
        val len = song.clipLengthTicks(sceneId, clip)
        if (len <= 0) return null
        val raw = (tickInIteration % len).toInt()
        val g = clip.grid.coerceAtLeast(1)
        // Held performance effects aren't quantised, since a quick press and
        // its release could land on the same grid line and the release would
        // win. The repeat keeps time by itself from the engine's grid.
        // A piano's pedal is played part of the way down and eased up, so
        // it's kept where it happened, as far down as it was, without the
        // points closer together than a controller step or two. Every other
        // machine's pedal is up or down.
        val pedal = unit == "performance" && name in com.rm.acidulous.model.PEDAL_LANES
        val half = pedal && MachineUi.halfPedal(track.machine.type)
        val tick = if (quantise && unit != "perform" && !half) (((raw + g / 2) / g) * g) % len else raw
        val key = laneKey(unit, name)
        val kept = if (pedal && !half) (if (value >= 64f / 127f) 1f else 0f) else value
        val before = clip.automation[key]
        if (pedal && before != null && before.points.isNotEmpty() &&
            kotlin.math.abs(before.valueAt(tick) - kept) < (if (half) 2f / 127f else 0.5f)
        ) return null
        dirty = true
        return song.updateClip(rack, sceneId, { clip }) { c ->
            val lane = c.automation[key] ?: newLane(unit, name, tick)
            c.copy(automation = c.automation + (key to lane.withPoint(tick, kept)))
        }
    }

    /**
     * An empty lane, except for held performance effects. A lane holds its
     * first value back to the start of the clip, so a repeat pressed on beat
     * three would play from beat one. Held effects start at rest instead.
     * Repeat and stop are switches, so they step rather than slide.
     */
    private fun newLane(unit: String, name: String, tick: Int): Lane {
        if (unit == "performance") return com.rm.acidulous.model.newLaneFor(laneKey(unit, name), tick)
        if (unit != "perform") return Lane()
        val rest = PERFORM_REST[name] ?: return Lane()
        val stepped = name in PERFORM_STEPPED
        val start = if (tick > 0) listOf(LanePoint(0, rest)) else emptyList()
        return Lane(points = start, linear = !stepped)
    }

    data class Result(val song: Song, val push: Boolean)

    private companion object {
        const val TAG = "Acidulous.Rec"
        /** Mirrors seq::kRecParam and seq::kRecNoteExpression. */
        const val CMD_PARAM = 0xf0
        const val CMD_EXPRESSION = 0xf1
        val NO_CURVES = listOf<Lane?>(null, null, null)
        /** Mirrors kPerfMod.. in Messages.h, by index: the wheel, pressure, then the pedals. */
        val PERF_PARAMS = listOf("mod", "pressure", "sustain", "sostenuto", "soft")
        /** Mirrors `Perform::P`, by index. */
        val PERFORM_PARAMS = listOf("repeat", "stop", "x", "y", "stoplen", "throwtime", "feedback", "reverse", "gate",
            "killlow", "killmid", "killhigh", "riser", "riserlen", "xmode", "ymode", "target",
        )
        /** Where each held control rests, normalised. */
        val PERFORM_REST = mapOf("repeat" to 0f, "stop" to 0f, "x" to 0.5f, "y" to 0f, "reverse" to 0f, "gate" to 0f,
            "killlow" to 0f, "killmid" to 0f, "killhigh" to 0f, "riser" to 0f,
        )
        /** The held controls that are switches or steps, so their lanes step rather than slide. */
        val PERFORM_STEPPED = setOf("repeat", "stop", "reverse", "gate", "killlow", "killmid", "killhigh", "riser")
        /**
         * Mirrors `kChannelDefs` in `Rack.cpp`, by index. Keep them in step,
         * or params after a missing one are recorded under the wrong name.
         */
        val CHANNEL_PARAMS = listOf(
            "gain", "pan", "mute", "solo", "sendreverb", "senddelay", "midimode", "midichannel", "swing",
            "output", "transpose", "velocity",
        )
        const val EFFECT_BYPASS_INDEX = -2 // mirrors kEffectBypassIndex
    }
}

/**
 * The name of each `acidulous::Unit`, by ordinal. The audio thread tags a
 * recorded knob move with the ordinal, and this turns it back into the name
 * the lane is keyed by.
 *
 * Must match the enum's order in Messages.h, or recorded moves get dropped
 * or land on the wrong unit. `RecordUnitsTest` checks the two match.
 */
internal val RECORD_UNITS = listOf(
    "machine", "effect1", "effect2", "mod1", "mod2", "mod3", "channel", "master",
    "send1", "send2", "input1", "input2", "performance", "master1", "master2",
    "group1fx1", "group1fx2", "group2fx1", "group2fx2", "group3fx1", "group3fx2", "group4fx1", "group4fx2",
    "perform",
)
