package com.rm.acidulous.model

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Filament's sympathetic bank came out at 0.2 of its level and comes out at
 * 3 now (kSymOut in Filament.cpp). The level sets both what the bank is fed
 * and what it gives back, so a level saved before is scaled by the square
 * root of 0.2 / 3 to sound the same: `symlevel`, which is added in at its
 * old default when a song or patch never set it, and the depth of every
 * matrix slot aimed at the bank, each as a knob, a lane's points or a step
 * lock. Songs and patches before version 5 hold the old level.
 *
 * The engine lines replayer does the same for files before
 * `acidulous-engine 4` (engine/machine/filament/SymLevel.h); keep the two
 * rules the same.
 */
internal object FilamentMigration {
    /** The first song and patch version with the louder bank. */
    const val VERSION = 5

    /** What a level saved before is multiplied by. */
    val SCALE = sqrt(0.2f / 3f)

    private const val LEVEL = "symlevel"
    /** `symlevel`'s default, normalised (0..1 over 0..1). */
    private const val LEVEL_DEFAULT = 0.35f
    /** Filament's matrix: eight slots, 18 destinations, the bank's is 12. */
    private const val SLOTS = 8
    private const val DESTS = 18
    private const val DST_SYMPATHETIC = 12
    private val DEPTH = Regex("m([1-8])_amt")

    /** A saved `symlevel` on today's bank. */
    fun level(v: Float): Float = v * SCALE

    /** A saved matrix depth, normalised over -1..1, on today's bank. */
    fun depth(v: Float): Float = ((v * 2f - 1f) * SCALE + 1f) / 2f

    /** Whether matrix slot [slot] (1..8) of [params] is aimed at the bank. */
    private fun aimsAtBank(params: Map<String, Float>, slot: Int): Boolean {
        val v = params["m${slot}_dst"] ?: return false
        return floor(v.coerceIn(0f, 1f) * (DESTS - 1) + 0.5f).toInt() == DST_SYMPATHETIC
    }

    private fun converter(params: Map<String, Float>, name: String): ((Float) -> Float)? {
        if (name == LEVEL) return ::level
        val slot = DEPTH.matchEntire(name)?.groupValues?.get(1)?.toInt() ?: return null
        return if (slot in 1..SLOTS && aimsAtBank(params, slot)) ::depth else null
    }

    fun params(params: Map<String, Float>): Map<String, Float> {
        val moved = params.mapValues { (name, v) -> converter(params, name)?.invoke(v) ?: v }
        return if (LEVEL in moved) moved else moved + (LEVEL to level(LEVEL_DEFAULT))
    }

    /** [song]'s Filaments on today's bank. Its version is left for the caller to set. */
    fun song(song: Song): Song {
        if (song.version >= VERSION) return song
        return song.copy(tracks = song.tracks.map { t ->
            if (t.machine.type != "Filament") return@map t
            val params = t.machine.params
            val moved = t.copy(
                machine = t.machine.copy(params = params(params)),
                clips = t.clips.mapValues { (_, clip) ->
                    if (clip.automation.isEmpty()) clip
                    else clip.copy(automation = clip.automation.mapValues { (key, lane) ->
                        val convert = if (laneUnit(key) == "machine") converter(params, laneParam(key)) else null
                        if (convert == null) lane
                        else lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it else it.copy(value = convert(it.value)) })
                    })
                },
            )
            TableMigration.refreshFreezes(song, t, moved)
        })
    }

    /** A Filament patch on today's bank. Its version is left for the caller to set. */
    fun patch(patch: Patch): Patch =
        if (patch.version >= VERSION || patch.machine != "Filament") patch
        else patch.copy(params = params(patch.params))
}
