package com.rm.acidulous.ui

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
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Lane
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.laneKey
import com.rm.acidulous.model.laneParam
import kotlin.math.roundToInt
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The parameter strip under the piano roll: one lane at a time, drawn as
 * a graph the width of the clip. Drag to write points at grid ticks (absolute
 * from the gesture base, so a stroke is one undo step); the picker cycles the
 * clip's lanes, and its menu adds a lane for any parameter or clears one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutomationStrip(
    clip: Clip,
    ticksPerBar: Int,
    playheadTick: Long?,
    /** The same window the roll is showing, so the playheads agree. */
    firstTick: Int = 0,
    visibleTicks: Int = 0,
    laneKeys: List<String>,          // every parameter a lane could be added for
    /** "Mosaic · grains position" for the list. */
    nameOf: (String) -> String = { it },
    /** "position" for the gutter, where one word fits. */
    shortOf: (String) -> String = { laneParam(it) },
    selected: String?,
    onSelect: (String?) -> Unit,
    onGestureBegin: () -> Unit,
    onDraw: (key: String, points: Map<Int, Float>) -> Unit, // all points of this stroke so far
    onGestureEnd: () -> Unit,
    onClear: (String) -> Unit,
    /** Folded to a single row, with the height handed back to the roll. */
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    /** Where a lane's knob is, for step locks' "back to the knob" to be drawn there. */
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
        // both and the two playheads line up. The upper part names the lane
        // being drawn and cycles through the ones that exist; a long press
        // opens the full list, where lanes are added and cleared. The lower
        // part folds the whole strip away - the roll is what an editor wants
        // the height for, and automation is not always being drawn.
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
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Turned on its side for the same reason the scale chip
                    // is: the width belongs to the graph. A stated length
                    // because this gutter is always eighty-eight dp tall, so
                    // a hundred and twenty is simply more than enough.
                    SideText(
                        current?.let { shortOf(it) } ?: "∿ auto",
                        Acid.colors.accent, 9.sp, length = 120.dp, family = FontFamily.Monospace,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .then(if (collapsed) Modifier.fillMaxHeight() else Modifier.height(18.dp))
                    .clickable { onToggleCollapse() },
                contentAlignment = Alignment.Center,
            ) { Text(if (collapsed) "▴" else "▾", color = Acid.colors.textMid, fontSize = 11.sp) }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ScaledMenu(menuScroll) {
                    for (k in laneKeys) {
                        DropdownMenuItem(
                            text = { Text((if (k in existing) "● " else "  ") + nameOf(k), fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                            // A lane is cleared where it is listed, so every lane
                            // that exists can be got rid of without selecting it first.
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
                        DropdownMenuItem(text = { Text("Clear ${shortOf(current)}") }, onClick = { menu = false; onClear(current) })
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        Canvas(
            Modifier.fillMaxWidth().fillMaxHeight().pointerInput(Unit) {
                awaitEachGesture {
                    // Always consume a touch before bailing: a block that returns
                    // without suspending makes awaitEachGesture spin the main thread.
                    val down = awaitFirstDown()
                    if (foldedState) {
                        // Too short to draw on: a touch here asks for it back.
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
                    fun add(p: Offset) {
                        val tick = ((from + (p.x / size.width) * span).roundToInt() / grid * grid).coerceIn(0, total - 1)
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
            val pxPerTick = size.width / span
            fun xOf(tick: Int) = (tick - from) * pxPerTick
            var t = from
            while (t <= last) {
                val x = xOf(t)
                drawLine(if (t % ticksPerBar == 0) c.gridBeat else c.gridStep, Offset(x, 0f), Offset(size.width, 0f).copy(x = x, y = size.height), 1f)
                t += PPQN
            }
            if (lane != null && lane.points.isNotEmpty()) {
                // sample the lane at every grid tick so step and linear both draw right
                val step = clip.grid.coerceAtLeast(8)
                var prev: Offset? = null
                var tick = from
                while (tick <= last) {
                    val v = lane.valueAt(tick)
                    val p = Offset(xOf(tick), (1f - v) * (size.height - 4f) + 2f)
                    prev?.let {
                        // A stepped lane - a switch's, or a step lock - holds
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
            playheadTick?.let { pt ->
                val t = (pt % total).toInt()
                if (t in from until last) {
                    drawLine(c.accent, Offset(xOf(t), 0f), Offset(xOf(t), size.height), 2f)
                }
            }
        }
        if (collapsed) {
            // The graph keeps drawing while folded, so the name needs a ground
            // of its own or it reads as part of the curve.
            Text(
                current?.let { shortOf(it) } ?: "∿ auto",
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
 * A row of live parameter sliders for the mounted machine - the placeholder for
 * M7's panel. They follow the engine (so a lane moves them) except while being
 * dragged, and show their normalised value.
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
        // The performance strip, for machines that answer it. These are the
        // only lanes that are not a unit's parameter: they leave as MIDI.
        (
            if (com.rm.acidulous.model.MachineUi.usesPerformance(track.machine.type)) {
                listOf("mod", "pressure").map { laneKey("performance", it) }
            } else {
                emptyList()
            }
            ) +
        // The pedals, on anything melodic: a MIDI pedal plays them, and the
        // lane can be drawn by hand.
        (
            if (com.rm.acidulous.model.MachineUi.takesTranspose(track.machine.type)) {
                com.rm.acidulous.model.PEDAL_LANES.map { laneKey("performance", it) }
            } else {
                emptyList()
            }
            )
