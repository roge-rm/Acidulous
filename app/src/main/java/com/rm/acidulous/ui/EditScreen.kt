package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.clipLengthTicks
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
    modifier: Modifier = Modifier,
) {
    val track = song.tracks.getOrNull(trackIndex) ?: return
    val scene = song.scenes.firstOrNull { it.id == sceneId } ?: return
    val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
    val ticksPerBar = song.signatureOf(scene).ticksPerBar
    val clipLen = song.clipLengthTicks(sceneId, clip)

    var mode by remember { mutableStateOf(EditMode.Draw) }
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
                "${track.name} · ${scene.name} · ${clip.bars} bar${if (clip.bars > 1) "s" else ""} · ${clip.notes.size} notes",
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { lowestPitch = (lowestPitch + 12).coerceAtMost(127 - ROWS) }) { Text("▲", color = Color.White) }
            TextButton(onClick = { lowestPitch = (lowestPitch - 12).coerceAtLeast(0) }) { Text("▼", color = Color.White) }
        }

        PianoRoll(
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

        // Footer: mode · undo/redo · transport · rec
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { mode = if (mode == EditMode.Draw) EditMode.Select else EditMode.Draw }) {
                Text(if (mode == EditMode.Draw) "✎ draw" else "⬚ select", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { selection = emptySet(); editor.undo(trackIndex) }, enabled = editor.canUndo(trackIndex)) { Text("↶", fontSize = 12.sp) }
            OutlinedButton(onClick = { selection = emptySet(); editor.redo(trackIndex) }, enabled = editor.canRedo(trackIndex)) { Text("↷", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay(song.scenes.indexOf(scene))
            }) { Text(if (playing) "■" else "▶", fontSize = 12.sp) }
            OutlinedButton(onClick = { onArm(!armed) }) {
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
