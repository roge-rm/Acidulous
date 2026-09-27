package com.rm.acidulous.model

import com.rm.acidulous.io.*


/**
 * The song side of an audio track.
 *
 * A tape's audio isn't in `Machine.settings` like other machines' samples. Each
 * clip holds its own window into a file, per scene and per lane, so the engine
 * gets a spec built from the whole track instead. This file writes that spec.
 */

/** How many takes sound together in one cell. Mirrors `kReelLanes`. */
const val BIAS_LANES = 4

/** The machine that plays them. */
const val BIAS_MACHINE = "Bias"

/** The longest a take can be. The engine won't go past it. */
const val BIAS_MAX_SECONDS = 300

/**
 * How many min/max pairs a take stores for drawing itself.
 *
 * Enough for a grid cell. The shape is measured once when the take is made and
 * kept on the take, so scrolling the grid reads no files. See `ui/TakePeaks`.
 */
const val TAKE_PEAK_COLUMNS = 40

private fun Track.audioLanes(sceneId: String): ClipAudio? =
    clips[sceneId]?.audio?.takeIf { !it.isEmpty }

/** True if this track holds any audio at all. The sync loop asks this first. */
fun Track.hasAudio(): Boolean =
    machine.type == BIAS_MACHINE && clips.values.any { it.audio?.isEmpty == false }

/**
 * What the engine is told, one line per region:
 *
 *     sceneId|lane|absPath|offset|frames|startTick|ticks|bpm|loop|fadeIn|fadeOut
 *
 * Paths are absolute because the engine has no root, and scene ids are engine
 * ids because that's what the scheduler hands back. Missing files are skipped
 * here rather than in the engine, so a file that turns up later gets loaded on
 * the next sync.
 */
fun reelSpec(song: Song, track: Track, root: File): String = buildString {
    for (scene in song.scenes) {
        val audio = track.audioLanes(scene.id) ?: continue
        for (lane in 0 until BIAS_LANES) {
            val take = audio.lane(lane) ?: continue
            val file = File(root, take.file)
            if (!file.isFile) continue
            append(scene.engineId).append('|').append(lane).append('|')
            append(file.absolutePath).append('|')
            append(take.offset).append('|').append(take.frames).append('|')
            append(take.startTick).append('|').append(take.ticks).append('|')
            append(take.bpm).append('|').append(if (take.loop) '1' else '0')
            append('|').append(take.fadeIn).append('|').append(take.fadeOut)
            append('\n')
        }
    }
}

/**
 * Peaks and labels are left out of the spec on purpose. `ensureReels` sends
 * nothing when the spec hasn't changed, so trimming one lane reloads only that
 * rack, and renaming a take or filling in its shape doesn't decode anything.
 */

// --- Edits -------------------------------------------------------------------

/**
 * [take] in [lane] of this cell, or null to empty the lane.
 *
 * The list is filled out to four and then trimmed, so a take in lane 4 alone
 * stays in lane 4 and a song that only uses lane 1 saves one entry.
 */
fun Clip.withTake(lane: Int, take: TakeRef?): Clip {
    if (lane !in 0 until BIAS_LANES) return this
    val lanes = MutableList<TakeRef?>(BIAS_LANES) { audio?.lane(it) }
    lanes[lane] = take
    val next = ClipAudio(lanes.dropLastWhile { it == null })
    return copy(audio = if (next.isEmpty) null else next)
}

/**
 * The cycle a cell covers in ticks: its bars times the scene's repeat.
 *
 * Not [clipLengthTicks], which is one pass. A take plays through the repeats
 * instead of restarting on each one (that's why `rackCycleTick` exists), so the
 * cycle has to include the repeat.
 */
fun Song.cycleTicks(sceneId: String, clip: Clip): Int {
    val scene = scenes.firstOrNull { it.id == sceneId } ?: return clip.bars * 4 * PPQN
    return clipLengthTicks(sceneId, clip) * scene.repeat.coerceAtLeast(1)
}

/** The tempo a scene actually plays at. Recordings are stamped with this. */
fun Song.bpmOf(sceneId: String): Float =
    scenes.firstOrNull { it.id == sceneId }?.tempo?.bpm ?: tempo

/**
 * A whole file as one cell's take, as on import.
 *
 * The caller gets [frames] from the engine, since reading it means opening the
 * file and this is a pure function. [bpm] is the file's own tempo if the caller
 * found one (a loop's, from `NativeEngine.loopShape`), otherwise the scene's.
 */
fun Song.takeForWholeFile(sceneId: String, clip: Clip, relative: String, frames: Int, bpm: Float? = null): TakeRef =
    TakeRef(
        file = relative, offset = 0, frames = frames,
        bpm = bpm ?: bpmOf(sceneId), ticks = cycleTicks(sceneId, clip),
    )

/** A loop's tempo from `NativeEngine.loopShape`, or null if it couldn't tell. */
fun loopTempo(shape: Pair<Float, Float>?): Float? =
    shape?.takeIf { it.first > 0f && it.second > 0f }?.let { (bars, seconds) -> bars * 4f * 60f / seconds }

/** How many of this cell's four lanes hold something. */
fun Clip.audioLaneCount(): Int = audio?.lanes?.count { it != null } ?: 0

/**
 * Whether any take here was recorded at a tempo the scene doesn't play at.
 *
 * Same check as `Freeze.stale`: the audio still plays, so the player should be
 * told why it drifts off the beat.
 */
fun Song.takeTempoDiffers(sceneId: String, clip: Clip): Boolean {
    val bpm = bpmOf(sceneId)
    return clip.audio?.lanes?.any { it != null && kotlin.math.abs(it.bpm - bpm) > 0.05f } == true
}

/**
 * How long a take is, in ticks at the tempo it was recorded at.
 *
 * Played at its own speed, a take recorded at 100 bpm in a 140 bpm scene lasts
 * the same number of seconds and so fewer bars. The cell draws it at this
 * length, and if the cell is shorter the take gets cut off by its cycle.
 */
fun TakeRef.lengthTicks(): Int =
    (frames.toDouble() / ENGINE_RATE * (bpm / 60.0) * PPQN).toInt().coerceAtLeast(1)

// --- Splitting a take --------------------------------------------------------

/**
 * One boundary the recording crossed, as the engine stamped it.
 *
 * See `sequencer/CaptureMarks.h`. The audio thread writes these as it goes, so
 * nothing here needs to know scene lengths, repeats or tempo. That's what makes
 * the split safe.
 */
data class Mark(
    val frame: Long, val sceneId: Long, val tick: Int, val cycleTicks: Int, val bpm: Float,
)

/** Turns the engine's flat array of longs, five per mark, into marks. */
fun marksFrom(raw: LongArray, count: Int): List<Mark> = (0 until count).map { i ->
    Mark(
        frame = raw[i * 5],
        sceneId = raw[i * 5 + 1],
        tick = raw[i * 5 + 2].toInt(),
        cycleTicks = raw[i * 5 + 3].toInt(),
        bpm = raw[i * 5 + 4] / 1000f,
    )
}

/**
 * Segments shorter than this are dropped as leftover edges.
 *
 * Crossing into a cell right at the end of a recording leaves a sliver of a
 * few hundred frames, and an empty cell is better than one holding that.
 */
const val MIN_TAKE_FRAMES = ENGINE_RATE / 4

/**
 * Pairs consecutive marks into segments, one per cell.
 *
 * No audio is copied: one file, several cells, each a window into it. The last
 * segment runs to [totalFrames], where the recording stopped.
 *
 * [sceneIdOf] maps the engine's hashed scene id back to the song's string id.
 * Marks for a scene that's since been deleted are dropped.
 */
fun splitTake(
    marks: List<Mark>,
    totalFrames: Long,
    file: String,
    sceneIdOf: (Long) -> String?,
): Map<String, TakeRef> {
    val out = LinkedHashMap<String, TakeRef>()
    for ((i, m) in marks.withIndex()) {
        val end = if (i + 1 < marks.size) marks[i + 1].frame else totalFrames
        val frames = (end - m.frame).toInt()
        if (frames < MIN_TAKE_FRAMES) continue
        val sceneId = sceneIdOf(m.sceneId) ?: continue
        // Last one wins: if a scene is crossed twice in one recording, the
        // second pass is the one to keep.
        out[sceneId] = TakeRef(
            file = file, offset = m.frame.toInt(), frames = frames,
            bpm = m.bpm, ticks = m.cycleTicks, startTick = m.tick,
        )
    }
    return out
}

/**
 * Whether this track's takes follow the song's tempo.
 *
 * When they do they don't drift, so the amber "recorded at another tempo"
 * warning doesn't apply. The lane's name shows it instead.
 */
fun Track.followsTempo(): Boolean =
    machine.type == BIAS_MACHINE && (machine.params["stretch"] ?: 0f) >= 0.5f
