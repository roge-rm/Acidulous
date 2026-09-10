package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.PlayMode
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.SceneTempo
import com.rm.acidulous.model.Signature

/** The reference sequencer's "4/4 × 1" chip, expanded: name, signature, repeat, tempo, fades. */
@Composable
fun SceneSettingsDialog(scene: Scene, songSignature: Signature, onDismiss: () -> Unit, onConfirm: (Scene) -> Unit) {
    var name by remember { mutableStateOf(scene.name) }
    var signature by remember { mutableStateOf(scene.signature) } // null = song default
    var repeat by remember { mutableStateOf(scene.repeat) }
    var ownTempo by remember { mutableStateOf(scene.tempo != null) }
    var bpm by remember { mutableStateOf(scene.tempo?.bpm ?: 120f) }
    var smooth by remember { mutableStateOf(scene.tempo?.smooth ?: false) }
    var fadeIn by remember { mutableStateOf(scene.fadeIn) }
    var fadeOut by remember { mutableStateOf(scene.fadeOut) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scene") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)

                Text("Signature", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallToggle("song (${songSignature.beats}/${songSignature.unit})", signature == null) { signature = null }
                    for (s in SIGNATURES.take(4)) SmallToggle("${s.beats}/${s.unit}", signature == s) { signature = s }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in SIGNATURES.drop(4)) SmallToggle("${s.beats}/${s.unit}", signature == s) { signature = s }
                }

                Stepper("Repeat", repeat, 1, 32) { repeat = it }

                LabeledSwitch("Own tempo", ownTempo) { ownTempo = it }
                if (ownTempo) {
                    Text("%.0f bpm".format(bpm), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 40f..240f)
                    LabeledSwitch("Smooth (glide in over one bar)", smooth) { smooth = it }
                }
                LabeledSwitch("Fade in", fadeIn) { fadeIn = it }
                LabeledSwitch("Fade out", fadeOut) { fadeOut = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    scene.copy(
                        name = name.ifBlank { scene.name },
                        signature = signature,
                        repeat = repeat,
                        tempo = if (ownTempo) SceneTempo(bpm = bpm, smooth = smooth) else null,
                        fadeIn = fadeIn,
                        fadeOut = fadeOut,
                    ),
                )
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The reference sequencer's "1 Bar" chip, expanded: bars, play mode, mute, grid. */
@Composable
fun ClipSettingsDialog(clip: Clip, onDismiss: () -> Unit, onConfirm: (Clip) -> Unit) {
    var bars by remember { mutableStateOf(clip.bars) }
    var mode by remember { mutableStateOf(clip.playMode) }
    var mute by remember { mutableStateOf(clip.mute) }
    var grid by remember { mutableStateOf(clip.grid) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper("Bars", bars, 1, 16) { bars = it }
                Text("Play mode", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallToggle("Loop", mode == PlayMode.Loop) { mode = PlayMode.Loop }
                    SmallToggle("1-Shot", mode == PlayMode.OneShot) { mode = PlayMode.OneShot }
                }
                LabeledSwitch("Mute", mute) { mute = it }
                Text("Grid", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, ticks) in GRIDS) SmallToggle(label, grid == ticks) { grid = ticks }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(clip.copy(bars = bars, playMode = mode, mute = mute, grid = grid)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PickerDialog(title: String, options: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (options.isEmpty()) Text("Nothing here yet.", fontSize = 12.sp)
                for (o in options) OutlinedButton(onClick = { onPick(o) }, modifier = Modifier.fillMaxWidth()) { Text(o) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { Button(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TempoDialog(tempo: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var bpm by remember { mutableStateOf(tempo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song tempo") },
        text = {
            Column {
                Text("%.0f bpm".format(bpm), fontFamily = FontFamily.Monospace)
                Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 40f..240f)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(bpm) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Small building blocks ------------------------------------------------------------

@Composable
private fun SmallToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label, fontSize = 11.sp) }
    else OutlinedButton(onClick = onClick) { Text(label, fontSize = 11.sp) }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
        OutlinedButton(onClick = { if (value > min) onChange(value - 1) }) { Text("−") }
        Text(value.toString(), fontFamily = FontFamily.Monospace)
        OutlinedButton(onClick = { if (value < max) onChange(value + 1) }) { Text("+") }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val SIGNATURES = listOf(
    Signature(4, 4), Signature(3, 4), Signature(2, 4), Signature(5, 4),
    Signature(6, 8), Signature(7, 8), Signature(9, 8), Signature(12, 8),
)

private val GRIDS = listOf("1/4" to PPQN, "1/8" to PPQN / 2, "1/16" to PPQN / 4, "1/32" to PPQN / 8, "1/8T" to PPQN / 3, "1/16T" to PPQN / 6)
