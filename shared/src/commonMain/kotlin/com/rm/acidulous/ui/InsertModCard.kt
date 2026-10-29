package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.InsertMod
import com.rm.acidulous.model.SIDECHAIN_PARAM
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.format
import com.rm.acidulous.res.stringResource
import kotlin.math.roundToInt

/**
 * How a mod card reads and writes its slot: a track's through its
 * [ParamBinding], so it records and locks; a song slot's through the document.
 */
internal class ModAccess(
    val value: (String) -> Float,
    val start: (String) -> Unit,
    val change: (String, Float) -> Unit,
    val end: () -> Unit,
    val set: (String, Float) -> Unit,
    /** A control's address for MIDI mapping, or null where the slot can't be mapped. */
    val mapTarget: (String) -> String? = { null },
)

internal fun ParamBinding.modAccess() =
    ModAccess(::value, ::start, ::change, { end() }, ::set, { MapTargets.param(trackIndex, unit, it) })

/** [name]'s control, mappable when the slot is. */
@Composable
private fun Modifier.mapped(a: ModAccess, name: String): Modifier = a.mapTarget(name)?.let { mappable(it) } ?: this

/** The mod's own table, from the engine, once. */
internal val modInfo: List<ParamInfo> by lazy { NativeEngine.effectParamInfo(InsertMod.TYPE) }

private val SOURCE_WORDS = listOf("off", "LFO", "steps", "follow", "track")
private val SHAPE_WORDS = listOf("sine", "tri", "ramp↑", "ramp↓", "square", "s&h", "drift")

/**
 * The mod card on an insert: a source and up to two of the effect's knobs it
 * moves, each with a depth either way. Only the controls the source uses show.
 */
@Composable
internal fun ModCard(type: String, trackNames: List<String>, a: ModAccess) {
    // The whole table, in the engine's order, since a target is an index into it.
    val effectInfo = remember(type) { NativeEngine.effectParamInfo(type) }
    Group("mod", perLine = 4, centred = true, background = Acid.colors.cardAlt) {
        val source = (a.value(InsertMod.SOURCE) * (InsertMod.SOURCES - 1)).roundToInt()
        Choice(a, InsertMod.SOURCE, "source", SOURCE_WORDS.map { panelWord(it) })
        if (source == InsertMod.OFF) return@Group
        when (source) {
            InsertMod.LFO -> {
                Choice(a, InsertMod.SHAPE, "shape", SHAPE_WORDS.map { panelWord(it) })
                Choice(a, InsertMod.RATE, "rate", NOTE_RATES)
            }
            InsertMod.STEPS -> {
                Choice(a, InsertMod.RATE, "rate", NOTE_RATES)
                val length = if (a.value(InsertMod.LENGTH) >= 0.5f) 16 else 8
                SwitchGrid(panelWord("length"), listOf("8", "16"), if (length == 16) 1 else 0, Modifier.mapped(a, InsertMod.LENGTH)) { i ->
                    a.set(InsertMod.LENGTH, i.toFloat())
                }
                StepBars(a, length)
            }
            else -> {
                if (source == InsertMod.KEYED) Choice(a, InsertMod.KEY, "track", trackNames + panelWord("none"))
                Continuous(a, InsertMod.ATTACK, panelWord("attack"))
                Continuous(a, InsertMod.RELEASE, panelWord("release"))
            }
        }
        // The effect's knobs a mod can move: all but the sidechain, which picks a track.
        val targets = remember(effectInfo) {
            listOf(-1) + effectInfo.indices.filter { effectInfo[it].name != SIDECHAIN_PARAM && it < InsertMod.TARGETS }
        }
        val none = panelWord("none")
        for (j in 1..2) {
            val name = InsertMod.target(j)
            val at = InsertMod.targetIndex(a.value(name))
            val pos = targets.indexOf(at).coerceAtLeast(0)
            val labels = targets.map { if (it < 0) none else panelWord(effectInfo[it].name) }
            Box(Modifier.mapped(a, name)) {
                CountKnob(
                    stringResource(Res.string.slot_mod_target, j), pos, targets.indices, labels[pos], Acid.colors.accent,
                    choices = labels, onStart = { a.start(name) }, onEnd = a.end,
                    pick = { i -> a.set(name, InsertMod.targetValue(targets[i])) },
                ) { i -> a.change(name, InsertMod.targetValue(targets[i])) }
            }
            val depth = InsertMod.depth(j)
            val d = a.value(depth)
            Knob(
                label = stringResource(Res.string.slot_mod_depth, j), value = d,
                display = "%+d%%".format(((d * 2f - 1f) * 100f).roundToInt()),
                modifier = Modifier.mapped(a, depth).then(panelKnobWidth()), accent = Acid.colors.accent,
                onStart = { a.start(depth) }, onChange = { v -> a.change(depth, v) }, onEnd = a.end,
            )
        }
    }
}

/** A stepped mod setting as a knob that names its step; held, a list. */
@Composable
private fun Choice(a: ModAccess, name: String, label: String, labels: List<String>) {
    val n = labels.size
    val idx = (a.value(name) * (n - 1)).roundToInt().coerceIn(0, n - 1)
    fun at(i: Int) = if (n > 1) i.toFloat() / (n - 1) else 0f
    Box(Modifier.mapped(a, name)) {
        CountKnob(
            panelWord(label), idx, 0 until n, labels[idx], choices = labels,
            onStart = { a.start(name) }, onEnd = a.end, pick = { i -> a.set(name, at(i)) },
        ) { i -> a.change(name, at(i)) }
    }
}

@Composable
private fun Continuous(a: ModAccess, name: String, label: String) {
    val info = modInfo.firstOrNull { it.name == name } ?: return
    val v = a.value(name)
    Knob(
        label = label, value = v, display = info.format(v), modifier = Modifier.mapped(a, name).then(panelKnobWidth()),
        onStart = { a.start(name) }, onChange = { nv -> a.change(name, nv) }, onEnd = a.end,
    )
}

/** The step pattern: a bar per step, set by tapping or dragging across. */
@Composable
private fun StepBars(a: ModAccess, length: Int) {
    val access by rememberUpdatedState(a)
    val count by rememberUpdatedState(length)
    val col = Acid.colors
    val said = panelWord("steps")
    Column {
        Text(said, color = col.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Canvas(
            Modifier.padding(top = 2.dp).width((length * 11).dp).height(PanelControlH - 18.dp)
                .semantics { contentDescription = said }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val i = (p.x / size.width * count).toInt().coerceIn(0, count - 1)
                        access.set(InsertMod.step(i), (1f - p.y / size.height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { access.start(InsertMod.step(0)) },
                        onDragEnd = { access.end() },
                        onDragCancel = { access.end() },
                    ) { change, _ ->
                        val p = change.position
                        val i = (p.x / size.width * count).toInt().coerceIn(0, count - 1)
                        access.change(InsertMod.step(i), (1f - p.y / size.height).coerceIn(0f, 1f))
                    }
                },
        ) {
            val w = size.width / count
            for (i in 0 until count) {
                val v = access.value(InsertMod.step(i))
                drawRect(col.control, Offset(i * w + 1f, 0f), Size(w - 2f, size.height))
                val h = v * size.height
                drawRect(if (i % 4 == 0) col.accent else col.teal, Offset(i * w + 1f, size.height - h), Size(w - 2f, h))
            }
        }
    }
}
