package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.emptyClipFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reference sequencer's Edit screen, phone-sized: header, piano roll, footer. The machine
 * panel sits under the roll.
 */
@Composable
fun EditScreen(
    song: Song,
    editor: SongEditor,
    trackIndex: Int,
    sceneId: String,
    position: Position,
    playing: Boolean,
    armed: Boolean,
    onArm: (Boolean) -> Unit,
    onBack: () -> Unit,
    patchNames: () -> List<String>,
    onSavePatch: (String) -> Unit,
    onLoadPatch: (String) -> Map<String, Float>?,
    factoryPatchNames: () -> List<String> = { emptyList() },
    userPatchNames: () -> List<String> = { emptyList() },
    onDeletePatch: (String) -> Unit = {},
    onImportSample: (track: Int, pad: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val track = song.tracks.getOrNull(trackIndex) ?: return
    val scene = song.scenes.firstOrNull { it.id == sceneId } ?: return
    val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
    val ticksPerBar = song.signatureOf(scene).ticksPerBar
    val clipLen = song.clipLengthTicks(sceneId, clip)

    var mode by remember { mutableStateOf(EditMode.Draw) }
    val kind = MachineUi.kindOf(track.machine.type)
    // The machine's alternate editor over the same clip. A drum machine opens on
    // its grid - that is the editor for it; a keyboard machine opens on the roll.
    var steps by remember(trackIndex, kind) { mutableStateOf(kind == MachineKind.Drums) }
    val voices = MachineUi.voicesOf(track.machine.type, track.machine.settings)
    var selectedPad by remember(trackIndex) { mutableStateOf(0) }
    val hasSteps = track.machine.type == "Subvert" || kind == MachineKind.Drums
    var laneKey by remember { mutableStateOf<String?>(null) }
    val slotTypes = track.effects.map { it.type } + track.eventors.map { it.type }
    val laneKeys = remember(track.machine.type, slotTypes) { automationKeysFor(track) }
    var panel by remember { mutableStateOf(0) } // 0 machine, 1 effects, 2 eventors - in the same space
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    var lowestPitch by remember {
        val lowest = clip.notes.minOfOrNull { it.pitch } ?: 36
        mutableStateOf((lowest - 3).coerceIn(0, 127 - ROWS))
    }
    val scope = rememberCoroutineScope()

    val playhead = if (playing && song.scenes.getOrNull(position.scene)?.id == sceneId) {
        position.tickInIteration % clipLen
    } else null

    fun preview(pitch: Int) {
        NativeEngine.noteOn(trackIndex, pitch, 100)
        scope.launch { delay(120); NativeEngine.noteOff(trackIndex, pitch) }
    }

    // `modifier` carries the Scaffold's system-bar padding; without it the
    // footer sits under the navigation bar and its taps become Back.
    Column(modifier.fillMaxSize().background(Color(0xFF1B1B1E)).padding(8.dp)) {
        // Header: back · track · scene · octave
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("◀", color = Color.White) }
            Text(
                "${track.name} · ${scene.name} · ${clip.bars} bar${if (clip.bars > 1) "s" else ""} · ${clip.notes.size} notes" + (if (clip.automation.isEmpty()) "" else " · ${clip.automation.values.sumOf { it.points.size }} auto"),
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { lowestPitch = (lowestPitch + 12).coerceAtMost(127 - ROWS) }) { Text("▲", color = Color.White) }
            TextButton(onClick = { lowestPitch = (lowestPitch - 12).coerceAtLeast(0) }) { Text("▼", color = Color.White) }
        }

        if (steps && kind == MachineKind.Drums) DrumGrid(
            clip = clip,
            ticksPerBar = ticksPerBar,
            voices = voices,
            playheadTick = playhead,
            onSetHit = { tick, note, hit ->
                editor.editClip(trackIndex, sceneId) { c ->
                    val others = c.notes.filter { !(it.tick == tick && it.pitch == note) }
                    c.copy(notes = (if (hit != null) others + hit else others).sortedBy { it.tick })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) else if (steps) StepEditor(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            onSetStep = { tick, note ->
                editor.editClip(trackIndex, sceneId) { c ->
                    val others = c.notes.filter { it.tick != tick }
                    c.copy(notes = (if (note != null) others + note else others).sortedBy { it.tick })
                }
            },
            onPitchGestureBegin = { editor.beginGesture(trackIndex) },
            onPitchGesture = { tick, note ->
                editor.updateGestureClip(sceneId) { base -> base.copy(notes = base.notes.map { if (it.tick == tick) note else it }) }
            },
            onPitchGestureEnd = { editor.endGesture() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) else PianoRoll(
            clip = clip,
            ticksPerBar = ticksPerBar,
            mode = mode,
            selection = selection,
            playheadTick = playhead,
            lowestPitch = lowestPitch,
            rows = ROWS,
            onTapEmpty = { tick, pitch ->
                selection = emptySet()
                editor.editClip(trackIndex, sceneId) { c -> c.copy(notes = c.notes + Note(tick, c.grid, pitch, 100)) }
                preview(pitch)
            },
            onTapNote = { index ->
                selection = emptySet()
                editor.editClip(trackIndex, sceneId) { c -> c.copy(notes = c.notes.filterIndexed { i, _ -> i != index }) }
            },
            onSelectionChange = { selection = it },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            onMove = { indices, dTick, dPitch ->
                editor.updateGestureClip(sceneId) { base ->
                    base.copy(notes = base.notes.mapIndexed { i, n ->
                        if (i in indices) n.copy(
                            tick = (n.tick + dTick).coerceIn(0, clipLen - 1),
                            pitch = (n.pitch + dPitch).coerceIn(0, 127),
                        ) else n
                    })
                }
            },
            onResize = { index, newLength ->
                editor.updateGestureClip(sceneId) { base ->
                    base.copy(notes = base.notes.mapIndexed { i, n -> if (i == index) n.copy(length = newLength) else n })
                }
            },
            onDraw = { tick, pitch, length ->
                editor.updateGestureClip(sceneId) { base -> base.copy(notes = base.notes + Note(tick, length, pitch, 100)) }
            },
            onGestureEnd = { editor.endGesture() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // Automation: the reference sequencer's parameter strip under the notes.
        AutomationStrip(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            laneKeys = laneKeys,
            selected = laneKey,
            onSelect = { laneKey = it },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            onDraw = { key, points ->
                editor.updateGestureClip(sceneId) { base ->
                    var lane = base.automation[key] ?: com.rm.acidulous.model.Lane()
                    for ((t, v) in points) lane = lane.withPoint(t, v)
                    base.copy(automation = base.automation + (key to lane))
                }
            },
            onGestureEnd = { editor.endGesture() },
            onClear = { key -> editor.editClip(trackIndex, sceneId) { c -> c.copy(automation = c.automation - key) } },
            modifier = Modifier.fillMaxWidth().height(88.dp).padding(top = 4.dp),
        )

        // The machine's face: knobs go to the engine as gestures and into the document as undo steps.
        // Or, behind the fx toggle, the track's two insert slots.
        if (panel == 1) SlotsPanel(SlotKind.Effects, track, trackIndex, editor, Modifier.fillMaxWidth().padding(top = 4.dp))
        else if (panel == 2) SlotsPanel(SlotKind.Eventors, track, trackIndex, editor, Modifier.fillMaxWidth().padding(top = 4.dp))
        else MachinePanel(
            track, trackIndex, editor, patchNames, onSavePatch, onLoadPatch,
            factoryPatchNames = factoryPatchNames, userPatchNames = userPatchNames, onDeletePatch = onDeletePatch,
            selectedPad = selectedPad,
            onImportSample = { pad -> onImportSample(trackIndex, pad) },
            onClearSample = { pad -> editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), null) } },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        // Played from pads or from a slim keyboard, by machine kind.
        var octave by remember { mutableStateOf(2) }
        if (kind == MachineKind.Drums) DrumPads(trackIndex, voices, selectedPad, { selectedPad = it }, Modifier.fillMaxWidth().height(72.dp).padding(top = 6.dp))
        else Row(Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            TextButton(onClick = { if (octave > 0) octave-- }) { Text("−", color = Color.White) }
            for (semitone in intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)) {
                val note = 12 * (octave + 1) + semitone
                KeyboardKey(note, trackIndex, Modifier.weight(1f).fillMaxSize())
            }
            TextButton(onClick = { if (octave < 8) octave++ }) { Text("+", color = Color.White) }
        }

        // Footer: mode · undo/redo · transport · rec
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasSteps) {
                OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { steps = !steps }) { Text(if (steps) "▦" else "▤", fontSize = 12.sp) }
            }
            if (!steps) OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { mode = if (mode == EditMode.Draw) EditMode.Select else EditMode.Draw }) {
                Text(if (mode == EditMode.Draw) "✎" else "⬚", fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { panel = if (panel == 1) 0 else 1 }) {
                Text("fx", color = if (panel == 1) Color(0xFFFFB454) else Color.Unspecified, fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { panel = if (panel == 2) 0 else 2 }) {
                Text("ev", color = if (panel == 2) Color(0xFFFFB454) else Color.Unspecified, fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { selection = emptySet(); editor.undo(trackIndex) }, enabled = editor.canUndo(trackIndex)) { Text("↶", fontSize = 12.sp) }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { selection = emptySet(); editor.redo(trackIndex) }, enabled = editor.canRedo(trackIndex)) { Text("↷", fontSize = 12.sp) }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = {
                if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay(song.scenes.indexOf(scene))
            }) { Text(if (playing) "■" else "▶", fontSize = 12.sp) }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { onArm(!armed) }) {
                Text(if (armed) "●" else "○", color = if (armed) Color(0xFFE74C3C) else Color.Unspecified, fontSize = 12.sp)
            }
            Text(
                if (selection.isEmpty()) "" else "${selection.size} sel",
                color = Color(0xFFBBBBBB), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                softWrap = false, maxLines = 1,
            )
        }
    }
}

private const val ROWS = 24

@Composable
private fun KeyboardKey(note: Int, rack: Int, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) Color(0xFF7FD1B9) else Color(0xFFE8E8E4))
            .pointerInput(note, rack) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        val down = currentEvent.changes.any { it.pressed }
                        if (down != pressed) {
                            pressed = down
                            if (down) NativeEngine.noteOn(rack, note, 100) else NativeEngine.noteOff(rack, note)
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(note.toString(), color = Color(0xFF333333), fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}
