package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.withParam
import androidx.compose.ui.text.drawText
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.FACE_HEADER
import com.rm.acidulous.model.FACE_LABEL_GAP
import com.rm.acidulous.model.NEXUS_CABLES
import com.rm.acidulous.model.NEXUS_KNOBS
import com.rm.acidulous.model.NEXUS_SLOTS
import com.rm.acidulous.model.NexusCable
import com.rm.acidulous.model.NexusFace
import com.rm.acidulous.model.NexusFaces
import com.rm.acidulous.model.NexusModule
import com.rm.acidulous.model.NexusFamily
import com.rm.acidulous.model.nexusFamilyOf
import com.rm.acidulous.model.NexusPalette
import com.rm.acidulous.model.NexusPatch
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.laneKey
import com.rm.acidulous.model.nexusCableA
import com.rm.acidulous.model.nexusCableB
import com.rm.acidulous.model.nexusKnob
import com.rm.acidulous.model.arranged
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors
import androidx.compose.ui.layout.onSizeChanged
import com.rm.acidulous.res.*

// The patch editor for Nexus. A graph needs more room than a knob strip, so
// it gets its own screen.
//
// Modules are drawn as modular panels hanging on rails, with their knobs and
// jacks on the face (see NexusFace for where everything goes). Cables hang
// off the jacks in front of the panels and sag under their own weight.
//
// One gesture loop handles everything, because two pointerInput modifiers
// fight over the first touch and a one-finger drag on a node must not pan the
// canvas. Down on a jack pulls a cable, down on a knob turns it, down on a
// module moves it, down on nothing pans, and a second finger anywhere starts
// a pinch.

private const val JACK_R = 7f
private const val GRID = 10f
/** Room left round a fitted patch, in its own units. */
private const val FIT_MARGIN = 10f
/** How far up a finger goes, in patch units, to turn a knob from one end to the other. Zooming in makes it finer. */
private const val KNOB_TRAVEL = 140f
/** Two taps on a knob this close together put it back. */
private const val DOUBLE_TAP_MS = 300L
private const val MIN_ZOOM = 0.35f
/** Further in than Fit goes, for fine knob moves. */
private const val MAX_ZOOM = 4f
/** How far a cable sags at most, in patch units. */
private const val MAX_SAG = 110f

private sealed class Selection {
    object None : Selection()
    data class Module(val slot: Int) : Selection()
    data class Cable(val index: Int) : Selection()
}

private data class Jack(val slot: Int, val port: Int, val output: Boolean)

/** A knob on a faceplate: [index] is the slot knob it turns, 0 to 7. */
private data class FaceKnobHit(val slot: Int, val index: Int)

private fun faceOf(m: NexusModule): NexusFace = NexusFaces.of(m.type)

private fun jackPosition(m: NexusModule, port: Int, output: Boolean): Offset {
    val face = faceOf(m)
    val p = (if (output) face.outputs else face.inputs).getOrNull(port)
        ?: return Offset(m.x + face.w / 2f, m.y + face.h / 2f)
    return Offset(m.x + p.x, m.y + p.y)
}

/**
 * The slot knobs a module shows, by number, in the order the face places
 * them. Unnamed knobs aren't drawn.
 */
private fun namedKnobs(type: String): List<Pair<Int, String>> =
    NexusPalette.of(type)?.knobs.orEmpty().withIndex()
        .filter { it.value.isNotEmpty() && it.index < NEXUS_KNOBS }
        .map { it.index to it.value }

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
    // In dp per patch unit, not pixels, so modules and their sp labels scale
    // together on every screen density. [scale] is what drawing and gestures
    // use; the labels are sized from zoom alone.
    var zoom by remember { mutableStateOf(0.9f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val scale = zoom * density
    var pulling by remember { mutableStateOf<Pair<Jack, Offset>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(FloatArray(0)) }
    var activity by remember { mutableStateOf(FloatArray(NEXUS_SLOTS + NEXUS_CABLES)) }
    // The canvas size in pixels, which Fit fits the patch to.
    var canvasPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // The last tap on a face knob, for a double tap to reset it.
    var lastKnobTap by remember { mutableStateOf<Pair<String, Long>?>(null) }

    val info = remember(track.machine.settings["nexus"]) { NativeEngine.nexusPalette() }
    // Every label on every faceplate is measured each frame while the patch
    // moves, so it keeps plenty.
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val monoTag = stringResource(Res.string.patch_mono_tag)
    val resources = AppStrings
    val word: (String) -> String = remember(resources) { { resources.panelWord(it) } }
    val binding = rememberParamBinding(trackIndex, "Nexus", remember { NativeEngine.machineParamInfo("Nexus") }, editor)

    // Mapping mode on the canvas: the same three colours as Modifier.mappable,
    // teal mappable, green mapped and blinking amber waiting for hardware.
    val mapMode = UiPrefs.mapMode
    val mapWaiting = UiPrefs.mapWaiting
    val mapped = (LocalSongMappings.current + UiPrefs.mappings)
        .filter { it.unit == binding.unit }.mapNotNull { it.name }.toSet()
    val blink = if (mapMode) {
        val b by rememberInfiniteTransition(label = "map").animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
            label = "blink",
        )
        b
    } else 1f
    val marks = AutomationMarks.lanes
    val locks = AutomationMarks.locks
    val knobLook = KnobLook(
        value = { binding.value(it) },
        display = { binding.display(it) },
        turning = binding.draggingName,
        ring = { name ->
            if (!mapMode) null else {
                val target = MapTargets.param(trackIndex, binding.unit, name)
                when {
                    mapWaiting == target -> c.accent.copy(alpha = blink)
                    name in mapped -> c.green
                    else -> c.teal
                }
            }
        },
        mark = { name ->
            val key = laneKey(binding.unit, name)
            when {
                key in locks -> KnobMark.Lock
                key in marks -> KnobMark.Automated
                else -> KnobMark.None
            }
        },
    )

    fun write(next: NexusPatch) {
        editor.edit(trackIndex) { t -> t.withSetting("nexus", next.encode()) }
    }

    // Polled at about frame rate so the lit cables and meters move smoothly.
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

    // Undo is the track's, like in the editor, and back returns to the editor.
    // See ui/Keys.kt.
    KeyScope(
        KeyAction.Undo to { if (editor.canUndo(trackIndex)) { selection = Selection.None; editor.undo(trackIndex) } },
        KeyAction.Redo to { if (editor.canRedo(trackIndex)) { selection = Selection.None; editor.redo(trackIndex) } },
        KeyAction.Back to { onBack() },
    )
    Column(modifier.fillMaxSize().background(c.bgDeep)) {
        // --- header -----------------------------------------------------------
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp,
            ),
            spacing = 2.dp,
        ) {
            HeaderButton("◀", description = stringResource(Res.string.a11y_back)) { onBack() }
            Text(
                listOf(
                    track.name,
                    pluralStringResource(Res.plurals.patch_modules, patch.modules.size, patch.modules.size),
                    pluralStringResource(Res.plurals.patch_cables, patch.cables.size, patch.cables.size),
                ).joinToString(" · "),
                color = Acid.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.flexible().padding(horizontal = 4.dp), maxLines = 1,
            )
            HeaderTextButton(stringResource(Res.string.patch_add)) { adding = true }
            // Fit rearranges the modules to the canvas's shape and then shows all of
            // it. The new layout is one undo step. See NexusPatch.arranged.
            HeaderTextButton(stringResource(Res.string.patch_fit)) {
                if (patch.modules.isNotEmpty() && canvasPx.width > 0 && canvasPx.height > 0) {
                    val next = patch.arranged(canvasPx.width / canvasPx.height.toFloat(), NexusFaces::size)
                    if (next != patch) write(next)
                    // Includes the rails and the cables, since one wrapping to the
                    // next row sags below the modules.
                    val box = patchBounds(next)
                    val left = box.left - FIT_MARGIN
                    val top = box.top - FIT_MARGIN
                    val w = box.width + 2 * FIT_MARGIN
                    val h = box.height + 2 * FIT_MARGIN
                    zoom = (min(canvasPx.width / w, canvasPx.height / h) / density).coerceIn(MIN_ZOOM, 2.6f)
                    // Centred in whichever direction there's room to spare.
                    val s = zoom * density
                    pan = Offset(left - (canvasPx.width / s - w) / 2f, top - (canvasPx.height / s - h) / 2f)
                }
            }
            LoadMeter()
        }

        // --- canvas and inspector ----------------------------------------------
        //
        // In landscape the inspector is a column on the right so the canvas keeps
        // the full height. Stacked, it would leave the canvas almost no room.
        val patchState by rememberUpdatedState(patch)
        val landscape = isLandscape()
        val canvasAndInspector: @Composable () -> Unit = {
        Box(if (landscape) Modifier.fillMaxHeight().weight(1f) else Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { canvasPx = it }
                    // One node for TalkBack. Selecting a module puts its knobs in the
                    // inspector below.
                    .button(
                        pluralStringResource(
                            Res.plurals.a11y_patch, patch.modules.size, patch.modules.size,
                            pluralStringResource(Res.plurals.a11y_patch_cables, patch.cables.size, patch.cables.size),
                        ),
                        actions = patch.modules.map { m ->
                            action(stringResource(Res.string.a11y_select_module, m.slot, m.type)) { selection = Selection.Module(m.slot) }
                        },
                    )
                    .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val world = { p: Offset -> Offset(p.x / (zoom * density) + pan.x, p.y / (zoom * density) + pan.y) }
                        val start = world(down.position)
                        val current = patchState

                        // What's under the finger: a jack beats a knob, a knob beats the
                        // module it sits on, a module beats the canvas. The nearest
                        // jack or knob wins, since their touch areas are bigger than
                        // they are and neighbours' can meet.
                        var jack: Jack? = null
                        var jackD = JACK_R * 2.4f
                        var knob: FaceKnobHit? = null
                        var knobD = Float.MAX_VALUE
                        for (m in current.modules) {
                            val face = faceOf(m)
                            for (output in listOf(false, true)) {
                                val ports = if (output) face.outputs else face.inputs
                                for (i in ports.indices) {
                                    val d = (jackPosition(m, i, output) - start).getDistance()
                                    if (d < jackD) { jackD = d; jack = Jack(m.slot, i, output) }
                                }
                            }
                            namedKnobs(m.type).zip(face.knobs).forEach { (k, fk) ->
                                val d = hypot(m.x + fk.x - start.x, m.y + fk.y - start.y)
                                if (d < fk.ring && d < knobD) { knobD = d; knob = FaceKnobHit(m.slot, k.first) }
                            }
                        }
                        val node = current.modules.lastOrNull {
                            val face = faceOf(it)
                            start.x >= it.x && start.x <= it.x + face.w && start.y >= it.y && start.y <= it.y + face.h
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
                            // Landed on a jack of the opposite kind? Then it's a cable.
                            var target: Jack? = null
                            var targetD = JACK_R * 3.0f
                            for (m in patchState.modules) {
                                val face = faceOf(m)
                                val list = if (jack!!.output) face.inputs else face.outputs
                                for (i in list.indices) {
                                    val d = (jackPosition(m, i, !jack!!.output) - cursor).getDistance()
                                    if (d < targetD) { targetD = d; target = Jack(m.slot, i, !jack!!.output) }
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
                        } else if (knob != null) {
                            val hit = knob!!
                            val name = nexusKnob(hit.slot, hit.index)
                            selection = Selection.Module(hit.slot)
                            down.consume()
                            if (UiPrefs.mapMode) {
                                // As Modifier.mappable: a tap arms the knob, a long press
                                // clears what drives it.
                                val target = MapTargets.param(trackIndex, binding.unit, name)
                                val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                        if (event.changes.none { it.pressed }) break
                                    }
                                }
                                if (lifted != null) {
                                    UiPrefs.chooseMapWaiting(target)
                                } else {
                                    clearMapping(target)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                        if (event.changes.none { it.pressed }) break
                                    }
                                }
                            } else {
                                val tap = lastKnobTap
                                val now = down.uptimeMillis
                                // Up and down to turn, measured in patch units, so the same
                                // finger travel turns it less the further in you zoom.
                                val from = binding.value(name)
                                var turning = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dy = (change.position.y - down.position.y) / (zoom * density)
                                    if (!turning && abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                                        turning = true
                                        binding.start(name)
                                    }
                                    if (turning) binding.change(name, (from - dy / KNOB_TRAVEL).coerceIn(0f, 1f))
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                if (turning) {
                                    binding.end()
                                    lastKnobTap = null
                                } else if (tap != null && tap.first == name && now - tap.second < DOUBLE_TAP_MS) {
                                    binding.reset(name)
                                    lastKnobTap = null
                                } else {
                                    lastKnobTap = name to now
                                }
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
                                        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
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
                drawPatch(patch, pan, scale, zoom, selection, pulling, scope, activity, measurer, c, monoTag, word, knobLook)
            }
        }

        Inspector(patch, selection, binding, scope, landscape,
            warning = com.rm.acidulous.engine.EngineSync.nexusWarnings[trackIndex].orEmpty(),
            onDelete = {
                when (val s = selection) {
                    is Selection.Module -> {
                        write(patch.copy(
                            modules = patch.modules.filterNot { it.slot == s.slot },
                            cables = patch.cables.filterNot { it.fromSlot == s.slot || it.toSlot == s.slot },
                            texts = patch.texts - s.slot,
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
            onSetText = { text ->
                val s = selection as? Selection.Module ?: return@Inspector
                write(patch.copy(texts = if (text.isBlank()) patch.texts - s.slot else patch.texts + (s.slot to text.trim())))
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
                // Placing a module writes its defaults into the slot, so the knobs
                // don't keep whatever the last module in that slot left behind.
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
                    ), texts = p.texts - slot).encode())
                }
            }
        }
    }
}

/** What a face knob shows besides its position. */
private enum class KnobMark { None, Automated, Lock }

/** How the canvas reads the knobs it draws, so drawPatch needn't know about bindings or mapping. */
private class KnobLook(
    val value: (String) -> Float,
    /** The value as the inspector would write it, for the tip shown while turning. */
    val display: (String) -> String,
    /** The knob being turned, if any. */
    val turning: String?,
    /** The mapping ring's colour, or null outside mapping mode. */
    val ring: (String) -> Color?,
    val mark: (String) -> KnobMark,
)

/** The control points of a cable's curve from an output at [a] to an input at [b], in any units. */
private fun cableControls(a: Offset, b: Offset, unit: Float): Pair<Offset, Offset> {
    val dx = b.x - a.x
    // The plugs point out of the panel, so the cord drops straight from each
    // and sags in between like a real one, more the longer it is.
    val sag = min(MAX_SAG * unit, 18f * unit + (b - a).getDistance() * 0.28f)
    return Offset(a.x + dx * 0.15f, a.y + sag) to Offset(b.x - dx * 0.15f, b.y + sag)
}

/** The curve drawPatch draws for a cable from an output at [a] to an input at [b]. */
private fun cablePath(a: Offset, b: Offset, unit: Float): Path = Path().apply {
    val (p1, p2) = cableControls(a, b, unit)
    moveTo(a.x, a.y)
    cubicTo(p1.x, p1.y, p2.x, p2.y, b.x, b.y)
}

private fun cablePoint(a: Offset, b: Offset, t: Float, unit: Float = 1f): Offset {
    val (p1, p2) = cableControls(a, b, unit)
    val u = 1f - t
    return a * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + b * (t * t * t)
}

/**
 * How far the rails reach past a module's sides, how deep they are, and how
 * far they run under its top and bottom edges, where its screws go in.
 */
private const val RAIL_REACH = 10f
private const val RAIL_DEPTH = 10f
private const val RAIL_UNDER = 2f

/** The bounds of everything the patch draws, in patch units: modules, their rails and the cable curves. */
private fun patchBounds(patch: NexusPatch): Rect {
    var left = patch.modules.minOf { it.x } - RAIL_REACH
    var top = patch.modules.minOf { it.y } - RAIL_DEPTH + RAIL_UNDER
    var right = patch.modules.maxOf { it.x + faceOf(it).w } + RAIL_REACH
    var bottom = patch.modules.maxOf { it.y + faceOf(it).h } + RAIL_DEPTH - RAIL_UNDER
    for (c in patch.cables) {
        val from = patch.moduleAt(c.fromSlot) ?: continue
        val to = patch.moduleAt(c.toSlot) ?: continue
        val a = jackPosition(from, c.fromPort, true)
        val b = jackPosition(to, c.toPort, false)
        // Walks the curve, since a Path's bounds include its control points,
        // which reach well past the curve itself.
        for (i in 1 until 16) {
            val at = cablePoint(a, b, i / 16f)
            left = minOf(left, at.x); right = maxOf(right, at.x)
            top = minOf(top, at.y); bottom = maxOf(bottom, at.y)
        }
    }
    return Rect(left, top, right, bottom)
}

private fun nearestCable(patch: NexusPatch, at: Offset): Int? {
    var best: Int? = null
    var bestD = 18f
    patch.cables.forEachIndexed { index, c ->
        val from = patch.moduleAt(c.fromSlot) ?: return@forEachIndexed
        val to = patch.moduleAt(c.toSlot) ?: return@forEachIndexed
        val a = jackPosition(from, c.fromPort, true)
        val b = jackPosition(to, c.toPort, false)
        // The middle of the cord, which hangs well below the straight line.
        val d = (cablePoint(a, b, 0.5f) - at).getDistance()
        if (d < bestD) { bestD = d; best = index }
    }
    return best
}

/** A colour for each kind of module: its stripe, its LED and the cables it sends. */
private fun familyColour(type: String, col: AcidColors): Color = when (nexusFamilyOf(type)) {
    NexusFamily.Source -> col.accent
    NexusFamily.Voice -> col.pink
    NexusFamily.Shape -> col.teal
    NexusFamily.Mod -> col.green
    NexusFamily.Time -> col.sceneQueued
    NexusFamily.Io -> col.textDim
}

/**
 * A cable takes the colour of the module it comes from, the way players
 * colour-code real patch cables, so you can see what kind of signal it is.
 * Modulators use a lighter green than their stripe, which is too dark for a cord.
 */
private fun cableColour(type: String, col: AcidColors): Color =
    if (nexusFamilyOf(type) == NexusFamily.Mod) col.modCable else familyColour(type, col)

private fun DrawScope.drawPatch(
    patch: NexusPatch,
    pan: Offset,
    /** Pixels per unit of patch space: the drawing's scale. */
    zoom: Float,
    /** The zoom the user set, which text is sized from. */
    textZoom: Float,
    selection: Selection,
    pulling: Pair<Jack, Offset>?,
    scope: FloatArray,
    activity: FloatArray,
    measurer: TextMeasurer,
    col: AcidColors,
    /** Shown after the name of a module that plays one voice. */
    monoTag: String,
    /** Translates a jack's name, see PanelText.kt. */
    word: (String) -> String,
    knobs: KnobLook,
) {
    fun screen(w: Offset) = Offset((w.x - pan.x) * zoom, (w.y - pan.y) * zoom)

    // A level as a 0 to 1 brightness, over a 60 dB range. On a linear scale
    // envelope tails, slow LFOs and nearly closed filters would all look off.
    // A signal 40 dB down still shows as a third lit.
    fun lit(level: Float): Float {
        if (level <= 1e-5f) return 0f
        return ((20f * log10(level) + 60f) / 60f).coerceIn(0f, 1f)
    }
    fun slotLit(slot: Int) = if (slot in 0 until NEXUS_SLOTS) lit(activity[slot]) else 0f
    fun cableLit(index: Int) =
        if (index in 0 until NEXUS_CABLES) lit(activity[NEXUS_SLOTS + index]) else 0f

    // The case: perforated, so panning is visible.
    val step = 16f * zoom
    if (step > 7f) {
        val dots = ArrayList<Offset>()
        var x = -((pan.x * zoom) % step)
        while (x < size.width) {
            var y = -((pan.y * zoom) % step)
            while (y < size.height) { dots += Offset(x, y); y += step }
            x += step
        }
        drawPoints(dots, PointMode.Points, col.canvasGrid, strokeWidth = (1.8f * zoom).coerceIn(1.5f, 4f), cap = StrokeCap.Round)
    }

    fun visible(m: NexusModule): Boolean {
        val face = faceOf(m)
        val at = screen(Offset(m.x - RAIL_REACH, m.y - RAIL_DEPTH + RAIL_UNDER))
        val w = (face.w + 2 * RAIL_REACH) * zoom
        val h = (face.h + 2 * (RAIL_DEPTH - RAIL_UNDER)) * zoom
        return !(at.x > size.width || at.y > size.height || at.x + w < 0f || at.y + h < 0f)
    }

    // Rails behind everything, so modules side by side share one.
    for (m in patch.modules) if (visible(m)) drawRails(m, ::screen, zoom, col)

    for (m in patch.modules) {
        if (!visible(m)) continue
        drawModule(
            m, screen(Offset(m.x, m.y)), zoom, textZoom,
            selected = (selection as? Selection.Module)?.slot == m.slot,
            live = slotLit(m.slot), scope = scope, measurer = measurer, col = col,
            monoTag = monoTag, word = word, knobs = knobs,
        )
    }

    // Cables in front of the panels, as they hang in a real case.
    val plugged = ArrayList<Pair<Offset, Color>>()
    patch.cables.forEachIndexed { index, c ->
        val from = patch.moduleAt(c.fromSlot) ?: return@forEachIndexed
        val to = patch.moduleAt(c.toSlot) ?: return@forEachIndexed
        val a = screen(jackPosition(from, c.fromPort, true))
        val b = screen(jackPosition(to, c.toPort, false))
        val selected = (selection as? Selection.Cable)?.index == index
        val tint = if (selected) col.accent else cableColour(from.type, col)
        drawCable(a, b, tint, cableLit(index), zoom, col, thick = selected)
        if (selected) drawCircle(col.accent, 4f * zoom, cablePoint(a, b, 0.5f, zoom))
        plugged += a to tint
        plugged += b to tint
    }
    for ((at, tint) in plugged) drawPlug(at, tint, zoom, col)

    pulling?.let { (jack, at) ->
        val m = patch.moduleAt(jack.slot)
        if (m != null) {
            val a = screen(jackPosition(m, jack.port, jack.output))
            val b = screen(at)
            if (jack.output) drawCable(a, b, col.accent, 0f, zoom, col, thick = false)
            else drawCable(b, a, col.accent, 0f, zoom, col, thick = false)
            drawPlug(a, col.accent, zoom, col)
        }
    }

    // The value of the knob being turned, over it, like the inspector's readout.
    knobs.turning?.let { name ->
        val slot = name.substring(1, 3).toIntOrNull() ?: return@let
        val index = name.substringAfter("_p").toIntOrNull()?.minus(1) ?: return@let
        val m = patch.moduleAt(slot) ?: return@let
        val at = namedKnobs(m.type).zip(faceOf(m).knobs).firstOrNull { it.first.first == index } ?: return@let
        val (named, fk) = at
        val text = measurer.measure(
            AnnotatedString("${word(named.second)} ${knobs.display(name)}"),
            TextStyle(color = col.accent, fontSize = (9f * textZoom).sp, fontFamily = FontFamily.Monospace),
        )
        val centre = screen(Offset(m.x + fk.x, m.y + fk.y - fk.ring - 12f))
        val pad = 4f * zoom
        val box = Size(text.size.width + 2 * pad, text.size.height + pad)
        val corner = centre - Offset(box.width / 2f, box.height / 2f)
        drawRoundRect(col.tip, corner, box, CornerRadius(4f * zoom))
        drawText(text, topLeft = corner + Offset(pad, pad / 2f))
    }
}

/** The rails a module hangs on, above and below it. Their holes line up along the whole canvas. */
private fun DrawScope.drawRails(m: NexusModule, screen: (Offset) -> Offset, zoom: Float, col: AcidColors) {
    val face = faceOf(m)
    for (y in listOf(m.y - RAIL_DEPTH + RAIL_UNDER, m.y + face.h - RAIL_UNDER)) {
        val at = screen(Offset(m.x - RAIL_REACH, y))
        val size = Size((face.w + 2 * RAIL_REACH) * zoom, RAIL_DEPTH * zoom)
        drawRect(Brush.verticalGradient(listOf(lerp(col.rail, col.nodeHi, 0.35f), col.rail), at.y, at.y + size.height), at, size)
        if (zoom > 0.9f) {
            val pitch = 12f
            var x = kotlin.math.ceil((m.x - RAIL_REACH + 2f) / pitch) * pitch
            while (x + 5f <= m.x + face.w + RAIL_REACH) {
                drawRoundRect(col.railHole, screen(Offset(x, y + 3.5f)), Size(5f * zoom, 3f * zoom), CornerRadius(1.5f * zoom))
                x += pitch
            }
        }
    }
}

/** A label in patch units at [size], shrunk to fit [maxWidth] pixels if it's too wide. */
private fun TextMeasurer.fitted(
    text: String, size: Float, textZoom: Float, color: Color, maxWidth: Float,
    bold: Boolean = false, spaced: Boolean = false,
): TextLayoutResult {
    fun style(s: Float) = TextStyle(
        color = color, fontSize = (s * textZoom).sp, fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        letterSpacing = if (spaced) 0.2.em else 0.em,
    )
    val first = measure(AnnotatedString(text), style(size), maxLines = 1, softWrap = false)
    if (first.size.width <= maxWidth || maxWidth <= 0f) return first
    val smaller = (size * maxWidth / first.size.width).coerceAtLeast(size * 0.6f)
    return measure(AnnotatedString(text), style(smaller), maxLines = 1, softWrap = false)
}

private fun DrawScope.drawModule(
    m: NexusModule,
    at: Offset,
    zoom: Float,
    textZoom: Float,
    selected: Boolean,
    /** How hard the module is working, 0 to 1, which its LED shows. */
    live: Float,
    scope: FloatArray,
    measurer: TextMeasurer,
    col: AcidColors,
    monoTag: String,
    word: (String) -> String,
    knobs: KnobLook,
) {
    val face = faceOf(m)
    val meta = NexusPalette.of(m.type)
    fun u(x: Float, y: Float) = Offset(at.x + x * zoom, at.y + y * zoom)
    val w = face.w * zoom
    val h = face.h * zoom
    val corner = CornerRadius(2.5f * zoom)
    val tint = familyColour(m.type, col)
    val labels = textZoom > 0.75f

    // Shadow, so the panel stands off the case, then the plate, lit from the
    // top left like brushed aluminium.
    drawRoundRect(col.faceShadow.copy(alpha = col.faceShadow.alpha * 0.5f), u(-2f, 2f), Size(w + 4f * zoom, h + 7f * zoom), CornerRadius(5f * zoom))
    drawRoundRect(col.faceShadow, u(1f, 4f), Size(w, h), corner)
    drawRoundRect(Brush.linearGradient(listOf(col.nodeHi, col.nodeBg), at, u(face.w * 0.3f, face.h)), at, Size(w, h), corner)

    // The stripe with the module's name, in its family's colour.
    val band = FACE_HEADER * zoom
    drawRoundRect(tint, at, Size(w, band), corner)
    drawRect(tint, u(0f, FACE_HEADER - 3f), Size(w, 3f * zoom))
    drawRect(
        Brush.verticalGradient(listOf(col.cableShine.copy(alpha = col.cableShine.alpha * 0.5f), col.faceShadow.copy(alpha = 0.12f)), at.y, at.y + band),
        at, Size(w, band),
    )
    if (textZoom > 0.45f) {
        val title = measurer.fitted(
            m.type.uppercase(), 9.5f, textZoom, col.onAccent, w - 26f * zoom, bold = true, spaced = true,
        )
        drawText(title, topLeft = Offset(at.x + (w - title.size.width) / 2f, at.y + (band - title.size.height) / 2f))
    }

    // Slot number and mono tag on a little badge, and the LED opposite.
    if (labels) {
        val badge = measurer.fitted(
            "%02d".format(m.slot) + if (m.poly) "" else monoTag, 7f, textZoom, col.textDim, w - 26f * zoom,
        )
        val pad = 3f * zoom
        val badgeAt = u(5f, FACE_HEADER + 4f)
        drawRoundRect(col.faceShadow.copy(alpha = 0.3f), badgeAt, Size(badge.size.width + 2 * pad, badge.size.height + pad), CornerRadius(3f * zoom))
        drawText(badge, topLeft = badgeAt + Offset(pad, pad / 2f))
    }
    val led = u(face.w - 10f, FACE_HEADER + 10f)
    if (live > 0.01f) drawCircle(tint.copy(alpha = 0.35f * live), 7f * zoom, led)
    drawCircle(lerp(col.jackHole, tint, 0.25f + 0.75f * live), 3.2f * zoom, led)
    drawCircle(col.faceShadow, 3.2f * zoom, led, style = Stroke(0.8f * zoom))
    drawCircle(col.cableShine, 1f * zoom, led - Offset(zoom, zoom))

    // Screws: four on a wide panel, two across the corners of a narrow one.
    val wide = face.w > 70f
    val screws = if (wide) {
        listOf(9f to 5f, face.w - 9f to 5f, 9f to face.h - 7f, face.w - 9f to face.h - 7f)
    } else {
        listOf(9f to 5f, face.w - 9f to face.h - 7f)
    }
    screws.forEachIndexed { i, (x, y) -> drawScrew(u(x, y), zoom, m.slot * 4 + i, col) }

    // The scope's screen, with its trace.
    face.screen?.let { r ->
        val topLeft = u(r.x, r.y)
        val sz = Size(r.w * zoom, r.h * zoom)
        drawRoundRect(col.screen, topLeft, sz, CornerRadius(4f * zoom))
        for (i in 1 until 6) {
            val x = topLeft.x + sz.width * i / 6f
            drawLine(col.screenGrid, Offset(x, topLeft.y), Offset(x, topLeft.y + sz.height), 0.6f * zoom)
        }
        for (i in 1 until 4) {
            val y = topLeft.y + sz.height * i / 4f
            drawLine(col.screenGrid, Offset(topLeft.x, y), Offset(topLeft.x + sz.width, y), 0.6f * zoom)
        }
        if (scope.isNotEmpty()) {
            val path = Path()
            scope.forEachIndexed { i, v ->
                val x = topLeft.x + 3f * zoom + (sz.width - 6f * zoom) * i / (scope.size - 1).coerceAtLeast(1)
                val y = topLeft.y + sz.height / 2f - v.coerceIn(-1f, 1f) * sz.height * 0.42f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col.screenTrace.copy(alpha = 0.25f), style = Stroke(3.5f * zoom))
            drawPath(path, col.screenTrace, style = Stroke(1.1f * zoom))
        }
        drawRoundRect(col.faceShadow, topLeft, sz, CornerRadius(4f * zoom), style = Stroke(1.2f * zoom))
    }

    // Outputs sit in a dark box, as on real modules.
    face.outBox?.let { r -> drawRoundRect(col.outBox, u(r.x, r.y), Size(r.w * zoom, r.h * zoom), CornerRadius(4f * zoom)) }

    // Knobs. The first is the module's main control, amber like the inspector's.
    namedKnobs(m.type).zip(face.knobs).forEachIndexed { i, (named, fk) ->
        val (index, label) = named
        val name = nexusKnob(m.slot, index)
        drawFaceKnob(
            u(fk.x, fk.y), fk.r * zoom, fk.arc * zoom, fk.ring * zoom, knobs.value(name),
            if (i == 0) col.accent else col.teal, knobs.ring(name), knobs.mark(name), zoom, col,
        )
        if (labels) {
            val t = measurer.fitted(word(label).uppercase(), 6.6f, textZoom, col.textDim, (face.span - 2f) * zoom)
            drawText(t, topLeft = u(fk.x, fk.y + fk.arc + FACE_LABEL_GAP) - Offset(t.size.width / 2f, 0f))
        }
    }

    // Jacks, teal in and amber out.
    for (output in listOf(false, true)) {
        val points = if (output) face.outputs else face.inputs
        val names = if (output) meta?.outputs.orEmpty() else meta?.inputs.orEmpty()
        points.forEachIndexed { i, p ->
            drawSocket(u(p.x, p.y), if (output) col.accent else col.teal, zoom, col)
            val name = names.getOrNull(i).orEmpty()
            if (labels && name.isNotEmpty()) {
                val t = measurer.fitted(
                    word(name).uppercase(), 6.4f, textZoom, if (output) col.outBoxText else col.textDim, (face.span - 2f) * zoom,
                )
                drawText(t, topLeft = u(p.x, p.y + 8.5f) - Offset(t.size.width / 2f, 0f))
            }
        }
    }

    drawRoundRect(
        if (selected) col.accent else col.nodeEdge, at, Size(w, h), corner,
        style = Stroke(if (selected) 2.5f * zoom.coerceAtMost(1.5f) else 1f),
    )
}

private fun DrawScope.drawScrew(at: Offset, zoom: Float, seed: Int, col: AcidColors) {
    val r = 3.4f * zoom
    drawCircle(Brush.radialGradient(listOf(col.screwHi, col.screw), at - Offset(zoom, zoom), r * 1.1f), r, at)
    drawCircle(col.faceShadow, r, at, style = Stroke(0.6f * zoom))
    // Each screw turned a different way, as they end up in a real case.
    val a = (seed * 2.399f) % PI.toFloat()
    val d = Offset(cos(a), sin(a)) * (2.3f * zoom)
    drawLine(col.screwSlot, at - d, at + d, 1.1f * zoom)
}

/** A 3.5 mm socket: a hex nut, a coloured ring and the hole. */
private fun DrawScope.drawSocket(at: Offset, ring: Color, zoom: Float, col: AcidColors) {
    val r = 6.6f * zoom
    val nut = Path().apply {
        for (i in 0 until 6) {
            val a = PI.toFloat() / 6f + i * PI.toFloat() / 3f
            val p = at + Offset(cos(a), sin(a)) * r
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(nut, Brush.verticalGradient(listOf(col.jackNutHi, col.jackNut), at.y - r, at.y + r))
    drawPath(nut, col.faceShadow, style = Stroke(0.6f * zoom))
    drawCircle(ring, 4.4f * zoom, at)
    drawCircle(col.jackHole, 2.6f * zoom, at)
}

/** A plug pushed into a socket, in its cable's colour. */
private fun DrawScope.drawPlug(at: Offset, tint: Color, zoom: Float, col: AcidColors) {
    val r = 5.4f * zoom
    drawCircle(col.cableShadow, r, at + Offset(0f, 1.5f * zoom))
    drawCircle(Brush.radialGradient(listOf(lerp(tint, col.nodeHi, 0.4f), lerp(tint, col.jackHole, 0.2f)), at - Offset(1.5f * zoom, 1.5f * zoom), r * 1.1f), r, at)
    drawCircle(col.faceShadow, 2.2f * zoom, at)
}

/** A patch cable from [a] to [b]: shadow, cord, its shine, and a glow while signal runs through it. */
private fun DrawScope.drawCable(a: Offset, b: Offset, tint: Color, glow: Float, zoom: Float, col: AcidColors, thick: Boolean) {
    val path = cablePath(a, b, zoom)
    val w = (if (thick) 4.4f else 3.4f) * zoom
    val round = { width: Float -> Stroke(width.coerceAtLeast(1f), cap = StrokeCap.Round) }
    val shadowOffset = Offset(1.5f * zoom, 4f * zoom)
    drawPath(Path().apply { addPath(path, shadowOffset) }, col.cableShadow, style = round(w * 1.6f))
    drawPath(path, lerp(tint, col.jackHole, 0.3f), style = round(w * 1.35f))
    drawPath(path, tint, style = round(w))
    if (glow > 0.01f) drawPath(path, tint.copy(alpha = 0.10f + 0.22f * glow), style = round(w + 7f * zoom * glow))
    drawPath(Path().apply { addPath(path, Offset(-0.6f * zoom, -0.9f * zoom)) }, col.cableShine, style = round(0.9f * zoom))
}

private fun DrawScope.drawFaceKnob(
    at: Offset,
    r: Float,
    arc: Float,
    ring: Float,
    value: Float,
    accent: Color,
    /** The mapping ring's colour, or null when not mapping. */
    mapRing: Color?,
    mark: KnobMark,
    zoom: Float,
    col: AcidColors,
) {
    val v = value.coerceIn(0f, 1f)
    val arcStroke = Stroke(2f * zoom, cap = StrokeCap.Round)
    val arcTopLeft = at - Offset(arc, arc)
    val arcSize = Size(arc * 2f, arc * 2f)
    drawArc(col.raised, 135f, 270f, false, arcTopLeft, arcSize, style = arcStroke)
    if (v > 0.001f) drawArc(accent, 135f, 270f * v, false, arcTopLeft, arcSize, style = arcStroke)

    // The cap, lit from the top left, with a knurled edge when it's big enough to see.
    drawCircle(col.faceShadow, r, at + Offset(0f, 1.5f * zoom))
    drawCircle(Brush.radialGradient(listOf(col.knobCapHi, col.knobCap), at - Offset(r * 0.35f, r * 0.4f), r * 1.1f), r, at)
    if (r > 10f) {
        for (j in 0 until 24) {
            val a = j / 24f * 2f * PI.toFloat()
            val d = Offset(cos(a), sin(a))
            drawLine(col.faceShadow, at + d * (r * 0.86f), at + d * r, 0.5f * zoom)
        }
    }
    val a = (135f + 270f * v) * PI.toFloat() / 180f
    val d = Offset(cos(a), sin(a))
    drawLine(col.knobPointer, at + d * (r * 0.25f), at + d * (r * 0.85f), (r * 0.16f).coerceAtLeast(1.3f * zoom), cap = StrokeCap.Round)

    when (mark) {
        KnobMark.None -> {}
        KnobMark.Automated -> drawCircle(col.accentSoft, 2f * zoom, at + Offset(arc * 0.8f, -arc * 0.8f))
        KnobMark.Lock -> drawRect(col.accentSoft, at + Offset(arc * 0.8f - 1.8f * zoom, -arc * 0.8f - 1.8f * zoom), Size(3.6f * zoom, 3.6f * zoom))
    }

    // The mapping ring stays inside the room NexusFace gave it, so neighbours' rings never touch.
    if (mapRing != null) {
        val width = 1.4f * zoom
        drawCircle(
            mapRing, ring - width / 2f, at,
            style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * zoom, 2f * zoom))),
        )
    }
}

@Composable
private fun Inspector(
    patch: NexusPatch,
    selection: Selection,
    binding: ParamBinding,
    scope: FloatArray,
    /** Show as a column on the right edge instead of a strip under the canvas. */
    vertical: Boolean = false,
    onDelete: () -> Unit,
    onTogglePoly: () -> Unit,
    /** What the engine said about the patch, such as a formula that doesn't parse. */
    warning: String = "",
    /** Sets the selected module's text: a formula module's expression. */
    onSetText: (String) -> Unit = {},
) {
    val c = Acid.colors
    // Built from `GroupRow` and `Group` like the machine panels.
    // `LocalPanelStacked` turns the row of cards into a column with two knobs
    // to a line, so the cards don't scroll sideways in a narrow column.
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
                    Text(stringResource(Res.string.patch_gone), color = Acid.colors.textDim, fontSize = 11.sp)
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
                                    Text(stringResource(if (m.poly) Res.string.patch_poly else Res.string.patch_mono), color = PanelAmber, fontSize = 11.sp)
                                }
                                TextButton(onClick = onDelete) {
                                    Text(stringResource(Res.string.patch_remove), color = Acid.colors.red, fontSize = 11.sp)
                                }
                            }
                        }
                        // A formula module is programmed with text, kept in the patch.
                        if (m.type == "formula") {
                            Group("formula") { ModuleFormula(m.slot, patch.texts[m.slot].orEmpty(), warning, onSetText) }
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
                            Text(stringResource(Res.string.patch_beyond), color = Acid.colors.textDim, fontSize = 10.sp)
                        }
                    }
                    Group("wire") {
                        Column {
                            Text(
                                if (c == null) "" else "%02d.%d → %02d.%d".format(c.fromSlot, c.fromPort, c.toSlot, c.toPort),
                                color = Acid.colors.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = onDelete) {
                                Text(stringResource(Res.string.patch_cut), color = Acid.colors.red, fontSize = 11.sp)
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

/**
 * A formula module's expression and whether it parses, with a button to
 * edit it. It's applied on OK, so a half-typed one isn't built on every key.
 */
@Composable
private fun ModuleFormula(slot: Int, text: String, warning: String, onSet: (String) -> Unit) {
    val c = Acid.colors
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(min = 140.dp, max = 260.dp)) {
        Text(
            text.ifEmpty { stringResource(Res.string.formula_none) },
            color = if (text.isEmpty()) c.textDim else c.textHi,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        // The engine names the module and its slot, so only this one's is shown.
        val prefix = "formula in slot $slot: "
        if (warning.startsWith(prefix)) Text(warning.removePrefix(prefix), color = c.red, fontSize = 10.sp, maxLines = 2)
        TextButton(onClick = { editing = true }) {
            Text(stringResource(Res.string.formula_edit), color = c.accent, fontSize = 12.sp)
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(text) }
        PlainDialog(
            title = stringResource(Res.string.formula_title),
            onDismiss = { editing = false },
            confirmLabel = stringResource(Res.string.ok),
            onConfirm = { editing = false; onSet(draft) },
            spacing = 8.dp,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text(stringResource(Res.string.formula_expression)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.typing() then Modifier.fillMaxWidth(),
            )
            Text(stringResource(Res.string.formula_examples), color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            for ((example, what) in FORMULA_EXAMPLES) {
                Row(
                    Modifier.fillMaxWidth().clickable { draft = example }.padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(example, color = c.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Text(panelWord(what), color = c.textDim, fontSize = 10.sp, maxLines = 1)
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
        title = stringResource(Res.string.patch_add_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.cancel),
        maxBodyHeight = 420.dp,
        spacing = 3.dp,
    ) {
        // A fixed three-across grid, since the names vary a lot in length and a
        // FlowRow left ragged rows that were hard to scan.
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
 * How wide the inspector is as a column. Same as the machine panel's width
 * in the landscape editor, since it's the same kind of column.
 */
private val INSPECTOR_W = 180.dp
