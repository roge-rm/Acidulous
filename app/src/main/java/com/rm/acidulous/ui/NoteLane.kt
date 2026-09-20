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

/** What a note holds for one property, in as few characters as it can be said. */
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
     * True when the editor above draws a note as a whole grid cell rather
     * than as its own length - which is what the drum grid does, and what the
     * piano roll does not. It decides only where a column is centred.
     */
    cellWide: Boolean = false,
    /**
     * Show only this pitch, or every pitch when null.
     *
     * A lane draws one column per note at the note's own tick, so notes that
     * share a tick share a column and only the nearest could ever be touched.
     * Dan: "for multiple notes on one step we need to be able to select them
     * each individually somehow". A drum step with a kick, a hat and a crash
     * on it is three bars in one place; with a pitch chosen it is one.
     *
     * It also makes a sweep mean something. Dragging across a bar to level
     * every hat in it is the gesture this lane exists for, and without a
     * filter the sweep takes whichever note of each step happens to be
     * nearest - a mixture nobody asked for.
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
    val beginState by rememberUpdatedState(onGestureBegin)
    val endState by rememberUpdatedState(onGestureEnd)
    val filterState by rememberUpdatedState(pitchFilter)
    val cellState by rememberUpdatedState(cellWide)
    val foldedState by rememberUpdatedState(collapsed)
    val expandState by rememberUpdatedState(onToggleCollapse)

    /**
     * The tick a note's column is centred on - the middle of the note as the
     * editor above draws it, not its left edge.
     *
     * Dan, looking at a roll: "these bars should be directly under the centre
     * of their notes, why are they off centre?" They were drawn at `xOf(tick)`
     * and `drawMark` centres on what it is given, so every bar sat half its
     * own width to the left of where the note begins - and the note goes on
     * for a cell after that.
     *
     * The two editors disagree about how wide a note is, so this asks the one
     * that is showing. The drum grid gives every hit a full cell whatever its
     * length (its notes are a thirty-second and its cells are usually a
     * sixteenth), so a drum column centres on the cell. The roll draws the
     * note's own length, so a roll column centres on that - capped at one
     * grid step, because a note four bars long has its middle off the side of
     * a one-bar window and its bar would be unreachable.
     */
    // **Through the updated states, not the parameters.**
    //
    // Both of these are called from the gesture block as well as the draw
    // block, and the gesture block is keyed on Unit so that it survives a
    // drag - which means it closes over whatever these were when it was
    // built. Reading `pitchFilter` directly made the filter work everywhere
    // the lane *draws* and nowhere it *touches*: picking one note of a chord
    // showed one bar and still set both, because the gesture was still
    // holding the null it started life with.
    fun shown(n: Note): Boolean = filterState == null || n.pitch == filterState

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
                    // **The value goes in the gutter, not next to the bar.**
                    //
                    // It was drawn at the tip of the bar being set, which is
                    // fine for a height and useless for a word: the trig lane
                    // tints the whole column and the label landed on top of
                    // the one already drawn there. Dan: "the trig condition
                    // display while modifying it is unreadable". The gutter is
                    // the one part of this lane a finger is never over - it is
                    // to the left of everything that can be touched - it is
                    // always in the same place, and every value this lane
                    // holds says itself in four characters or fewer. So the
                    // name steps aside while a value is being set and comes
                    // back when the finger lifts.
                    val live = editing?.first?.let { clip.notes.getOrNull(it) }
                    if (live != null) {
                        Text(
                            valueText(live, prop),
                            color = if (prop == NoteProp.Chance && live.chance < 100) c.pink else c.accent,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, softWrap = false,
                        )
                    } else {
                        // "vel" on its own is every note; "vel SD" is the
                        // snares. The gutter is the only place that can say
                        // so - the lane itself looks the same either way,
                        // just emptier. On its side, as the automation
                        // strip's is: the width belongs to the notes.
                        SideText(
                            prop.short + (pitchFilter?.let { " " + pitchName(it) } ?: ""),
                            if (pitchFilter != null) c.accent else c.teal,
                            9.sp, length = 120.dp, family = FontFamily.Monospace,
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .then(if (collapsed) Modifier.fillMaxHeight() else Modifier.height(18.dp))
                    .clickable { onToggleCollapse() },
                contentAlignment = Alignment.Center,
            ) { Text(if (collapsed) "▴" else "▾", color = c.textMid, fontSize = 11.sp) }
            // A position bar: this grows a row per distinct pitch in the clip, so on a
            // stacked one it is longer than the screen. See ui/Scrollbar.kt.
            val menuScroll = rememberScrollState()
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                modifier = Modifier.scrollbar(menuScroll, color = Acid.colors.scrollbar),
                scrollState = menuScroll,
            ) {
                ScaledWindow {
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
                    // **Which notes, under which property.** One popup rather
                    // than a second control: the gutter is thirty-four dp wide
                    // and has a name, a fold box and nothing else in it, and a
                    // filter that is only needed on stacked clips should not cost
                    // a permanent button on every clip.
                    val pitches = clip.notes.map { it.pitch }.distinct().sortedDescending()
                    if (pitches.size > 1) {
                        HorizontalDivider(color = c.line)
                        for (pitch in listOf(null) + pitches) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (pitch == pitchFilter) "● " else "  ") +
                                            (pitch?.let { pitchName(it) } ?: "all notes"),
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

                        /**
                         * **Every note in the column under this x**, or empty.
                         *
                         * A column is a place in the clip, and a chord or a
                         * drum step puts several notes in one. Returning only
                         * the nearest meant the rest could not be touched at
                         * all: the same index won every time, and the notes
                         * behind it were drawn over and unreachable. Dan, of
                         * the drum grid: "for multiple notes on one step we
                         * need to be able to select them each individually
                         * somehow" - and then of the roll, where a chord is
                         * the ordinary case rather than the awkward one.
                         *
                         * So a touch takes the whole stack and the pitch
                         * filter is how you take one of it. Unfiltered, the
                         * column behaves like the step it draws: setting it
                         * sets the chord. Filtered, the stack is one note by
                         * construction and this needs no second rule.
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
                            // Within half a grid step, or the finger was not
                            // pointing at anything and must not move a note
                            // three bars away.
                            if (best < 0 || bestD > here.grid.coerceAtLeast(1) / 2f) return emptyList()
                            val at = centreTick(here.notes[best])
                            return here.notes.indices.filter {
                                shown(here.notes[it]) && centreTick(here.notes[it]) == at
                            }
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

                // The clamp in dp rather than in pixels: written as bare
                // floats, how wide a column came out depended on how dense the
                // screen was, and did not move when the interface scale did.
                val wide = (clip.grid.coerceAtLeast(1) * pxPerTick * 0.7f)
                    .coerceIn(2.dp.toPx(), 7.dp.toPx())
                for (n in clip.notes) {
                    // **At the note's tick, not where the nudge puts it.**
                    // A column here stands for a note, and a note is where
                    // it was written; the nudge is a property of it like the
                    // velocity is. Drawing at `tick + nudge` meant the bar
                    // you were dragging slid out from under the finger
                    // setting it, and in the nudge lane the deflection and
                    // the position then said the same thing twice. Dan:
                    // "don't have the bar move to the left/right as you
                    // change the value - that's confusing".
                    if (!shown(n)) continue
                    val mid = centreTick(n)
                    if (mid < from - clip.grid || mid > from + span) continue
                    drawMark(n, prop, xOf(mid), wide, c)
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
            // **A height, like every other lane.**
            //
            // This drew one tint for "has a condition" and nothing else, so
            // the bar never moved while it was being set and the only way to
            // know what a note held was to read the gutter. Dan: "the bar
            // under the notes never changes height ... it would be easier to
            // tell what is happening if each trig condition set a certain
            // height". Forty conditions over the lane is about five pixels a
            // step, which is not enough to pick one out exactly and is
            // plenty to see that the finger is moving something and roughly
            // where in the list it has got to.
            //
            // `Always` is the floor and is drawn as the floor line the other
            // lanes use for nought, so a note with no condition on it still
            // looks like a note. The last of the Nth family is the ceiling.
            val v = Trig.codeOf(n.trig).toFloat() / (Trig.inOrder.size - 1).toFloat()
            val h = (size.height - 4f) * v
            drawRect(c.teal.copy(alpha = 0.35f), Offset(left, size.height - 2f), Size(wide, 2f))
            if (h > 0f) {
                drawRect(c.accent, Offset(left, size.height - 2f - h), Size(wide, h))
            }
        }
    }
}
