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
import com.rm.acidulous.model.Freeze
import com.rm.acidulous.model.addTrack
import com.rm.acidulous.ui.UiPrefs.withDefaultScale
import com.rm.acidulous.model.changeMachine
import com.rm.acidulous.model.deleteScene
import com.rm.acidulous.model.deleteTrack
import com.rm.acidulous.model.duplicateScene
import com.rm.acidulous.model.duplicateTrack
import com.rm.acidulous.model.emptyClipFor
import com.rm.acidulous.model.moveScene
import com.rm.acidulous.model.renameTrack
import com.rm.acidulous.model.updateScene
import com.rm.acidulous.ui.theme.Acid

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
    /** Render these clips to audio, or throw the renders away. */
    onFreeze: (List<Freeze.Target>) -> Unit,
    onThaw: (List<Freeze.Target>) -> Unit,
    /** What freezing is doing at the moment, or null when it is not. */
    freezeStatus: String?,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    if (freezeStatus != null) {
        // Modal on purpose: the audio stream is down while a render runs, so
        // there is nothing useful to do until it comes back.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Freezing") },
            text = { Text(freezeStatus, fontSize = 12.sp) },
            confirmButton = {},
        )
    }
    var fileMenu by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Acid.colors.bg)) {
        // --- Header: song, structure undo, file ----------------------------------------
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            spacing = 4.dp,
        ) {
            Text(song.name, color = Acid.colors.text, fontSize = 16.sp, modifier = Modifier.flexible(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeaderButton("↶", enabled = editor.canUndoSong()) { editor.undoSong() }
            HeaderButton("↷", enabled = editor.canRedoSong()) { editor.redoSong() }
            HeaderTextButton("save", onClick = onSave)
            // Button and menu in one box on purpose: a Popup anchors to its
            // parent layout node, and left loose in the row that parent is
            // the whole header - which opened the menu at the far left,
            // nowhere near the button that was pressed. Boxed, the anchor is
            // the button, and the menu drops under it against the right edge.
            Box {
                HeaderTextButton("file ▾", color = Acid.colors.accent) { fileMenu = true }
                DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                    DropdownMenuItem(text = { Text("New song…") }, onClick = { fileMenu = false; dialog = Dialog.NewSong })
                    DropdownMenuItem(text = { Text("Save as…") }, onClick = { fileMenu = false; dialog = Dialog.SaveAs })
                    DropdownMenuItem(text = { Text("Songs…") }, onClick = { fileMenu = false; dialog = Dialog.Songs })
                    DropdownMenuItem(text = { Text("Export WAV…") }, onClick = { fileMenu = false; onExport() })
                    DropdownMenuItem(text = { Text("MIDI in…") }, onClick = { fileMenu = false; dialog = Dialog.Midi })
                    DropdownMenuItem(text = { Text("Record sample…") }, onClick = { fileMenu = false; dialog = Dialog.Sampler })
                    DropdownMenuItem(text = { Text("Settings…") }, onClick = { fileMenu = false; dialog = Dialog.Settings })
                }
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
                    val row = remember(song, index) { Freeze.track(song, index) }
                    val rowFrozen = remember(song, index) {
                        song.scenes.count { track.clips[it.id]?.frozen != null }
                    }
                    TrackHeader(
                        name = track.name, machine = track.machine.type, colour = trackColour(index),
                        onChangeMachine = { dialog = Dialog.PickMachine(index) },
                        onRename = { dialog = Dialog.RenameTrack(index) },
                        onDuplicate = { editor.editSong { it.duplicateTrack(index) } },
                        onDelete = { editor.editSong { it.deleteTrack(index) } },
                        freezable = row.size, frozen = rowFrozen,
                        onFreeze = { onFreeze(row) },
                        onThaw = { onThaw(song.scenes.map { Freeze.Target(index, it.id) }) },
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
                            freezable = Freeze.scene(song, scene.id).size,
                            frozen = song.tracks.count { it.clips[scene.id]?.frozen != null },
                            onFreeze = { onFreeze(Freeze.scene(song, scene.id)) },
                            onThaw = { onThaw(song.tracks.indices.map { Freeze.Target(it, scene.id) }) },
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
                                frozen = clip?.frozen != null,
                                stale = clip != null && Freeze.stale(song, scene.id, clip),
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
        Column(Modifier.fillMaxWidth().background(Acid.colors.bar).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val pad = PaddingValues(horizontal = 12.dp)
                OutlinedButton(onClick = { if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay(position.scene) }, contentPadding = pad) {
                    Text(if (playing) "■" else "▶")
                }
                // Panic. A modular makes a runaway easy to build and a pair
                // of headphones does not forgive one, so this is one tap,
                // never behind a menu, and it is red for a reason.
                OutlinedButton(
                    onClick = { NativeEngine.panic() },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Acid.colors.red),
                ) { Text("panic", color = Acid.colors.red, fontSize = 12.sp, maxLines = 1) }
                OutlinedButton(onClick = { onLoopScene(!loopScene) }, contentPadding = pad) {
                    Text(if (loopScene) "loop: scene" else "loop: song", fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(onClick = { onArm(!armed) }, contentPadding = pad) {
                    Text(if (armed) "● REC" else "○ rec", color = if (armed) Acid.colors.red else Color.Unspecified, fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = { dialog = Dialog.Tempo }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("%.1f bpm".format(bpm), color = Acid.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                TextButton(onClick = { showMixer = !showMixer }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (showMixer) "▾ mix" else "▴ mix", color = Acid.colors.text, fontSize = 12.sp, maxLines = 1)
                }
            }
            Text(
                "S%d/%d %-8s r%d/%d  %d.%d.%03d".format(
                    position.scene + 1, song.scenes.size, scene?.name ?: "-",
                    position.repeat + 1, scene?.repeat ?: 1, bar, beat, tick,
                ),
                color = Acid.colors.textHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
            Text(diagnostics, color = Acid.colors.textFaint, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            ClipSettingsDialog(
                current,
                onDismiss = { dialog = null },
                tempo = song.scenes.firstOrNull { it.id == d.sceneId }?.tempo?.bpm ?: song.tempo,
                onFreeze = { dialog = null; onFreeze(listOf(Freeze.Target(d.track, d.sceneId))) },
                onThaw = { dialog = null; onThaw(listOf(Freeze.Target(d.track, d.sceneId))) },
            ) { edited ->
                editor.editClip(d.track, d.sceneId) { edited }
                dialog = null
            }
        }
        is Dialog.PickMachine -> PickerDialog("Machine", NativeEngine.machineTypes, onDismiss = { dialog = null }) { type ->
            editor.editSong {
                if (d.track == null) it.addTrack(type).withDefaultScale(it.tracks.size)
                else it.changeMachine(d.track, type)
            }
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
        Dialog.Midi -> MidiDialog(onDismiss = { dialog = null })
        Dialog.Sampler -> SamplerDialog(onDismiss = { dialog = null })
        Dialog.Settings -> SettingsDialog(song.tracks.map { it.name }, onDismiss = { dialog = null })
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
    object Midi : Dialog()
    object Sampler : Dialog()
    object Settings : Dialog()
}

@Composable
private fun SceneHeader(
    index: Int, name: String, repeat: Int, bars: Int, hasTempo: Boolean,
    progress: Float?, repeatIdx: Int?, holding: Boolean, finishing: Boolean, queued: Boolean,
    onAudition: () -> Unit, onLoopThis: () -> Unit, onPlayThrough: () -> Unit,
    onSettings: () -> Unit, onInsertAfter: () -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit, onMoveLeft: () -> Unit, onMoveRight: () -> Unit,
    /** How many clips in this scene could be frozen, and how many already are. */
    freezable: Int, frozen: Int,
    onFreeze: () -> Unit, onThaw: () -> Unit,
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
                    finishing -> Acid.colors.green.copy(alpha = pulse)
                    queued -> Acid.colors.sceneQueued.copy(alpha = pulse)
                    progress != null -> Acid.colors.green
                    else -> Acid.colors.control
                },
            )
            .combinedClickable(onClick = onAudition, onLongClick = { menu = true }),
    ) {
        if (progress != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Acid.colors.sceneProgress))
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
                color = Acid.colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("×$repeat ${bars}b")
                    if (hasTempo) append(" ♩")
                    if (repeatIdx != null) append(" r${repeatIdx + 1}")
                },
                color = Acid.colors.textMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Loop this scene") }, onClick = { menu = false; onLoopThis() })
            DropdownMenuItem(text = { Text("Play on from here") }, onClick = { menu = false; onPlayThrough() })
            DropdownMenuItem(text = { Text("Settings…") }, onClick = { menu = false; onSettings() })
            if (freezable > 0) {
                DropdownMenuItem(text = { Text("Freeze scene ($freezable)") }, onClick = { menu = false; onFreeze() })
            }
            if (frozen > 0) {
                DropdownMenuItem(text = { Text("Thaw scene ($frozen)") }, onClick = { menu = false; onThaw() })
            }
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
    freezable: Int, frozen: Int, onFreeze: () -> Unit, onThaw: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(TRACK_W).height(CELL_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Acid.colors.control)
            .combinedClickable(onClick = { menu = true }),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(colour))
        Column(Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp)) {
            Text(name, color = Acid.colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machine, color = Acid.colors.textMid, fontSize = 10.sp, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Change machine…") }, onClick = { menu = false; onChangeMachine() })
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; onRename() })
            if (freezable > 0) {
                DropdownMenuItem(text = { Text("Freeze track ($freezable)") }, onClick = { menu = false; onFreeze() })
            }
            if (frozen > 0) {
                DropdownMenuItem(text = { Text("Thaw track ($frozen)") }, onClick = { menu = false; onThaw() })
            }
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
    frozen: Boolean = false,
    /** Frozen, but at another tempo, so the machine is playing after all. */
    stale: Boolean = false,
) {
    Box(
        Modifier
            .width(CELL_W).height(CELL_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Acid.colors.card)
            .border(1.dp, if (playing) colour else Acid.colors.raised, RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onSettings),
    ) {
        if (clip == null) {
            Text("+", color = Acid.colors.textFaint, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            ClipThumbnail(clip, ticksPerBar, colour, Modifier.fillMaxSize())
            // A clip shorter than its scene comes round more than once, so
            // the scene's progress bar cannot speak for it.
            if (progress != null) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(Acid.colors.overlay),
                )
                Box(
                    Modifier.fillMaxHeight().width(2.dp).align(Alignment.CenterStart)
                        .offset(x = (CELL_W - 6.dp) * progress.coerceIn(0f, 1f))
                        .background(Acid.colors.accent),
                )
            }
            Text(
                buildString {
                    append("${clip.bars}b")
                    if (clip.playMode == com.rm.acidulous.model.PlayMode.OneShot) append(" 1")
                    if (clip.mute) append(" M")
                },
                color = Acid.colors.textHi, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            )
            // A frozen clip is playing audio, not notes. It says so in the
            // corner rather than by looking different, because what is in
            // it - the notes - has not changed.
            if (frozen) {
                Text(
                    // U+FE0E: the text presentation of the snowflake. Without
                    // it Android draws the emoji, in its own blue, and the
                    // teal-for-playing amber-for-stale distinction is lost.
                    "\u2744\uFE0E", color = if (stale) Acid.colors.accent else Acid.colors.teal, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 3.dp),
                )
            }
        }
    }
}

private val TRACK_W = 96.dp
private val CELL_W = 84.dp
private val CELL_H = 56.dp
private val SCENE_H = 54.dp
