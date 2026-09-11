package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.addScene
import com.rm.acidulous.model.addTrack
import com.rm.acidulous.model.changeMachine
import com.rm.acidulous.model.deleteScene
import com.rm.acidulous.model.deleteTrack
import com.rm.acidulous.model.duplicateScene
import com.rm.acidulous.model.duplicateTrack
import com.rm.acidulous.model.emptyClipFor
import com.rm.acidulous.model.moveScene
import com.rm.acidulous.model.renameTrack
import com.rm.acidulous.model.updateScene

/**
 * The reference sequencer's main screen, phone-sized: the song section (scene columns x track
 * rows, each cell a clip) with transport below. The mixer becomes a slide-up
 * panel in M5.
 *
 * Gestures: tap a scene chip to audition it (that scene loops), long-press for
 * its menu; tap a cell to edit the clip, long-press for its settings; tap a
 * track header for its menu.
 */
@Composable
fun MainScreen(
    song: Song,
    editor: SongEditor,
    position: Position,
    playing: Boolean,
    armed: Boolean,
    loopScene: Boolean,
    stopAtEnd: Boolean,
    queuedScene: Int,
    bpm: Float,
    diagnostics: String,
    rackPeaks: FloatArray,
    masterPeak: Float,
    clickOn: Boolean,
    onClick: (Boolean) -> Unit,
    onArm: (Boolean) -> Unit,
    onLoopScene: (Boolean) -> Unit,
    onOpenClip: (track: Int, sceneId: String) -> Unit,
    onSave: () -> Unit,
    onSaveAs: (String) -> Unit,
    onNew: (String) -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    songNames: () -> List<String>,
    onExport: () -> Unit,
    exportState: ExportState?,
    onExportCancel: () -> Unit,
    onExportDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var fileMenu by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Color(0xFF1B1B1E))) {
        // --- Header: song, structure undo, file ----------------------------------------
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            spacing = 4.dp,
        ) {
            Text(song.name, color = Color.White, fontSize = 16.sp, modifier = Modifier.flexible(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeaderButton("↶", enabled = editor.canUndoSong()) { editor.undoSong() }
            HeaderButton("↷", enabled = editor.canRedoSong()) { editor.redoSong() }
            HeaderTextButton("save", onClick = onSave)
            HeaderTextButton("file ▾", color = Color(0xFFFFB454)) { fileMenu = true }
            DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                DropdownMenuItem(text = { Text("New song…") }, onClick = { fileMenu = false; dialog = Dialog.NewSong })
                DropdownMenuItem(text = { Text("Save as…") }, onClick = { fileMenu = false; dialog = Dialog.SaveAs })
                DropdownMenuItem(text = { Text("Songs…") }, onClick = { fileMenu = false; dialog = Dialog.Songs })
                DropdownMenuItem(text = { Text("Export WAV…") }, onClick = { fileMenu = false; onExport() })
            }
        }

        // --- Song section -----------------------------------------------------------------
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().weight(1f).verticalScrollWithBar(vScroll)) {
            // Track headers, fixed on the left.
            Column(Modifier.width(TRACK_W)) {
                Spacer(Modifier.height(SCENE_H))
                song.tracks.forEachIndexed { index, track ->
                    TrackHeader(
                        name = track.name, machine = track.machine.type, colour = trackColour(index),
                        onChangeMachine = { dialog = Dialog.PickMachine(index) },
                        onRename = { dialog = Dialog.RenameTrack(index) },
                        onDuplicate = { editor.editSong { it.duplicateTrack(index) } },
                        onDelete = { editor.editSong { it.deleteTrack(index) } },
                    )
                }
                OutlinedButton(
                    onClick = { dialog = Dialog.PickMachine(null) },
                    modifier = Modifier.width(TRACK_W).height(CELL_H).padding(3.dp),
                    contentPadding = PaddingValues(4.dp),
                ) { Text("+ track", fontSize = 11.sp, maxLines = 1) }
            }
            // Scenes, scrolling horizontally.
            Column(Modifier.horizontalScrollWithBar(hScroll)) {
                Row {
                    song.scenes.forEachIndexed { index, scene ->
                        val isCurrent = playing && position.scene == index
                        val bars = song.barsOf(scene)
                        val iterTicks = bars * song.signatureOf(scene).ticksPerBar
                        SceneHeader(
                            index = index, name = scene.name, repeat = scene.repeat, bars = bars,
                            hasTempo = scene.tempo != null,
                            progress = if (isCurrent && iterTicks > 0) position.tickInIteration.toFloat() / iterTicks else null,
                            repeatIdx = if (isCurrent) position.repeat else null,
                            holding = isCurrent && loopScene && playing,
                            finishing = isCurrent && playing && stopAtEnd,
                            queued = playing && !isCurrent && queuedScene == index,
                            // A tap has always held the scene it starts; now it
                            // says so, and the menu offers the other choice.
                            onAudition = {
                                when {
                                    // Already running: a tap says "finish the
                                    // repeats you owe and stop", and another
                                    // tap takes it back.
                                    playing && isCurrent -> NativeEngine.stopAtEnd = !stopAtEnd
                                    // Something else is running: line this one
                                    // up rather than cutting in. Tap again to
                                    // take it out of the queue.
                                    playing -> NativeEngine.queuedScene = if (queuedScene == index) -1 else index
                                    else -> { onLoopScene(true); NativeEngine.transportPlay(index) }
                                }
                            },
                            onLoopThis = { onLoopScene(true); NativeEngine.transportPlay(index) },
                            onPlayThrough = { onLoopScene(false); NativeEngine.transportPlay(index) },
                            onSettings = { dialog = Dialog.SceneSettings(index) },
                            onInsertAfter = { editor.editSong { it.addScene(afterIndex = index) } },
                            onDuplicate = { editor.editSong { it.duplicateScene(index) } },
                            onDelete = { editor.editSong { it.deleteScene(index) } },
                            onMoveLeft = { editor.editSong { it.moveScene(index, index - 1) } },
                            onMoveRight = { editor.editSong { it.moveScene(index, index + 1) } },
                        )
                    }
                    OutlinedButton(
                        onClick = { editor.editSong { it.addScene() } },
                        modifier = Modifier.width(CELL_W).height(SCENE_H).padding(3.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text("+ scene", fontSize = 11.sp, maxLines = 1) }
                }
                song.tracks.forEachIndexed { trackIndex, track ->
                    Row {
                        song.scenes.forEachIndexed { sceneIndex, scene ->
                            val clip = track.clips[scene.id]
                            ClipCell(
                                clip = clip,
                                ticksPerBar = song.signatureOf(scene).ticksPerBar,
                                colour = trackColour(trackIndex),
                                playing = playing && position.scene == sceneIndex,
                                progress = if (playing && position.scene == sceneIndex && clip != null && !clip.mute) {
                                    val len = song.clipLengthTicks(scene.id, clip)
                                    if (len > 0) (position.tickInIteration % len).toFloat() / len else null
                                } else {
                                    null
                                },
                                onOpen = { onOpenClip(trackIndex, scene.id) },
                                onSettings = { dialog = Dialog.ClipSettings(trackIndex, scene.id) },
                            )
                        }
                    }
                }
            }
        }

        if (showMixer) {
            MixerPanel(song, editor, rackPeaks, masterPeak, clickOn, onClick, Modifier.fillMaxWidth())
        }

        // --- Transport ------------------------------------------------------------------------
        val scene = song.scenes.getOrNull(position.scene)
        val ticksPerBar = scene?.let { song.signatureOf(it).ticksPerBar } ?: (4 * PPQN)
        val bar = position.tickInIteration / ticksPerBar + 1
        val beat = (position.tickInIteration % ticksPerBar) / PPQN + 1
        val tick = position.tickInIteration % PPQN
        Column(Modifier.fillMaxWidth().background(Color(0xFF232326)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val pad = PaddingValues(horizontal = 12.dp)
                OutlinedButton(onClick = { if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay(position.scene) }, contentPadding = pad) {
                    Text(if (playing) "■" else "▶")
                }
                OutlinedButton(onClick = { onLoopScene(!loopScene) }, contentPadding = pad) {
                    Text(if (loopScene) "loop: scene" else "loop: song", fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(onClick = { onArm(!armed) }, contentPadding = pad) {
                    Text(if (armed) "● REC" else "○ rec", color = if (armed) Color(0xFFE74C3C) else Color.Unspecified, fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = { dialog = Dialog.Tempo }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("%.1f bpm".format(bpm), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                TextButton(onClick = { showMixer = !showMixer }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (showMixer) "▾ mix" else "▴ mix", color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
            }
            Text(
                "S%d/%d %-8s r%d/%d  %d.%d.%03d".format(
                    position.scene + 1, song.scenes.size, scene?.name ?: "-",
                    position.repeat + 1, scene?.repeat ?: 1, bar, beat, tick,
                ),
                color = Color(0xFFDDDDDD), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
            Text(diagnostics, color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    // --- Dialogs ------------------------------------------------------------------------------
    when (val d = dialog) {
        null -> {}
        is Dialog.SceneSettings -> song.scenes.getOrNull(d.index)?.let { scene ->
            SceneSettingsDialog(scene, song.signature, onDismiss = { dialog = null }) { edited ->
                editor.editSong { it.updateScene(d.index) { edited } }
                dialog = null
            }
        }
        is Dialog.ClipSettings -> {
            val current = song.tracks.getOrNull(d.track)?.clips?.get(d.sceneId) ?: song.emptyClipFor(d.sceneId)
            ClipSettingsDialog(current, onDismiss = { dialog = null }) { edited ->
                editor.editClip(d.track, d.sceneId) { edited }
                dialog = null
            }
        }
        is Dialog.PickMachine -> PickerDialog("Machine", NativeEngine.machineTypes, onDismiss = { dialog = null }) { type ->
            editor.editSong { if (d.track == null) it.addTrack(type) else it.changeMachine(d.track, type) }
            dialog = null
        }
        is Dialog.RenameTrack -> TextInputDialog("Track name", song.tracks.getOrNull(d.index)?.name ?: "", onDismiss = { dialog = null }) { name ->
            editor.editSong { it.renameTrack(d.index, name) }
            dialog = null
        }
        Dialog.Tempo -> TempoDialog(song.tempo, onDismiss = { dialog = null }) { t ->
            editor.editSong { it.copy(tempo = t) }
            dialog = null
        }
        Dialog.Songs -> SongBrowserDialog(
            names = songNames(), current = song.name,
            onLoad = { name -> onLoad(name); dialog = null },
            onDelete = onDelete,
            onDismiss = { dialog = null },
        )
        Dialog.SaveAs -> TextInputDialog("Save as", song.name, onDismiss = { dialog = null }) { name ->
            onSaveAs(name)
            dialog = null
        }
        Dialog.NewSong -> TextInputDialog("New song", "Untitled", onDismiss = { dialog = null }) { name ->
            onNew(name)
            dialog = null
        }
    }
    exportState?.let { ExportDialog(it, onCancel = onExportCancel, onDismiss = onExportDismiss) }
}

private sealed class Dialog {
    data class SceneSettings(val index: Int) : Dialog()
    data class ClipSettings(val track: Int, val sceneId: String) : Dialog()
    data class PickMachine(val track: Int?) : Dialog() // null = new track
    data class RenameTrack(val index: Int) : Dialog()
    object Tempo : Dialog()
    object Songs : Dialog()
    object SaveAs : Dialog()
    object NewSong : Dialog()
}

@Composable
private fun SceneHeader(
    index: Int, name: String, repeat: Int, bars: Int, hasTempo: Boolean,
    progress: Float?, repeatIdx: Int?, holding: Boolean, finishing: Boolean, queued: Boolean,
    onAudition: () -> Unit, onLoopThis: () -> Unit, onPlayThrough: () -> Unit,
    onSettings: () -> Unit, onInsertAfter: () -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit, onMoveLeft: () -> Unit, onMoveRight: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "finishing").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "finishing",
    )
    Box(
        Modifier
            .width(CELL_W).height(SCENE_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    // Both pending states pulse, so a scene about to end and
                    // one about to start are never mistaken for settled ones.
                    finishing -> Color(0xFF3F7D5E).copy(alpha = pulse)
                    queued -> Color(0xFF7A5A24).copy(alpha = pulse)
                    progress != null -> Color(0xFF3F7D5E)
                    else -> Color(0xFF2E2E33)
                },
            )
            .combinedClickable(onClick = onAudition, onLongClick = { menu = true }),
    ) {
        if (progress != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color(0xFF55A583)))
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(
                // The loop mark sits on the scene being held, so "which one
                // is repeating" is answered where you are looking.
                when {
                    finishing -> "${index + 1} $name ■"
                    queued -> "${index + 1} $name →"
                    holding -> "${index + 1} $name ⟳"
                    else -> "${index + 1} $name"
                },
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("×$repeat ${bars}b")
                    if (hasTempo) append(" ♩")
                    if (repeatIdx != null) append(" r${repeatIdx + 1}")
                },
                color = Color(0xFFCCCCCC), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Loop this scene") }, onClick = { menu = false; onLoopThis() })
            DropdownMenuItem(text = { Text("Play on from here") }, onClick = { menu = false; onPlayThrough() })
            DropdownMenuItem(text = { Text("Settings…") }, onClick = { menu = false; onSettings() })
            DropdownMenuItem(text = { Text("Insert after") }, onClick = { menu = false; onInsertAfter() })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; onMoveLeft() })
            DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; onMoveRight() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun TrackHeader(
    name: String, machine: String, colour: Color,
    onChangeMachine: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(TRACK_W).height(CELL_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2E2E33))
            .combinedClickable(onClick = { menu = true }),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(colour))
        Column(Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp)) {
            Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machine, color = Color(0xFFAAAAAA), fontSize = 10.sp, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Change machine…") }, onClick = { menu = false; onChangeMachine() })
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun ClipCell(
    clip: com.rm.acidulous.model.Clip?, ticksPerBar: Int, colour: Color, playing: Boolean,
    /** How far through its own loop this clip is, 0..1, or null when silent. */
    progress: Float?,
    onOpen: () -> Unit, onSettings: () -> Unit,
) {
    Box(
        Modifier
            .width(CELL_W).height(CELL_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF26262B))
            .border(1.dp, if (playing) colour else Color(0xFF3A3A40), RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onSettings),
    ) {
        if (clip == null) {
            Text("+", color = Color(0xFF666666), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            ClipThumbnail(clip, ticksPerBar, colour, Modifier.fillMaxSize())
            // A clip shorter than its scene comes round more than once, so
            // the scene's progress bar cannot speak for it.
            if (progress != null) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(Color(0x22FFFFFF)),
                )
                Box(
                    Modifier.fillMaxHeight().width(2.dp).align(Alignment.CenterStart)
                        .offset(x = (CELL_W - 6.dp) * progress.coerceIn(0f, 1f))
                        .background(Color(0xFFFFB454)),
                )
            }
            Text(
                buildString {
                    append("${clip.bars}b")
                    if (clip.playMode == com.rm.acidulous.model.PlayMode.OneShot) append(" 1")
                    if (clip.mute) append(" M")
                },
                color = Color(0xFFDDDDDD), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            )
        }
    }
}

private val TRACK_W = 96.dp
private val CELL_W = 84.dp
private val CELL_H = 56.dp
private val SCENE_H = 54.dp
