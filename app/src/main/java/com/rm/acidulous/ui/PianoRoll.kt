package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

enum class EditMode { Draw, Select }

/**
 * How the roll treats a scale. Chromatic ignores it, Dim greys the rows a
 * Scale eventor would move, and Fold drops those rows entirely so only
 * playable notes have a lane. The corner of the roll cycles them.
 */
enum class ScaleView { Chromatic, Dim, Fold }

/**
 * The clip editor: one Canvas, notes drawn by hand, hit-tested by hand.
 * A composable per note would crawl on a 16-bar clip with chords.
 *
 * The conventions kept: in Draw mode a tap on empty adds a note of one
 * grid unit and a tap on a note deletes it; dragging a note moves it, dragging
 * its right edge resizes it; dragging on empty draws a note and stretches it.
 * In Select mode dragging on empty rubber-bands, tapping toggles a note,
 * dragging a selected note moves the whole selection. Velocity shows as the
 * brighter region inside each note.
 *
 * Every drag reports the *total* change since it began, so the caller can
 * derive from a gesture base (see SongEditor) rather than accumulate.
 */
@Composable
fun PianoRoll(
    clip: Clip,
    ticksPerBar: Int,
    mode: EditMode,
    selection: Set<Int>,
    playheadTick: Long?,
    lowestPitch: Int,
    rows: Int,
    scalePitchClasses: Set<Int>?,
    /** How the running scale writes its notes; empty is chromatic. */
    noteSpelling: Map<Int, String> = emptyMap(),
    scaleView: ScaleView,
    onCycleScaleView: () -> Unit,
    /** The window this page shows, in ticks from the start of the clip. */
    firstTick: Int,
    visibleTicks: Int,
    onTapEmpty: (tick: Int, pitch: Int) -> Unit,
    onTapNote: (index: Int) -> Unit,
    onSelectionChange: (Set<Int>) -> Unit,
    onGestureBegin: () -> Unit,
    onMove: (indices: Set<Int>, dTick: Int, dPitch: Int) -> Unit,
    onResize: (index: Int, newLength: Int) -> Unit,
    onDraw: (tick: Int, pitch: Int, length: Int) -> Unit,
    onGestureEnd: () -> Unit,
    /** Tapping a name in the gutter sounds that pitch, as a keyboard would. */
    onAudition: (pitch: Int) -> Unit = {},
    /**
     * Dragging the gutter moves the pitch window, by this many semitones.
     * The roll shows sixteen rows of a hundred and twenty-eight notes, and
     * this is how you reach the rest: the two header buttons that used to do
     * it were forty-two dp each in a row that had to fit around a camera
     * hole, and the pitch axis already had a column of its own.
     */
    onScrollPitch: (delta: Int) -> Unit = {},
    /** Two fingers sideways: the window moves by this many ticks. */
    onScrollTime: (ticks: Float) -> Unit = {},
    /**
     * A pinch. Each axis is a multiplier on what is shown - under one is
     * fewer rows or fewer ticks, which is closer in - and an axis the fingers
     * are not spread along reports 1, so a sideways pinch zooms time and
     * leaves the pitch alone.
     */
    onZoom: (pitchScale: Float, timeScale: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // The pointer handler must survive the clip changing under it mid-drag
    // (every updateGesture commits a new clip), so it reads through these.
    val clipState by rememberUpdatedState(clip)
    val modeState by rememberUpdatedState(mode)
    val selectionState by rememberUpdatedState(selection)
    val lowestState by rememberUpdatedState(lowestPitch)
    val rowsState by rememberUpdatedState(rows)
    val cb by rememberUpdatedState(
        Callbacks(onTapEmpty, onTapNote, onSelectionChange, onGestureBegin, onMove, onResize, onDraw, onGestureEnd,
            onAudition, onCycleScaleView, onScrollPitch, onScrollTime, onZoom),
    )
    // Which pitch each row carries. Chromatic and Dim step by semitone; Fold
    // keeps only what the scale allows, so a row is always a playable note.
    val scale = scalePitchClasses?.takeIf { it.isNotEmpty() && scaleView != ScaleView.Chromatic }
    val rowPitches = remember(lowestPitch, rows, scale, scaleView) {
        if (scale == null || scaleView != ScaleView.Fold) {
            IntArray(rows) { lowestPitch + rows - 1 - it }
        } else {
            val kept = ArrayList<Int>(rows)
            var p = lowestPitch
            while (kept.size < rows && p <= 127) {
                if (scale.contains(((p % 12) + 12) % 12)) kept += p
                ++p
            }
            while (kept.size < rows) kept += kept.lastOrNull() ?: lowestPitch
            IntArray(rows) { kept[rows - 1 - it] }
        }
    }
    val rowsState2 by rememberUpdatedState(rowPitches)

    // Read here and captured by the Canvas below: drawing is not
    // composition, so the lambda cannot reach the theme on its own.
    val c = Acid.colors

    var rubberBand by remember { mutableStateOf<Rect?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val geo = Geometry(
                    canvasSize, clipState, ticksPerBar, rowsState,
                    GutterWidth.toPx(), RulerHeight.toPx(), rowsState2, firstTick, visibleTicks,
                )
                val press = down.position

                // Two fingers move the view and never the notes - and the
                // check comes first, before the gutter, the ruler or a note,
                // because a pinch puts its fingers down a few milliseconds
                // apart and whatever the first one landed on must not act in
                // the meantime.
                if (currentEvent.changes.count { it.pressed } >= 2) {
                    twoFingers(geo, cb)
                    return@awaitEachGesture
                }

                // The gutter plays the row it names and scrolls the window;
                // the ruler is a legend and takes no edits; the corner
                // between them cycles the scale view. None of the three can
                // draw a note by accident.
                if (press.x < geo.originX || press.y < geo.originY) {
                    if (press.y < geo.originY) {
                        if (press.x < geo.originX) cb.onCycleScaleView()
                        down.consume()
                        return@awaitEachGesture
                    }
                    // The down is *not* consumed here, unlike the ruler and
                    // the corner: awaitTouchSlopOrCancellation gives up the
                    // moment it sees a consumed change, so consuming first
                    // meant the drag below could never begin.
                    //
                    // The audition waits for the finger to lift rather than
                    // firing on the way down, or every scroll would begin
                    // with a note nobody asked for.
                    val gate = slopOrSecondFinger(down.id, viewConfiguration.touchSlop, press)
                    if (gate.second) {
                        twoFingers(geo, cb)
                        return@awaitEachGesture
                    }
                    val slop = gate.past
                    if (slop == null) {
                        if (currentEvent.changes.none { it.pressed }) cb.onAudition(geo.pitchAt(press.y))
                        return@awaitEachGesture
                    }
                    // The window follows the finger: drag down and the rows
                    // come down with it, which brings higher notes in at the
                    // top - the way a list scrolls, and the way every other
                    // drag in this app already behaves.
                    //
                    // Measured from where the press began rather than summed
                    // from each event's delta, because positionChange() is
                    // zero on a change that has been consumed and everything
                    // here consumes. The rest of this file reads absolute
                    // positions for the same reason.
                    var applied = 0
                    drag(slop.id) { change ->
                        change.consume()
                        val want = ((change.position.y - press.y) / geo.rowH).toInt()
                        if (want != applied) {
                            cb.onScrollPitch(want - applied)
                            applied = want
                        }
                    }
                    return@awaitEachGesture
                }
                val hit = geo.hitTest(press)

                val gate = slopOrSecondFinger(down.id, viewConfiguration.touchSlop, press)
                if (gate.second) {
                    twoFingers(geo, cb)
                    return@awaitEachGesture
                }
                val slop = gate.past
                if (slop == null) {
                    // Released before moving: a tap.
                    val released = currentEvent.changes.none { it.pressed }
                    if (!released) return@awaitEachGesture
                    when (hit) {
                        null -> if (modeState == EditMode.Draw) {
                            cb.onTapEmpty(geo.tickAt(press.x, snap = true), geo.pitchAt(press.y))
                        } else {
                            cb.onSelectionChange(emptySet())
                        }
                        else -> if (modeState == EditMode.Draw) {
                            cb.onTapNote(hit.index)
                        } else {
                            val sel = selectionState
                            cb.onSelectionChange(if (hit.index in sel) sel - hit.index else sel + hit.index)
                        }
                    }
                    return@awaitEachGesture
                }

                // A drag. Decide what it is from where it started.
                when {
                    hit != null && hit.onEdge -> {
                        val note = clipState.notes[hit.index]
                        cb.onGestureBegin()
                        drag(slop.id) { change ->
                            change.consume()
                            val endTick = geo.tickAt(change.position.x, snap = true)
                            cb.onResize(hit.index, max(clipState.grid / 2, endTick - note.tick))
                        }
                        cb.onGestureEnd()
                    }
                    hit != null -> {
                        val sel = selectionState
                        val indices = if (modeState == EditMode.Select && hit.index in sel) sel else setOf(hit.index)
                        cb.onGestureBegin()
                        drag(slop.id) { change ->
                            change.consume()
                            val dTick = geo.snapDelta(change.position.x - press.x)
                            val dPitch = geo.pitchAt(change.position.y) - geo.pitchAt(press.y)
                            cb.onMove(indices, dTick, dPitch)
                        }
                        cb.onGestureEnd()
                    }
                    modeState == EditMode.Draw -> {
                        val tick = geo.tickAt(press.x, snap = true)
                        val pitch = geo.pitchAt(press.y)
                        cb.onGestureBegin()
                        cb.onDraw(tick, pitch, clipState.grid)
                        drag(slop.id) { change ->
                            change.consume()
                            val endTick = geo.tickAt(change.position.x, snap = true)
                            cb.onDraw(tick, pitch, max(clipState.grid / 2, endTick - tick))
                        }
                        cb.onGestureEnd()
                    }
                    else -> {
                        drag(slop.id) { change ->
                            change.consume()
                            rubberBand = Rect(
                                min(press.x, change.position.x), min(press.y, change.position.y),
                                max(press.x, change.position.x), max(press.y, change.position.y),
                            )
                        }
                        rubberBand?.let { band ->
                            val inside = clipState.notes.indices.filter { geo.noteRect(clipState.notes[it]).overlaps(band) }.toSet()
                            cb.onSelectionChange(inside)
                        }
                        rubberBand = null
                    }
                }
            }
        },
    ) {
        canvasSize = size
        val geo = Geometry(size, clip, ticksPerBar, rows, GutterWidth.toPx(), RulerHeight.toPx(), rowPitches,
            firstTick, visibleTicks)

        // Rows: black keys darker, C rows marked, and rows the scale would
        // move pushed further back when Dim is on.
        for (r in 0 until rows) {
            val pitch = geo.pitchOfRow(r)
            val inScale = scale?.contains(((pitch % 12) + 12) % 12) ?: true
            drawRect(
                color = when {
                    !inScale -> c.rowOut
                    isBlackKey(pitch) -> c.rowBlack
                    else -> c.rowWhite
                },
                topLeft = Offset(geo.originX, geo.originY + r * geo.rowH),
                size = Size(geo.fieldW, geo.rowH),
            )
            if (pitch % 12 == 0) {
                val y = geo.originY + (r + 1) * geo.rowH
                drawLine(c.line, Offset(geo.originX, y), Offset(size.width, y), 2f)
            }
        }

        // Grid: subdivision, beat, bar.
        var t = geo.firstTick
        while (t <= geo.lastTick) {
            val x = geo.xOf(t)
            val (color, width) = when {
                t % ticksPerBar == 0 -> c.gridBar to 2.5f
                t % PPQN == 0 -> c.gridBeat to 1.5f
                else -> c.gridStep to 1f
            }
            drawLine(color, Offset(x, geo.originY), Offset(x, size.height), width)
            t += clip.grid.coerceAtLeast(1)
        }

        // Notes, with velocity as the bright inner region.
        clip.notes.forEachIndexed { i, note ->
            // Off this page entirely: nothing to draw.
            if (note.tick + max(1, note.length) <= geo.firstTick || note.tick >= geo.lastTick) return@forEachIndexed
            val rect = geo.noteRect(note)
            if (rect.bottom < 0f || rect.top > size.height) return@forEachIndexed
            val selected = i in selection
            drawRect(if (selected) c.noteSel else c.note, rect.topLeft, rect.size)
            val velH = (rect.height - 4f) * (note.velocity.coerceIn(1, 127) / 127f)
            drawRect(
                if (selected) c.noteSelEdge else c.teal,
                Offset(rect.left + 2f, rect.bottom - 2f - velH),
                Size(max(0f, rect.width - 4f), velH),
            )
            drawBend(note, rect, c)
            drawRect(c.bg, rect.topLeft, rect.size, style = Stroke(1.5f))
        }

        rubberBand?.let { band ->
            drawRect(c.selectBand, band.topLeft, band.size)
            drawRect(c.selectEdge, band.topLeft, band.size, style = Stroke(1.5f))
        }

        playheadTick?.let { tick ->
            val t = (tick % max(1, geo.totalTicks)).toInt()
            if (t >= geo.firstTick && t < geo.lastTick) {
                val x = geo.xOf(t)
                drawLine(c.accent, Offset(x, geo.originY), Offset(x, size.height), 3f)
            }
        }

        drawNameGutter(geo, textMeasurer, scale, c, noteSpelling)
        drawPitchPosition(geo, c)
        drawBarRuler(geo, size, textMeasurer, playheadTick, c)
        drawScaleCorner(geo, textMeasurer, scalePitchClasses != null, scaleView, c)
    }
}

/**
 * A recorded bend, drawn inside the note it belongs to.
 *
 * Inside, and not in a lane of its own, because the roll's height is the
 * thing the editor is short of and a note is already exactly as wide as the
 * time its curve covers. Full deflection is the note's own row: a bend that
 * fills the box is a semitone, which is the reading a player wants at a
 * glance, and anything wider simply pins to the edge rather than drawing over
 * the neighbours.
 *
 * Only bend is drawn. Pressure and slide are recorded and played, but three
 * lines in a box sixteen pixels tall is not a readout, it is a smudge - and
 * pitch is the one of the three that has a direction on this screen already.
 */
private fun DrawScope.drawBend(note: Note, rect: Rect, c: AcidColors) {
    val bend = note.bend ?: return
    if (bend.points.size < 2 || rect.width < 4f) return
    val len = max(1, note.length)
    val mid = rect.center.y
    val half = (rect.height - 3f) / 2f
    // One sample per pixel of the note's own width, capped: a two-point glide
    // needs two and a vibrato needs the note.
    val steps = rect.width.toInt().coerceIn(2, 96)
    var prev: Offset? = null
    for (i in 0..steps) {
        val t = len * i / steps
        val semis = Note.bendFrom01(bend.valueAt(t))
        val y = mid - (semis.coerceIn(-1f, 1f)) * half
        val p = Offset(rect.left + rect.width * i / steps, y)
        prev?.let { drawLine(c.accent, it, p, 2f) }
        prev = p
    }
}

/**
 * Where the sixteen rows on screen sit in the hundred and twenty-eight.
 *
 * Every scrolling thing in this app carries a position bar, and since the
 * octave buttons left the header the roll is plainly one of them. It does
 * two jobs: it answers "where am I" the way the buttons never did, and it is
 * the only thing on screen that says the gutter can be dragged at all.
 *
 * Drawn to `ui/Scrollbar.kt`'s measurements - three dp thick, one dp in, a
 * twenty dp floor under the thumb - because a bar that matched the lists
 * everywhere else is the point of having a rule about it.
 */
private fun DrawScope.drawPitchPosition(geo: Geometry, c: AcidColors) {
    val top = geo.pitchOfRow(0)
    val bottom = geo.pitchOfRow(geo.rows - 1)
    val shown = (top - bottom + 1).coerceIn(1, 128)
    if (shown >= 128) return
    val track = geo.fieldH
    val thickness = 3.dp.toPx()
    val inset = 1.dp.toPx()
    // Floor first, then ceiling, and never coerceIn between the two: the roll
    // can be squeezed to nothing - open the fx panel with a Filter in it and
    // the weighted grid gets zero - and a twenty dp floor above a one pixel
    // track is an empty range, which throws rather than clamping.
    val thumb = (track * shown / 128f).coerceAtLeast(20.dp.toPx()).coerceAtMost(track)
    // Pitch runs up the screen and the bar runs down it, so the top of the
    // thumb is measured from the highest note, not the lowest.
    val travel = track - thumb
    val pos = geo.originY + travel * ((127 - top).coerceIn(0, 127) / (128f - shown).coerceAtLeast(1f))
    drawRoundRect(c.scrollbar, Offset(inset, pos), Size(thickness, thumb), CornerRadius(thickness / 2f))
}

/**
 * The left gutter names every row rather than drawing a keyboard: this app
 * reads its values in monospace everywhere else, and a column of names is
 * both easier to read on a phone and easier to hit than a drawn key. Black
 * keys keep their darker fill so the shape of the octave is still there, and
 * every C is called out in the accent colour.
 *
 * When rows are too short for a label, only the C rows keep one.
 */
private fun DrawScope.drawNameGutter(
    geo: Geometry, measurer: TextMeasurer, scale: Set<Int>?, c: AcidColors,
    noteSpelling: Map<Int, String>,
) {
    drawRect(c.bg, Offset.Zero, Size(geo.originX, size.height))
    // A 10sp line is about 12dp tall, so below this the names would collide
    // and only the Cs keep one. The measured guard below is the real stop.
    val labelEveryRow = geo.rowH >= 13.dp.toPx()
    for (r in 0 until geo.rows) {
        val pitch = geo.pitchOfRow(r)
        if (pitch < 0 || pitch > 127) continue
        val top = geo.originY + r * geo.rowH
        val black = isBlackKey(pitch)
        val isC = pitch % 12 == 0
        val inScale = scale?.contains(((pitch % 12) + 12) % 12) ?: true
        drawRect(
            color = when {
                !inScale -> c.gutterOut
                black -> c.gutterBlack
                else -> c.gutterWhite
            },
            topLeft = Offset(0f, top + 0.5f),
            size = Size(geo.originX - 2f, max(1f, geo.rowH - 1f)),
        )
        if (!labelEveryRow && !isC) continue
        val style = TextStyle(
            color = when {
                !inScale -> c.textFaint
                isC -> c.accent
                black -> c.textDim
                else -> c.textHi
            },
            fontSize = NameTextSize,
            fontFamily = FontFamily.Monospace,
        )
        val laid = measurer.measure(AnnotatedString(noteName(pitch, noteSpelling)), style)
        if (laid.size.height <= geo.rowH) {
            drawText(laid, topLeft = Offset(3f, top + (geo.rowH - laid.size.height) / 2f))
        }
    }
    drawLine(c.lineStrong, Offset(geo.originX, geo.originY), Offset(geo.originX, size.height), 1.5f)
}

/**
 * The ruler counts bars, with a tick per beat between them. Bar numbers sit
 * just right of their line so a number always belongs to the bar that starts
 * under it, and the playhead shows as a wedge rather than a full-height line
 * so it never hides a number.
 */
private fun DrawScope.drawBarRuler(geo: Geometry, size: Size, measurer: TextMeasurer, playheadTick: Long?, c: AcidColors) {
    drawRect(c.sunken, Offset.Zero, Size(size.width, geo.originY))
    val beats = max(1, geo.ticksPerBar / PPQN)
    val barW = geo.pxPerTick * geo.ticksPerBar
    // Number the beats too when a bar is wide enough to read them; otherwise
    // they stay as ticks and only the bars are named.
    val nameBeats = barW / beats > 56.dp.toPx()
    val barStyle = TextStyle(color = c.textHi, fontSize = RulerTextSize, fontFamily = FontFamily.Monospace)
    val beatStyle = TextStyle(color = c.textDim, fontSize = TickTextSize, fontFamily = FontFamily.Monospace)
    val firstBar = geo.firstTick / geo.ticksPerBar
    val lastBar = (geo.lastTick + geo.ticksPerBar - 1) / geo.ticksPerBar
    for (bar in firstBar until max(firstBar + 1, lastBar)) {
        val barTick = bar * geo.ticksPerBar
        val x = geo.xOf(barTick)
        drawLine(c.gridBar, Offset(x, 2f), Offset(x, geo.originY), 2f)
        val laid = measurer.measure(AnnotatedString("${bar + 1}"), barStyle)
        drawText(laid, topLeft = Offset(x + 3f, (geo.originY - laid.size.height) / 2f))
        for (beat in 1 until beats) {
            val bx = geo.xOf(barTick + beat * PPQN)
            drawLine(c.gridBeat, Offset(bx, geo.originY * 0.45f), Offset(bx, geo.originY), 1.5f)
            if (nameBeats) {
                // Beats read ".2" against the bar's plain "2", the same way
                // the transport writes 1.1.000, so the two never look alike.
                val bl = measurer.measure(AnnotatedString(".${beat + 1}"), beatStyle)
                drawText(bl, topLeft = Offset(bx + 3f, (geo.originY - bl.size.height) / 2f))
            }
        }
    }
    drawLine(c.lineStrong, Offset(0f, geo.originY), Offset(size.width, geo.originY), 1.5f)
    playheadTick?.let { tick ->
        val t = (tick % max(1, geo.totalTicks)).toInt()
        if (t < geo.firstTick || t >= geo.lastTick) return@let
        val x = geo.xOf(t)
        val w = 5f
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(x - w, 1f); lineTo(x + w, 1f); lineTo(x, geo.originY - 1f); close()
            },
            c.accent,
        )
    }
}

private class Callbacks(
    val onTapEmpty: (Int, Int) -> Unit,
    val onTapNote: (Int) -> Unit,
    val onSelectionChange: (Set<Int>) -> Unit,
    val onGestureBegin: () -> Unit,
    val onMove: (Set<Int>, Int, Int) -> Unit,
    val onResize: (Int, Int) -> Unit,
    val onDraw: (Int, Int, Int) -> Unit,
    val onGestureEnd: () -> Unit,
    val onAudition: (Int) -> Unit,
    val onCycleScaleView: () -> Unit,
    val onScrollPitch: (Int) -> Unit,
    val onScrollTime: (Float) -> Unit,
    val onZoom: (Float, Float) -> Unit,
)

/**
 * What two fingers are doing: where their middle is, and how far apart.
 *
 * Shared with the drum grid, which asks the same questions of the same
 * gesture over the same clip.
 */
internal class TwoFingers(val centre: Offset, val spreadX: Float, val spreadY: Float) {
    /** How far apart the fingers are, for telling a pinch from a push. */
    val distance: Float get() = kotlin.math.hypot(spreadX, spreadY)

    companion object {
        /**
         * How far apart two fingers must be on an axis before a pinch along
         * it is believed.
         *
         * A pinch is almost never square to the grid, so both axes report
         * *some* change and zooming on both would wobble the one you did not
         * mean. Below this the axis reports no change at all, which is what
         * makes a sideways pinch zoom time and leave the pitch where it was.
         */
        const val MinSpread = 48f

        /**
         * How far a gesture must go before it is called one thing or the
         * other. The same slop a drag uses: below it nobody knows what you
         * meant yet, and guessing early is what made a pinch scroll.
         */
        const val LockSlop = 24.0f

        fun of(event: androidx.compose.ui.input.pointer.PointerEvent): TwoFingers? {
            val down = event.changes.filter { it.pressed }
            if (down.size < 2) return null
            val a = down[0].position
            val b = down[1].position
            return TwoFingers(
                Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f),
                abs(a.x - b.x), abs(a.y - b.y),
            )
        }
    }
}

/**
 * What a two-finger gesture turned out to be. One of them, and never two.
 *
 * Moving the fingers apart also moves their middle a little, and sliding them
 * across also changes how far apart they are a little, so a handler that acts
 * on both at once scrolls while it zooms and zooms while it scrolls - which
 * reads as the grid squirming rather than as either thing being done. So the
 * gesture is watched until one of the two is plainly winning, and from then
 * on it is only that until the fingers come up. Zoom also takes one axis, the
 * one the fingers are lined up along, for the same reason.
 */
internal enum class TwoFingerMode { Undecided, Pan, ZoomTime, ZoomPitch }

/** Which of them this is, once it is far enough along to tell. */
internal fun decideTwoFinger(start: TwoFingers, now: TwoFingers): TwoFingerMode {
    val panned = (now.centre - start.centre).getDistance()
    val pinched = abs(now.distance - start.distance)
    return when {
        panned < TwoFingers.LockSlop && pinched < TwoFingers.LockSlop -> TwoFingerMode.Undecided
        panned >= pinched -> TwoFingerMode.Pan
        now.spreadX >= now.spreadY -> TwoFingerMode.ZoomTime
        else -> TwoFingerMode.ZoomPitch
    }
}

/** The result of waiting for a drag: past the slop, or outvoted by a second finger. */
private class Gate(val past: PointerInputChange?, val second: Boolean)

/**
 * Touch slop, unless a second finger arrives first.
 *
 * `awaitTouchSlopOrCancellation` cannot say why it gave up, and here the
 * difference matters: a cancelled gesture leaves the notes alone, a second
 * finger starts moving the view. So this is the same loop with one more exit.
 */
private suspend fun AwaitPointerEventScope.slopOrSecondFinger(
    pointer: PointerId,
    touchSlop: Float,
    start: Offset,
): Gate {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) return Gate(null, true)
        val change = event.changes.firstOrNull { it.id == pointer } ?: return Gate(null, false)
        if (!change.pressed) return Gate(null, false) // released: the caller decides if that was a tap
        if ((change.position - start).getDistance() > touchSlop) {
            change.consume()
            return Gate(change, false)
        }
    }
}

/**
 * Two fingers: the window moves and zooms, and nothing is edited.
 *
 * Panning follows the fingers, as the gutter's drag does - push the grid
 * right and you are looking further back. Zoom is the ratio of how far apart
 * they were to how far apart they are, taken per axis so that one gesture can
 * do either or both without the two being tangled together.
 */
private suspend fun AwaitPointerEventScope.twoFingers(geo: Geometry, cb: Callbacks) {
    val start = TwoFingers.of(currentEvent) ?: return
    var last = start
    var mode = TwoFingerMode.Undecided
    var rowCarry = 0f
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        val now = TwoFingers.of(event) ?: break
        if (mode == TwoFingerMode.Undecided) mode = decideTwoFinger(start, now)

        when (mode) {
            TwoFingerMode.Pan -> {
                cb.onScrollTime(-(now.centre.x - last.centre.x) / geo.pxPerTick)
                // Whole rows only, with the remainder carried, so a slow drag
                // moves one row at a time rather than stalling.
                rowCarry += (now.centre.y - last.centre.y) / geo.rowH
                val rows = rowCarry.toInt()
                if (rows != 0) {
                    cb.onScrollPitch(rows)
                    rowCarry -= rows.toFloat()
                }
            }
            TwoFingerMode.ZoomTime ->
                if (last.spreadX > TwoFingers.MinSpread && now.spreadX > TwoFingers.MinSpread) {
                    cb.onZoom(1.0f, last.spreadX / now.spreadX)
                }
            TwoFingerMode.ZoomPitch ->
                if (last.spreadY > TwoFingers.MinSpread && now.spreadY > TwoFingers.MinSpread) {
                    cb.onZoom(last.spreadY / now.spreadY, 1.0f)
                }
            TwoFingerMode.Undecided -> {}
        }
        last = now
    }
}

private class Hit(val index: Int, val onEdge: Boolean)

/**
 * The corner where the gutter meets the ruler was empty; it now cycles how
 * the roll treats the scale. It greys out and stops responding when no scale
 * is running, because there would be nothing to cycle through.
 */
private fun DrawScope.drawScaleCorner(geo: Geometry, measurer: TextMeasurer, hasScale: Boolean, view: ScaleView, c: AcidColors) {
    drawRect(c.bg, Offset.Zero, Size(geo.originX, geo.originY))
    // Drawn as a key, not as a label: the corner of a table reads as blank
    // unless something in it says otherwise, and this one is a button.
    val pad = 2f
    drawRoundRect(
        c.controlAlt, Offset(pad, pad),
        Size(geo.originX - pad * 2f, geo.originY - pad * 2f),
        androidx.compose.ui.geometry.CornerRadius(3f, 3f),
    )
    drawRoundRect(
        if (hasScale && view != ScaleView.Chromatic) c.accent else c.textFaint,
        Offset(pad, pad), Size(geo.originX - pad * 2f, geo.originY - pad * 2f),
        androidx.compose.ui.geometry.CornerRadius(3f, 3f),
        style = Stroke(width = 1f),
    )
    val label = if (!hasScale) "scl" else when (view) {
        ScaleView.Chromatic -> "chr"
        ScaleView.Dim -> "dim"
        ScaleView.Fold -> "fit"
    }
    val colour = when {
        !hasScale -> c.textDim
        view == ScaleView.Chromatic -> c.textHi
        else -> c.accent
    }
    val laid = measurer.measure(
        AnnotatedString(label),
        TextStyle(color = colour, fontSize = NameTextSize, fontFamily = FontFamily.Monospace),
    )
    drawText(laid, topLeft = Offset((geo.originX - laid.size.width) / 2f, (geo.originY - laid.size.height) / 2f))
}

internal fun isBlackKey(pitch: Int): Boolean = (((pitch % 12) + 12) % 12) in intArrayOf(1, 3, 6, 8, 10)

/**
 * Ticks and pitches ↔ pixels. Rebuilt per event; it is just arithmetic.
 *
 * The note area starts at [originX] and [originY]: everything left of the
 * first is the name gutter, everything above the second is the bar ruler.
 * Both live in the same Canvas as the notes, so there is one coordinate
 * system and one thing to keep in step.
 */
private class Geometry(
    size: Size, val clip: Clip, val ticksPerBar: Int, val rows: Int,
    val originX: Float = 0f, val originY: Float = 0f,
    /** The pitch each row carries, top first. Not always chromatic. */
    private val rowPitches: IntArray = IntArray(0),
    val firstTick: Int = 0,
    windowTicks: Int = 0,
) {
    val totalTicks = clip.bars * ticksPerBar
    /** One past the last tick this page shows. */
    val lastTick = if (windowTicks > 0) min(totalTicks, firstTick + windowTicks) else totalTicks
    private val span = max(1, lastTick - firstTick)
    val fieldW = max(1f, size.width - originX)
    val fieldH = max(1f, size.height - originY)
    val pxPerTick = fieldW / span
    val rowH = if (rows > 0) fieldH / rows else 1f
    val topPitch = rowPitches.firstOrNull() ?: 0
    private val grid = clip.grid.coerceAtLeast(1)

    fun pitchOfRow(r: Int): Int = rowPitches.getOrElse(r) { topPitch - r }

    /**
     * The row a pitch belongs on, which may be off the top or the bottom.
     *
     * When the rows are folded to a scale, a note the scale does not contain
     * takes the row nearest to where it will actually sound, which is the
     * truth the eventor will impose anyway. That search used to run over
     * every row without a bound, so a note *scrolled out of view* also took
     * the nearest row - the last one - and was drawn there: scroll a bass
     * line up two semitones and the C2s reappeared as D2s, sitting on the
     * bottom edge and answering taps meant for the row they had landed on.
     *
     * Nearest-row is for notes between rows, not for notes outside the
     * window. A pitch past either end returns a row past that end, by the
     * semitones it is out by, and the callers' own culling does the rest.
     */
    fun rowOfPitch(pitch: Int): Int {
        if (rowPitches.isEmpty()) return topPitch - pitch
        val highest = rowPitches.first() // row 0: the rows descend in pitch
        val lowest = rowPitches[rowPitches.size - 1]
        if (pitch > highest) return -(pitch - highest)
        if (pitch < lowest) return rowPitches.size - 1 + (lowest - pitch)
        var best = 0
        var bestD = Int.MAX_VALUE
        for (r in rowPitches.indices) {
            val d = kotlin.math.abs(rowPitches[r] - pitch)
            if (d < bestD) { bestD = d; best = r }
            if (d == 0) break
        }
        return best
    }

    fun isExact(pitch: Int): Boolean = rowPitches.isEmpty() || rowPitches.any { it == pitch }

    fun xOf(tick: Int): Float = originX + (tick - firstTick) * pxPerTick
    fun yOf(pitch: Int): Float = originY + rowOfPitch(pitch) * rowH

    fun tickAt(x: Float, snap: Boolean): Int {
        val raw = (firstTick + ((x - originX) / pxPerTick).roundToInt()).coerceIn(0, max(0, totalTicks - 1))
        return if (snap) ((raw + grid / 2) / grid * grid).coerceIn(0, max(0, totalTicks - grid)) else raw
    }

    fun snapDelta(dx: Float): Int = ((dx / pxPerTick) / grid).roundToInt() * grid

    fun pitchAt(y: Float): Int {
        val r = ((y - originY) / rowH).toInt().coerceIn(0, max(0, rows - 1))
        return pitchOfRow(r).coerceIn(0, 127)
    }

    fun noteRect(note: Note): Rect {
        val left = xOf(note.tick)
        val right = xOf(min(lastTick, note.tick + max(1, note.length)))
        val top = yOf(note.pitch)
        return Rect(left, top, max(right, left + 3f), top + rowH)
    }

    fun hitTest(p: Offset): Hit? {
        // Later notes draw on top, so search from the end.
        for (i in clip.notes.indices.reversed()) {
            val r = noteRect(clip.notes[i])
            if (p.x in r.left..r.right && p.y in r.top..r.bottom) {
                val edgeZone = min(r.width * 0.35f, 48f)
                return Hit(i, onEdge = r.width > 24f && abs(p.x - r.right) <= edgeZone)
            }
        }
        return null
    }
}
