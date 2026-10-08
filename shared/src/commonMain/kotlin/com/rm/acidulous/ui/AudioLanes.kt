package com.rm.acidulous.ui

import com.rm.acidulous.io.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.ENGINE_RATE
import com.rm.acidulous.model.audioLaneCount
import com.rm.acidulous.model.cycleTicks
import com.rm.acidulous.model.bpmOf
import com.rm.acidulous.model.emptyClipFor
import com.rm.acidulous.model.loopTempo
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.BIAS_LANES
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.BIAS_MACHINE
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.samplesInUse
import com.rm.acidulous.model.takeForWholeFile
import com.rm.acidulous.model.withTake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rm.acidulous.model.lengthTicks
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * The four-track editor: four lanes under one ruler, across this cell's cycle.
 *
 * The axis is the whole cycle, not one pass of the clip. A tape plays straight
 * through a scene's repeats (that's what `SceneScheduler::rackCycleTick` is
 * for), so drawing against `clip.bars` would hide part of what the cell
 * plays. Every tick in here is a tick of the cycle.
 *
 * On each lane you can:
 *
 *   - set where it starts, by dragging its body, snapped to the clip's grid;
 *   - trim its start and end, by dragging either end, which trims the
 *     recording without moving the audio;
 *   - mute it, which is a machine parameter and so automates and records
 *     like any other.
 *
 * Nothing is written until the finger lifts. The reel is compared using a
 * string built from these numbers, so writing every frame of a drag would
 * decode the take every frame, which is far too slow for a long take.
 */
private val GutterW = 34.dp
private val RulerH = 16.dp
private val HandleGrab = 22.dp

/**
 * Which lane the next recording goes onto, if any.
 *
 * There's only one capture in the engine, so only one lane records at a time
 * (see `Engine::armedRack`). Arming a second lane disarms the first instead
 * of refusing.
 *
 * Only kept for the session, not saved as a preference.
 */
object BiasArm {
    var track by mutableStateOf(-1)
        private set
    var lane by mutableStateOf(-1)
        private set

    fun armed(t: Int, l: Int): Boolean = track == t && lane == l
    val any: Boolean get() = track >= 0 && lane >= 0

    fun arm(t: Int, l: Int) {
        val off = armed(t, l)
        track = if (off) -1 else t
        lane = if (off) -1 else l
        NativeEngine.armCapture(track)
    }

    fun clear() {
        track = -1
        lane = -1
        NativeEngine.armCapture(-1)
    }
}

/** Which part of which lane a finger is holding. */
private data class LaneDrag(val lane: Int, val part: Part, val take: TakeRef) {
    enum class Part { Body, Head, Tail, FadeIn, FadeOut }
}

@Composable
fun AudioLanes(
    clip: Clip,
    ticksPerBar: Int,
    /** Bars times the scene's repeat, in ticks: everything this cell plays. */
    cycleTicks: Int,
    /** Where the rack is in that cycle, or null when it isn't playing this cell. */
    playheadTick: () -> Int?,
    trackIndex: Int,
    sceneId: String,
    editor: SongEditor,
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    val total = cycleTicks.coerceAtLeast(1)
    val root = EngineSync.sampleRoot
    var drag by remember { mutableStateOf<LaneDrag?>(null) }
    var picking by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    // The mute is the machine's own parameter, the same one as in the panel
    // below, so automating, mapping and recording it all work here. The
    // binding is built here because these lanes are the only UI for a tape.
    val info = remember { NativeEngine.machineParamInfo(BIAS_MACHINE) }
    val b = rememberParamBinding(trackIndex, BIAS_MACHINE, info, editor)
    val commit: (Int, TakeRef?) -> Unit = { lane, take ->
        editor.editClip(trackIndex, sceneId) { c2 -> c2.withTake(lane, take) }
    }

    Column(modifier.background(c.bgDeep)) {
        // The ruler: bar numbers along the cycle, so a repeat shows as a
        // second pass rather than a longer bar.
        Row(Modifier.fillMaxWidth().height(RulerH)) {
            Box(Modifier.width(GutterW))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    val bars = (total + ticksPerBar - 1) / ticksPerBar
                    for (b in 0 until bars) {
                        val x = size.width * (b.toFloat() * ticksPerBar) / total
                        drawLine(if (b == 0) c.raised else c.raised.copy(alpha = 0.6f),
                            Offset(x, size.height * 0.4f), Offset(x, size.height), 1f)
                    }
                }
            }
        }
        for (lane in 0 until BIAS_LANES) {
            val stored = clip.audio?.lane(lane)
            // While a finger is down the lane draws the drag, which hasn't
            // been written to the song yet.
            val take = if (drag?.lane == lane) drag?.take else stored
            val shape = TakePeaks.rememberShape(root, take?.file.orEmpty())
            val isMuted = (b.value("mute${lane + 1}") ?: 0f) >= 0.5f
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The gutter is two buttons. The top half is the lane number
                // and mutes it, the bottom half is the record dot and arms it.
                // You need both while a song is playing, so neither is hidden.
                val isArmed = BiasArm.armed(trackIndex, lane)
                Column(Modifier.width(GutterW).fillMaxHeight()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isMuted) c.card else c.control)
                            .clickable { b.set("mute${lane + 1}", if (isMuted) 0f else 1f) }
                            .button(stringResource(Res.string.a11y_lane_mute, lane + 1), stringResource(if (isMuted) Res.string.a11y_on else Res.string.a11y_off)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isMuted) stringResource(Res.string.tape_muted_short) else "${lane + 1}",
                            color = if (isMuted) c.red else c.textHi,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().weight(1f).padding(top = 1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isArmed) c.red.copy(alpha = 0.35f) else c.card)
                            .clickable { BiasArm.arm(trackIndex, lane) }
                            .button(stringResource(Res.string.a11y_lane_arm, lane + 1), stringResource(if (isArmed) Res.string.a11y_armed else Res.string.a11y_not_armed)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "\u25CF", color = if (isArmed) c.red else c.textFaint, fontSize = 11.sp,
                        )
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.card)
                        .then(
                            if (take == null) {
                                Modifier.clickable { picking = lane }
                            } else {
                                Modifier.pointerInput(lane, stored, total) {
                                    laneGestures(
                                        lane, stored ?: return@pointerInput, total, clip.grid,
                                        fileFrames = { TakePeaks.cached(stored.file)?.frames ?: stored.offset + stored.frames },
                                        handleGrabPx = HandleGrab.toPx(),
                                        onDrag = { drag = it },
                                        onCommit = { d -> drag = null; commit(lane, d) },
                                    )
                                }
                            },
                        ),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val mid = size.height / 2f
                        val half = size.height / 2f - 2f
                        val bars = (total + ticksPerBar - 1) / ticksPerBar
                        for (b in 1 until bars) {
                            val x = size.width * (b.toFloat() * ticksPerBar) / total
                            drawLine(c.raised.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        drawLine(c.textDim.copy(alpha = 0.25f), Offset(0f, mid), Offset(size.width, mid), 1f)
                        if (take != null) {
                            val from = size.width * take.startTick / total
                            val span = size.width * take.lengthTicks() / total
                            val to = (from + span).coerceAtMost(size.width)
                            val colour = if (isMuted) c.textDim else c.accent
                            drawRect(
                                c.accentDim.copy(alpha = 0.18f), Offset(from, 0f),
                                Size((to - from).coerceAtLeast(1f), size.height),
                            )
                            val survey = shape
                            if (survey != null && survey.frames > 0) {
                                // Which columns of the file this region covers.
                                // The cache holds the whole file, so trimming
                                // just changes these two numbers.
                                val columns = survey.peaks.size / 2
                                val c0 = (columns.toLong() * take.offset / survey.frames).toInt()
                                val c1 = (columns.toLong() * (take.offset + take.frames) / survey.frames)
                                    .toInt().coerceIn(c0 + 1, columns)
                                // Past the right edge the region is cut off by
                                // its cycle, so only draw what's heard.
                                val shown = if (span > 0f) ((c1 - c0) * (to - from) / span).toInt() else 0
                                drawShape(survey.peaks, c0, (c0 + shown).coerceIn(c0 + 1, c1),
                                    from, to, mid, half, colour)
                            } else {
                                drawLine(colour.copy(alpha = 0.4f), Offset(from, mid), Offset(to, mid), 2f)
                            }
                            // Both handles stay inside the cell. A take longer
                            // than the cycle has its end off screen, and you
                            // need to reach it to shorten it, which is common
                            // since a whole recording dropped on a lane is
                            // usually longer than the cell. Clamped, the end
                            // shows it carries on past here and can still be
                            // dragged back.
                            for (x in listOf(from.coerceIn(0f, size.width - 1f),
                                             to.coerceIn(1f, size.width - 1f))) {
                                drawLine(c.teal, Offset(x, 0f), Offset(x, size.height), 2f)
                            }
                            // The fades, drawn as slopes from the region's
                            // bottom corners up to where it reaches full level.
                            // They're dragged from the bottom half of the same
                            // two edges.
                            val perFrame = if (take.frames > 0) span / take.frames else 0f
                            if (take.fadeIn > 0) {
                                val x = from + take.fadeIn * perFrame
                                drawLine(c.teal, Offset(from, size.height), Offset(x, 0f), 1.5f)
                            }
                            if (take.fadeOut > 0) {
                                val x = to - take.fadeOut * perFrame
                                drawLine(c.teal, Offset(x, 0f), Offset(to, size.height), 1.5f)
                            }
                        }
                    }
                    if (take == null) {
                        Text(
                            "−", color = c.textFaint, fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        val head = playheadTick() ?: return@Canvas
                        val x = size.width * head.coerceIn(0, total) / total
                        drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), 1.5f)
                    }
                }
            }
        }
    }
    if (picking >= 0) {
        TakePicker(trackIndex, sceneId, picking, editor, scope) { picking = -1 }
    }
}

/**
 * Mixes the four lanes into one: a comp.
 *
 * It flattens the lanes, their levels, mutes and fades, but not the tape
 * patch. The patch is how you listen, so baking it in would make it
 * permanent and then apply it a second time.
 *
 * The four takes are replaced by one in lane 1, and the file is saved in the
 * sound library under its own name. The original recordings are still there,
 * so you can put them back if you don't want the comp.
 */
@Composable
fun CompButton(
    trackIndex: Int, sceneId: String, editor: SongEditor,
    scope: kotlinx.coroutines.CoroutineScope,
    /** A string resource and its arguments, as `EngineSync.onProblem` takes them. */
    onProblem: (StringResource, Array<out Any>) -> Unit,
) {
    val c = Acid.colors
    val song = editor.song
    val clip = song.tracks.getOrNull(trackIndex)?.clips?.get(sceneId)
    val lanes = clip?.audioLaneCount() ?: 0
    TextButton(
        enabled = lanes > 0,
        onClick = {
            val theClip = clip ?: return@TextButton
            val scene = song.scenes.firstOrNull { it.id == sceneId } ?: return@TextButton
            val bpm = song.bpmOf(sceneId)
            val ticks = song.cycleTicks(sceneId, theClip)
            val frames = (ticks.toDouble() / PPQN * 60.0 / bpm * ENGINE_RATE).toInt()
            val root = com.rm.acidulous.io.File(
                com.rm.acidulous.engine.EngineAssets.userRoot(), "samples",
            ).apply { mkdirs() }
            val file = com.rm.acidulous.io.File(root, com.rm.acidulous.engine.uniqueIn(root, "comp.wav"))
            scope.launch {
                val peak = FloatArray(1)
                val error = withContext(Dispatchers.Default) {
                    NativeEngine.compCell(trackIndex, scene.engineId, frames, bpm,
                                          file.absolutePath, peak)
                }
                if (error.isNotEmpty()) {
                    onProblem(Res.string.tape_comp_failed, arrayOf(error))
                    file.delete()
                    return@launch
                }
                val rel = "samples/" + file.name
                val survey = withContext(Dispatchers.Default) {
                    TakePeaks.survey(EngineSync.sampleRoot, rel)
                } ?: return@launch
                editor.editClip(trackIndex, sceneId) { cl ->
                    // One take in the first lane and the other three emptied:
                    // the four are now this one.
                    var next = cl
                    for (l in 0 until BIAS_LANES) next = next.withTake(l, null)
                    next.withTake(
                        0,
                        song.takeForWholeFile(sceneId, cl, rel, survey.frames)
                            .copy(peaks = survey.peaks),
                    )
                }
                EngineSync.sync(editor.song)
            }
        },
    ) {
        Text(stringResource(Res.string.tape_comp), color = if (lanes > 0) c.textMid else c.textFaint, fontSize = 11.sp)
    }
}

/**
 * Where the finger lands decides what it does: the ends trim and the middle
 * moves. No modes or tools.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.laneGestures(
    lane: Int,
    stored: TakeRef,
    total: Int,
    grid: Int,
    fileFrames: () -> Int,
    handleGrabPx: Float,
    onDrag: (LaneDrag?) -> Unit,
    onCommit: (TakeRef) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val w = size.width.toFloat().coerceAtLeast(1f)
        // Clamped the same way they're drawn, or the end of a take that runs
        // past the cell could only be grabbed off screen.
        val from = (w * stored.startTick / total).coerceIn(0f, w - 1f)
        val to = (w * stored.startTick / total + w * stored.lengthTicks() / total).coerceIn(1f, w - 1f)
        // Which half of the lane the finger is in decides what the ends do:
        // the top half trims and the bottom half fades, so both work on the
        // same two edges without a mode.
        val fading = down.position.y > size.height * 0.5f
        // A fade-out needs a visible end. If the take runs past the cell, a
        // fade measured from its real end would jump to its limit on the
        // first drag or be cut off by the cycle before it starts. So on an
        // overrunning take both halves of the right edge trim.
        val endsInView = w * (stored.startTick + stored.lengthTicks()) / total <= w - 1f
        val part = when {
            abs(down.position.x - from) <= handleGrabPx ->
                if (fading) LaneDrag.Part.FadeIn else LaneDrag.Part.Head
            abs(down.position.x - to) <= handleGrabPx ->
                if (fading && endsInView) LaneDrag.Part.FadeOut else LaneDrag.Part.Tail
            down.position.x in from..to -> LaneDrag.Part.Body
            else -> return@awaitEachGesture
        }
        down.consume()
        var latest = stored
        // The ends follow the finger's position, the body follows the drag
        // distance. A clamped handle moved by drag distance would barely seem
        // to move on a take much longer than the screen. Following the
        // finger, it ends up where you put it. The body has no end to follow,
        // so it moves by the drag.
        val head = stored.startTick
        val tail = stored.startTick + stored.lengthTicks()
        drag(down.id) { change ->
            change.consume()
            val atTick = (change.position.x / w * total).roundToInt()
            val dTicks = when (part) {
                LaneDrag.Part.Head, LaneDrag.Part.FadeIn -> atTick - head
                LaneDrag.Part.Tail, LaneDrag.Part.FadeOut -> atTick - tail
                LaneDrag.Part.Body -> ((change.position.x - down.position.x) / w * total).roundToInt()
            }
            latest = apply(stored, part, dTicks, total, grid, fileFrames())
            onDrag(LaneDrag(lane, part, latest))
        }
        onCommit(latest)
    }
}

/**
 * Keeps fades within the take.
 *
 * `fadeAt` handles it either way by taking the smaller ramp, but a trim that
 * leaves a two-second fade on a one-second take makes no sense afterwards,
 * so trimming shortens the fades too.
 */
private fun TakeRef.withFadesInside(): TakeRef {
    val half = (frames / 2).coerceAtLeast(0)
    return if (fadeIn <= half && fadeOut <= half) this
    else copy(fadeIn = fadeIn.coerceAtMost(half), fadeOut = fadeOut.coerceAtMost(half))
}

/** The maths of one drag, kept apart from the gesture code so it's easier to read. */
private fun apply(take: TakeRef, part: LaneDrag.Part, dTicks: Int, total: Int, grid: Int, fileFrames: Int): TakeRef {
    /** Ticks to frames at the take's own tempo. */
    fun frames(ticks: Int): Int =
        (ticks.toDouble() / PPQN * 60.0 / take.bpm * ENGINE_RATE).roundToInt()

    return when (part) {
        // Where it starts, snapped to the notes' grid. Never before the start
        // of the cycle, since a cell can't play what came before it.
        LaneDrag.Part.Body -> {
            val step = grid.coerceAtLeast(1)
            val want = ((take.startTick + dTicks).toDouble() / step).roundToInt() * step
            take.copy(startTick = want.coerceIn(0, total))
        }
        // The head trims rather than moves. Dragging right hides the start of
        // the recording and the rest stays in place, like trimming the lead-in
        // before a vocal. Offset and start move together.
        LaneDrag.Part.Head -> {
            val d = frames(dTicks).coerceIn(-take.offset, take.frames - frames(PPQN / 8))
            take.copy(
                offset = take.offset + d,
                frames = take.frames - d,
                startTick = (take.startTick + dTicks).coerceAtLeast(0),
            ).withFadesInside()
        }
        // The tail is the end of the recording and can't go past the end of
        // the file.
        LaneDrag.Part.Tail -> {
            val room = (fileFrames - take.offset).coerceAtLeast(1)
            take.copy(frames = (take.frames + frames(dTicks)).coerceIn(frames(PPQN / 8), room))
                .withFadesInside()
        }
        // A fade is dragged inwards from its end, so the handle starts at the
        // take's edge and the distance is the fade length. Neither can be more
        // than half the take, or the two fades would overlap.
        LaneDrag.Part.FadeIn ->
            take.copy(fadeIn = frames(dTicks).coerceIn(0, take.frames / 2))
        LaneDrag.Part.FadeOut ->
            take.copy(fadeOut = (-frames(dTicks)).coerceIn(0, take.frames / 2))
    }
}

/**
 * The sound library, and picking a take from it for a lane.
 *
 * Shared by the panel and the lanes because both do the same thing: pick a
 * recording, measure it once off the main thread, and write one take into
 * this cell. See `TakePeaks` for the measuring.
 */
@Composable
fun TakePicker(
    trackIndex: Int, sceneId: String, lane: Int, editor: SongEditor,
    /**
     * The caller's scope, not this window's.
     *
     * Picking closes the window, which would cancel a scope from
     * `rememberCoroutineScope` here and stop the survey mid-decode, leaving the
     * lane empty. The work has to outlive the window.
     */
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    val song = editor.song
    RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Library,
        inUse = song.samplesInUse(),
        onPick = { rel ->
            onDone()
            scope.launch {
                val survey = withContext(Dispatchers.Default) {
                    TakePeaks.survey(EngineSync.sampleRoot, rel)
                } ?: return@launch
                // Loops fit the song. The tempo is worked out from the loop's
                // length and where its hits fall, and the track is set to
                // follow the song tempo, so a 90 bpm break in a 126 bpm scene
                // plays at 126 instead of drifting. A file that isn't a loop
                // gets the scene's tempo.
                val loopBpm: Float? = withContext(Dispatchers.Default) {
                    val root = EngineSync.sampleRoot ?: return@withContext null
                    loopTempo(NativeEngine.loopShape(com.rm.acidulous.io.File(root, rel).absolutePath))
                }
                editor.edit(trackIndex) { track ->
                    val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
                    // Loops loop: two bars in a four-bar cell play twice.
                    val take = song.takeForWholeFile(sceneId, clip, rel, survey.frames, loopBpm)
                        .copy(peaks = survey.peaks, loop = loopBpm != null)
                    val follow = loopBpm != null && kotlin.math.abs(loopBpm - song.bpmOf(sceneId)) > 0.05f
                    track.copy(
                        clips = track.clips + (sceneId to clip.withTake(lane, take)),
                        machine = if (follow) track.machine.copy(params = track.machine.params + ("stretch" to 1f)) else track.machine,
                    )
                }
            }
        },
        onDismiss = onDone,
    )
}
