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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EditMode { Draw, Select }

/**
 * The clip editor: one Canvas, notes drawn by hand, hit-tested by hand.
 * A composable per note would crawl on a 16-bar clip with chords.
 *
 * The reference sequencer's conventions, kept: in Draw mode a tap on empty adds a note of one
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
    onTapEmpty: (tick: Int, pitch: Int) -> Unit,
    onTapNote: (index: Int) -> Unit,
    onSelectionChange: (Set<Int>) -> Unit,
    onGestureBegin: () -> Unit,
    onMove: (indices: Set<Int>, dTick: Int, dPitch: Int) -> Unit,
    onResize: (index: Int, newLength: Int) -> Unit,
    onDraw: (tick: Int, pitch: Int, length: Int) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The pointer handler must survive the clip changing under it mid-drag
    // (every updateGesture commits a new clip), so it reads through these.
    val clipState by rememberUpdatedState(clip)
    val modeState by rememberUpdatedState(mode)
    val selectionState by rememberUpdatedState(selection)
    val lowestState by rememberUpdatedState(lowestPitch)
    val rowsState by rememberUpdatedState(rows)
    val cb by rememberUpdatedState(
        Callbacks(onTapEmpty, onTapNote, onSelectionChange, onGestureBegin, onMove, onResize, onDraw, onGestureEnd),
    )

    var rubberBand by remember { mutableStateOf<Rect?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val geo = Geometry(canvasSize, clipState, ticksPerBar, lowestState, rowsState)
                val press = down.position
                val hit = geo.hitTest(press)

                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
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
        val geo = Geometry(size, clip, ticksPerBar, lowestPitch, rows)

        // Rows: black keys darker, C rows marked.
        for (r in 0 until rows) {
            val pitch = geo.topPitch - r
            val isBlack = (pitch % 12) in setOf(1, 3, 6, 8, 10)
            drawRect(
                color = if (isBlack) Color(0xFF232326) else Color(0xFF2C2C30),
                topLeft = Offset(0f, r * geo.rowH),
                size = Size(size.width, geo.rowH),
            )
            if (pitch % 12 == 0) {
                drawLine(Color(0xFF3E3E44), Offset(0f, (r + 1) * geo.rowH), Offset(size.width, (r + 1) * geo.rowH), 2f)
            }
        }

        // Grid: subdivision, beat, bar.
        var t = 0
        while (t <= geo.totalTicks) {
            val x = geo.xOf(t)
            val (color, width) = when {
                t % ticksPerBar == 0 -> Color(0xFF8A8A92) to 2.5f
                t % PPQN == 0 -> Color(0xFF55555C) to 1.5f
                else -> Color(0xFF3A3A40) to 1f
            }
            drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
            t += clip.grid.coerceAtLeast(1)
        }

        // Notes, with velocity as the bright inner region.
        clip.notes.forEachIndexed { i, note ->
            val rect = geo.noteRect(note)
            if (rect.bottom < 0f || rect.top > size.height) return@forEachIndexed
            val selected = i in selection
            drawRect(if (selected) Color(0xFFF2F2F0) else Color(0xFF4E8F73), rect.topLeft, rect.size)
            val velH = (rect.height - 4f) * (note.velocity.coerceIn(1, 127) / 127f)
            drawRect(
                if (selected) Color(0xFFB7E3CF) else Color(0xFF7FD1B9),
                Offset(rect.left + 2f, rect.bottom - 2f - velH),
                Size(max(0f, rect.width - 4f), velH),
            )
            drawRect(Color(0xFF1B1B1E), rect.topLeft, rect.size, style = Stroke(1.5f))
        }

        rubberBand?.let { band ->
            drawRect(Color(0x33FFFFFF), band.topLeft, band.size)
            drawRect(Color(0xCCFFFFFF), band.topLeft, band.size, style = Stroke(1.5f))
        }

        playheadTick?.let { tick ->
            val x = geo.xOf((tick % max(1, geo.totalTicks)).toInt())
            drawLine(Color(0xFFFFB454), Offset(x, 0f), Offset(x, size.height), 3f)
        }
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
)

private class Hit(val index: Int, val onEdge: Boolean)

/** Ticks and pitches ↔ pixels. Rebuilt per event; it is just arithmetic. */
private class Geometry(size: Size, val clip: Clip, ticksPerBar: Int, lowestPitch: Int, rows: Int) {
    val totalTicks = clip.bars * ticksPerBar
    val pxPerTick = if (totalTicks > 0) size.width / totalTicks else 1f
    val rowH = if (rows > 0) size.height / rows else 1f
    val topPitch = lowestPitch + rows - 1
    private val grid = clip.grid.coerceAtLeast(1)

    fun xOf(tick: Int): Float = tick * pxPerTick
    fun yOf(pitch: Int): Float = (topPitch - pitch) * rowH

    fun tickAt(x: Float, snap: Boolean): Int {
        val raw = (x / pxPerTick).roundToInt().coerceIn(0, max(0, totalTicks - 1))
        return if (snap) ((raw + grid / 2) / grid * grid).coerceIn(0, max(0, totalTicks - grid)) else raw
    }

    fun snapDelta(dx: Float): Int = ((dx / pxPerTick) / grid).roundToInt() * grid

    fun pitchAt(y: Float): Int = (topPitch - (y / rowH).toInt()).coerceIn(0, 127)

    fun noteRect(note: Note): Rect {
        val left = xOf(note.tick)
        val right = xOf(min(totalTicks, note.tick + max(1, note.length)))
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
