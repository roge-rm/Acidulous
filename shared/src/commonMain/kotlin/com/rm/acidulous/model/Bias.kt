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
 * A lane with a stack of takes follows its region line with a line per take
 * after the first and a line per piece:
 *
 *     take|sceneId|lane|absPath|offset
 *     piece|sceneId|lane|start|end|take
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
            appendRegion(scene.engineId, lane, take, root)
        }
    }
}

/**
 * [take]'s lines in a reel spec as lane [lane] of cell [sceneId]. Nothing if
 * its file is missing.
 */
internal fun StringBuilder.appendRegion(sceneId: Long, lane: Int, take: TakeRef, root: File) {
    val file = File(root, take.file)
    if (!file.isFile) return
    append(sceneId).append('|').append(lane).append('|')
    append(file.absolutePath).append('|')
    append(take.offset).append('|').append(take.frames).append('|')
    append(take.startTick).append('|').append(take.ticks).append('|')
    append(take.bpm).append('|').append(if (take.loop) '1' else '0')
    append('|').append(take.fadeIn).append('|').append(take.fadeOut)
    append('\n')
    // A stack: the region line was take 0, then the rest in order and
    // the pieces. A take whose file is missing is still sent, so the
    // pieces' take numbers line up, and the engine plays it as a gap.
    for (t in take.takes.drop(1)) {
        append("take|").append(sceneId).append('|').append(lane).append('|')
        append(File(root, t.file).absolutePath).append('|').append(t.offset).append('\n')
    }
    for (p in take.pieces) {
        append("piece|").append(sceneId).append('|').append(lane).append('|')
        append(p.start).append('|').append(p.end).append('|').append(p.take).append('\n')
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
 * What `NativeEngine.loopShape` found in a file: how many bars it probably is
 * (0 if no whole number fits), how long they are and where they start, both
 * in seconds.
 */
data class LoopShape(val bars: Float, val seconds: Float, val start: Float)

/**
 * A whole file as one cell's take, as on import.
 *
 * The caller gets [frames] from the engine, since reading it means opening the
 * file and this is a pure function. [bpm] is the file's own tempo if the caller
 * found one, otherwise the scene's.
 */
fun Song.takeForWholeFile(sceneId: String, clip: Clip, relative: String, frames: Int, bpm: Float? = null): TakeRef =
    TakeRef(
        file = relative, offset = 0, frames = frames,
        bpm = bpm ?: bpmOf(sceneId), ticks = cycleTicks(sceneId, clip),
    )

/**
 * A file as one cell's take, looping if [shape] found it's a loop.
 *
 * A loop's take is just its bars, from the first hit to the end of the last
 * bar, so it repeats on the grid at the tempo [loopTempo] gives. Room before
 * the first hit and ringing past the last bar are left out, since a take
 * stops at its end and the next pass starts on the first hit. Anything else
 * is the whole file at the scene's tempo.
 */
fun Song.takeForFile(sceneId: String, clip: Clip, relative: String, frames: Int, shape: LoopShape?): TakeRef {
    val bpm = loopTempo(shape) ?: return takeForWholeFile(sceneId, clip, relative, frames)
    val offset = kotlin.math.round(shape!!.start * ENGINE_RATE).toInt().coerceIn(0, (frames - 1).coerceAtLeast(0))
    val length = kotlin.math.round(shape.seconds * ENGINE_RATE).toInt().coerceIn(1, (frames - offset).coerceAtLeast(1))
    return takeForWholeFile(sceneId, clip, relative, frames, bpm).copy(offset = offset, frames = length, loop = true)
}

/** A loop's tempo from `NativeEngine.loopShape`, or null if it couldn't tell. */
fun loopTempo(shape: LoopShape?): Float? =
    shape?.takeIf { it.bars > 0f && it.seconds > 0f }?.let { it.bars * 4f * 60f / it.seconds }

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
 * Pairs consecutive marks into segments, one per pass through a cell, in the
 * order they were recorded.
 *
 * No audio is copied: one file, several cells, each a window into it. The last
 * segment runs to [totalFrames], where the recording stopped. A scene that
 * loops gives a segment per pass, and each one goes on that cell's stack (see
 * [Clip.withRecorded]).
 *
 * [sceneIdOf] maps the engine's hashed scene id back to the song's string id.
 * Marks for a scene that's since been deleted are dropped.
 */
fun splitTake(
    marks: List<Mark>,
    totalFrames: Long,
    file: String,
    sceneIdOf: (Long) -> String?,
): List<Pair<String, TakeRef>> {
    val out = ArrayList<Pair<String, TakeRef>>()
    for ((i, m) in marks.withIndex()) {
        val end = if (i + 1 < marks.size) marks[i + 1].frame else totalFrames
        val frames = (end - m.frame).toInt()
        if (frames < MIN_TAKE_FRAMES) continue
        val sceneId = sceneIdOf(m.sceneId) ?: continue
        out += sceneId to TakeRef(
            file = file, offset = m.frame.toInt(), frames = frames,
            bpm = m.bpm, ticks = m.cycleTicks, startTick = m.tick,
        )
    }
    return out
}

// --- Take stacks -------------------------------------------------------------

/**
 * The lane's takes, take 0 first. A one-take lane is a stack of one covering
 * the whole region.
 */
fun TakeRef.stack(): List<StackTake> = takes.ifEmpty { listOf(StackTake(file, offset, 0, frames)) }

/** How many takes the lane holds. */
val TakeRef.takeCount: Int get() = takes.size.coerceAtLeast(1)

/** The take heard over the most of the lane: 0 for a one-take lane. */
fun TakeRef.heardTake(): Int {
    if (pieces.isEmpty()) return 0
    return pieces.groupBy { it.take }
        .maxByOrNull { (_, ps) -> ps.sumOf { (it.end - it.start).toLong() } }?.key ?: 0
}

/** Take [k] heard over the whole lane, as far as it has audio. */
fun TakeRef.hearing(k: Int): TakeRef {
    if (takes.isEmpty()) return this
    val t = takes.getOrNull(k) ?: return this
    return copy(pieces = listOf(Piece(t.start, t.end, k)))
}

/** These pieces with take [take] laid over [start, end), cutting what was under it. */
fun List<Piece>.overlay(start: Int, end: Int, take: Int): List<Piece> {
    val out = ArrayList<Piece>()
    for (p in this) {
        if (p.end <= start || p.start >= end) { out += p; continue }
        if (p.start < start) out += p.copy(end = start)
        if (p.end > end) out += p.copy(start = end)
    }
    if (end > start) out += Piece(start, end, take)
    return out.sortedBy { it.start }
}

/**
 * Whether [pass] can go on this lane's stack: recorded against the same
 * cycle at the same tempo, and neither is a loop dropped in from a file.
 */
fun TakeRef.canStack(pass: TakeRef): Boolean =
    !loop && !pass.loop && ticks == pass.ticks && kotlin.math.abs(bpm - pass.bpm) <= 0.05f

/**
 * [pass] added to the top of this lane's stack and heard over the stretch it
 * covers. The rest of the lane keeps playing what it did, so a pass stopped
 * half way replaces only the half it has.
 *
 * The region grows to cover both if the pass starts earlier or runs later,
 * and every take and piece moves with it so nothing shifts in time.
 */
fun TakeRef.stacked(pass: TakeRef): TakeRef {
    val perTick = ENGINE_RATE * 60.0 / (bpm.toDouble() * PPQN)
    val from = minOf(startTick, pass.startTick)
    val shift = kotlin.math.round((startTick - from) * perTick).toInt()
    val passAt = kotlin.math.round((pass.startTick - from) * perTick).toInt()
    val length = maxOf(shift + frames, passAt + pass.frames)
    val old = stack().map { it.copy(offset = it.offset - shift, start = it.start + shift, end = it.end + shift) }
    val heard = pieces.ifEmpty { listOf(Piece(0, frames, 0)) }
        .map { it.copy(start = it.start + shift, end = it.end + shift) }
    val k = old.size
    val added = StackTake(pass.file, pass.offset - passAt, passAt, passAt + pass.frames)
    return copy(
        file = old[0].file, offset = old[0].offset, frames = length, startTick = from,
        takes = old + added,
        pieces = heard.overlay(added.start, added.end, k),
    )
}

/**
 * A pass recorded onto [lane]: on top of what's there if it can stack,
 * otherwise in its place as before.
 */
fun Clip.withRecorded(lane: Int, pass: TakeRef): Clip {
    val held = audio?.lane(lane)
    return withTake(lane, if (held != null && held.canStack(pass)) held.stacked(pass) else pass)
}

/**
 * This lane after a trim, given what it was. A one-take lane needs nothing.
 * A stack keeps every take and piece on the same audio: the region's start
 * moved by the same frames in each take.
 */
fun TakeRef.reframed(old: TakeRef): TakeRef {
    if (takes.isEmpty()) return this
    val d = offset - old.offset
    val moved = takes.map {
        it.copy(offset = it.offset + d, start = (it.start - d).coerceIn(0, frames), end = (it.end - d).coerceIn(0, frames))
    }
    val cut = pieces.mapNotNull { p ->
        val s = (p.start - d).coerceIn(0, frames)
        val e = (p.end - d).coerceIn(0, frames)
        if (e > s) p.copy(start = s, end = e) else null
    }
    // An empty piece list would play take 0 whole, so a trim that leaves no
    // piece keeps an empty one, which the engine plays as silence.
    return copy(takes = moved, pieces = cut.ifEmpty { listOf(Piece(0, 0, 0)) })
}

/** How far back a stacked lane's head can be dragged: to the earliest audio in it. */
fun TakeRef.headRoom(): Int = if (takes.isEmpty()) offset else takes.minOf { it.start }.coerceAtLeast(0)

/** How long a stacked lane can be: to the latest audio in it. Null for a one-take lane. */
fun TakeRef.stackLength(): Int? = if (takes.isEmpty()) null else takes.maxOf { it.end }

/**
 * Whether this track's takes follow the song's tempo.
 *
 * When they do they don't drift, so the amber "recorded at another tempo"
 * warning doesn't apply. The lane's name shows it instead.
 */
fun Track.followsTempo(): Boolean =
    machine.type == BIAS_MACHINE && (machine.params["stretch"] ?: 0f) >= 0.5f
