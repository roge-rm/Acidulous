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
import com.rm.acidulous.model.audioLaneCount
import com.rm.acidulous.model.cycleTicks
import com.rm.acidulous.model.bpmOf
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

/**
 * Which lane the next recording goes onto, if any.
 *
 * **One capture exists, so one lane records at a time.** That is an engine
 * fact rather than a interface choice - see `Engine::armedRack` - and arming a
 * second lane disarms the first rather than being refused, because being
 * refused is the answer nobody wants when they have already decided.
 *
 * Session state, not a preference: what you were about to record is not
 * something to remember until tomorrow.
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

/** Which end of which lane a finger has hold of. */
private data class LaneDrag(val lane: Int, val part: Part, val take: TakeRef) {
    enum class Part { Body, Head, Tail, FadeIn, FadeOut }
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
                // **The gutter is two buttons, not one with a mode.** The top
                // half is the lane and mutes it; the bottom half is the record
                // dot and arms it. Both are things you reach for while a song
                // is playing, so neither may be behind the other.
                val isArmed = BiasArm.armed(trackIndex, lane)
                Column(Modifier.width(GutterW).fillMaxHeight()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isMuted) c.card else c.control)
                            .clickable { b.set("mute${lane + 1}", if (isMuted) 0f else 1f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isMuted) "M" else "${lane + 1}",
                            color = if (isMuted) c.red else c.textHi,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().weight(1f).padding(top = 1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isArmed) c.red.copy(alpha = 0.35f) else c.card)
                            .clickable { BiasArm.arm(trackIndex, lane) },
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
                            // The fades, drawn as the slopes they are: a line
                            // from the corner of the region up to where the
                            // envelope reaches full. Dragged from the bottom
                            // half of the same two edges, which is why they
                            // are drawn from the bottom corners.
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
 * Four lanes into one: a comp.
 *
 * **What it flattens is the lanes, their levels, their mutes and their fades -
 * and not the medium.** A patch is a way of listening and a comp is an edit;
 * baking the cassette in would make it permanent *and* leave the patch
 * applying it a second time on top of itself.
 *
 * The four takes are replaced by one, in lane 1, and the file goes into the
 * sound library under its own name - so the original recordings are still
 * there and a comp you did not want is undone by putting them back.
 */
@Composable
fun CompButton(
    trackIndex: Int, sceneId: String, editor: SongEditor,
    scope: kotlinx.coroutines.CoroutineScope,
    onProblem: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = Acid.colors
    val song = editor.song
    val clip = song.tracks.getOrNull(trackIndex)?.clips?.get(sceneId)
    val lanes = clip?.audioLaneCount() ?: 0
    androidx.compose.material3.TextButton(
        enabled = lanes > 0,
        onClick = {
            val theClip = clip ?: return@TextButton
            val scene = song.scenes.firstOrNull { it.id == sceneId } ?: return@TextButton
            val bpm = song.bpmOf(sceneId)
            val ticks = song.cycleTicks(sceneId, theClip)
            val frames = (ticks.toDouble() / PPQN * 60.0 / bpm * ENGINE_RATE).toInt()
            val root = java.io.File(
                com.rm.acidulous.engine.EngineAssets.userRoot(context), "samples",
            ).apply { mkdirs() }
            val file = java.io.File(root, com.rm.acidulous.engine.uniqueIn(root, "comp.wav"))
            scope.launch {
                val peak = FloatArray(1)
                val error = withContext(Dispatchers.Default) {
                    NativeEngine.compCell(trackIndex, scene.engineId, frames, bpm,
                                          file.absolutePath, peak)
                }
                if (error.isNotEmpty()) {
                    onProblem("That cell would not flatten - $error.")
                    file.delete()
                    return@launch
                }
                val rel = "samples/" + file.name
                val survey = withContext(Dispatchers.Default) {
                    TakePeaks.survey(EngineSync.sampleRoot, rel)
                } ?: return@launch
                editor.editClip(trackIndex, sceneId) { cl ->
                    // One take, in the first lane, and the other three emptied:
                    // what the comp says is that these four are now this one.
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
        Text("comp", color = if (lanes > 0) c.textMid else c.textFaint, fontSize = 11.sp)
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
        // **Which half of the lane a finger is in decides what the ends do.**
        // The top half trims and the bottom half fades, so both live on the
        // same two edges without a mode anywhere: a take has two ends and two
        // things you do at each of them, and the one you want is the one you
        // reach for.
        val fading = down.position.y > size.height * 0.5f
        // **A fade-out needs an end you can see.** A take longer than the cell
        // has its end off the right of the screen, and a fade measured from
        // there is a control that slams to its limit on the first drag and is
        // inaudible if it does not - the take is cut off by the cycle long
        // before the fade begins. So on an overrunning take both halves of the
        // right edge trim, which is the thing to do first anyway.
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
 * A fade can never be longer than the take it is on.
 *
 * `fadeAt` is safe either way - it takes the smaller of the two ramps - but a
 * trim that leaves a two-second fade on a one-second take is a number nobody
 * can make sense of afterwards, so a trim brings them with it.
 */
private fun TakeRef.withFadesInside(): TakeRef {
    val half = (frames / 2).coerceAtLeast(0)
    return if (fadeIn <= half && fadeOut <= half) this
    else copy(fadeIn = fadeIn.coerceAtMost(half), fadeOut = fadeOut.coerceAtMost(half))
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
            ).withFadesInside()
        }
        // The tail is the end of the recording, and cannot pass the end of the
        // file: there is nothing there to play.
        LaneDrag.Part.Tail -> {
            val room = (fileFrames - take.offset).coerceAtLeast(1)
            take.copy(frames = (take.frames + frames(dTicks)).coerceIn(frames(PPQN / 8), room))
                .withFadesInside()
        }
        // **A fade is dragged inwards from the end it belongs to**, so the
        // handle starts where the take does and the distance is the length.
        // Neither may eat more than half the take, because two fades that
        // overlap is an envelope with no take in the middle of it.
        LaneDrag.Part.FadeIn ->
            take.copy(fadeIn = frames(dTicks).coerceIn(0, take.frames / 2))
        LaneDrag.Part.FadeOut ->
            take.copy(fadeOut = (-frames(dTicks)).coerceIn(0, take.frames / 2))
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
