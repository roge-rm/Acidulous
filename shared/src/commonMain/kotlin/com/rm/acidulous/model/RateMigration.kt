package com.rm.acidulous.model

import kotlin.math.roundToInt

/**
 * Tempo-locked LFO rates were eight note values, 1/16 to 8 bars, until
 * 0.9.12, and then seventeen, with 1/32, the triplets and the dotted ones in
 * between (dsp::Lfo::kBeats). A knob is saved as a fraction of its range, so
 * an old value would land on another rate: these move each one saved (a
 * knob, a lane's points, a step lock) to the same rate on the new list.
 */
internal object RateMigration {
    /** The song and patch versions that have the seventeen. */
    const val SONG_VERSION = 2
    const val PATCH_VERSION = 2

    /** Where each of the old eight is on the new list. */
    private val OLD_TO_NEW = intArrayOf(2, 5, 8, 11, 13, 14, 15, 16)
    private const val OLD_LAST = 7
    private const val NEW_LAST = 16

    /** Each effect with a tempo-locked LFO, and its rate. */
    private val EFFECT_RATE = mapOf(
        "Filter" to "lforate", "Phaser" to "rate", "Flanger" to "rate", "Chorus" to "rate", "Tremolo" to "rate",
    )

    /** Machines whose LFOs sync: free, then the note values. */
    private val SYNC_MACHINES = setOf("Trinity", "Mosaic", "Ratio")
    private val SYNC = Regex("l\\d+_sync")

    /** An effect's rate, from the eight to the seventeen. */
    fun rate(v: Float): Float = OLD_TO_NEW[(v * OLD_LAST).roundToInt().coerceIn(0, OLD_LAST)] / NEW_LAST.toFloat()

    /** A machine LFO's sync, free then the eight, to free then the seventeen. */
    fun sync(v: Float): Float {
        val i = (v * (OLD_LAST + 1)).roundToInt().coerceIn(0, OLD_LAST + 1)
        return if (i == 0) 0f else (OLD_TO_NEW[i - 1] + 1) / (NEW_LAST + 1f)
    }

    /** How [name] on a unit of [type] moves, or null if it doesn't. */
    private fun converter(type: String, name: String): ((Float) -> Float)? = when {
        EFFECT_RATE[type] == name -> ::rate
        type in SYNC_MACHINES && SYNC.matches(name) -> ::sync
        else -> null
    }

    fun params(type: String, params: Map<String, Float>): Map<String, Float> =
        params.mapValues { (name, v) -> converter(type, name)?.invoke(v) ?: v }

    private fun slot(u: UnitSlot): UnitSlot = if (u.isEmpty) u else u.copy(params = params(u.type, u.params))

    private fun lane(convert: (Float) -> Float, lane: Lane): Lane =
        lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it else it.copy(value = convert(it.value)) })

    /** [song] with its rates on the seventeen, if it was saved with the eight. */
    fun song(song: Song): Song {
        if (song.version >= SONG_VERSION) return song
        val master = song.master.copy(sends = song.master.sends.map(::slot), inserts = song.master.inserts.map(::slot))
        val input = song.input.map(::slot)
        // A lane's unit is the track's machine, one of its effects, or a
        // send, a master insert or an input slot; see SongEdits.
        fun typeOf(track: Track, unit: String): String? {
            fun n(prefix: String) = unit.removePrefix(prefix).toIntOrNull()?.minus(1) ?: -1
            return when {
                unit == "machine" -> track.machine.type
                unit.startsWith("effect") -> track.effectAt(n("effect")).type
                unit.startsWith("send") -> master.sendAt(n("send")).type
                unit.startsWith("master") -> master.insertAt(n("master")).type
                unit.startsWith("input") -> input.getOrNull(n("input"))?.type
                else -> null
            }
        }
        val tracks = song.tracks.map { t ->
            t.copy(
                machine = t.machine.copy(params = params(t.machine.type, t.machine.params)),
                effects = t.effects.map(::slot),
                clips = t.clips.mapValues { (_, clip) ->
                    if (clip.automation.isEmpty()) clip
                    else clip.copy(automation = clip.automation.mapValues { (key, l) ->
                        val convert = typeOf(t, laneUnit(key))?.let { converter(it, laneParam(key)) }
                        if (convert == null) l else lane(convert, l)
                    })
                },
            )
        }
        return song.copy(version = SONG_VERSION, master = master, input = input, tracks = tracks)
    }

    /** A patch saved with the eight, on the seventeen. */
    fun patch(patch: Patch): Patch =
        if (patch.version >= PATCH_VERSION) patch
        else patch.copy(params = params(patch.machine.removePrefix("fx."), patch.params), version = PATCH_VERSION)
}
