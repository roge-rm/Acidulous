package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.MidiFile
import com.rm.acidulous.model.MidiImport
import com.rm.acidulous.model.Song
import com.rm.acidulous.ui.theme.Acid

/**
 * A MIDI file, before it becomes a song: which machine each part goes to,
 * and how long a scene is.
 *
 * It comes in as a new song, never into the open one (Dan's choice): the
 * file's tempo, signature and length are its own, and a song with them
 * forced onto it is a different song.
 */
@Composable
fun MidiImportDialog(
    fileName: String,
    parsed: MidiFile.Parsed,
    onDismiss: () -> Unit,
    onImport: (Song) -> Unit,
) {
    // A machine per part, or null to leave it out. Past sixteen there are no
    // tracks left, so those start left out and say so.
    val machines = remember {
        mutableStateListOf<String?>().apply {
            parsed.parts.forEachIndexed { i, p -> add(if (i < 16) MidiImport.defaultMachine(p) else null) }
        }
    }
    var sceneBars by remember { mutableStateOf(8) }
    val song = remember(machines.toList(), sceneBars) {
        MidiImport.build(fileName, parsed, machines.toList(), sceneBars)
    }
    val totalBars = song.scenes.sumOf { song.barsOf(it) * it.repeat }

    PlainDialog(
        title = "Import MIDI",
        onDismiss = onDismiss,
        confirmLabel = "Import",
        confirmEnabled = song.tracks.isNotEmpty(),
        onConfirm = { onImport(song) },
        spacing = 6.dp,
    ) {
        WindowCards {
            WindowCard("arrangement · $fileName") {
                SwitchGrid("scenes of", listOf("4 bars", "8 bars", "16 bars"), listOf(4, 8, 16).indexOf(sceneBars), columns = 3) {
                    sceneBars = listOf(4, 8, 16)[it]
                }
                val sig = song.signature
                Box(Modifier.fillMaxWidth()) {
                    Readout(
                        "${song.scenes.size} scene${if (song.scenes.size == 1) "" else "s"} · $totalBars bars · " +
                            "%.0f bpm · %d/%d".format(song.tempo, sig.beats, sig.unit),
                    )
                }
            }
            WindowCard("tracks") {
                parsed.parts.forEachIndexed { i, part ->
                    PartRow(part, machines[i]) { machines[i] = it }
                }
            }
        }
    }
}

/** Machines a MIDI part can go to: the ones that play notes with no file of their own. */
private val PLAYABLE = MachineUi.machineGroups.flatMap { it.machines }
    .filter { it !in setOf("Forage", "Mosaic", "Pollen", "Dice", "Molt", "Bias", "Cipher") }

@Composable
private fun PartRow(part: MidiFile.Part, machine: String?, pick: (String?) -> Unit) {
    val c = Acid.colors
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(part.name, color = if (machine == null) c.textDim else c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val lost = machine?.let { MidiImport.unmatched(part, it) } ?: 0
            Text(
                "ch ${part.channel + 1} · ${part.notes.size} notes" + if (lost > 0) " · $lost with no sound here" else "",
                color = c.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
        Box {
            Box(
                Modifier.width(104.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (machine == null) c.control else c.green)
                    .clickable { open = true }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(machine ?: "skip", color = if (machine == null) c.textMid else c.onAccent, fontSize = 12.sp)
            }
            val scroll = rememberScrollState()
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ScaledMenu(scroll) {
                    DropdownMenuItem(text = { Text("skip", fontSize = 12.sp) }, onClick = { open = false; pick(null) })
                    for (m in PLAYABLE) {
                        DropdownMenuItem(
                            text = { Text(m, fontSize = 12.sp, color = if (m == machine) c.accent else c.text) },
                            onClick = { open = false; pick(m) },
                        )
                    }
                }
            }
        }
    }
}
