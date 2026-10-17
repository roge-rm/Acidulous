package com.rm.acidulous.model

import kotlin.math.floor

/**
 * Trinity had 12 waves (four shapes and eight tables) and Nexus's wtosc block
 * eight tables until the wavetable bank grew to 32. A choice is saved as a
 * fraction of its range, so these move each saved one (a knob, a lane's
 * points, a step lock) to the same wave on the longer list. Songs before
 * version 3 and patches before version 3 hold the old counts.
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

    private val TRINITY_WAVE = setOf("o1_wave", "o2_wave", "o3_wave")
    private val FIRST_KNOB = Regex("s(\\d\\d)_p1")

    /** A Trinity wave saved over 12 steps, on today's count. */
    fun trinityWave(v: Float): Float {
        val step = floor(v.coerceIn(0f, 1f) * (TRINITY_WAVES_BEFORE - 1) + 0.5f).toInt()
        return step / (TRINITY_WAVES - 1).toFloat()
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

    /** How [name] on a machine of [type] moves, or null if it doesn't. */
    private fun converter(type: String, settings: Map<String, String>, name: String): ((Float) -> Float)? = when (type) {
        "Trinity" -> if (name in TRINITY_WAVE) ::trinityWave else null
        "Nexus" -> FIRST_KNOB.matchEntire(name)?.groupValues?.get(1)?.toInt()
            ?.takeIf { it in tableSlots(settings["nexus"]) }?.let { ::nexusTable }
        else -> null
    }

    private fun params(type: String, settings: Map<String, String>, params: Map<String, Float>): Map<String, Float> =
        params.mapValues { (name, v) -> converter(type, settings, name)?.invoke(v) ?: v }

    /** The patch text marked as holding the new count. */
    private fun settings(type: String, settings: Map<String, String>): Map<String, String> {
        val text = settings["nexus"]
        if (type != "Nexus" || text == null || !text.startsWith("v|1\n")) return settings
        return settings + ("nexus" to "v|2\n" + text.removePrefix("v|1\n"))
    }

    private fun machine(m: Machine): Machine =
        m.copy(params = params(m.type, m.settings, m.params), settings = settings(m.type, m.settings))

    /**
     * [song]'s waves and tables on today's counts. Its version is left for
     * the caller to set. A freeze that matched the track before still
     * matches after: the rescaled values are the same sound, so its saved
     * hashes are moved along with them ([Freeze.voiceOf], [Freeze.contentOf]).
     */
    fun song(song: Song): Song = song.copy(tracks = song.tracks.map { t ->
        val moved = t.copy(
            machine = machine(t.machine),
            clips = t.clips.mapValues { (_, clip) ->
                if (clip.automation.isEmpty()) clip
                else clip.copy(automation = clip.automation.mapValues { (key, lane) ->
                    val convert = if (laneUnit(key) == "machine") converter(t.machine.type, t.machine.settings, laneParam(key)) else null
                    if (convert == null) lane
                    else lane.copy(points = lane.points.map { if (it.value == LANE_BASE) it else it.copy(value = convert(it.value)) })
                })
            },
        )
        if (moved == t) t else refreshFreezes(song, t, moved)
    })

    private fun refreshFreezes(song: Song, before: Track, after: Track): Track {
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

    /** A patch's waves and tables on today's counts. Its version is left for the caller to set. */
    fun patch(patch: Patch): Patch =
        patch.copy(params = params(patch.machine, patch.settings, patch.params), settings = settings(patch.machine, patch.settings))
}
