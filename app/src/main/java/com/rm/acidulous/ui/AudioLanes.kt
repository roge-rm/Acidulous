package com.rm.acidulous.ui

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

/**
 * The four-track's editor: four lanes under one ruler, along this cell's cycle.
 *
 * **The axis is the cycle, not one pass of the clip.** A scene set to repeat
 * twice plays a tape straight through both passes - that is what
 * `SceneScheduler::rackCycleTick` is for - so an editor drawn against
 * `clip.bars` would show the first half of what the cell sounds and hide the
 * rest. Every tick in here is a tick of the cycle.
 *
 * What a lane can be told:
 *
 *   - **where it enters**, by dragging its body, snapped to the clip's grid;
 *   - **where it starts and stops**, by dragging either end - which trims the
 *     recording rather than moving it, so the audio stays where it was put;
 *   - **whether it sounds**, by its mute, which is a machine parameter and so
 *     automates and records like every other.
 *
 * **Nothing is written until a finger lifts.** The reel is diffed on a string
 * built from exactly these numbers, so an edit per frame of a drag is a decode
 * per frame of a drag - which for a five-minute take is a second of work and a
 * hundred megabytes, fifteen times a second.
 */
private val GutterW = 34.dp
private val RulerH = 16.dp
private val HandleGrab = 22.dp

/** Which end of which lane a finger has hold of. */
private data class LaneDrag(val lane: Int, val part: Part, val take: TakeRef) {
    enum class Part { Body, Head, Tail }
}

@Composable
fun AudioLanes(
    clip: Clip,
    ticksPerBar: Int,
    /** Bars times the scene's repeat, in ticks: the whole of what this cell plays. */
    cycleTicks: Int,
    /** Where the rack is in that cycle, or null when it is not playing this cell. */
    playheadTick: Int?,
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
    // **The mute is the machine's parameter, reached the way every parameter
    // is reached.** Not a second switch that happens to do the same thing: it
    // is the one in the panel below, so automating it, mapping it to a pad and
    // recording it while you play all work here and none of it is written
    // twice. The binding is built here rather than passed in because these
    // lanes are the only thing that is ever composed for a tape.
    val info = remember { NativeEngine.machineParamInfo(BIAS_MACHINE) }
    val b = rememberParamBinding(trackIndex, BIAS_MACHINE, info, editor)
    val commit: (Int, TakeRef?) -> Unit = { lane, take ->
        editor.editClip(trackIndex, sceneId) { c2 -> c2.withTake(lane, take) }
    }

    Column(modifier.background(c.bgDeep)) {
        // The ruler: bar numbers along the cycle, so a repeat is visibly a
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
            // While a finger is down the lane draws what the finger says, and
            // the document has not heard about it yet.
            val take = if (drag?.lane == lane) drag?.take else stored
            val shape = TakePeaks.rememberShape(root, take?.file.orEmpty())
            val isMuted = (b.value("mute${lane + 1}") ?: 0f) >= 0.5f
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.width(GutterW).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isMuted) c.card else c.control)
                        .clickable { b.set("mute${lane + 1}", if (isMuted) 0f else 1f) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${lane + 1}",
                        color = if (isMuted) c.textFaint else c.textHi,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                    if (isMuted) Text("M", color = c.red, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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
                                // Which columns of the *file* this region is,
                                // which is why the cache holds the whole file:
                                // trimming moves these two numbers and reads
                                // nothing.
                                val columns = survey.peaks.size / 2
                                val c0 = (columns.toLong() * take.offset / survey.frames).toInt()
                                val c1 = (columns.toLong() * (take.offset + take.frames) / survey.frames)
                                    .toInt().coerceIn(c0 + 1, columns)
                                // Past the right edge the region is cut off by
                                // its own cycle; draw only what is heard.
                                val shown = if (span > 0f) ((c1 - c0) * (to - from) / span).toInt() else 0
                                drawShape(survey.peaks, c0, (c0 + shown).coerceIn(c0 + 1, c1),
                                    from, to, mid, half, colour)
                            } else {
                                drawLine(colour.copy(alpha = 0.4f), Offset(from, mid), Offset(to, mid), 2f)
                            }
                            // **Both handles stay in the cell.** A take longer
                            // than the cycle has its end off the right of the
                            // screen, and a handle you cannot reach is a take
                            // you cannot shorten - which is precisely the case
                            // this editor is for, since a whole recording
                            // dropped on a lane is nearly always longer than
                            // the cell it lands in. Clamped, the end reads as
                            // "it goes on past here" and can still be dragged
                            // back.
                            for (x in listOf(from.coerceIn(0f, size.width - 1f),
                                             to.coerceIn(1f, size.width - 1f))) {
                                drawLine(c.teal, Offset(x, 0f), Offset(x, size.height), 2f)
                            }
                        }
                    }
                    if (take == null) {
                        Text(
                            "−", color = c.textFaint, fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (playheadTick != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val x = size.width * playheadTick.coerceIn(0, total) / total
                            drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), 1.5f)
                        }
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
 * Where a finger landed decides what it does: the two ends trim, the middle
 * moves. There is no mode and no tool - a region has three parts and each one
 * means the thing you would expect it to mean.
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
        // Clamped exactly as they are drawn, or the end of a take that runs
        // past the cell would be grabbable off the edge of the screen and
        // nowhere else.
        val from = (w * stored.startTick / total).coerceIn(0f, w - 1f)
        val to = (w * stored.startTick / total + w * stored.lengthTicks() / total).coerceIn(1f, w - 1f)
        val part = when {
            abs(down.position.x - from) <= handleGrabPx -> LaneDrag.Part.Head
            abs(down.position.x - to) <= handleGrabPx -> LaneDrag.Part.Tail
            down.position.x in from..to -> LaneDrag.Part.Body
            else -> return@awaitEachGesture
        }
        down.consume()
        var latest = stored
        // **The ends follow the finger; the body follows the drag.** A handle
        // asked to move by *how far the finger went* is unusable the moment it
        // has been clamped into the cell - the take's real end is off the
        // screen, so half a screen of drag takes half a screen off a take that
        // is two screens long and nothing appears to happen. Asked instead for
        // *where the finger is*, it ends where you put it, clamped or not. The
        // body has no such end to stand at, so it moves by the drag.
        val head = stored.startTick
        val tail = stored.startTick + stored.lengthTicks()
        drag(down.id) { change ->
            change.consume()
            val atTick = (change.position.x / w * total).roundToInt()
            val dTicks = when (part) {
                LaneDrag.Part.Head -> atTick - head
                LaneDrag.Part.Tail -> atTick - tail
                LaneDrag.Part.Body -> ((change.position.x - down.position.x) / w * total).roundToInt()
            }
            latest = apply(stored, part, dTicks, total, grid, fileFrames())
            onDrag(LaneDrag(lane, part, latest))
        }
        onCommit(latest)
    }
}

/** The arithmetic of one drag, kept apart from the gesture so it can be read. */
private fun apply(take: TakeRef, part: LaneDrag.Part, dTicks: Int, total: Int, grid: Int, fileFrames: Int): TakeRef {
    /** Ticks into frames at the take's own tempo - audio does not stretch. */
    fun frames(ticks: Int): Int =
        (ticks.toDouble() / PPQN * 60.0 / take.bpm * ENGINE_RATE).roundToInt()

    return when (part) {
        // Where it enters, snapped to the grid the notes use. Never before the
        // start of the cycle: a cell cannot play what happened before it.
        LaneDrag.Part.Body -> {
            val step = grid.coerceAtLeast(1)
            val want = ((take.startTick + dTicks).toDouble() / step).roundToInt() * step
            take.copy(startTick = want.coerceIn(0, total))
        }
        // **The head trims; it does not move.** Dragging right hides the first
        // of the recording and the rest stays where it was put, which is what
        // trimming a take in front of a vocal entry means. Both numbers move
        // together, and the entry moves with them.
        LaneDrag.Part.Head -> {
            val d = frames(dTicks).coerceIn(-take.offset, take.frames - frames(PPQN / 8))
            take.copy(
                offset = take.offset + d,
                frames = take.frames - d,
                startTick = (take.startTick + dTicks).coerceAtLeast(0),
            )
        }
        // The tail is the end of the recording, and cannot pass the end of the
        // file: there is nothing there to play.
        LaneDrag.Part.Tail -> {
            val room = (fileFrames - take.offset).coerceAtLeast(1)
            take.copy(frames = (take.frames + frames(dTicks)).coerceIn(frames(PPQN / 8), room))
        }
    }
}

/**
 * The library, and what it leaves behind on a lane.
 *
 * Shared by the panel and the lanes because it is the same act in both: pick a
 * recording, measure it once off the main thread, and write one take into this
 * cell. The measurement is the reason it is worth sharing - see `TakePeaks`.
 */
@Composable
fun TakePicker(
    trackIndex: Int, sceneId: String, lane: Int, editor: SongEditor,
    /**
     * **The caller's scope, not this window's.**
     *
     * Picking dismisses the window, and a scope from `rememberCoroutineScope`
     * here would be cancelled by that dismissal - which is exactly what
     * happened: the dialog closed, the survey was cancelled mid-decode, and the
     * lane stayed empty with nothing said. The work outlives the window that
     * asked for it, so the scope has to as well.
     */
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    val song = editor.song
    RecorderDialog(
        startOn = RecorderPage.Library,
        inUse = song.samplesInUse(),
        onPick = { rel ->
            onDone()
            scope.launch {
                val survey = withContext(Dispatchers.Default) {
                    TakePeaks.survey(EngineSync.sampleRoot, rel)
                } ?: return@launch
                editor.editClip(trackIndex, sceneId) { clip ->
                    clip.withTake(
                        lane,
                        song.takeForWholeFile(sceneId, clip, rel, survey.frames)
                            .copy(peaks = survey.peaks),
                    )
                }
            }
        },
        onDismiss = onDone,
    )
}
