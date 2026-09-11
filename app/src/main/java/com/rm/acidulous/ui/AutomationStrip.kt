package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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

/**
 * The reference sequencer's parameter strip under the piano roll: one lane at a time, drawn as
 * a graph the width of the clip. Drag to write points at grid ticks (absolute
 * from the gesture base, so a stroke is one undo step); the picker cycles the
 * clip's lanes, and its menu adds a lane for any parameter or clears one.
 */
@Composable
fun AutomationStrip(
    clip: Clip,
    ticksPerBar: Int,
    playheadTick: Long?,
    laneKeys: List<String>,          // every parameter a lane could be added for
    selected: String?,
    onSelect: (String?) -> Unit,
    onGestureBegin: () -> Unit,
    onDraw: (key: String, points: Map<Int, Float>) -> Unit, // all points of this stroke so far
    onGestureEnd: () -> Unit,
    onClear: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val existing = clip.automation.keys.sorted()
    val current = selected ?: existing.firstOrNull()
    val lane = current?.let { clip.automation[it] }
    var menu by remember { mutableStateOf(false) }

    val clipState by rememberUpdatedState(clip)
    val cb by rememberUpdatedState(Triple(onGestureBegin, onDraw, onGestureEnd))
    val keyState by rememberUpdatedState(current)

    Row(modifier.background(Color(0xFF17171A))) {
        // As narrow as the roll's name gutter, so a tick is at the same x in
        // both and the two playheads line up. The upper part names the lane
        // being drawn and cycles through the ones that exist; the lower opens
        // the full list.
        Column(Modifier.width(GutterWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable {
                    if (existing.size > 1) {
                        val i = existing.indexOf(current)
                        onSelect(existing[(i + 1) % existing.size])
                    } else {
                        menu = true
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    current?.let { laneParam(it) } ?: "∿ auto",
                    color = Color(0xFFFFB454), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, softWrap = false,
                    // Turned on its side for the same reason the scale chip is:
                    // the width belongs to the graph.
                    modifier = Modifier.requiredWidth(120.dp).rotate(-90f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(18.dp).clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) { Text("⋯", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.scrollbar(menuScroll), scrollState = menuScroll) {
                for (k in laneKeys) {
                    DropdownMenuItem(
                        text = { Text((if (k in existing) "● " else "  ") + k, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                        onClick = { menu = false; onSelect(k) },
                    )
                }
                if (current != null) {
                    DropdownMenuItem(text = { Text("Clear ${laneParam(current)}") }, onClick = { menu = false; onClear(current) })
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().fillMaxHeight().pointerInput(Unit) {
                awaitEachGesture {
                    // Always consume a touch before bailing: a block that returns
                    // without suspending makes awaitEachGesture spin the main thread.
                    val down = awaitFirstDown()
                    val key = keyState ?: return@awaitEachGesture
                    val total = (clipState.bars * ticksPerBar).coerceAtLeast(1)
                    val grid = clipState.grid.coerceAtLeast(1)
                    val stroke = HashMap<Int, Float>()
                    fun add(p: Offset) {
                        val tick = (((p.x / size.width) * total).roundToInt() / grid * grid).coerceIn(0, total - 1)
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
            val pxPerTick = size.width / total
            var t = 0
            while (t <= total) {
                val x = t * pxPerTick
                drawLine(if (t % ticksPerBar == 0) Color(0xFF55555C) else Color(0xFF2E2E33), Offset(x, 0f), Offset(size.width, 0f).copy(x = x, y = size.height), 1f)
                t += PPQN
            }
            if (lane != null && lane.points.isNotEmpty()) {
                // sample the lane at every grid tick so step and linear both draw right
                val step = clip.grid.coerceAtLeast(8)
                var prev: Offset? = null
                var tick = 0
                while (tick <= total) {
                    val v = lane.valueAt(tick)
                    val p = Offset(tick * pxPerTick, (1f - v) * (size.height - 4f) + 2f)
                    prev?.let { drawLine(Color(0xFFFFB454), it, p, 2f) }
                    prev = p
                    tick += step
                }
                for (pt in lane.points) {
                    val p = Offset(pt.tick * pxPerTick, (1f - pt.value) * (size.height - 4f) + 2f)
                    drawRect(Color(0xFFFFE0A0), Offset(p.x - 3f, p.y - 3f), Size(6f, 6f))
                }
            }
            playheadTick?.let { pt ->
                val x = (pt % total) * pxPerTick
                drawLine(Color(0xFFFFB454), Offset(x, 0f), Offset(x, size.height), 2f)
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
    Row(modifier.background(Color(0xFF1F1F23)), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, name ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, color = Color(0xFF9A9AA2), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                MiniSlider(
                    value = values[i], modifier = Modifier.width(56.dp).height(20.dp),
                    onStart = { dragging = i },
                    onChange = { v -> values = values.copyOf().also { it[i] = v }; com.rm.acidulous.engine.NativeEngine.setParam(rack, "machine", name, v) },
                    onEnd = { dragging = -1 },
                )
                Text("%.2f".format(values[i]), color = Color(0xFFFFB454), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

fun automationKeysFor(track: com.rm.acidulous.model.Track): List<String> =
    com.rm.acidulous.engine.NativeEngine.machineParamNames(track.machine.type).map { laneKey("machine", it) } +
        (0 until com.rm.acidulous.model.EVENTOR_SLOTS).flatMap { slot ->
            val ev = track.eventorAt(slot)
            if (ev.isEmpty) emptyList()
            else (com.rm.acidulous.engine.NativeEngine.eventorParamInfo(ev.type).map { it.name } + "bypass")
                .map { laneKey(com.rm.acidulous.model.eventorUnit(slot), it) }
        } +
        (0 until com.rm.acidulous.model.EFFECT_SLOTS).flatMap { slot ->
            val fx = track.effectAt(slot)
            if (fx.isEmpty) emptyList()
            else (com.rm.acidulous.engine.NativeEngine.effectParamInfo(fx.type).map { it.name } + "bypass")
                .map { laneKey(com.rm.acidulous.model.effectUnit(slot), it) }
        } +
        listOf("gain", "pan", "sendreverb", "senddelay").map { laneKey("channel", it) }
