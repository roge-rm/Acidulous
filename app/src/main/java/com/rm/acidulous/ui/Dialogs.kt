package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
            Column(Modifier.verticalScrollWithBar(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
fun ClipSettingsDialog(
    clip: Clip,
    onDismiss: () -> Unit,
    /** The song's tempo here, to say whether a freeze can still be used. */
    tempo: Float = 0f,
    /** Render this clip to audio, or throw the render away. Both dismiss. */
    onFreeze: () -> Unit = {},
    onThaw: () -> Unit = {},
    onConfirm: (Clip) -> Unit,
) {
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
                // Freeze is an action rather than a setting, so it does its
                // own thing and closes: the other fields here are edits that
                // wait for OK.
                val frozen = clip.frozen
                if (frozen != null) {
                    val stale = tempo > 0f && kotlin.math.abs(frozen.bpm - tempo) >= 0.01f
                    Text(
                        "Frozen: %.1f s of audio at %.0f bpm, peak %.2f.".format(
                            frozen.frames / 48000f, frozen.bpm, frozen.peak,
                        ) + if (stale) " The song is at %.0f now, so the machine is playing instead - freeze it again.".format(tempo) else "",
                        fontSize = 11.sp,
                    )
                    OutlinedButton(onClick = onThaw) { Text("Thaw", fontSize = 12.sp) }
                } else if (clip.notes.isNotEmpty()) {
                    Text("Freezing renders this clip to audio: the track stops running its machine.", fontSize = 11.sp)
                    OutlinedButton(onClick = onFreeze) { Text("Freeze", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(clip.copy(bars = bars, playMode = mode, mute = mute, grid = grid)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A name and its line, at a height every row shares. */
private val MACHINE_ROW_H = 68.dp

/**
 * A group's name, with the hedge set in italic: "realish" is doing a
 * qualifier's job, and it should look like one.
 */
private fun chipLabel(label: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val cut = if (label.endsWith("ish") && label.length > 3) label.length - 3 else label.length
        append(label.substring(0, cut))
        if (cut < label.length) {
            withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(label.substring(cut))
            }
        }
    }

/**
 * The machine picker: four groups behind chips, each machine with a line
 * saying what it is. A flat list of twelve names told you nothing unless you
 * already knew, which defeats the point of having twelve.
 */
@Composable
fun MachinePickerDialog(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val groups = com.rm.acidulous.model.MachineUi.machineGroups
    val known = remember { com.rm.acidulous.engine.NativeEngine.machineTypes.toSet() }
    var tab by rememberSaveable {
        mutableStateOf(groups.indexOfFirst { current in it.machines }.coerceAtLeast(0))
    }
    val c = com.rm.acidulous.ui.theme.Acid.colors
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).widthIn(max = 720.dp),
            shape = RoundedCornerShape(16.dp),
            color = c.card,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Machine", color = c.text, fontSize = 20.sp)
                Box(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                    SectionChipsStyled(groups.map { chipLabel(it.label) }, tab) { tab = it }
                }
                // Anything the engine offers that no group claims still has
                // to be reachable, so it lands in the last group.
                val listed = groups.flatMap { it.machines }.toSet()
                val contents = groups.mapIndexed { i, g ->
                    g.machines.filter { it in known } +
                        (if (i == groups.lastIndex) known.filter { it !in listed } else emptyList())
                }
                // Every row is the same height and the list is as tall as the
                // longest group, so the dialog keeps its size and its place
                // when you change tabs. A window that jumps under your thumb
                // is a window you have to find again.
                val rows = contents.maxOf { it.size }
                Column(
                    Modifier.height(MACHINE_ROW_H * rows + 6.dp * (rows - 1))
                        .verticalScrollWithBar(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (type in contents[tab]) {
                        val on = type == current
                        Column(
                            Modifier.fillMaxWidth().height(MACHINE_ROW_H)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) c.accentDim else c.control)
                                .clickable { onPick(type) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(type, color = if (on) c.accent else c.text, fontSize = 14.sp)
                            Text(
                                com.rm.acidulous.model.MachineUi.describe(type),
                                color = c.textDim, fontSize = 11.sp, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun PickerDialog(title: String, options: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScrollWithBar(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

internal val SIGNATURES = listOf(
    Signature(4, 4), Signature(3, 4), Signature(2, 4), Signature(5, 4),
    Signature(6, 8), Signature(7, 8), Signature(9, 8), Signature(12, 8),
)

private val GRIDS = listOf("1/4" to PPQN, "1/8" to PPQN / 2, "1/16" to PPQN / 4, "1/32" to PPQN / 8, "1/8T" to PPQN / 3, "1/16T" to PPQN / 6)
