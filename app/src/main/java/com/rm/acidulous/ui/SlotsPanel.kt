package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import com.rm.acidulous.model.withEventorParam
import com.rm.acidulous.model.withEventorBypass
import com.rm.acidulous.model.withEventor
import com.rm.acidulous.model.eventorUnit
import com.rm.acidulous.model.UnitSlot
import com.rm.acidulous.model.EVENTOR_SLOTS
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.effectUnit
import com.rm.acidulous.model.withEffect
import com.rm.acidulous.model.withEffectBypass
import com.rm.acidulous.model.withEffectParam

/** What a slot panel edits: the track's insert effects or its eventors. */
enum class SlotKind(
    val label: String, val slots: Int,
    val types: () -> List<String>, val paramInfo: (String) -> List<com.rm.acidulous.engine.ParamInfo>,
    val unit: (Int) -> String, val at: (Track, Int) -> UnitSlot,
    val withType: (Track, Int, String) -> Track, val withParam: (Track, Int, String, Float) -> Track, val withBypass: (Track, Int, Boolean) -> Track,
) {
    Effects("FX", EFFECT_SLOTS, { NativeEngine.effectTypes }, { NativeEngine.effectParamInfo(it) }, ::effectUnit, { t, s -> t.effectAt(s) },
        { t, s, ty -> t.withEffect(s, ty) }, { t, s, n, v -> t.withEffectParam(s, n, v) }, { t, s, b -> t.withEffectBypass(s, b) }),
    Eventors("EV", EVENTOR_SLOTS, { NativeEngine.eventorTypes }, { NativeEngine.eventorParamInfo(it) }, ::eventorUnit, { t, s -> t.eventorAt(s) },
        { t, s, ty -> t.withEventor(s, ty) }, { t, s, n, v -> t.withEventorParam(s, n, v) }, { t, s, b -> t.withEventorBypass(s, b) }),
}

/**
 * A track's two slots of one kind: pick a unit, bypass it, turn its knobs.
 * Knobs go through the same [ParamBinding] as a machine's, addressed to the
 * slot's unit name, so they record, automate and undo the same way.
 */
@Composable
fun SlotsPanel(kind: SlotKind, track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) {
    val types = remember(kind) { kind.types() }
    Column(modifier.background(Color(0xFF1F1F23)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (slot in 0 until kind.slots) SlotRow(kind, track, trackIndex, slot, types, editor)
    }
}

@Composable
fun EffectsPanel(track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) =
    SlotsPanel(SlotKind.Effects, track, trackIndex, editor, modifier)

@Composable
private fun SlotRow(kind: SlotKind, track: Track, trackIndex: Int, slot: Int, types: List<String>, editor: SongEditor) {
    val fx = kind.at(track, slot)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262B)).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${kind.label}${slot + 1}", color = Color(0xFF7FD1B9), fontSize = 10.sp)
            TextButton(onClick = { menu = true }) {
                Text(if (fx.isEmpty) "none ▾" else "${fx.type} ▾", color = Color(0xFFFFB454), fontSize = 12.sp)
            }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.scrollbar(menuScroll), scrollState = menuScroll) {
                DropdownMenuItem(text = { Text("none", fontSize = 12.sp) }, onClick = {
                    menu = false
                    editor.edit(trackIndex) { t -> kind.withType(t, slot, "") }
                })
                for (t in types) DropdownMenuItem(text = { Text(t, fontSize = 12.sp) }, onClick = {
                    menu = false
                    if (t != fx.type) editor.edit(trackIndex) { tr -> kind.withType(tr, slot, t) }
                })
            }
            if (!fx.isEmpty) {
                val on = !fx.bypass
                TextButton(
                    onClick = {
                        val bypass = !fx.bypass
                        // The document push mounts nothing new; the flag goes straight to the running effect too.
                        editor.edit(trackIndex) { t -> kind.withBypass(t, slot, bypass) }
                        NativeEngine.setParam(trackIndex, kind.unit(slot), "bypass", if (bypass) 1f else 0f, record = true)
                    },
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Color(0xFF3F7D5E) else Color(0xFF2E2E33)),
                ) { Text(if (on) "on" else "bypass", color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 10.sp) }
            }
        }
        if (!fx.isEmpty) SlotFace(kind, fx.type, trackIndex, slot, editor)
    }
}

@Composable
private fun SlotFace(kind: SlotKind, type: String, trackIndex: Int, slot: Int, editor: SongEditor) {
    val info = remember(kind, type) { kind.paramInfo(type) }
    val unit = kind.unit(slot)
    val b = rememberParamBinding(trackIndex, type, info, editor, unit) { t, n, v -> kind.withParam(t, slot, n, v) }
    // The panel follows the engine; on first show the engine holds whatever the
    // document pushed, so nothing to seed here.
    Column {
        Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            for (p in info) {
                if (type == "Arp" && p.name.length == 3 && p.name[0] == 's' && p.name[1].isDigit()) continue // the step row below
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
        if (type == "Arp") ArpSteps(b)
    }
}

/** The arp's pattern: sixteen compact step toggles in one row, the ones past `length` dimmed. */
@Composable
private fun ArpSteps(b: ParamBinding) {
    val length = b.infoOf("length")?.map(b.value("length"))?.toInt() ?: 16
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("steps", color = Color(0xFF9A9AA2), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        for (i in 1..16) {
            val name = "s%02d".format(i)
            val on = b.value(name) >= 0.5f
            val inRange = i <= length
            Box(
                Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (on && inRange) Color(0xFF3F7D5E) else if (on) Color(0xFF2E4A3E) else Color(0xFF2E2E33))
                    .clickable { b.set(name, if (on) 0f else 1f) },
                contentAlignment = Alignment.Center,
            ) { Text("$i", color = if (inRange) Color.White else Color(0xFF777777), fontSize = 8.sp) }
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
    "Scale" to setOf("mode", "snap"),
    "Chord" to setOf("mode", "voicing", "spread", "strum", "strumdir", "velspread"),
    "Arp" to setOf("ratchet", "ratchetchance", "chance", "shift", "cycles", "humanise", "latch"),
)

// Mirrors engine/eventor/Scales.h - same order.
val SCALE_NAMES = listOf(
    "Ionian (Major)", "Dorian", "Phrygian", "Lydian", "Mixolydian", "Aeolian (Minor)", "Locrian",
    "Harmonic Minor", "Melodic Minor",
    "Major Pentatonic", "Minor Pentatonic", "Major Blues", "Minor Blues", "Egyptian",
    "Hungarian Minor", "Byzantine", "Persian", "Hirajoshi", "In Sen", "Iwato", "Enigmatic", "Phrygian Dominant", "Neapolitan Minor", "Neapolitan Major",
    "Altered", "Lydian Dominant", "Lydian Augmented", "Locrian nat2", "Bebop Dominant", "Bebop Major",
    "Whole Tone", "Dim Whole-Half", "Dim Half-Whole",
)
val KEY_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val CHORD_NAMES = listOf(
    "maj", "min", "dim", "aug", "sus2", "sus4", "5", "6", "m6", "7", "maj7", "m7", "m7b5", "dim7", "mMaj7", "7sus4",
    "add9", "madd9", "9", "maj9", "m9", "11", "13", "oct", "5+oct",
)
private val ARP_MODES = listOf("up", "down", "up-down", "down-up", "up&down", "converge", "diverge", "random", "walk", "played", "chord", "pinky", "thumb")
private val ARP_RATES = listOf("1/1", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32")

private val NOTE_RATES = listOf("1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8")

private fun switchLabels(type: String, name: String, steps: Int): List<String>? = when {
    name == "mode" && type == "Scale" -> listOf("snap", "degree")
    name == "snap" -> listOf("nearest", "down", "up")
    name == "mode" && type == "Chord" -> listOf("fixed", "diatonic")
    name == "mode" && type == "Arp" -> ARP_MODES
    name == "key" -> KEY_NAMES
    name == "scale" -> SCALE_NAMES
    name == "type" && type == "Chord" -> CHORD_NAMES
    name == "voicing" -> listOf("triad", "7th", "9th")
    name == "inversion" -> listOf("root", "1st", "2nd", "3rd")
    name == "strumdir" -> listOf("up", "down")
    name == "octmode" -> listOf("up", "down", "alt")
    name == "velmode" -> listOf("played", "fixed", "accent", "ramp↑", "ramp↓")
    name == "sync" -> listOf("restart", "free")
    name == "rate" && type == "Arp" -> ARP_RATES
    name == "transpose" || name == "shift" -> (-12..12).map { if (it > 0) "+$it" else "$it" }
    name == "octave" && type == "Scale" -> (-2..2).map { if (it > 0) "+$it" else "$it" }
    name == "octaves" || name == "ratchet" -> listOf("1", "2", "3", "4")
    name == "cycles" -> (1..8).map { "$it" }
    name == "length" -> (1..16).map { "$it" }
    steps == 2 -> listOf("off", "on")
    name == "time" && type == "Delay" -> listOf("1/32", "1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2", "1")
    name == "mode" && type == "Distortion" -> listOf("soft", "hard", "fold", "tube")
    name == "mode" && type == "Filter" -> listOf("LP", "BP", "HP")
    name == "stages" -> listOf("2", "4", "6", "8")
    name == "pumprate" -> NOTE_RATES.take(4)
    name == "lforate" || name == "rate" -> NOTE_RATES
    else -> null
}
