package com.rm.acidulous.model

import kotlin.math.floor

/**
 * Trinity had 12 waves (four shapes and eight tables) and Nexus's wtosc block
 * eight tables until the wavetable bank grew to 32. A choice is saved as a
 * fraction of its range, so these move each saved one (a knob, a lane's
 * points, a step lock) to the same wave on the longer list. Songs before
 * version 3 and patches before version 3 hold the old counts.
 *
 * Trinity's matrix and macro destinations then grew from 34 to 37, with the
 * LFO step ones at the end. Songs and patches before version 4 hold 34.
 *
 * The engine lines replayer does the same for `acidulous-engine 1` files
 * (engine/machine/TableCounts.h); keep the two rules the same.
 */
internal object TableMigration {
    /** The wavetable bank's tables (WavetableBank::kTables). */
    const val WAVETABLES = 32
    /** Four shapes and the bank's tables (Trinity::WaveCount). */
    const val TRINITY_WAVES = 4 + WAVETABLES
    private const val TRINITY_WAVES_BEFORE = 12
    private const val NEXUS_TABLES_BEFORE = 8
    /** Trinity::DestCount, and before the LFO step destinations. */
    const val TRINITY_DESTS = 37
    private const val TRINITY_DESTS_BEFORE = 34
    /** The first song and patch version with 32 tables, and with 37 destinations. */
    private const val TABLES_VERSION = 3
    private const val DESTS_VERSION = 4

    private val TRINITY_WAVE = setOf("o1_wave", "o2_wave", "o3_wave")
    private val FIRST_KNOB = Regex("s(\\d\\d)_p1")
    private val TRINITY_DEST = Regex("m\\d\\d_dest|mac[1-8]_d[1-4]")

    /** A Trinity wave saved over 12 steps, on today's count. */
    fun trinityWave(v: Float): Float {
        val step = floor(v.coerceIn(0f, 1f) * (TRINITY_WAVES_BEFORE - 1) + 0.5f).toInt()
        return step / (TRINITY_WAVES - 1).toFloat()
    }

    /** A Trinity destination saved over 34 steps, on today's count. */
    fun trinityDest(v: Float): Float {
        val step = floor(v.coerceIn(0f, 1f) * (TRINITY_DESTS_BEFORE - 1) + 0.5f).toInt()
        return step / (TRINITY_DESTS - 1).toFloat()
    }

    /** A Nexus wtosc table knob read over eight tables, on today's count: the middle of the same table's range. */
    fun nexusTable(v: Float): Float {
        val step = (v.coerceIn(0f, 0.9999f) * NEXUS_TABLES_BEFORE).toInt()
        return (step + 0.5f) / WAVETABLES
    }

    /** The slots of a Nexus patch's text that hold a wtosc. */
    private fun tableSlots(text: String?): Set<Int> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val f = line.split('|')
            if (f.size >= 3 && f[0] == "m" && f[2] == "wtosc") f[1].toIntOrNull() else null
        }.toSet()

    /** How [name] on a machine of [type], saved at [version], moves, or null if it doesn't. */
    private fun converter(type: String, settings: Map<String, String>, name: String, version: Int): ((Float) -> Float)? = when {
        version >= DESTS_VERSION -> null
        type == "Trinity" && TRINITY_DEST.matches(name) -> ::trinityDest
        version >= TABLES_VERSION -> null
        type == "Trinity" -> if (name in TRINITY_WAVE) ::trinityWave else null
        type == "Nexus" -> FIRST_KNOB.matchEntire(name)?.groupValues?.get(1)?.toInt()
            ?.takeIf { it in tableSlots(settings["nexus"]) }?.let { ::nexusTable }
        else -> null
    }

    private fun params(type: String, settings: Map<String, String>, params: Map<String, Float>, version: Int): Map<String, Float> =
        params.mapValues { (name, v) -> converter(type, settings, name, version)?.invoke(v) ?: v }

    /** The patch text marked as holding the new count. */
    private fun settings(type: String, settings: Map<String, String>, version: Int): Map<String, String> {
        val text = settings["nexus"]
        if (version >= TABLES_VERSION || type != "Nexus" || text == null || !text.startsWith("v|1\n")) return settings
        return settings + ("nexus" to "v|2\n" + text.removePrefix("v|1\n"))
    }

    private fun machine(m: Machine, version: Int): Machine =
        m.copy(params = params(m.type, m.settings, m.params, version), settings = settings(m.type, m.settings, version))

    /**
     * [song]'s waves, tables and destinations on today's counts, from the
     * version it was saved at. Its version is left for the caller to set. A freeze that matched the track before still
     * matches after: the rescaled values are the same sound, so its saved
     * hashes are moved along with them ([Freeze.voiceOf], [Freeze.contentOf]).
     */
    fun song(song: Song): Song = song.copy(tracks = song.tracks.map { t ->
        val moved = t.copy(
            machine = machine(t.machine, song.version),
            clips = t.clips.mapValues { (_, clip) ->
                if (clip.automation.isEmpty()) clip
                else clip.copy(automation = clip.automation.mapValues { (key, lane) ->
                    val convert = if (laneUnit(key) == "machine") converter(t.machine.type, t.machine.settings, laneParam(key), song.version) else null
                    if (convert == null) lane
                    else lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it else it.copy(value = convert(it.value)) })
                })
            },
        )
        if (moved == t) t else refreshFreezes(song, t, moved)
    })

    /** [after]'s frozen clips still marked as matching, if they matched [before]. */
    internal fun refreshFreezes(song: Song, before: Track, after: Track): Track {
        val voiceBefore = Freeze.voiceOf(before)
        val voiceAfter = Freeze.voiceOf(after)
        return after.copy(clips = after.clips.mapValues { (id, clip) ->
            val f = clip.frozen ?: return@mapValues clip
            val old = before.clips[id] ?: return@mapValues clip
            val voice = if (f.voice == voiceBefore) voiceAfter else f.voice
            val content = if (f.content != 0 && f.content == Freeze.contentOf(song, before, old)) Freeze.contentOf(song, after, clip) else f.content
            clip.copy(frozen = f.copy(voice = voice, content = content))
        })
    }

    /** A patch's waves, tables and destinations on today's counts. Its version is left for the caller to set. */
    fun patch(patch: Patch): Patch = patch.copy(
        params = params(patch.machine, patch.settings, patch.params, patch.version),
        settings = settings(patch.machine, patch.settings, patch.version),
    )
}
