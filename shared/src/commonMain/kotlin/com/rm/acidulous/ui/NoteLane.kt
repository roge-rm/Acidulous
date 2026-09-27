package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Trig
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * The per-note values shown in the note lane under the roll: velocity,
 * chance, trig condition, ratchet and nudge.
 *
 * The lane is laid out like the automation strip (same gutter width so the
 * ticks line up, same side label and fold) but draws one bar per note
 * instead of a curve.
 */
enum class NoteProp(val short: StringResource, val label: StringResource) {
    Velocity(Res.string.note_prop_velocity_short, Res.string.note_prop_velocity),
    Chance(Res.string.note_prop_chance_short, Res.string.note_prop_chance),
    Cond(Res.string.note_prop_cond_short, Res.string.note_prop_cond),
    Ratchet(Res.string.note_prop_ratchet_short, Res.string.note_prop_ratchet),
    Nudge(Res.string.note_prop_nudge_short, Res.string.note_prop_nudge),
}

/** How far a note may be pushed off the grid: half a sixteenth either way. */
const val NUDGE_RANGE = PPQN / 4

/** A note's value for one property as short text. */
private fun valueText(n: Note, prop: NoteProp): String = when (prop) {
    NoteProp.Velocity -> "${n.velocity}"
    NoteProp.Chance -> "${n.chance}%"
    NoteProp.Ratchet -> "x${n.ratchet}"
    NoteProp.Nudge -> if (n.nudge > 0) "+${n.nudge}" else "${n.nudge}"
    NoteProp.Cond -> n.trig.short.ifEmpty { "-" }
}

@Composable
fun NoteLane(
    clip: Clip,
    ticksPerBar: Int,
    playheadTick: Long?,
    firstTick: Int = 0,
    visibleTicks: Int = 0,
    prop: NoteProp,
    onProp: (NoteProp) -> Unit,
    onGestureBegin: () -> Unit,
    /** Every note the finger passed over, with the value it should take. */
    onSet: (Map<Int, Float>) -> Unit,
    onGestureEnd: () -> Unit,
    /**
     * True when the editor above draws every note as a whole grid cell (the
     * drum grid) instead of at its own length (the piano roll). Only changes
     * where a column is centred.
     */
    cellWide: Boolean = false,
    /**
     * Show only this pitch, or every pitch when null. Notes on the same tick
     * share a column, so this is how you pick out one of them, and how a
     * sweep sets only the hats in a bar.
     */
    pitchFilter: Int? = null,
    onPitchFilter: (Int?) -> Unit = {},
    /** What to call a pitch: a drum voice's short name, or a note name. */
    pitchName: (Int) -> String = { "$it" },
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    var menu by remember { mutableStateOf(false) }
    /**
     * The note being dragged and its new value, so the gutter can show the
     * number. Cleared when the gesture ends.
     */
    var editing by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    // The gesture block is keyed on Unit so it never restarts mid-drag, which
    // means it has to read everything through these updated states.
    val clipState by rememberUpdatedState(clip)
    val propState by rememberUpdatedState(prop)
    val setState by rememberUpdatedState(onSet)
    val beginState by rememberUpdatedState(onGestureBegin)
    val endState by rememberUpdatedState(onGestureEnd)
    val filterState by rememberUpdatedState(pitchFilter)
    val cellState by rememberUpdatedState(cellWide)
    val foldedState by rememberUpdatedState(collapsed)
    val expandState by rememberUpdatedState(onToggleCollapse)

    // shown() and centreTick() are called from the gesture block too, so they
    // read the updated states. Reading the parameters directly would keep the
    // values from when the gesture block was built.
    fun shown(n: Note): Boolean = filterState == null || n.pitch == filterState

    // Keyboard editing: Enter starts, left and right move between the notes
    // shown, up and down change the value (one undo step each), Esc stops.
    var keyFocused by remember { mutableStateOf(false) }
    var keyEditing by remember { mutableStateOf(false) }
    var keyNote by remember { mutableStateOf(-1) } // an index into clip.notes
    val laneKeys = Modifier
        .onFocusChanged { keyFocused = it.isFocused; if (!it.isFocused) keyEditing = false }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
            val e = ev.press
            val code = e.keyCode
            val enter = code == KeyCodes.KEYCODE_ENTER || code == KeyCodes.KEYCODE_NUMPAD_ENTER ||
                code == KeyCodes.KEYCODE_DPAD_CENTER
            val c = clipState
            // The notes the lane shows, in time order.
            val order = c.notes.indices.filter { shown(c.notes[it]) }.sortedBy { c.notes[it].tick }
            if (!keyEditing) {
                if (!enter || order.isEmpty()) return@onKeyEvent false
                keyEditing = true
                if (keyNote !in order) keyNote = order.first()
                return@onKeyEvent true
            }
            when (code) {
                KeyCodes.KEYCODE_ESCAPE -> { keyEditing = false; true }
                KeyCodes.KEYCODE_DPAD_LEFT, KeyCodes.KEYCODE_DPAD_RIGHT -> {
                    val at = order.indexOf(keyNote).coerceAtLeast(0)
                    val next = at + if (code == KeyCodes.KEYCODE_DPAD_RIGHT) 1 else -1
                    if (next in order.indices) keyNote = order[next]
                    true
                }
                KeyCodes.KEYCODE_DPAD_UP, KeyCodes.KEYCODE_DPAD_DOWN -> {
                    val n = c.notes.getOrNull(keyNote) ?: return@onKeyEvent true
                    val dir = if (code == KeyCodes.KEYCODE_DPAD_UP) 1 else -1
                    val prop = propState
                    val to = (fractionOf(n, prop) + dir * stepOf(prop, e.isShiftPressed)).coerceIn(0f, 1f)
                    beginState(); setState(mapOf(keyNote to to)); endState()
                    true
                }
                else -> false
            }
        }

    // The tick a note's column is centred on. The drum grid draws every hit as
    // a full cell, so centre on the cell. The roll draws the note's length, so
    // centre on that, capped at one grid step so a long note's bar stays in view.
    fun centreTick(n: Note): Int {
        val g = clipState.grid.coerceAtLeast(1)
        return n.tick + (if (cellState) g else minOf(maxOf(1, n.length), g)) / 2
    }

    val total = clip.bars * ticksPerBar
    val from = firstTick.coerceIn(0, maxOf(0, total - 1))
    val span = (if (visibleTicks > 0) visibleTicks else total).coerceAtLeast(1)

    Row(modifier.background(c.sunken)) {
        Column(Modifier.width(GutterWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!collapsed) {
                Box(
                    // A tap opens the property list. All five properties
                    // always exist, so cycling through them would be slow.
                    Modifier.weight(1f).fillMaxWidth().clickable { menu = true }
                        .button(stringResource(Res.string.a11y_note_lane), stringResource(prop.label)),
                    contentAlignment = Alignment.Center,
                ) {
                    // While a value is being set it's shown here in the
                    // gutter, where the finger never is, in place of the
                    // property name.
                    val live = editing?.first?.let { clip.notes.getOrNull(it) }
                    if (live != null) {
                        Text(
                            valueText(live, prop),
                            color = if (prop == NoteProp.Chance && live.chance < 100) c.pink else c.accent,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, softWrap = false,
                        )
                    } else {
                        // "vel" is every note, "vel SD" is only the snares.
                        // Written sideways like the automation strip's name.
                        SideText(
                            stringResource(prop.short) + (pitchFilter?.let { " " + pitchName(it) } ?: ""),
                            if (pitchFilter != null) c.accent else c.teal,
                            9.sp, length = 120.dp, family = FontFamily.Monospace,
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .then(if (collapsed) Modifier.fillMaxHeight() else Modifier.height(18.dp))
                    .clickable { onToggleCollapse() }
                    .button(stringResource(if (collapsed) Res.string.a11y_unfold_note_lane else Res.string.a11y_fold_note_lane)),
                contentAlignment = Alignment.Center,
            ) { Text(if (collapsed) "▴" else "▾", color = c.textMid, fontSize = 11.sp) }
            // Scrolls, since it has a row per pitch in the clip and can be
            // taller than the screen. See ui/Scrollbar.kt.
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ScaledMenu(menuScroll) {
                    for (p in NoteProp.entries) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (p == prop) "● " else "  ") + stringResource(p.label),
                                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                )
                            },
                            onClick = { menu = false; onProp(p) },
                        )
                    }
                    // The pitch filter lives in the same menu, since the
                    // gutter has no room for another button.
                    val pitches = clip.notes.map { it.pitch }.distinct().sortedDescending()
                    if (pitches.size > 1) {
                        HorizontalDivider(color = c.line)
                        for (pitch in listOf(null) + pitches) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (pitch == pitchFilter) "● " else "  ") +
                                            (pitch?.let { pitchName(it) } ?: stringResource(Res.string.note_lane_all_notes)),
                                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    )
                                },
                                onClick = { menu = false; onPitchFilter(pitch) },
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            Canvas(
                Modifier.fillMaxWidth().fillMaxHeight().then(laneKeys).pointerInput(Unit) {
                    awaitEachGesture {
                        // Always consume a down before bailing, or
                        // awaitEachGesture spins the main thread.
                        val down = awaitFirstDown()
                        if (foldedState) {
                            down.consume()
                            expandState()
                            return@awaitEachGesture
                        }
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val here = clipState
                        val what = propState

                        /**
                         * Every shown note in the column under this x, or
                         * empty. A chord or drum step sets all its notes
                         * together; use the pitch filter to set just one.
                         */
                        fun stackAt(x: Float): List<Int> {
                            val tick = from + (x / w) * span
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            here.notes.forEachIndexed { i, n ->
                                if (!shown(n)) return@forEachIndexed
                                val d = abs(centreTick(n) - tick)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            // Only within half a grid step, so a touch on
                            // empty space doesn't move a far away note.
                            if (best < 0 || bestD > here.grid.coerceAtLeast(1) / 2f) return emptyList()
                            val at = centreTick(here.notes[best])
                            return here.notes.indices.filter {
                                shown(here.notes[it]) && centreTick(here.notes[it]) == at
                            }
                        }

                        // A drag that starts sideways sweeps and sets every
                        // note it passes. A drag that starts vertically locks
                        // onto its first note, so the finger can move aside
                        // to see the bar without touching the neighbours.
                        val stroke = HashMap<Int, Float>()
                        var locked: List<Int> = emptyList()   // what a vertical drag owns
                        var decided = false
                        fun add(p: Offset) {
                            val at = if (locked.isNotEmpty()) locked else stackAt(p.x)
                            if (at.isEmpty()) return
                            val v = (1f - p.y / h).coerceIn(0f, 1f)
                            for (i in at) stroke[i] = v
                            editing = at.first() to v
                            setState(stroke)
                        }
                        beginState()
                        add(down.position)
                        down.consume()
                        drag(down.id) { change ->
                            if (!decided) {
                                val d = change.position - down.position
                                if (d.getDistance() > viewConfiguration.touchSlop) {
                                    decided = true
                                    if (abs(d.y) > abs(d.x)) locked = stackAt(down.position.x)
                                }
                            }
                            add(change.position)
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                        editing = null
                        endState()
                    }
                },
            ) {
                drawRect(c.panel, size = size)
                val pxPerTick = size.width / span
                fun xOf(tick: Int) = (tick - from) * pxPerTick

                // Same bar and beat lines as the roll and automation strip.
                var t = from - (from % PPQN)
                while (t <= from + span) {
                    if (t >= 0) {
                        drawLine(
                            if (t % ticksPerBar == 0) c.gridBeat else c.gridStep,
                            Offset(xOf(t), 0f), Offset(xOf(t), size.height), 1f,
                        )
                    }
                    t += PPQN
                }
                if (prop == NoteProp.Nudge) {
                    // Zero nudge is the middle, so draw a centre line.
                    drawLine(
                        c.textDim.copy(alpha = 0.4f),
                        Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f,
                    )
                }

                // Clamped in dp so the width follows screen density and UI scale.
                val wide = (clip.grid.coerceAtLeast(1) * pxPerTick * 0.7f)
                    .coerceIn(2.dp.toPx(), 7.dp.toPx())
                for (n in clip.notes) {
                    // Drawn at the note's tick without its nudge, so the bar
                    // doesn't slide sideways while you change the nudge.
                    if (!shown(n)) continue
                    val mid = centreTick(n)
                    if (mid < from - clip.grid || mid > from + span) continue
                    drawMark(n, prop, xOf(mid), wide, c)
                }
                if (keyFocused) {
                    drawRect(c.accent, Offset.Zero, size, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    clip.notes.getOrNull(keyNote)?.takeIf { keyEditing }?.let { n ->
                        val x = xOf(centreTick(n))
                        drawRect(
                            c.pink, Offset(x - wide, 1f), Size(wide * 2f, size.height - 2f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f),
                        )
                    }
                }

                playheadTick?.let { pt ->
                    if (total > 0) {
                        val x = xOf((pt % total).toInt())
                        if (x >= 0f && x <= size.width) {
                            drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), 2f)
                        }
                    }
                }
            }
        }
    }
}

/** Draws one note's value for one property. */
private fun DrawScope.drawMark(
    n: Note, prop: NoteProp, x: Float, wide: Float, c: com.rm.acidulous.ui.theme.AcidColors,
) {
    val left = x - wide / 2f
    when (prop) {
        NoteProp.Velocity, NoteProp.Chance -> {
            val v = if (prop == NoteProp.Velocity) n.velocity / 127f else n.chance / 100f
            val h = (size.height - 4f) * v.coerceIn(0f, 1f)
            // Always draw a floor line so a note at zero is still visible.
            drawRect(c.teal.copy(alpha = 0.35f), Offset(left, size.height - 2f), Size(wide, 2f))
            if (h > 0f) {
                drawRect(
                    if (prop == NoteProp.Chance && n.chance < 100) c.pink else c.accent,
                    Offset(left, size.height - 2f - h), Size(wide, h),
                )
            }
        }
        NoteProp.Ratchet -> {
            // One block per repeat, so the count is easy to read.
            val r = n.ratchet.coerceIn(1, 8)
            val gap = 2f
            val tick = ((size.height - 6f) / 8f - gap).coerceAtLeast(1f)
            for (i in 0 until r) {
                val y = size.height - 3f - (i + 1) * (tick + gap)
                drawRect(if (r > 1) c.accent else c.teal.copy(alpha = 0.5f), Offset(left, y), Size(wide, tick))
            }
        }
        NoteProp.Nudge -> {
            val v = (n.nudge.toFloat() / NUDGE_RANGE).coerceIn(-1f, 1f)
            val mid = size.height / 2f
            val h = (size.height / 2f - 3f) * abs(v)
            if (h <= 0.5f) {
                drawRect(c.teal.copy(alpha = 0.5f), Offset(left, mid - 1f), Size(wide, 2f))
            } else {
                drawRect(
                    if (v > 0f) c.accent else c.pink,
                    Offset(left, if (v > 0f) mid - h else mid), Size(wide, h),
                )
            }
        }
        NoteProp.Cond -> {
            // Each condition gets its own height, in list order. `Always` is
            // the floor line and the last condition is the top.
            val v = Trig.codeOf(n.trig).toFloat() / (Trig.inOrder.size - 1).toFloat()
            val h = (size.height - 4f) * v
            drawRect(c.teal.copy(alpha = 0.35f), Offset(left, size.height - 2f), Size(wide, 2f))
            if (h > 0f) {
                drawRect(c.accent, Offset(left, size.height - 2f - h), Size(wide, h))
            }
        }
    }
}

/**
 * A note's value for [prop] as a lane height from 0 to 1. The inverse of
 * EditScreen's `onSet`.
 */
internal fun fractionOf(n: Note, prop: NoteProp): Float = when (prop) {
    NoteProp.Velocity -> n.velocity / 127f
    NoteProp.Chance -> n.chance / 100f
    NoteProp.Ratchet -> n.ratchet / 8f
    NoteProp.Nudge -> n.nudge / (2f * NUDGE_RANGE) + 0.5f
    NoteProp.Cond -> {
        val all = com.rm.acidulous.model.Trig.inOrder
        all.indexOf(n.trig).coerceAtLeast(0).toFloat() / (all.size - 1).coerceAtLeast(1)
    }
}

/** One key's worth of change: a step of the value, or a finer one with Shift. */
internal fun stepOf(prop: NoteProp, fine: Boolean): Float = when (prop) {
    NoteProp.Velocity -> (if (fine) 1f else 8f) / 127f
    NoteProp.Chance -> (if (fine) 1f else 5f) / 100f
    NoteProp.Ratchet -> 1f / 8f
    NoteProp.Nudge -> (if (fine) 1f else 4f) / (2f * NUDGE_RANGE)
    NoteProp.Cond -> 1f / (com.rm.acidulous.model.Trig.inOrder.size - 1).coerceAtLeast(1)
}
