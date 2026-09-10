package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.withPatch
import kotlinx.coroutines.delay

/**
 * The machine's face in the Edit screen. Knob values live in three places
 * that must agree: the engine (live, smoothed), the document (persisted, on
 * the track), and the panel. A turn goes to the engine at once as a user
 * gesture (recordable), and into the document as one undo step; between
 * turns the panel follows the engine, so lanes move the knobs.
 */
@Composable
fun MachinePanel(
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    patchNames: () -> List<String>,
    onSavePatch: (String) -> Unit,
    onLoadPatch: (String) -> Map<String, Float>?,
    modifier: Modifier = Modifier,
) {
    val type = track.machine.type
    val info = remember(type) { NativeEngine.machineParamInfo(type) }
    val binding = rememberParamBinding(trackIndex, type, info, editor)

    Column(modifier.background(Color(0xFF1F1F23)).padding(6.dp)) {
        PatchBar(type, patchNames, onSavePatch) { name ->
            onLoadPatch(name)?.let { params ->
                editor.edit(trackIndex) { t -> t.withPatch(params) }
                binding.applyAll(params)
            }
        }
        when (type) {
            "SubVert" -> SubVertPanel(binding)
            else -> GenericPanel(binding)
        }
    }
}

/** Live values + the plumbing to change them. */
class ParamBinding(
    val trackIndex: Int,
    val info: List<ParamInfo>,
    private val editor: SongEditor,
    private val values: androidx.compose.runtime.MutableState<Map<String, Float>>,
    private val dragging: androidx.compose.runtime.MutableState<String?>,
) {
    fun value(name: String): Float = values.value[name] ?: info.firstOrNull { it.name == name }?.defaultNormalized ?: 0f
    fun display(name: String): String = info.firstOrNull { it.name == name }?.format(value(name)) ?: ""
    fun infoOf(name: String): ParamInfo? = info.firstOrNull { it.name == name }

    fun start(name: String) { dragging.value = name; editor.beginGesture(trackIndex) }
    fun change(name: String, v: Float) {
        values.value = values.value + (name to v)
        NativeEngine.setParam(trackIndex, "machine", name, v, record = true)
        editor.updateGesture { t -> t.withParam(name, v) }
    }
    fun end() { dragging.value = null; editor.endGesture() }

    /** A tap on a stepped control: one undo step, no gesture. */
    fun set(name: String, v: Float) {
        values.value = values.value + (name to v)
        NativeEngine.setParam(trackIndex, "machine", name, v, record = true)
        editor.edit(trackIndex) { t -> t.withParam(name, v) }
    }

    fun applyAll(params: Map<String, Float>) {
        val full = info.associate { it.name to (params[it.name] ?: it.defaultNormalized) }
        values.value = full
        for ((n, v) in full) NativeEngine.setParam(trackIndex, "machine", n, v, record = false)
    }

    val draggingName: String? get() = dragging.value
}

@Composable
fun rememberParamBinding(trackIndex: Int, type: String, info: List<ParamInfo>, editor: SongEditor): ParamBinding {
    val values = remember(trackIndex, type) { mutableStateOf(info.associate { it.name to it.defaultNormalized }) }
    val dragging = remember { mutableStateOf<String?>(null) }
    val binding = remember(trackIndex, type) { ParamBinding(trackIndex, info, editor, values, dragging) }
    LaunchedEffect(trackIndex, type) {
        while (true) {
            val d = dragging.value
            values.value = info.associate { p ->
                p.name to (if (p.name == d) values.value[p.name] ?: p.defaultNormalized
                else NativeEngine.paramNormalized(trackIndex, "machine", p.name).takeIf { it >= 0f } ?: values.value[p.name] ?: p.defaultNormalized)
            }
            delay(100)
        }
    }
    return binding
}

@Composable
private fun PatchBar(type: String, patchNames: () -> List<String>, onSave: (String) -> Unit, onLoad: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(type, color = Color.White, fontSize = 13.sp)
        TextButton(onClick = { menu = true }) { Text("patch ▾", color = Color(0xFFFFB454), fontSize = 11.sp) }
        TextButton(onClick = { saving = true }) { Text("save as…", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (n in patchNames()) DropdownMenuItem(text = { Text(n, fontSize = 12.sp) }, onClick = { menu = false; onLoad(n) })
        }
    }
    if (saving) TextInputDialog("Patch name", "", onDismiss = { saving = false }) { name -> onSave(name); saving = false }
}

@Composable
private fun PanelKnob(b: ParamBinding, name: String, label: String = name, accent: Color = Color(0xFF7FD1B9)) {
    Knob(
        label = label, value = b.value(name), display = b.display(name), accent = accent,
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
    )
}

@Composable
private fun PanelSwitch(b: ParamBinding, name: String, labels: List<String>) {
    val info = b.infoOf(name) ?: return
    val idx = info.map(b.value(name)).toInt().coerceIn(0, labels.size - 1)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Color(0xFF9A9AA2), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEachIndexed { i, l ->
                val on = i == idx
                TextButton(
                    onClick = { b.set(name, if (labels.size > 1) i.toFloat() / (labels.size - 1) else 0f) },
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Color(0xFF3F7D5E) else Color(0xFF2E2E33)),
                ) { Text(l, color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262B)).padding(6.dp)) {
        Text(title, color = Color(0xFF7FD1B9), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) { content() }
    }
}

/** SubVert: the classic layer left to right, the open layer after it. */
@Composable
private fun SubVertPanel(b: ParamBinding) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Group("osc") { PanelSwitch(b, "wave", listOf("saw", "pulse")); PanelKnob(b, "pw"); PanelKnob(b, "sub"); PanelKnob(b, "tune") }
        Group("filter") { PanelKnob(b, "cutoff", accent = Color(0xFFFFB454)); PanelKnob(b, "resonance", "reso", Color(0xFFFFB454)); PanelKnob(b, "envmod", accent = Color(0xFFFFB454)); PanelKnob(b, "decay", accent = Color(0xFFFFB454)); PanelSwitch(b, "mode", listOf("lp", "bp")) }
        Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "slide") }
        Group("out") { PanelKnob(b, "drive", accent = Color(0xFFE07A9A)); PanelKnob(b, "volume") }
    }
}

/** Any machine without a face yet: every parameter as a knob. */
@Composable
private fun GenericPanel(b: ParamBinding) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in b.info) PanelKnob(b, p.name)
    }
}
