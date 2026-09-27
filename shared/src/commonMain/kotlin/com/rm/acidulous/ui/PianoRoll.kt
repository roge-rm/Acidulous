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
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.draw.clipToBounds
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
import com.rm.acidulous.res.*

enum class EditMode { Draw, Select }

/**
 * How the roll treats a scale. Chromatic ignores it, Dim greys the rows a
 * Scale modifier would move, and Fold drops those rows entirely so only
 * playable notes have a lane. The corner of the roll cycles them.
 */
enum class ScaleView { Chromatic, Dim, Fold }

/**
 * The clip editor: one Canvas, with notes drawn and hit tested by hand. A
 * composable per note would be far too slow on a 16 bar clip with chords.
 *
 * Draw mode: a tap on empty adds a one grid note, a tap on a note deletes
 * it, dragging a note moves it, dragging its right edge resizes it, dragging
 * on empty draws and stretches a note. Select mode: dragging on empty
 * rubber-bands, a tap toggles a note, dragging a selected note moves the
 * selection. Velocity is the brighter region inside each note.
 *
 * Every drag reports the total change since it began, so the caller works
 * from a gesture base (see SongEditor) instead of accumulating.
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
    /** How the running scale spells its notes; empty is chromatic. */
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
    /** Tapping a name in the gutter plays that pitch. */
    onAudition: (pitch: Int) -> Unit = {},
    /** Ticks where a step's parameter lock starts; notes there are marked. */
    lockedTicks: Set<Int> = emptySet(),
    /**
     * Dragging the gutter moves the pitch window by this many semitones, to
     * reach the notes outside the sixteen rows shown.
     */
    onScrollPitch: (delta: Int) -> Unit = {},
    /** Two fingers sideways move the window by this many ticks. */
    onScrollTime: (ticks: Float) -> Unit = {},
    /**
     * A pinch. Each axis is a multiplier on what's shown, below 1 zooms in. An
     * axis the fingers aren't spread along reports 1, so a sideways pinch only
     * zooms time.
     */
    onZoom: (pitchScale: Float, timeScale: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val scaleWords = listOf(
        stringResource(Res.string.roll_scale_none), stringResource(Res.string.roll_scale_chromatic),
        stringResource(Res.string.roll_scale_dim), stringResource(Res.string.roll_scale_fit),
    )
    // The pointer handler must survive the clip changing mid-drag (every
    // updateGesture commits a new clip), so it reads through these.
    val clipState by rememberUpdatedState(clip)
    val modeState by rememberUpdatedState(mode)
    val selectionState by rememberUpdatedState(selection)
    val lowestState by rememberUpdatedState(lowestPitch)
    val rowsState by rememberUpdatedState(rows)
    val cb by rememberUpdatedState(
        Callbacks(onTapEmpty, onTapNote, onSelectionChange, onGestureBegin, onMove, onResize, onDraw, onGestureEnd,
            onAudition, onCycleScaleView, onScrollPitch, onScrollTime, onZoom),
    )
    // The pitch of each row. Chromatic and Dim step by semitone; Fold keeps
    // only the scale's notes, so every row is playable.
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

    // Read here because the Canvas draw lambda isn't composition and can't
    // read the theme itself.
    val c = Acid.colors

    var rubberBand by remember { mutableStateOf<Rect?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // TalkBack gets a summary, since one picture can't be walked note by
    // note. Notes go in by playing the keys while recording.
    val resources = AppStrings
    val summary = if (clip.notes.isEmpty()) resources.getString(Res.string.a11y_roll_empty) else {
        val low = clip.notes.minOf { it.pitch }
        val high = clip.notes.maxOf { it.pitch }
        resources.getQuantityString(
            Res.plurals.a11y_roll, clip.notes.size, clip.notes.size,
            spokenNote(low, noteSpelling, resources), spokenNote(high, noteSpelling, resources),
        )
    }
    // Keyboard editing. Enter starts editing and shows a one grid step cursor
    // on one pitch. Arrows move it, and the page and pitch window follow. Enter
    // adds or removes a note there, Shift+arrows change its length, Alt+arrows
    // move it, Delete removes it, and Esc stops editing so the arrows move
    // between controls again. It's a mode so a phone whose only arrows are a
    // touchpad can still leave the roll.
    var keyFocused by remember { mutableStateOf(false) }
    var keyEditing by remember { mutableStateOf(false) }
    var curTick by remember { mutableIntStateOf(firstTick) }
    var curPitch by remember { mutableIntStateOf(lowestPitch + rows / 2) }
    val firstState by rememberUpdatedState(firstTick)
    val visibleState by rememberUpdatedState(visibleTicks)
    val keyMod = Modifier
        .onFocusChanged { keyFocused = it.isFocused; if (!it.isFocused) keyEditing = false }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
            val e = ev.press
            val code = e.keyCode
            val enter = code == KeyCodes.KEYCODE_ENTER || code == KeyCodes.KEYCODE_NUMPAD_ENTER ||
                code == KeyCodes.KEYCODE_DPAD_CENTER
            if (!keyEditing) {
                if (!enter) return@onKeyEvent false
                keyEditing = true
                // Start where the user is looking.
                val lo = lowestState
                if (curTick !in firstState until firstState + visibleState) curTick = firstState
                if (curPitch !in lo until lo + rowsState) curPitch = lo + rowsState / 2
                return@onKeyEvent true
            }
            val c = clipState
            val grid = c.grid.coerceAtLeast(1)
            val total = (c.bars * ticksPerBar).coerceAtLeast(grid)
            val under = c.notes.indexOfFirst { it.pitch == curPitch && curTick >= it.tick && curTick < it.tick + max(1, it.length) }
            fun keepInView() {
                val first = firstState
                val visible = visibleState
                if (curTick < first) cb.onScrollTime((curTick - first).toFloat())
                else if (curTick + grid > first + visible) cb.onScrollTime((curTick + grid - first - visible).toFloat())
                val lo = lowestState
                if (curPitch < lo) cb.onScrollPitch(curPitch - lo)
                else if (curPitch > lo + rowsState - 1) cb.onScrollPitch(curPitch - (lo + rowsState - 1))
            }
            fun step(dTick: Int, dPitch: Int) {
                if (e.isAltPressed && under >= 0) {
                    // Move the note, and the cursor with it.
                    cb.onGestureBegin(); cb.onMove(setOf(under), dTick, dPitch); cb.onGestureEnd()
                }
                curTick = (curTick + dTick).coerceIn(0, total - grid)
                curPitch = (curPitch + dPitch).coerceIn(0, 127)
                keepInView()
            }
            when (code) {
                KeyCodes.KEYCODE_ESCAPE -> { keyEditing = false; true }
                KeyCodes.KEYCODE_ENTER, KeyCodes.KEYCODE_NUMPAD_ENTER,
                KeyCodes.KEYCODE_DPAD_CENTER -> {
                    if (under >= 0) cb.onTapNote(under) else cb.onTapEmpty(curTick, curPitch)
                    true
                }
                KeyCodes.KEYCODE_DEL, KeyCodes.KEYCODE_FORWARD_DEL -> {
                    if (under >= 0) cb.onTapNote(under)
                    true
                }
                KeyCodes.KEYCODE_DPAD_LEFT, KeyCodes.KEYCODE_DPAD_RIGHT -> {
                    val dir = if (code == KeyCodes.KEYCODE_DPAD_RIGHT) 1 else -1
                    if (e.isShiftPressed && under >= 0) {
                        val n = c.notes[under]
                        cb.onGestureBegin(); cb.onResize(under, (n.length + dir * grid).coerceAtLeast(grid)); cb.onGestureEnd()
                    } else step(dir * grid, 0)
                    true
                }
                KeyCodes.KEYCODE_DPAD_UP -> { step(0, 1); true }
                KeyCodes.KEYCODE_DPAD_DOWN -> { step(0, -1); true }
                KeyCodes.KEYCODE_PAGE_UP -> { step(0, 12); true }
                KeyCodes.KEYCODE_PAGE_DOWN -> { step(0, -12); true }
                else -> false
            }
        }
    // On desktop the wheel does what two fingers do, see onWheel. Rows move
    // three per notch and time a tenth of the window, with the remainder
    // carried so small touchpad steps add up.
    val wheelCarry = remember { floatArrayOf(0f) }
    val wheel = Modifier.onWheel { w ->
        when {
            w.zoom && w.shift -> cb.onZoom(w.zoomFactor, 1f)
            w.zoom -> cb.onZoom(1f, w.zoomFactor)
            else -> {
                if (w.across != 0f) cb.onScrollTime(w.across * visibleTicks / 10f)
                wheelCarry[0] -= w.down * 3f
                val rows = wheelCarry[0].toInt()
                if (rows != 0) {
                    cb.onScrollPitch(rows)
                    wheelCarry[0] -= rows.toFloat()
                }
            }
        }
        true
    }
    Canvas(
        // Clipped, or notes just above the window draw over the header.
        modifier = modifier.clipToBounds().semantics { contentDescription = summary }.then(keyMod).then(wheel).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val geo = Geometry(
                    canvasSize, clipState, ticksPerBar, rowsState,
                    GutterWidth.toPx(), RulerHeight.toPx(), rowsState2, firstTick, visibleTicks,
                )
                val press = down.position

                // Two fingers move the view, never the notes. Checked first, because
                // a pinch's fingers land a few milliseconds apart and whatever the
                // first one hit must not act in the meantime.
                if (currentEvent.changes.count { it.pressed } >= 2) {
                    twoFingers(geo, cb)
                    return@awaitEachGesture
                }

                // The gutter plays the row it names and scrolls the window, the
                // ruler takes no edits, and the corner between them cycles the scale
                // view. None of them can draw a note.
                if (press.x < geo.originX || press.y < geo.originY) {
                    if (press.y < geo.originY) {
                        if (press.x < geo.originX) cb.onCycleScaleView()
                        down.consume()
                        return@awaitEachGesture
                    }
                    // Don't consume the down here: awaitTouchSlopOrCancellation
                    // gives up as soon as it sees a consumed change, so the drag
                    // below could never start.
                    //
                    // The note plays on release, not on down, so a scroll doesn't
                    // start with a stray note.
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
                    // Drag down and the rows come down with it, like a list scrolls.
                    //
                    // Measured from where the press began, not summed from deltas,
                    // because positionChange() is zero once a change is consumed.
                    // The rest of this file uses absolute positions for the same
                    // reason.
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

                // A drag. What it does depends on where it started.
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
        // move pushed further back in Dim.
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
            // Off this page entirely.
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
            // The same wedge the drum grid draws: a mark that the note has a
            // condition. The note lane below shows which.
            if (note.hasTrig) {
                val w = minOf(rect.width, rect.height) * 0.4f
                drawPath(
                    Path().apply {
                        moveTo(rect.right, rect.top)
                        lineTo(rect.right - w, rect.top)
                        lineTo(rect.right, rect.top + w)
                        close()
                    },
                    c.teal,
                )
            }
            // A locked note gets the knob's ◆, in the other corner from the trig's.
            if (note.tick in lockedTicks) {
                val w = minOf(rect.width, rect.height) * 0.22f
                val cx = rect.left + w * 1.4f
                val cy = rect.bottom - w * 1.4f
                drawPath(
                    Path().apply { moveTo(cx, cy - w); lineTo(cx + w, cy); lineTo(cx, cy + w); lineTo(cx - w, cy); close() },
                    c.pink,
                )
            }
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

        // The keyboard's focus and cursor.
        if (keyFocused) {
            drawRect(c.accent, Offset.Zero, size, style = Stroke(2.dp.toPx()))
            if (keyEditing) {
                val grid = clip.grid.coerceAtLeast(1)
                val left = geo.xOf(curTick)
                val right = geo.xOf(curTick + grid)
                val top = geo.yOf(curPitch)
                if (right > geo.originX && left < size.width && top >= geo.originY - 1f && top < size.height) {
                    drawRect(c.pink, Offset(left, top), Size(max(right - left, 4f), geo.rowH), style = Stroke(2.5f))
                }
            }
        }

        drawNameGutter(geo, textMeasurer, scale, c, noteSpelling)
        drawPitchPosition(geo, c)
        drawBarRuler(geo, size, textMeasurer, playheadTick, c)
        drawScaleCorner(geo, textMeasurer, scalePitchClasses != null, scaleView, c, scaleWords)
    }
}

/**
 * A recorded bend, drawn inside its note. Full deflection is the note's own
 * row height and means a semitone; anything wider pins to the edge.
 *
 * Only bend is drawn. Pressure and slide are recorded and played, but three
 * lines in one small box can't be read.
 */
private fun DrawScope.drawBend(note: Note, rect: Rect, c: AcidColors) {
    val bend = note.bend ?: return
    if (bend.points.size < 2 || rect.width < 4f) return
    val len = max(1, note.length)
    val mid = rect.center.y
    val half = (rect.height - 3f) / 2f
    // One sample per pixel of the note's width, capped.
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
 * A position bar showing where the sixteen rows sit in the 128 pitches. It
 * also hints that the gutter can be dragged. Drawn to the sizes in
 * `ui/Scrollbar.kt` so it matches the lists.
 */
private fun DrawScope.drawPitchPosition(geo: Geometry, c: AcidColors) {
    val top = geo.pitchOfRow(0)
    val bottom = geo.pitchOfRow(geo.rows - 1)
    val shown = (top - bottom + 1).coerceIn(1, 128)
    if (shown >= 128) return
    val track = geo.fieldH
    val thickness = 3.dp.toPx()
    val inset = 1.dp.toPx()
    // Floor first, then ceiling, never coerceIn: the roll can be squeezed to
    // nothing (the fx panel with a Filter in it) and a 20 dp floor over a one
    // pixel track is an empty range, which throws.
    val thumb = (track * shown / 128f).coerceAtLeast(20.dp.toPx()).coerceAtMost(track)
    // Pitch runs up the screen and the bar runs down, so the thumb is measured
    // from the highest note.
    val travel = track - thumb
    val pos = geo.originY + travel * ((127 - top).coerceIn(0, 127) / (128f - shown).coerceAtLeast(1f))
    drawRoundRect(c.scrollbar, Offset(inset, pos), Size(thickness, thumb), CornerRadius(thickness / 2f))
}

/**
 * The left gutter names every row instead of drawing a keyboard, which is
 * easier to read and to hit on a phone. Black keys keep a darker fill and
 * every C is in the accent colour. When rows are too short, only the C rows
 * keep a label.
 */
private fun DrawScope.drawNameGutter(
    geo: Geometry, measurer: TextMeasurer, scale: Set<Int>?, c: AcidColors,
    noteSpelling: Map<Int, String>,
) {
    drawRect(c.bg, Offset.Zero, Size(geo.originX, size.height))
    // A 10sp line is about 12dp tall, so below this only the Cs are named.
    // The measured check below is the real limit.
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
 * The ruler counts bars, with a tick per beat. Bar numbers sit just right of
 * their line, and the playhead is a wedge so it never hides a number.
 */
private fun DrawScope.drawBarRuler(geo: Geometry, size: Size, measurer: TextMeasurer, playheadTick: Long?, c: AcidColors) {
    drawRect(c.sunken, Offset.Zero, Size(size.width, geo.originY))
    val beats = max(1, geo.ticksPerBar / PPQN)
    val barW = geo.pxPerTick * geo.ticksPerBar
    // Number the beats too when a bar is wide enough.
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
                // Beats read ".2" and bars "2", like the transport's 1.1.000, so they
                // don't look alike.
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
 * Where two fingers' middle is and how far apart they are. Shared with the
 * drum grid.
 */
internal class TwoFingers(val centre: Offset, val spreadX: Float, val spreadY: Float) {
    /** How far apart the fingers are, to tell a pinch from a push. */
    val distance: Float get() = kotlin.math.hypot(spreadX, spreadY)

    companion object {
        /**
         * How far apart two fingers must be on an axis before a pinch along it
         * counts. A pinch is never square to the grid, so without this both axes
         * would zoom. It's what makes a sideways pinch zoom only time.
         */
        const val MinSpread = 48f

        /**
         * How far a gesture must go before it's decided. Same as a drag's slop;
         * deciding earlier made pinches scroll.
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
 * What a two-finger gesture turned out to be, only ever one. Spreading the
 * fingers also moves their middle a bit and vice versa, so the gesture is
 * watched until one is clearly winning and then stays that until the fingers
 * lift. Zoom also takes only the axis the fingers are lined up along.
 */
internal enum class TwoFingerMode { Undecided, Pan, ZoomTime, ZoomPitch }

/** Which mode this is, once it's far enough along to tell. */
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

/** The result of waiting for a drag: past the slop, or a second finger arrived. */
private class Gate(val past: PointerInputChange?, val second: Boolean)

/**
 * Touch slop, unless a second finger arrives first.
 *
 * `awaitTouchSlopOrCancellation` can't say why it gave up, and here it
 * matters: a cancel leaves the notes alone, a second finger moves the view.
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
 * Two fingers move and zoom the window and edit nothing.
 *
 * Panning follows the fingers like the gutter drag. Zoom is the ratio of the
 * old spread to the new, per axis.
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
                // moves one row at a time.
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
 * The corner where the gutter meets the ruler cycles the scale view. It
 * greys out and does nothing when no scale is running.
 */
private fun DrawScope.drawScaleCorner(
    geo: Geometry, measurer: TextMeasurer, hasScale: Boolean, view: ScaleView, c: AcidColors,
    /** The corner's labels: no scale, then [ScaleView]'s three, in order. */
    words: List<String>,
) {
    drawRect(c.bg, Offset.Zero, Size(geo.originX, geo.originY))
    // Drawn as a key so it looks like a button.
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
    val label = if (!hasScale) words[0] else when (view) {
        ScaleView.Chromatic -> words[1]
        ScaleView.Dim -> words[2]
        ScaleView.Fold -> words[3]
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
 * Converts ticks and pitches to pixels and back. Rebuilt per event.
 *
 * The note area starts at [originX] and [originY]. Left of it is the name
 * gutter, above it the bar ruler, all in the same Canvas.
 */
private class Geometry(
    size: Size, val clip: Clip, val ticksPerBar: Int, val rows: Int,
    val originX: Float = 0f, val originY: Float = 0f,
    /** The pitch of each row, top first. Not always chromatic. */
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
     * With rows folded to a scale, a note outside the scale takes the nearest
     * row, which is where it will sound. That only applies between rows: a
     * pitch past either end returns a row past that end by the semitones it's
     * out by, so scrolled-out notes aren't drawn on the edge row.
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
