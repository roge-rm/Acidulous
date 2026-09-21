package com.rm.acidulous.model

import java.io.File

/**
 * The document side of an audio track.
 *
 * A tape's material is not in `Machine.settings` like every other machine's
 * sample, because it is not one file for the machine - it is a window into a
 * file *per scene, per lane*, which is to say it belongs to the clips. So the
 * engine cannot be told about it by mounting a sample; it is told by a spec
 * built from the whole track, and that is what this file writes.
 */

/** How many takes sound together in one cell. Mirrors `kReelLanes`. */
const val BIAS_LANES = 4

/** The machine that plays them. Named once, since several places ask. */
const val BIAS_MACHINE = "Bias"

/** How long one take may be, which the engine refuses to exceed. */
const val BIAS_MAX_SECONDS = 300

/**
 * How many min/max pairs a take stores for drawing itself.
 *
 * Enough for a grid cell and no more: the shape is measured once, when the take
 * is made, and kept on the take so that scrolling the grid reads no files. See
 * `ui/TakePeaks`.
 */
const val TAKE_PEAK_COLUMNS = 40

private fun Track.audioLanes(sceneId: String): ClipAudio? =
    clips[sceneId]?.audio?.takeIf { !it.isEmpty }

/** True if this track holds any audio at all - what the sync loop asks first. */
fun Track.hasAudio(): Boolean =
    machine.type == BIAS_MACHINE && clips.values.any { it.audio?.isEmpty == false }

/**
 * What the engine is told, a line per region:
 *
 *     sceneId|lane|absPath|offset|frames|startTick|ticks|bpm|loop
 *
 * Absolute, because the engine has no root; scene *engine* ids, because that
 * is what the scheduler hands the machine back. A missing file is dropped
 * here rather than in the engine, so that a file which appears later is picked
 * up on the next sync rather than being remembered as loaded.
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
            append('\n')
        }
    }
}

/**
 * Peaks and labels are deliberately absent from the spec, which makes the spec
 * its own identity: `ensureReels` compares the string it is about to send and
 * sends nothing when it has not changed, so trimming one lane reloads that
 * rack and leaves the other fifteen alone, while renaming a take or filling in
 * its drawn shape re-decodes nothing. A five-minute file is not read again
 * because somebody typed a name.
 */

// --- Edits -------------------------------------------------------------------

/**
 * [take] in [lane] of this cell, or null to empty the lane.
 *
 * The list is rebuilt to four and then trimmed, so a take in lane 4 alone
 * still lands in lane 4 and a song that only ever used lane 1 stays one entry
 * long on disk.
 */
fun Clip.withTake(lane: Int, take: TakeRef?): Clip {
    if (lane !in 0 until BIAS_LANES) return this
    val lanes = MutableList<TakeRef?>(BIAS_LANES) { audio?.lane(it) }
    lanes[lane] = take
    val next = ClipAudio(lanes.dropLastWhile { it == null })
    return copy(audio = if (next.isEmpty) null else next)
}

/**
 * The cycle a cell covers: its bars, times the scene's repeat, in ticks.
 *
 * Not [clipLengthTicks], which is one pass. A take runs *through* the repeats
 * rather than starting again at each - which is the whole of decision 4 and
 * the reason `rackCycleTick` exists - so the cycle a region is measured
 * against has the repeat in it.
 */
fun Song.cycleTicks(sceneId: String, clip: Clip): Int {
    val scene = scenes.firstOrNull { it.id == sceneId } ?: return clip.bars * 4 * PPQN
    return clipLengthTicks(sceneId, clip) * scene.repeat.coerceAtLeast(1)
}

/** The tempo a scene actually plays at, which is what a recording is stamped with. */
fun Song.bpmOf(sceneId: String): Float =
    scenes.firstOrNull { it.id == sceneId }?.tempo?.bpm ?: tempo

/**
 * A whole file as one cell's take: the simple case, and the one an import is.
 *
 * [frames] is the file's own length and is asked of the engine by the caller,
 * because reading it means opening the file and this is a pure function.
 */
fun Song.takeForWholeFile(sceneId: String, clip: Clip, relative: String, frames: Int): TakeRef =
    TakeRef(
        file = relative, offset = 0, frames = frames,
        bpm = bpmOf(sceneId), ticks = cycleTicks(sceneId, clip),
    )

/** How many of this cell's four lanes hold something. */
fun Clip.audioLaneCount(): Int = audio?.lanes?.count { it != null } ?: 0

/**
 * Whether any take here was recorded at a tempo the scene does not play at.
 *
 * The same question `Freeze.stale` asks of a frozen clip, and worth asking for
 * the same reason: the audio is going to sound anyway, and the player is owed
 * the one fact that explains why it drifts away from the beat. Audio does not
 * stretch until M55.
 */
fun Song.takeTempoDiffers(sceneId: String, clip: Clip): Boolean {
    val bpm = bpmOf(sceneId)
    return clip.audio?.lanes?.any { it != null && kotlin.math.abs(it.bpm - bpm) > 0.05f } == true
}

/**
 * How long a take is, in ticks **of the tempo it was recorded at**.
 *
 * Which is the tempo that decides where it ends in a cell, because audio does
 * not stretch: a take sung at 100 bpm and dropped into a 140 bpm scene runs for
 * the same number of seconds and therefore fewer bars. The cell draws it to
 * this length, and a cell narrower than this is a take that is cut off by its
 * own cycle rather than trimmed.
 */
fun TakeRef.lengthTicks(): Int =
    (frames.toDouble() / ENGINE_RATE * (bpm / 60.0) * PPQN).toInt().coerceAtLeast(1)

// --- Splitting a take --------------------------------------------------------

/**
 * One boundary the recording crossed, as the engine stamped it.
 *
 * See `sequencer/CaptureMarks.h`: the audio thread wrote these down as it went,
 * so nothing here has to know how long a scene is, how many times it repeats,
 * or what tempo it was playing at. That is the whole reason the split is safe.
 */
data class Mark(
    val frame: Long, val sceneId: Long, val tick: Int, val cycleTicks: Int, val bpm: Float,
)

/** The engine's flat array of longs, five to a mark, as marks. */
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
 * A segment shorter than this is not a take, it is the edge of one.
 *
 * Crossing into a cell in the last moments of a recording leaves a sliver -
 * a few hundred frames of a held note - and a cell holding forty milliseconds
 * of somebody's last syllable is worse than a cell holding nothing.
 */
const val MIN_TAKE_FRAMES = ENGINE_RATE / 4

/**
 * Consecutive marks pair into segments, and each segment is one cell's take.
 *
 * **No audio is copied**: one file, N cells, each a window into it. The last
 * segment runs to [totalFrames], which is where the recording stopped.
 *
 * [sceneIdOf] turns the engine's hashed scene id back into the document's,
 * because the engine has never heard of the document's string ids. A mark for
 * a scene that has since been deleted is dropped rather than guessed at.
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
        // Last one wins: a scene crossed twice in one pass of the record button
        // is somebody going round again, and the second time is the keeper.
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
 * The amber "recorded at another tempo" marks exist to warn that a take will
 * drift. Following, it does not drift, so the warning is not a warning any
 * more - it is a fact about where the audio came from, and the place for that
 * is the lane's own name rather than a colour that means something is wrong.
 */
fun Track.followsTempo(): Boolean =
    machine.type == BIAS_MACHINE && (machine.params["stretch"] ?: 0f) >= 0.5f
