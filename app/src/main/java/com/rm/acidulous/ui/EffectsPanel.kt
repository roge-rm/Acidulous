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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.EFFECT_SLOTS
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.effectUnit
import com.rm.acidulous.model.withEffect
import com.rm.acidulous.model.withEffectBypass
import com.rm.acidulous.model.withEffectParam

/**
 * The two insert slots of a track: pick an effect, bypass it, turn its knobs.
 * Knobs go through the same [ParamBinding] as a machine's, addressed to
 * "effect1"/"effect2", so they record, automate and undo the same way.
 */
@Composable
fun EffectsPanel(track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) {
    val types = remember { NativeEngine.effectTypes }
    Column(modifier.background(Color(0xFF1F1F23)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (slot in 0 until EFFECT_SLOTS) EffectSlotRow(track, trackIndex, slot, types, editor)
    }
}

@Composable
private fun EffectSlotRow(track: Track, trackIndex: Int, slot: Int, types: List<String>, editor: SongEditor) {
    val fx = track.effectAt(slot)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262B)).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FX${slot + 1}", color = Color(0xFF7FD1B9), fontSize = 10.sp)
            TextButton(onClick = { menu = true }) {
                Text(if (fx.isEmpty) "none ▾" else "${fx.type} ▾", color = Color(0xFFFFB454), fontSize = 12.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("none", fontSize = 12.sp) }, onClick = {
                    menu = false
                    editor.edit(trackIndex) { t -> t.withEffect(slot, "") }
                })
                for (t in types) DropdownMenuItem(text = { Text(t, fontSize = 12.sp) }, onClick = {
                    menu = false
                    if (t != fx.type) editor.edit(trackIndex) { tr -> tr.withEffect(slot, t) }
                })
            }
            if (!fx.isEmpty) {
                val on = !fx.bypass
                TextButton(
                    onClick = {
                        val bypass = !fx.bypass
                        // The document push mounts nothing new; the flag goes straight to the running effect too.
                        editor.edit(trackIndex) { t -> t.withEffectBypass(slot, bypass) }
                        NativeEngine.setParam(trackIndex, effectUnit(slot), "bypass", if (bypass) 1f else 0f, record = true)
                    },
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Color(0xFF3F7D5E) else Color(0xFF2E2E33)),
                ) { Text(if (on) "on" else "bypass", color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 10.sp) }
            }
        }
        if (!fx.isEmpty) EffectFace(fx.type, trackIndex, slot, editor)
    }
}

@Composable
private fun EffectFace(type: String, trackIndex: Int, slot: Int, editor: SongEditor) {
    val info = remember(type) { NativeEngine.effectParamInfo(type) }
    val unit = effectUnit(slot)
    val b = rememberParamBinding(trackIndex, type, info, editor, unit) { t, n, v -> t.withEffectParam(slot, n, v) }
    // The panel follows the engine; on first show the engine holds whatever the
    // document pushed, so nothing to seed here.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        for (p in info) {
            val labels = switchLabels(type, p.name, p.steps)
            val accent = if (p.name in EXTRA[type].orEmpty()) Color(0xFFFFB454) else Color(0xFF7FD1B9)
            when {
                // a few choices: buttons; many (note values): a stepped knob that names its step
                p.curve == 2 && labels != null && labels.size <= 4 -> PanelSwitch(b, p.name, labels)
                p.curve == 2 && labels != null -> Knob(
                    label = p.name, value = b.value(p.name), accent = accent,
                    display = labels[p.map(b.value(p.name)).toInt().coerceIn(0, labels.size - 1)],
                    onStart = { b.start(p.name) }, onChange = { v -> b.change(p.name, v) }, onEnd = { b.end() },
                )
                else -> PanelKnob(b, p.name, accent = accent)
            }
        }
    }
}

/** The "extra something" controls, drawn in the accent colour so they stand out from the classic set. */
private val EXTRA = mapOf(
    "Delay" to setOf("duck", "wobble"),
    "Reverb" to setOf("freeze", "gate"),
    "Eq" to setOf("tilt"),
    "Distortion" to setOf("mode", "bias"),
    "Compressor" to setOf("pump", "pumprate"),
    "Filter" to setOf("lforate", "lfodepth", "envdepth"),
    "Bitcrusher" to setOf("jitter", "tone"),
    "Phaser" to setOf("spread"),
    "Flanger" to setOf("negative", "spread"),
)

private val NOTE_RATES = listOf("1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8")

private fun switchLabels(type: String, name: String, steps: Int): List<String>? = when {
    steps == 2 -> listOf("off", "on")
    name == "time" && type == "Delay" -> listOf("1/32", "1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2", "1")
    name == "mode" && type == "Distortion" -> listOf("soft", "hard", "fold", "tube")
    name == "mode" && type == "Filter" -> listOf("LP", "BP", "HP")
    name == "stages" -> listOf("2", "4", "6", "8")
    name == "pumprate" -> NOTE_RATES.take(4)
    name == "lforate" || name == "rate" -> NOTE_RATES
    else -> null
}
