package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Trig
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a note carries besides its pitch.
 *
 * A note has grown five properties that the roll has nowhere to put: how hard
 * it is struck, how likely it is to sound, what has to be true for it to, how
 * many times it is struck, and how far off the grid it sits. The roll can draw
 * one of those inside the note rect and already does - velocity as the bright
 * region - and the reason it stops there is written in PianoRoll's own comment
 * about the bend curve: three lines in a box sixteen pixels tall is not a
 * readout, it is a smudge.
 *
 * So they get a lane, built to the shape of the automation strip beside it -
 * same gutter width so the ticks line up, same rotated name that cycles on a
 * tap and opens a menu on a hold, same fold, same "a touch while folded opens
 * it rather than drawing". What is deliberately *not* shared is the drawing:
 * an automation lane is a curve sampled anywhere, and this is one value per
 * note and nothing in between.
 */
enum class NoteProp(val short: String, val label: String) {
    Velocity("vel", "note volume"),
    Chance("prob", "probability"),
    Cond("trig", "trig condition"),
    Ratchet("ratch", "ratchet"),
    Nudge("nudge", "micro timing"),
}

/** How far a note may be pushed off the grid: half a sixteenth either way. */
const val NUDGE_RANGE = PPQN / 4

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
    /** A tap on a note's column, for the one property a bar cannot express. */
    onCycle: (noteIndex: Int, by: Int) -> Unit,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    var menu by remember { mutableStateOf(false) }
    // The trig lane is the only one whose value is a word. Measured properly
    // rather than guessed at: `1:2` and `!pr` are three glyphs of monospace at
    // eight sp and the canvas has no idea how wide that is.
    val measurer = rememberTextMeasurer()
    /**
     * The note being dragged and the value it now holds, while it is being
     * dragged and not afterwards.
     *
     * A bar is a picture of a number and for most of the time that is enough;
     * it stops being enough at the moment you are setting it, which is also
     * the moment a finger is over it. Cleared when the gesture ends, so the
     * lane goes back to being a shape rather than a row of figures.
     */
    var editing by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    // Read here and captured by the gesture block, which is keyed on Unit so
    // it never restarts mid-drag - the same trap the automation strip records.
    val clipState by rememberUpdatedState(clip)
    val propState by rememberUpdatedState(prop)
    val setState by rememberUpdatedState(onSet)
    val cycleState by rememberUpdatedState(onCycle)
    val beginState by rememberUpdatedState(onGestureBegin)
    val endState by rememberUpdatedState(onGestureEnd)
    val foldedState by rememberUpdatedState(collapsed)
    val expandState by rememberUpdatedState(onToggleCollapse)

    val total = clip.bars * ticksPerBar
    val from = firstTick.coerceIn(0, maxOf(0, total - 1))
    val span = (if (visibleTicks > 0) visibleTicks else total).coerceAtLeast(1)

    Row(modifier.background(c.sunken)) {
        Column(Modifier.width(GutterWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!collapsed) {
                Box(
                    // A tap opens the list. The automation strip cycles on a
                    // tap because most clips have one or two lanes and
                    // stepping through them is quicker than choosing; here
                    // all five properties always exist, so cycling means
                    // tapping four times to reach the one you want and
                    // passing through three you did not. Dan asked for the
                    // chooser, and the strip's own menu is what it looks
                    // like.
                    Modifier.weight(1f).fillMaxWidth().clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        prop.short,
                        color = c.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false,
                        // On its side, as the automation strip's is: the width
                        // belongs to the notes.
                        modifier = Modifier.requiredWidth(120.dp).rotate(-90f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .then(if (collapsed) Modifier.fillMaxHeight() else Modifier.height(18.dp))
                    .clickable { onToggleCollapse() },
                contentAlignment = Alignment.Center,
            ) { Text(if (collapsed) "▴" else "▾", color = c.textMid, fontSize = 11.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (p in NoteProp.entries) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                (if (p == prop) "● " else "  ") + p.label,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            )
                        },
                        onClick = { menu = false; onProp(p) },
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            Canvas(
                Modifier.fillMaxWidth().fillMaxHeight().pointerInput(Unit) {
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

                        /** Which note is under this x, or -1. */
                        fun noteAt(x: Float): Int {
                            val tick = from + (x / w) * span
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            here.notes.forEachIndexed { i, n ->
                                val d = abs((n.tick + n.nudge) - tick)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            // Within half a grid step, or the finger was not
                            // pointing at anything and must not move a note
                            // three bars away.
                            return if (best >= 0 && bestD <= here.grid.coerceAtLeast(1) / 2f) best else -1
                        }

                        if (what == NoteProp.Cond) {
                            // A condition is a word, not a height. A tap steps
                            // it forward, a vertical drag walks the list - the
                            // same shape as a stepped knob.
                            val at = noteAt(down.position.x)
                            if (at < 0) { down.consume(); return@awaitEachGesture }
                            down.consume()
                            var last = down.position.y
                            var moved = false
                            drag(down.id) { change ->
                                val dy = last - change.position.y
                                if (abs(dy) >= 18f) {
                                    cycleState(at, if (dy > 0) 1 else -1)
                                    last = change.position.y
                                    moved = true
                                }
                                change.consume()
                            }
                            if (!moved) cycleState(at, 1)
                            return@awaitEachGesture
                        }

                        // **One note, or a sweep across many, decided by
                        // which way the finger set off.**
                        //
                        // The stroke gesture paints every note it passes
                        // over, which is what makes levelling a bar in one
                        // movement possible and is worth keeping. But it also
                        // meant a finger could not move aside to see the bar
                        // it was setting without dragging the neighbours with
                        // it - Dan: "it's hard to see where the bar is with my
                        // finger in the way". So a drag that sets off *upward*
                        // owns the note it started on and keeps it however far
                        // sideways it wanders; a drag that sets off sideways
                        // sweeps as before. Both gestures survive and neither
                        // has to be learned.
                        val stroke = HashMap<Int, Float>()
                        var locked = -1        // the note a vertical drag owns
                        var decided = false
                        fun add(p: Offset) {
                            val at = if (locked >= 0) locked else noteAt(p.x)
                            if (at < 0) return
                            val v = (1f - p.y / h).coerceIn(0f, 1f)
                            stroke[at] = v
                            editing = at to v
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
                                    if (abs(d.y) > abs(d.x)) locked = noteAt(down.position.x)
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

                // The same bar and beat lines the roll and the strip draw, so
                // a column means the same thing in all three.
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
                    // Zero is the middle of this one's range, so it gets a
                    // line to be measured from.
                    drawLine(
                        c.textDim.copy(alpha = 0.4f),
                        Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f,
                    )
                }

                val wide = (clip.grid.coerceAtLeast(1) * pxPerTick * 0.7f).coerceIn(3f, 18f)
                for (n in clip.notes) {
                    val at = n.tick + n.nudge
                    if (at < from - clip.grid || at > from + span) continue
                    drawMark(n, prop, xOf(at), wide, c)
                    if (prop == NoteProp.Cond && n.trig != Trig.Always) {
                        val laid = measurer.measure(
                            n.trig.short,
                            // Dark on the tinted column, as a selected switch
                            // cell is: near-white on pale accent is a label
                            // you can see is there and cannot read.
                            TextStyle(color = c.onAccent, fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                        )
                        drawText(
                            laid,
                            topLeft = Offset(
                                (xOf(at) - laid.size.width / 2f).coerceIn(0f, size.width - laid.size.width),
                                size.height / 2f - laid.size.height / 2f,
                            ),
                        )
                    }
                }

                // The number, while it is being set and only then.
                //
                // At the tip of the bar rather than under its foot, because
                // the foot is where the bar is widest and the text would be
                // drawn on top of itself; above the tip while there is room
                // and below it near the ceiling, so it is never off the lane.
                // Same colour as the bar it belongs to, so there is no
                // question which note it is about.
                editing?.let { (index, v) ->
                    val n = clip.notes.getOrNull(index)
                    if (n != null) {
                        val text = when (prop) {
                            NoteProp.Velocity -> "${(v * 127f).roundToInt().coerceIn(1, 127)}"
                            NoteProp.Chance -> "${(v * 100f).roundToInt().coerceIn(0, 100)}%"
                            NoteProp.Ratchet -> "x${(v * 8f).roundToInt().coerceIn(1, 8)}"
                            NoteProp.Nudge -> {
                                val t = ((v - 0.5f) * 2f * NUDGE_RANGE).roundToInt()
                                if (t > 0) "+$t" else "$t"
                            }
                            NoteProp.Cond -> n.trig.short
                        }
                        val laid = measurer.measure(
                            text,
                            TextStyle(
                                color = if (prop == NoteProp.Chance && n.chance < 100) c.pink else c.accent,
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            ),
                        )
                        val tip = when (prop) {
                            NoteProp.Nudge -> size.height / 2f - (v - 0.5f) * 2f * (size.height / 2f - 3f)
                            else -> size.height - 2f - (size.height - 4f) * v
                        }
                        val above = tip - laid.size.height - 2f
                        drawText(
                            laid,
                            topLeft = Offset(
                                (xOf(n.tick + n.nudge) - laid.size.width / 2f)
                                    .coerceIn(0f, size.width - laid.size.width),
                                if (above >= 0f) above else (tip + 3f).coerceAtMost(size.height - laid.size.height),
                            ),
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

/** One note's value for one property. Never a curve: there is nothing between. */
private fun DrawScope.drawMark(
    n: Note, prop: NoteProp, x: Float, wide: Float, c: com.rm.acidulous.ui.theme.AcidColors,
) {
    val left = x - wide / 2f
    when (prop) {
        NoteProp.Velocity, NoteProp.Chance -> {
            val v = if (prop == NoteProp.Velocity) n.velocity / 127f else n.chance / 100f
            val h = (size.height - 4f) * v.coerceIn(0f, 1f)
            // A floor line even at nought, so a note with no chance at all is
            // still visibly a note and not an absence.
            drawRect(c.teal.copy(alpha = 0.35f), Offset(left, size.height - 2f), Size(wide, 2f))
            if (h > 0f) {
                drawRect(
                    if (prop == NoteProp.Chance && n.chance < 100) c.pink else c.accent,
                    Offset(left, size.height - 2f - h), Size(wide, h),
                )
            }
        }
        NoteProp.Ratchet -> {
            // A stack of that many ticks. A bar of height three eighths says
            // nothing you can read; three ticks says three.
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
            // Nothing to scale, so the column is tinted and the word is drawn
            // over it by the caller's text layer - see below.
            if (n.trig != Trig.Always) {
                drawRect(c.accentSoft, Offset(left, 2f), Size(wide, size.height - 4f))
            } else {
                drawRect(c.teal.copy(alpha = 0.25f), Offset(left, size.height - 4f), Size(wide, 2f))
            }
        }
    }
}
