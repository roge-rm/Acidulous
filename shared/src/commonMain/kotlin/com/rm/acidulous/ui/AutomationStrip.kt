package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Lane
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.laneKey
import com.rm.acidulous.model.laneParam
import kotlin.math.roundToInt
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors
import com.rm.acidulous.res.*

/**
 * The parameter strip under the piano roll: one lane at a time, drawn as a
 * graph the width of the clip. Drag to write points at grid ticks (absolute
 * from the gesture base, so a stroke is one undo step). The picker cycles
 * through the clip's lanes, and its menu adds a lane for any parameter or
 * clears one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutomationStrip(
    clip: Clip,
    ticksPerBar: Int,
    playheadTick: Long?,
    /** The same window the roll is showing, so the playheads line up. */
    firstTick: Int = 0,
    visibleTicks: Int = 0,
    /**
     * How many times the whole clip is drawn side by side. A tape runs
     * through a scene's repeats, so under it the clip's automation is drawn
     * once per pass and [playheadTick] counts through all of them. A stroke
     * in any pass edits the one clip.
     */
    passes: Int = 1,
    laneKeys: List<String>,          // every parameter a lane could be added for
    /** "Mosaic · grains position" for the list. */
    nameOf: (String) -> String = { it },
    /** "position" for the gutter, where only one word fits. */
    shortOf: (String) -> String = { laneParam(it) },
    selected: String?,
    onSelect: (String?) -> Unit,
    onGestureBegin: () -> Unit,
    onDraw: (key: String, points: Map<Int, Float>) -> Unit, // all points of this stroke so far
    onGestureEnd: () -> Unit,
    onClear: (String) -> Unit,
    /** Folded to a single row, giving the height back to the roll. */
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    /** Where a lane's knob is, so step locks' "back to the knob" can be drawn there. */
    baseOf: (String) -> Float? = { null },
    modifier: Modifier = Modifier,
) {
    val existing = clip.automation.keys.sorted()
    val current = selected ?: existing.firstOrNull()
    val lane = current?.let { key ->
        clip.automation[key]?.let {
            if (com.rm.acidulous.model.Locks.isLocks(it)) com.rm.acidulous.model.Locks.resolve(it, baseOf(key) ?: 0f) else it
        }
    }
    var menu by remember { mutableStateOf(false) }

    val clipState by rememberUpdatedState(clip)
    val cb by rememberUpdatedState(Triple(onGestureBegin, onDraw, onGestureEnd))
    val keyState by rememberUpdatedState(current)
    val foldedState by rememberUpdatedState(collapsed)
    val expand by rememberUpdatedState(onToggleCollapse)
    val c = Acid.colors

    Row(modifier.background(c.sunken)) {
        // As narrow as the roll's name gutter, so a tick is at the same x in
        // both and the playheads line up. The upper part names the current
        // lane and cycles through the others, and a long press opens the full
        // list where lanes are added and cleared. The lower part folds the
        // strip away, since the roll usually needs the height more.
        Column(Modifier.width(GutterWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!collapsed) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().combinedClickable(
                        onClick = {
                            if (existing.size > 1) {
                                val i = existing.indexOf(current)
                                onSelect(existing[(i + 1) % existing.size])
                            } else {
                                menu = true
                            }
                        },
                        onLongClick = { menu = true },
                    ).button(
                        stringResource(Res.string.a11y_auto_lane),
                        current?.let { nameOf(it) } ?: stringResource(Res.string.a11y_none_chosen),
                        listOf(action(stringResource(Res.string.a11y_choose_lane)) { menu = true }),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Turned sideways like the scale chip, so the width goes to
                    // the graph. The gutter is always 88dp tall, so 120 is
                    // plenty.
                    SideText(
                        current?.let { shortOf(it) } ?: stringResource(Res.string.auto_none),
                        Acid.colors.accent, 9.sp, length = 120.dp, family = FontFamily.Monospace,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .then(if (collapsed) Modifier.fillMaxHeight() else Modifier.height(18.dp))
                    .clickable { onToggleCollapse() }
                    .button(stringResource(if (collapsed) Res.string.a11y_unfold_lane else Res.string.a11y_fold_lane)),
                contentAlignment = Alignment.Center,
            ) { Text(if (collapsed) "▴" else "▾", color = Acid.colors.textMid, fontSize = 11.sp) }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ScaledMenu(menuScroll) {
                    for (k in laneKeys) {
                        DropdownMenuItem(
                            text = { Text((if (k in existing) "● " else "  ") + nameOf(k), fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                            // Lanes can be cleared from the list, so any lane
                            // can be removed without selecting it first.
                            trailingIcon = if (k !in existing) null else ({
                                Text(
                                    "✕", color = Acid.colors.red, fontSize = 13.sp,
                                    modifier = Modifier.clickable { menu = false; onClear(k) }.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }),
                            onClick = { menu = false; onSelect(k) },
                        )
                    }
                    if (current != null) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.auto_clear, shortOf(current))) }, onClick = { menu = false; onClear(current) })
                    }
                }
            }
        }
        // The keyboard cursor, like the roll's: when focused the strip shows
        // a ring, Enter starts editing, left and right move along the grid,
        // and up and down set the lane's value there as a point, one undo
        // step each. Esc stops editing.
        var keyFocused by remember { mutableStateOf(false) }
        var keyEditing by remember { mutableStateOf(false) }
        var keyTick by remember { mutableStateOf(firstTick) }
        val firstState by rememberUpdatedState(firstTick)
        val currentState by rememberUpdatedState(current)
        val laneState by rememberUpdatedState(lane)
        val stripKeys = Modifier
            .onFocusChanged { keyFocused = it.isFocused; if (!it.isFocused) keyEditing = false }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                val e = ev.press
                val code = e.keyCode
                val c0 = clipState
                val grid = c0.grid.coerceAtLeast(1)
                val total = (c0.bars * ticksPerBar).coerceAtLeast(grid)
                if (!keyEditing) {
                    val enter = code == KeyCodes.KEYCODE_ENTER || code == KeyCodes.KEYCODE_NUMPAD_ENTER ||
                        code == KeyCodes.KEYCODE_DPAD_CENTER
                    if (!enter) return@onKeyEvent false
                    keyEditing = true
                    keyTick = (firstState / grid * grid).coerceIn(0, total - grid)
                    return@onKeyEvent true
                }
                when (code) {
                    KeyCodes.KEYCODE_ESCAPE -> { keyEditing = false; true }
                    KeyCodes.KEYCODE_DPAD_LEFT -> { keyTick = (keyTick - grid).coerceAtLeast(0); true }
                    KeyCodes.KEYCODE_DPAD_RIGHT -> { keyTick = (keyTick + grid).coerceAtMost(total - grid); true }
                    KeyCodes.KEYCODE_DPAD_UP, KeyCodes.KEYCODE_DPAD_DOWN -> {
                        val key = currentState ?: return@onKeyEvent true
                        val dir = if (code == KeyCodes.KEYCODE_DPAD_UP) 1 else -1
                        val now = laneState?.valueAt(keyTick) ?: 0.5f
                        val to = (now + dir * (if (e.isShiftPressed) 0.01f else 0.05f)).coerceIn(0f, 1f)
                        cb.first(); cb.second(key, mapOf(keyTick to to)); cb.third()
                        true
                    }
                    else -> false
                }
            }
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        Canvas(
            Modifier.fillMaxWidth().fillMaxHeight().then(stripKeys).pointerInput(Unit) {
                awaitEachGesture {
                    // Always consume a touch before returning. A block that
                    // returns without suspending makes awaitEachGesture spin
                    // the main thread.
                    val down = awaitFirstDown()
                    if (foldedState) {
                        // Too short to draw on, so a touch here unfolds it.
                        down.consume()
                        expand()
                        return@awaitEachGesture
                    }
                    val key = keyState ?: return@awaitEachGesture
                    val total = (clipState.bars * ticksPerBar).coerceAtLeast(1)
                    val from = firstTick.coerceIn(0, total - 1)
                    val span = (if (visibleTicks > 0) minOf(visibleTicks, total - from) else total).coerceAtLeast(1)
                    val grid = clipState.grid.coerceAtLeast(1)
                    val stroke = HashMap<Int, Float>()
                    val laps = passes.coerceAtLeast(1)
                    fun add(p: Offset) {
                        // Which pass it's in doesn't matter: they're all the one clip.
                        val along = ((p.x / size.width) * span * laps).roundToInt() % span
                        val tick = ((from + along) / grid * grid).coerceIn(0, total - 1)
                        stroke[tick] = (1f - p.y / size.height).coerceIn(0f, 1f)
                        cb.second(key, stroke)
                    }
                    cb.first()
                    add(down.position)
                    drag(down.id) { change -> change.consume(); add(change.position) }
                    cb.third()
                }
            },
        ) {
            val total = (clip.bars * ticksPerBar).coerceAtLeast(1)
            val from = firstTick.coerceIn(0, total - 1)
            val span = (if (visibleTicks > 0) minOf(visibleTicks, total - from) else total).coerceAtLeast(1)
            val last = from + span
            val laps = passes.coerceAtLeast(1)
            val pxPerTick = size.width / (span.toFloat() * laps)
            // The pass being drawn; everything below is drawn once per pass.
            var lap = 0
            fun xOf(tick: Int) = (lap * span + tick - from) * pxPerTick
            for (l in 0 until laps) {
            lap = l
            var t = from
            while (t <= last) {
                val x = xOf(t)
                drawLine(if (t % ticksPerBar == 0) c.gridBeat else c.gridStep, Offset(x, 0f), Offset(size.width, 0f).copy(x = x, y = size.height), 1f)
                t += PPQN
            }
            if (lane != null && lane.points.isNotEmpty()) {
                // Sample the lane at every grid tick so step and linear both draw right.
                val step = clip.grid.coerceAtLeast(8)
                var prev: Offset? = null
                var tick = from
                while (tick <= last) {
                    val v = lane.valueAt(tick)
                    val p = Offset(xOf(tick), (1f - v) * (size.height - 4f) + 2f)
                    prev?.let {
                        // A stepped lane (a switch's, or a step lock) holds
                        // until its next point and then jumps: across, then up.
                        if (lane.linear) {
                            drawLine(c.accent, it, p, 2f)
                        } else {
                            drawLine(c.accent, it, Offset(p.x, it.y), 2f)
                            drawLine(c.accent, Offset(p.x, it.y), p, 2f)
                        }
                    }
                    prev = p
                    tick += step
                }
                for (pt in lane.points) {
                    if (pt.tick < from || pt.tick > last) continue
                    val p = Offset(xOf(pt.tick), (1f - pt.value) * (size.height - 4f) + 2f)
                    val dot = 3.dp.toPx()
                    drawRect(c.accentSoft, Offset(p.x - dot / 2, p.y - dot / 2), Size(dot, dot))
                }
            }
            }
            lap = 0
            playheadTick?.let { pt ->
                // Across every pass: the tick counts through them all.
                val cycle = (pt % (total.toLong() * laps)).toInt()
                lap = cycle / total
                val t = cycle % total
                if (t in from until last) {
                    drawLine(c.accent, Offset(xOf(t), 0f), Offset(xOf(t), size.height), 2f)
                }
                lap = 0
            }
            if (keyFocused) {
                drawRect(c.accent, Offset.Zero, size, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                if (keyEditing && keyTick in from until last) {
                    val x = xOf(keyTick)
                    drawLine(c.pink, Offset(x, 0f), Offset(x, size.height), 2.5f)
                    lane?.let { l ->
                        val y = (1f - l.valueAt(keyTick)) * (size.height - 4f) + 2f
                        val dot = 6.dp.toPx()
                        drawRect(c.pink, Offset(x - dot / 2, y - dot / 2), Size(dot, dot))
                    }
                }
            }
        }
        if (collapsed) {
            // The graph keeps drawing while folded, so the name needs its own
            // background or it looks like part of the curve.
            Text(
                current?.let { shortOf(it) } ?: stringResource(Res.string.auto_none),
                color = Acid.colors.accent, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = 3.dp)
                    .background(c.tip, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        }
    }
}

/**
 * A row of live parameter sliders for the loaded machine. They follow the
 * engine (so a lane moves them) except while being dragged, and show their
 * normalised value.
 */
@Composable
fun ParamStrip(rack: Int, machineType: String, modifier: Modifier = Modifier) {
    val c = Acid.colors
    val names = remember(machineType) { com.rm.acidulous.engine.NativeEngine.machineParamNames(machineType) }
    var values by remember(machineType, rack) { mutableStateOf(FloatArray(names.size) { 0.5f }) }
    var dragging by remember { mutableStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(machineType, rack) {
        while (true) {
            val next = FloatArray(names.size) { i ->
                if (i == dragging) values[i]
                else com.rm.acidulous.engine.NativeEngine.paramNormalized(rack, "machine", names[i]).takeIf { it >= 0f } ?: values[i]
            }
            values = next
            kotlinx.coroutines.delay(100)
        }
    }
    Row(modifier.background(c.panel), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, name ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, color = Acid.colors.textDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                MiniSlider(
                    value = values[i], modifier = Modifier.width(56.dp).height(20.dp),
                    onStart = { dragging = i },
                    onChange = { v -> values = values.copyOf().also { it[i] = v }; com.rm.acidulous.engine.NativeEngine.setParam(rack, "machine", name, v) },
                    onEnd = { dragging = -1 },
                )
                Text("%.2f".format(values[i]), color = Acid.colors.accent, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

fun automationKeysFor(track: com.rm.acidulous.model.Track): List<String> =
    com.rm.acidulous.engine.NativeEngine.machineParamNames(track.machine.type).map { laneKey("machine", it) } +
        (0 until com.rm.acidulous.model.MODIFIER_SLOTS).flatMap { slot ->
            val ev = track.modifierAt(slot)
            if (ev.isEmpty) emptyList()
            else (com.rm.acidulous.engine.NativeEngine.inputModParamInfo(ev.type).map { it.name } + "bypass")
                .map { laneKey(com.rm.acidulous.model.modifierUnit(slot), it) }
        } +
        (0 until com.rm.acidulous.model.EFFECT_SLOTS).flatMap { slot ->
            val fx = track.effectAt(slot)
            if (fx.isEmpty) emptyList()
            else (com.rm.acidulous.engine.NativeEngine.effectParamInfo(fx.type).map { it.name } + "bypass")
                .map { laneKey(com.rm.acidulous.model.effectUnit(slot), it) }
        } +
        listOf("gain", "pan", "sendreverb", "senddelay").map { laneKey("channel", it) } +
        // The performance strip, for machines that use it. These are the only
        // lanes that aren't a unit's parameter; they're sent as MIDI.
        (
            if (com.rm.acidulous.model.MachineUi.usesPerformance(track.machine.type)) {
                listOf("mod", "pressure").map { laneKey("performance", it) }
            } else {
                emptyList()
            }
            ) +
        // The pedals, on anything melodic. A MIDI pedal plays them, and the
        // lane can also be drawn by hand.
        (
            if (com.rm.acidulous.model.MachineUi.takesTranspose(track.machine.type)) {
                com.rm.acidulous.model.PEDAL_LANES.map { laneKey("performance", it) }
            } else {
                emptyList()
            }
            )
