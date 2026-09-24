package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.withParam
import androidx.compose.ui.text.drawText
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.NEXUS_CABLES
import com.rm.acidulous.model.NEXUS_KNOBS
import com.rm.acidulous.model.NEXUS_SLOTS
import com.rm.acidulous.model.NexusCable
import com.rm.acidulous.model.NexusModule
import com.rm.acidulous.model.NexusFamily
import com.rm.acidulous.model.nexusFamilyOf
import com.rm.acidulous.model.NexusPalette
import com.rm.acidulous.model.NexusPatch
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.nexusCableA
import com.rm.acidulous.model.nexusCableB
import com.rm.acidulous.model.nexusKnob
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.rm.acidulous.R

// The patch editor.
//
// Every other machine in this app is knobs, and a strip of knobs under the
// piano roll is the right shape for knobs. A graph is not: it needs room,
// two fingers, and somewhere to put a cable down. So Nexus gets a screen.
//
// One gesture loop handles everything, because two pointerInput modifiers
// fight over who consumes the first touch and a one-finger drag on a node
// must not pan the canvas. Down on a jack pulls a cable, down on a node
// moves it, down on nothing moves the view, and a second finger anywhere
// takes over as a pinch.

private const val NODE_W = 150f
private const val NODE_H = 92f
private const val JACK_R = 7f
private const val GRID = 10f

private sealed class Selection {
    object None : Selection()
    data class Module(val slot: Int) : Selection()
    data class Cable(val index: Int) : Selection()
}

private data class Jack(val slot: Int, val port: Int, val output: Boolean)

private fun jackPosition(m: NexusModule, port: Int, output: Boolean, ports: Int): Offset {
    val span = NODE_H - 26f
    val step = if (ports <= 1) 0f else span / (ports - 1)
    val y = m.y + 22f + (if (ports <= 1) span * 0.5f else port * step)
    return Offset(if (output) m.x + NODE_W else m.x, y)
}

@Composable
fun PatchScreen(
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    val patch = remember(track.machine.settings["nexus"]) {
        NexusPatch.decode(track.machine.settings["nexus"])
    }
    val palette = remember { NexusPalette.placeable }
    var selection by remember { mutableStateOf<Selection>(Selection.None) }
    var pan by remember { mutableStateOf(Offset(-40f, -40f)) }
    // In dp per unit of the patch's own space, not pixels. It was pixels,
    // which drew the modules a third the size on a dense screen as on a
    // plain one while their labels, in sp, stayed the same - so on the
    // densest the labels overran the modules they name. [scale] is what the
    // drawing and the gestures use; the labels are sized from zoom alone.
    var zoom by remember { mutableStateOf(0.9f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val scale = zoom * density
    var pulling by remember { mutableStateOf<Pair<Jack, Offset>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(FloatArray(0)) }
    var activity by remember { mutableStateOf(FloatArray(NEXUS_SLOTS + NEXUS_CABLES)) }

    val info = remember(track.machine.settings["nexus"]) { NativeEngine.nexusPalette() }
    val measurer = rememberTextMeasurer()
    val monoTag = stringResource(R.string.patch_mono_tag)
    val binding = rememberParamBinding(trackIndex, "Nexus", remember { NativeEngine.machineParamInfo("Nexus") }, editor)

    fun write(next: NexusPatch) {
        editor.edit(trackIndex) { t -> t.withSetting("nexus", next.encode()) }
    }

    // The patch lights up as it plays, which is why this polls at something
    // like a frame rate rather than the scope's old sixteen times a second: a
    // meter that updates every sixty milliseconds reads as a stutter, and the
    // whole point is to watch the signal move through the cables.
    LaunchedEffect(trackIndex) {
        val buffer = FloatArray(512)
        val levels = FloatArray(NEXUS_SLOTS + NEXUS_CABLES)
        while (true) {
            val n = NativeEngine.nexusScope(trackIndex, buffer)
            if (n > 0) scope = buffer.copyOf(n)
            if (NativeEngine.nexusActivity(trackIndex, levels) > 0) activity = levels.copyOf()
            delay(33)
        }
    }

    Column(modifier.fillMaxSize().background(c.bgDeep)) {
        // --- header -----------------------------------------------------------
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp,
            ),
            spacing = 2.dp,
        ) {
            HeaderButton("◀") { onBack() }
            Text(
                listOf(
                    track.name,
                    pluralStringResource(R.plurals.patch_modules, patch.modules.size, patch.modules.size),
                    pluralStringResource(R.plurals.patch_cables, patch.cables.size, patch.cables.size),
                ).joinToString(" · "),
                color = Acid.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.flexible().padding(horizontal = 4.dp), maxLines = 1,
            )
            HeaderTextButton(stringResource(R.string.patch_add)) { adding = true }
            HeaderTextButton(stringResource(R.string.patch_fit)) {
                if (patch.modules.isNotEmpty()) {
                    pan = Offset(patch.modules.minOf { it.x } - 30f, patch.modules.minOf { it.y } - 30f)
                    zoom = 0.9f
                }
            }
            LoadMeter()
        }

        // --- canvas and inspector ----------------------------------------------
        //
        // **Sideways they stand side by side.** The canvas is the thing being
        // worked on and the only child here that can give up any size, so
        // stacking them hands it the least room exactly when the screen is
        // shortest - three hundred and ninety-three dp less a header less an
        // inspector is not a patch you can see. Turned, the inspector takes a
        // column against the right edge instead and the canvas keeps the
        // height; it already has its own pan and zoom, so it needs nothing
        // else from this.
        val patchState by rememberUpdatedState(patch)
        val landscape = isLandscape()
        val canvasAndInspector: @Composable () -> Unit = {
        Box(if (landscape) Modifier.fillMaxHeight().weight(1f) else Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val world = { p: Offset -> Offset(p.x / (zoom * density) + pan.x, p.y / (zoom * density) + pan.y) }
                        val start = world(down.position)
                        val current = patchState

                        // What is under the finger, in the order that reads best:
                        // a jack beats the node it sits on, a node beats the canvas.
                        var jack: Jack? = null
                        for (m in current.modules) {
                            val meta = NexusPalette.of(m.type) ?: continue
                            meta.outputs.forEachIndexed { i, _ ->
                                if ((jackPosition(m, i, true, meta.outputs.size) - start).getDistance() < JACK_R * 2.4f) {
                                    jack = Jack(m.slot, i, true)
                                }
                            }
                            meta.inputs.forEachIndexed { i, _ ->
                                if ((jackPosition(m, i, false, meta.inputs.size) - start).getDistance() < JACK_R * 2.4f) {
                                    jack = Jack(m.slot, i, false)
                                }
                            }
                        }
                        val node = current.modules.lastOrNull {
                            start.x >= it.x && start.x <= it.x + NODE_W && start.y >= it.y && start.y <= it.y + NODE_H
                        }

                        if (jack != null) {
                            pulling = jack!! to start
                            down.consume()
                            var cursor = start
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                cursor = world(change.position)
                                pulling = jack!! to cursor
                                change.consume()
                                if (!change.pressed) break
                            }
                            // Landed on a jack of the opposite kind? Then it is a cable.
                            var target: Jack? = null
                            for (m in patchState.modules) {
                                val meta = NexusPalette.of(m.type) ?: continue
                                val list = if (jack!!.output) meta.inputs else meta.outputs
                                list.forEachIndexed { i, _ ->
                                    if ((jackPosition(m, i, !jack!!.output, list.size) - cursor)
                                            .getDistance() < JACK_R * 3.0f
                                    ) {
                                        target = Jack(m.slot, i, !jack!!.output)
                                    }
                                }
                            }
                            pulling = null
                            val t = target
                            if (t != null && t.slot != jack!!.slot) {
                                val from = if (jack!!.output) jack!! else t
                                val to = if (jack!!.output) t else jack!!
                                write(patchState.copy(cables = patchState.cables +
                                    NexusCable(from.slot, from.port, to.slot, to.port)))
                            }
                        } else if (node != null) {
                            val grabbed = node.slot
                            val offset = start - Offset(node.x, node.y)
                            selection = Selection.Module(grabbed)
                            down.consume()
                            editor.beginGesture(trackIndex)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val w = world(change.position) - offset
                                editor.updateGesture { t ->
                                    val p = NexusPatch.decode(t.machine.settings["nexus"])
                                    t.withSetting("nexus", p.copy(modules = p.modules.map {
                                        if (it.slot == grabbed) {
                                            it.copy(x = (w.x / GRID).toInt() * GRID, y = (w.y / GRID).toInt() * GRID)
                                        } else it
                                    }).encode())
                                }
                                change.consume()
                                if (!change.pressed) break
                            }
                            editor.endGesture()
                        } else {
                            // Empty canvas: pan, and pinch if a second finger lands.
                            selection = nearestCable(patchState, start)?.let { Selection.Cable(it) } ?: Selection.None
                            down.consume()
                            var last = down.position
                            var lastSpan = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val span = (pressed[0].position - pressed[1].position).getDistance()
                                    val centre = (pressed[0].position + pressed[1].position) * 0.5f
                                    if (lastSpan > 1f) {
                                        val factor = (span / lastSpan).coerceIn(0.8f, 1.25f)
                                        val before = Offset(centre.x / (zoom * density) + pan.x, centre.y / (zoom * density) + pan.y)
                                        zoom = (zoom * factor).coerceIn(0.35f, 2.6f)
                                        val after = Offset(centre.x / (zoom * density) + pan.x, centre.y / (zoom * density) + pan.y)
                                        pan += before - after
                                    }
                                    lastSpan = span
                                    last = centre
                                } else {
                                    val p = pressed[0].position
                                    pan -= (p - last) / (zoom * density)
                                    last = p
                                    lastSpan = 0f
                                }
                                pressed.forEach { it.consume() }
                            }
                        }
                    }
                },
            ) {
                drawPatch(patch, pan, scale, zoom, selection, pulling, scope, activity, measurer, c, monoTag)
            }
        }

        Inspector(patch, selection, binding, scope, landscape,
            onDelete = {
                when (val s = selection) {
                    is Selection.Module -> {
                        write(patch.copy(
                            modules = patch.modules.filterNot { it.slot == s.slot },
                            cables = patch.cables.filterNot { it.fromSlot == s.slot || it.toSlot == s.slot },
                        ))
                        selection = Selection.None
                    }
                    is Selection.Cable -> {
                        write(patch.copy(cables = patch.cables.filterIndexed { i, _ -> i != s.index }))
                        selection = Selection.None
                    }
                    else -> {}
                }
            },
            onTogglePoly = {
                val s = selection as? Selection.Module ?: return@Inspector
                write(patch.copy(modules = patch.modules.map {
                    if (it.slot == s.slot) it.copy(poly = !it.poly) else it
                }))
            },
        )
        }
        if (landscape) {
            Row(Modifier.fillMaxWidth().weight(1f)) { canvasAndInspector() }
        } else {
            canvasAndInspector()
        }
    }

    if (adding) {
        AddModuleDialog(palette, onDismiss = { adding = false }) { type ->
            adding = false
            val slot = patch.freeSlot()
            if (slot >= 0) {
                val meta = NexusPalette.of(type)
                // Placing a module writes its defaults into the slot, so the
                // knobs mean what the module says they mean rather than
                // whatever the last module in that slot left behind.
                meta?.defaults?.forEachIndexed { i, d ->
                    if (i < NEXUS_KNOBS) NativeEngine.setParam(trackIndex, "machine", nexusKnob(slot, i), d)
                }
                editor.edit(trackIndex) { t ->
                    val p = NexusPatch.decode(t.machine.settings["nexus"])
                    var next = t
                    meta?.defaults?.forEachIndexed { i, d ->
                        if (i < NEXUS_KNOBS) next = next.withParam(nexusKnob(slot, i), d)
                    }
                    next.withSetting("nexus", p.copy(modules = p.modules + NexusModule(
                        slot, type, meta?.canPoly != false,
                        pan.x + 120f, pan.y + 100f,
                    )).encode())
                }
            }
        }
    }
}

private fun nearestCable(patch: NexusPatch, at: Offset): Int? {
    var best: Int? = null
    var bestD = 18f
    patch.cables.forEachIndexed { index, c ->
        val from = patch.moduleAt(c.fromSlot) ?: return@forEachIndexed
        val to = patch.moduleAt(c.toSlot) ?: return@forEachIndexed
        val a = jackPosition(from, c.fromPort, true, NexusPalette.of(from.type)?.outputs?.size ?: 1)
        val b = jackPosition(to, c.toPort, false, NexusPalette.of(to.type)?.inputs?.size ?: 1)
        val mid = (a + b) * 0.5f
        val d = (mid - at).getDistance()
        if (d < bestD) { bestD = d; best = index }
    }
    return best
}

private fun DrawScope.drawPatch(
    patch: NexusPatch,
    pan: Offset,
    /** Pixels per unit of patch space: the drawing's scale. */
    zoom: Float,
    /** The zoom a person set, which is what the words are sized from. */
    textZoom: Float,
    selection: Selection,
    pulling: Pair<Jack, Offset>?,
    scope: FloatArray,
    activity: FloatArray,
    measurer: TextMeasurer,
    col: AcidColors,
    /** What a module that plays one voice says after its name. */
    monoTag: String,
) {
    fun screen(w: Offset) = Offset((w.x - pan.x) * zoom, (w.y - pan.y) * zoom)

    // A level, as something to draw with.
    //
    // Linear amplitude is the wrong scale for this: half the interesting life
    // of a patch happens below a tenth of full scale - an envelope's tail, a
    // slow LFO, a filter that has nearly closed - and on a linear map all of
    // that is indistinguishable from off. Sixty decibels of range, so a signal
    // forty decibels down still shows as a third lit.
    fun lit(level: Float): Float {
        if (level <= 1e-5f) return 0f
        return ((20f * log10(level) + 60f) / 60f).coerceIn(0f, 1f)
    }
    fun slotLit(slot: Int) = if (slot in 0 until NEXUS_SLOTS) lit(activity[slot]) else 0f
    fun cableLit(index: Int) =
        if (index in 0 until NEXUS_CABLES) lit(activity[NEXUS_SLOTS + index]) else 0f

    // A grid, so panning has something to push against.
    val step = 50f * zoom
    if (step > 8f) {
        var x = -((pan.x * zoom) % step)
        while (x < size.width) { drawLine(col.canvasGrid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = -((pan.y * zoom) % step)
        while (y < size.height) { drawLine(col.canvasGrid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }

    // Cables behind the boxes, as they are in life.
    patch.cables.forEachIndexed { index, c ->
        val from = patch.moduleAt(c.fromSlot) ?: return@forEachIndexed
        val to = patch.moduleAt(c.toSlot) ?: return@forEachIndexed
        val a = screen(jackPosition(from, c.fromPort, true, NexusPalette.of(from.type)?.outputs?.size ?: 1))
        val b = screen(jackPosition(to, c.toPort, false, NexusPalette.of(to.type)?.inputs?.size ?: 1))
        val selected = (selection as? Selection.Cable)?.index == index
        val path = Path().apply {
            moveTo(a.x, a.y)
            val bend = (abs(b.x - a.x) * 0.4f + 24f * zoom)
            cubicTo(a.x + bend, a.y, b.x - bend, b.y, b.x, b.y)
        }
        // The dark cable is always there, so an idle patch still reads as a
        // patch; what is carrying something is drawn over the top of it. Two
        // passes rather than one interpolated colour, because a glow wants to
        // be both brighter *and* thicker and a single stroke can only be one.
        drawPath(path, if (selected) col.accent else col.cable, style = Stroke(if (selected) 3.5f else 2.2f))
        val glow = cableLit(index)
        if (glow > 0.01f) {
            val tint = if (selected) col.accent else col.teal
            drawPath(path, tint.copy(alpha = 0.10f + 0.22f * glow), style = Stroke(2.2f + 7f * glow))
            drawPath(path, tint.copy(alpha = 0.35f + 0.65f * glow), style = Stroke(1.6f + 2.2f * glow))
        }
        if (selected) drawCircle(col.accent, 5f, (a + b) * 0.5f)
    }
    pulling?.let { (jack, at) ->
        val m = patch.moduleAt(jack.slot)
        if (m != null) {
            val meta = NexusPalette.of(m.type)
            val ports = if (jack.output) meta?.outputs?.size ?: 1 else meta?.inputs?.size ?: 1
            val a = screen(jackPosition(m, jack.port, jack.output, ports))
            val b = screen(at)
            drawLine(col.accent, a, b, 2.5f)
        }
    }

    for (m in patch.modules) {
        val meta = NexusPalette.of(m.type)
        val at = screen(Offset(m.x, m.y))
        val w = NODE_W * zoom
        val h = NODE_H * zoom
        if (at.x > size.width || at.y > size.height || at.x + w < 0f || at.y + h < 0f) continue
        val selected = (selection as? Selection.Module)?.slot == m.slot
        // A band of colour across the top, by what kind of module it is.
        // Thirty types all drawn the same grey is thirty identical boxes, and
        // the thing you want from a glance at a patch is its shape: where the
        // sound starts, where it is shaped, what is moving it.
        val family = nexusFamilyOf(m.type)
        val tint = when (family) {
            NexusFamily.Source -> col.accent
            NexusFamily.Voice -> col.pink
            NexusFamily.Shape -> col.teal
            NexusFamily.Mod -> col.green
            NexusFamily.Time -> col.sceneQueued
            NexusFamily.Io -> col.textDim
        }
        // How hard this module is working, which is what the colour band and
        // the halo below are both saying. A module that is silent keeps its
        // family colour at the old fixed strength, so the patch looks the same
        // as it always did when nothing is playing.
        val live = slotLit(m.slot)
        if (live > 0.01f) {
            drawRoundRect(
                tint.copy(alpha = 0.16f * live),
                at - Offset(5f * zoom, 5f * zoom), Size(w + 10f * zoom, h + 10f * zoom),
                androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            )
        }
        drawRoundRect(col.nodeBg, at, Size(w, h), androidx.compose.ui.geometry.CornerRadius(6f, 6f))
        val band = 14f * zoom
        drawRoundRect(
            tint.copy(alpha = 0.22f + 0.55f * live), at, Size(w, band + 6f * zoom),
            androidx.compose.ui.geometry.CornerRadius(6f, 6f),
        )
        drawRect(col.nodeBg, at + Offset(0f, band), Size(w, 6f * zoom))
        drawRoundRect(
            if (selected) col.accent else col.nodeEdge, at, Size(w, h),
            androidx.compose.ui.geometry.CornerRadius(6f, 6f), style = Stroke(if (selected) 2.5f else 1.2f),
        )
        if (textZoom > 0.55f) {
            val title = measurer.measure(
                AnnotatedString("${m.slot} ${m.type}${if (m.poly) "" else monoTag}"),
                TextStyle(color = tint, fontSize = (9f * textZoom).sp, fontFamily = FontFamily.Monospace),
            )
            drawText(title, topLeft = at + Offset(6f * zoom, 2f * zoom))
        }
        // Jacks, and what they are called. A cable is drawn between two dots
        // and until now there was no way to know which dot was "pitch" and
        // which was "fm" without selecting the module and reading the panel.
        val labels = textZoom > 0.85f
        meta?.inputs?.forEachIndexed { i, name ->
            val pos = screen(jackPosition(m, i, false, meta.inputs.size))
            drawCircle(col.teal, JACK_R * zoom, pos)
            if (labels && name.isNotEmpty()) {
                val t = measurer.measure(
                    AnnotatedString(name),
                    TextStyle(color = col.textDim, fontSize = (7f * textZoom).sp, fontFamily = FontFamily.Monospace),
                )
                drawText(t, topLeft = pos + Offset(JACK_R * zoom + 2f * zoom, -t.size.height / 2f))
            }
        }
        meta?.outputs?.forEachIndexed { i, name ->
            val pos = screen(jackPosition(m, i, true, meta.outputs.size))
            drawCircle(col.accent, JACK_R * zoom, pos)
            if (labels && name.isNotEmpty()) {
                val t = measurer.measure(
                    AnnotatedString(name),
                    TextStyle(color = col.textDim, fontSize = (7f * textZoom).sp, fontFamily = FontFamily.Monospace),
                )
                drawText(t, topLeft = pos - Offset(t.size.width + JACK_R * zoom + 2f * zoom, t.size.height / 2f))
            }
        }
        // A scope draws its own trace: it is the one module that is a picture.
        if (m.type == "scope" && scope.isNotEmpty() && textZoom > 0.5f) {
            val path = Path()
            scope.forEachIndexed { i, v ->
                val x = at.x + 8f * zoom + (w - 16f * zoom) * i / (scope.size - 1).coerceAtLeast(1)
                val y = at.y + h * 0.62f - v.coerceIn(-1f, 1f) * h * 0.28f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col.teal, style = Stroke(1.6f))
        }
    }
}

@Composable
private fun Inspector(
    patch: NexusPatch,
    selection: Selection,
    binding: ParamBinding,
    scope: FloatArray,
    /** A column against the right edge rather than a strip under the canvas. */
    vertical: Boolean = false,
    onDelete: () -> Unit,
    onTogglePoly: () -> Unit,
) {
    val c = Acid.colors
    // The body is `GroupRow` and `Group`, the same two the machine panels are
    // built from - so a column of them is a thing the app already knows how
    // to draw. `LocalPanelStacked` turns the row of cards into a column of
    // them and the knobs two to a line; without it the cards went on
    // scrolling sideways inside two hundred dp, which shows one and a half.
    androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides vertical) {
    Box(
        (if (vertical) {
            Modifier.width(INSPECTOR_W).fillMaxHeight()
        } else {
            Modifier.fillMaxWidth().heightIn(min = 96.dp)
        }).background(c.panel).padding(6.dp),
    ) {
        when (selection) {
            is Selection.Module -> {
                val m = patch.moduleAt(selection.slot)
                val meta = m?.let { NexusPalette.of(it.type) }
                if (m == null || meta == null) {
                    Text(stringResource(R.string.patch_gone), color = Acid.colors.textDim, fontSize = 11.sp)
                } else {
                    GroupRow {
                        Group("${m.slot} ${m.type}") {
                            meta.knobs.forEachIndexed { i, label ->
                                if (label.isNotEmpty() && i < NEXUS_KNOBS) {
                                    PanelKnob(binding, nexusKnob(m.slot, i), label,
                                        if (i == 0) PanelAmber else PanelTeal)
                                }
                            }
                        }
                        Group("slot") {
                            Column {
                                TextButton(onClick = onTogglePoly, enabled = meta.canPoly && meta.canMono) {
                                    Text(stringResource(if (m.poly) R.string.patch_poly else R.string.patch_mono), color = PanelAmber, fontSize = 11.sp)
                                }
                                TextButton(onClick = onDelete) {
                                    Text(stringResource(R.string.patch_remove), color = Acid.colors.red, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            is Selection.Cable -> {
                val c = patch.cables.getOrNull(selection.index)
                GroupRow {
                    Group("cable ${selection.index + 1}") {
                        if (selection.index < com.rm.acidulous.model.NEXUS_CABLES) {
                            PanelKnob(binding, nexusCableA(selection.index), "depth A", PanelAmber)
                            PanelKnob(binding, nexusCableB(selection.index), "depth B", PanelAmber)
                        } else {
                            Text(stringResource(R.string.patch_beyond), color = Acid.colors.textDim, fontSize = 10.sp)
                        }
                    }
                    Group("wire") {
                        Column {
                            Text(
                                if (c == null) "" else "%02d.%d → %02d.%d".format(c.fromSlot, c.fromPort, c.toSlot, c.toPort),
                                color = Acid.colors.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = onDelete) {
                                Text(stringResource(R.string.patch_cut), color = Acid.colors.red, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Selection.None -> GroupRow {
                Group("morph") { PanelKnob(binding, "morph", "A→B", PanelAmber) }
                Group("macros") {
                    for (i in 1..8) PanelKnob(binding, "macro$i", "$i")
                }
                Group("out") {
                    PanelKnob(binding, "volume", "volume")
                    PanelKnob(binding, "pan", "pan")
                    PanelKnob(binding, "drive", "drive", PanelPink)
                }
            }
        }
    }
    }
}

@Composable
private fun AddModuleDialog(
    palette: List<com.rm.acidulous.model.NexusModuleInfo>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = Acid.colors
    PlainDialog(
        title = stringResource(R.string.patch_add_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.cancel),
        maxBodyHeight = 420.dp,
        spacing = 3.dp,
    ) {
        // A fixed three-across grid rather than a FlowRow: the names are
        // very different lengths and wrapping them left ragged rows that
        // were hard to scan for the one you wanted.
        palette.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { info ->
                    Text(
                        info.name,
                        color = Acid.colors.textHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                            .background(c.control)
                            .clickable { onPick(info.name) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                    )
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * How wide the inspector stands when it is a column.
 *
 * The same hundred and eighty the editor gives the machine panel sideways,
 * because it is the same thing: a column of `Group` cards with the knobs two
 * to a line. One number, one shape, both screens.
 */
private val INSPECTOR_W = 180.dp
