package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.Math
import kotlinx.coroutines.launch

/**
 * Which note each pad of the note grid plays. Columns go up the scale, each
 * row starts a fourth above the one below. With a scale running only its
 * notes are there and a row starts on the scale note nearest a fourth up, so
 * the root stays on the left of the bottom row. Without one it's chromatic
 * from C, five semitones a row. With [allNotes] a scale only sets the root
 * and which pads are out of key: still chromatic, five a row, from the root.
 */
internal class GridNotes(scale: Set<Int>?, root: Int?, val octave: Int, allNotes: Boolean = false) {
    /** The root's pitch class; C when there's no scale. */
    val root: Int = if (scale.isNullOrEmpty()) 0 else Math.floorMod(root ?: scale.min(), 12)
    /** Semitones above the root of each scale note, or all twelve. */
    val steps: List<Int> = scale?.takeIf { it.isNotEmpty() && !allNotes }
        ?.map { Math.floorMod(it - this.root, 12) }?.distinct()?.sorted()
        ?: (0..11).toList()
    val chromatic = steps.size == 12
    private val keyClasses: Set<Int>? = scale?.takeIf { it.isNotEmpty() }?.map { Math.floorMod(it, 12) }?.toSet()
    /** Whether out-of-key pads are shown dimmed: every note with a scale running. */
    val dimsOutOfKey = allNotes && keyClasses != null

    /** How many scale notes up each row starts: the one nearest a fourth, the higher on a tie. */
    val rowStep: Int = steps.indices.minByOrNull { i -> kotlin.math.abs(steps[i] - 5) * 2 - (if (steps[i] > 5) 1 else 0) }
        ?.coerceAtLeast(1) ?: 1

    /** The note at [row] (0 at the bottom) and [col] (0 on the left), or null outside MIDI's range. */
    fun noteAt(row: Int, col: Int): Int? {
        val idx = row * rowStep + col
        val n = steps.size
        val note = 12 * (octave + 1) + root + 12 * Math.floorDiv(idx, n) + steps[Math.floorMod(idx, n)]
        return note.takeIf { it in 0..127 }
    }

    /** True for every note when no scale is running. */
    fun inKey(note: Int): Boolean = keyClasses?.contains(Math.floorMod(note, 12)) ?: true

    fun isRoot(note: Int): Boolean = Math.floorMod(note, 12) == root
}

/** The smallest a pad gets. Fewer pads, not smaller ones. */
private val PadMin = 70.dp
private val PadGap = 3.dp

/** Columns, rows and the pad's side for an area [w] by [h], all in px. */
private class GridFit(w: Float, h: Float, padMin: Float) {
    val cols = (w / padMin).toInt().coerceIn(1, 16)
    val rows = (h / padMin).toInt().coerceIn(1, 10)
    val side = minOf(w / cols, h / rows)
    val x0 = (w - cols * side) / 2f
    val y0 = (h - rows * side) / 2f

    /** Row (0 at the bottom) and column under [p], or null between or outside the pads. */
    fun cellAt(p: Offset): Pair<Int, Int>? {
        val cx = ((p.x - x0) / side).let { if (it < 0f) return null else it.toInt() }
        val cy = ((p.y - y0) / side).let { if (it < 0f) return null else it.toInt() }
        if (cx >= cols || cy >= rows) return null
        return (rows - 1 - cy) to cx
    }

    /** How far up its pad a touch landed, 0 at the bottom edge and 1 at the top. */
    fun strikeAt(p: Offset): Float {
        if (side <= 0f) return 1f
        val y = p.y - y0
        val inPad = y - kotlin.math.floor(y / side) * side
        return (1f - inPad / side).coerceIn(0f, 1f)
    }

    fun topLeft(row: Int, col: Int) = Offset(x0 + col * side, y0 + (rows - 1 - row) * side)
}

/**
 * The note grid: square pads, rows a fourth apart, playing [rack] the way
 * [PianoKeys] does: one note per finger, sliding moves between pads, higher
 * on a pad is harder. The root is lit. Out-of-scale notes aren't there, or with
 * [allNotes] are dimmed.
 */
@Composable
fun NoteGrid(
    rack: Int,
    scalePitchClasses: Set<Int>?,
    scaleRoot: Int?,
    octave: Int,
    modifier: Modifier = Modifier,
    noteSpelling: Map<Int, String> = emptyMap(),
    allNotes: Boolean = false,
) {
    val c = Acid.colors
    val measurer = rememberTextMeasurer()
    val notes = remember(scalePitchClasses, scaleRoot, octave, allNotes) { GridNotes(scalePitchClasses, scaleRoot, octave, allNotes) }
    var held by remember { mutableStateOf(mapOf<Long, Int>()) }
    var strikes by remember { mutableStateOf(mapOf<Int, Float>()) }
    Box(
        modifier.pointerInput(notes, rack) {
            val fitOf = { GridFit(size.width.toFloat(), size.height.toFloat(), PadMin.toPx()) }
            // The loop owns which notes are down, as in PianoKeys.
            val down = HashMap<Long, Int>()
            val force = HashMap<Int, Float>()
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val fit = fitOf()
                        var changed = false
                        for (change in event.changes) {
                            val id = change.id.value
                            if (change.pressed) {
                                val note = fit.cellAt(change.position)?.let { (r, col) -> notes.noteAt(r, col) }
                                if (down[id] != note) {
                                    down[id]?.let { NativeEngine.noteOff(rack, it); force.remove(it) }
                                    if (note != null) {
                                        val strike = if (UiPrefs.keysFullStrength) 1f else fit.strikeAt(change.position)
                                        val vel = if (UiPrefs.keysFullStrength) HARD else SOFT + (HARD - SOFT) * strike
                                        NativeEngine.noteOn(rack, note, vel.toInt())
                                        down[id] = note
                                        force[note] = strike
                                    } else down.remove(id)
                                    changed = true
                                }
                            } else if (down.containsKey(id)) {
                                down.remove(id)?.let { NativeEngine.noteOff(rack, it); force.remove(it) }
                                changed = true
                            }
                            change.consume()
                        }
                        if (changed) {
                            held = HashMap(down)
                            strikes = HashMap(force)
                        }
                    }
                }
            } finally {
                for (note in down.values) NativeEngine.noteOff(rack, note)
                down.clear()
                force.clear()
                held = emptyMap()
                strikes = emptyMap()
            }
        },
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .then(if (KeyHub.playMode) Modifier.border(2.dp, c.accent, RoundedCornerShape(3.dp)) else Modifier),
        ) {
            val fit = GridFit(size.width, size.height, PadMin.toPx())
            val gap = PadGap.toPx()
            val corner = CornerRadius(4.dp.toPx())
            val down = held.values.toSet() + KeyHub.lit.mapNotNull { (r, note) -> note.takeIf { r == rack } }
            val nameStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            for (row in 0 until fit.rows) for (col in 0 until fit.cols) {
                val note = notes.noteAt(row, col) ?: continue
                val tl = fit.topLeft(row, col) + Offset(gap / 2f, gap / 2f)
                val s = fit.side - gap
                val root = notes.isRoot(note)
                // Without a scale the black-key notes are darker so the grid can be read.
                val face = when {
                    root -> c.accentDim
                    notes.dimsOutOfKey -> if (notes.inKey(note)) c.raised else c.card
                    notes.chromatic && isBlackKey(note) -> c.card
                    else -> c.raised
                }
                drawRoundRect(face, tl, Size(s, s), corner)
                if (note in down) {
                    // Lit from the bottom edge to where the finger landed, as the keys are.
                    val fill = s * (strikes[note] ?: 1f)
                    drawRoundRect(c.teal, Offset(tl.x, tl.y + s - fill), Size(s, fill), corner)
                }
                val laid = measurer.measure(
                    AnnotatedString(noteName(note, noteSpelling)),
                    nameStyle.copy(color = if (note in down) c.keyLabel else if (root) c.accent else if (notes.dimsOutOfKey && !notes.inKey(note)) c.textFaint else c.textMid),
                )
                drawText(laid, topLeft = Offset(tl.x + 5f, tl.y + s - laid.size.height - 4f))
            }
        }
        PadsForTalkBack(rack, notes, noteSpelling)
    }
}

/** One TalkBack button per pad, named for its note; a double tap plays it briefly. */
@Composable
private fun PadsForTalkBack(rack: Int, notes: GridNotes, spelling: Map<Int, String>) {
    val density = LocalDensity.current
    val resources = AppStrings
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = with(density) { GridFit(maxWidth.toPx(), maxHeight.toPx(), PadMin.toPx()) }
        val side = with(density) { fit.side.toDp() }
        // Bottom row first, so TalkBack reads up in pitch.
        for (row in 0 until fit.rows) for (col in 0 until fit.cols) {
            val note = notes.noteAt(row, col) ?: continue
            val tl = fit.topLeft(row, col)
            Box(
                Modifier
                    .offset { IntOffset(tl.x.toInt(), tl.y.toInt()) }
                    .size(side)
                    .button(spokenNote(note, spelling, resources), onClick = {
                        NativeEngine.noteOn(rack, note, 100)
                        scope.launch { kotlinx.coroutines.delay(300); NativeEngine.noteOff(rack, note) }
                    }),
            )
        }
    }
}
