package com.rm.acidulous.model

/**
 * The Delay's `pingpong` switch (off, on) became `mode` (normal, ping-pong,
 * multi-tap, reverse), saved as a fraction over four steps. An old 1.0 would
 * read as reverse, so every saved `pingpong` moves to `mode` as a knob, a
 * lane's points or a step lock, a lane's address and a controller mapping.
 * Only the Delay ever had a param called `pingpong`, so a name is enough to
 * find one. Songs and patches before version 6 hold the switch.
 */
internal object DelayMigration {
    /** The first song and patch version with `mode`. */
    const val VERSION = 6

    private const val OLD = "pingpong"
    private const val NEW = "mode"

    /** Off is normal, on is ping-pong. */
    fun mode(v: Float): Float = if (v >= 0.5f) 1f / 3f else 0f

    private fun params(params: Map<String, Float>): Map<String, Float> {
        val v = params[OLD] ?: return params
        return params - OLD + (NEW to mode(v))
    }

    private fun slot(u: UnitSlot): UnitSlot = if (u.type == "Delay" && OLD in u.params) u.copy(params = params(u.params)) else u

    private fun automation(a: Map<String, Lane>): Map<String, Lane> {
        if (a.keys.none { laneParam(it) == OLD }) return a
        val out = LinkedHashMap<String, Lane>()
        for ((key, lane) in a) {
            if (laneParam(key) != OLD) { out[key] = lane; continue }
            out[laneKey(laneUnit(key), NEW)] = lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it else it.copy(value = mode(it.value)) })
        }
        return out
    }

    /** [song] with the switch as a mode. Its version is left for the caller to set. */
    fun song(song: Song): Song {
        if (song.version >= VERSION) return song
        return song.copy(
            master = song.master.copy(sends = song.master.sends.map(::slot), inserts = song.master.inserts.map(::slot)),
            input = song.input.map(::slot),
            mappings = song.mappings.map { if (it.name == OLD) it.copy(name = NEW) else it },
            tracks = song.tracks.map { t ->
                t.copy(
                    effects = t.effects.map(::slot),
                    clips = t.clips.mapValues { (_, clip) -> if (clip.automation.isEmpty()) clip else clip.copy(automation = automation(clip.automation)) },
                )
            },
        )
    }

    /** A Delay patch with the switch as a mode. Its version is left for the caller to set. */
    fun patch(patch: Patch): Patch =
        if (patch.version >= VERSION || patch.machine != "fx.Delay" || OLD !in patch.params) patch
        else patch.copy(params = params(patch.params))
}
