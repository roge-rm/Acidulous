package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import com.rm.acidulous.engine.LaunchState
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.model.Action
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
 * The main screen, phone-sized: the song section (scene columns x track
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
    countInBeats: Int = 0,
    playing: Boolean,
    armed: Boolean,
    loopScene: Boolean,
    stopAtEnd: Boolean,
    queuedScene: Int,
    /** Clip mode: the grid as a launcher. One state per track. */
    clipMode: Boolean,
    launchStates: List<LaunchState>,
    onClipMode: (Boolean) -> Unit,
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
        PlainDialog(title = "Freezing", onDismiss = {}, dismissLabel = "") {
            Readout(freezeStatus)
        }
    }
    var fileMenu by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Acid.colors.bg)) {
        // --- Header: song, structure undo, file ----------------------------------------
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            spacing = 4.dp,
        ) {
            Text(song.name, color = Acid.colors.text, fontSize = 16.sp, modifier = Modifier.flexible(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The tempo, in the slot undo and redo used to have. Plain text
            // rather than a pill, like save and the file menu beside it: it
            // is a reading you tap to change, which is what everything else
            // in this row is, and a bordered pill among them looked like a
            // transport control that had wandered up from the bar.
            HeaderTextButton("%.1f".format(bpm), color = Acid.colors.text) { dialog = Dialog.Tempo }
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
                    DropdownMenuItem(text = { Text("Export…") }, onClick = { fileMenu = false; onExport() })
                    DropdownMenuItem(text = { Text("MIDI…") }, onClick = { fileMenu = false; dialog = Dialog.Midi })
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
                ModeToggle(clipMode, onClipMode)
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
                            queued = if (clipMode) {
                                launchStates.indices.any { t -> launchStates[t].pending == index }
                            } else {
                                playing && !isCurrent && queuedScene == index
                            },
                            // A tap has always held the scene it starts; now it
                            // says so, and the menu offers the other choice.
                            onAudition = {
                                when {
                                    // Clip mode: a scene chip is a column
                                    // launch. Every track holding a clip in
                                    // this scene is queued at once, which is
                                    // how you move a whole arrangement.
                                    clipMode -> {
                                        song.tracks.forEachIndexed { t, tr ->
                                            if (tr.clips[scene.id] != null) NativeEngine.launchClip(t, scene.engineId)
                                        }
                                        if (!playing) NativeEngine.transportPlay(0)
                                    }
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
                            val launch = launchStates.getOrElse(trackIndex) { LaunchState.idle }
                            val live = if (clipMode) launch.scene == sceneIndex else position.scene == sceneIndex
                            ClipCell(
                                clip = clip,
                                ticksPerBar = song.signatureOf(scene).ticksPerBar,
                                colour = trackColour(trackIndex),
                                playing = playing && live,
                                progress = if (playing && live && clip != null && !clip.mute) {
                                    val len = song.clipLengthTicks(scene.id, clip)
                                    // In clip mode the tick is the track's own,
                                    // because every track is somewhere else.
                                    val at = if (clipMode) launch.tickInCycle else position.tickInIteration
                                    if (len > 0) (at % len).toFloat() / len else null
                                } else {
                                    null
                                },
                                onOpen = { onOpenClip(trackIndex, scene.id) },
                                onSettings = { dialog = Dialog.ClipSettings(trackIndex, scene.id) },
                                frozen = clip?.frozen != null,
                                stale = clip != null && Freeze.stale(song, scene.id, clip),
                                clipMode = clipMode,
                                queued = clipMode && launch.pending == sceneIndex,
                                stopping = clipMode && launch.stopping && launch.scene == sceneIndex,
                                onCancelLaunch = { NativeEngine.cancelLaunch(trackIndex) },
                                onLaunch = {
                                    if (clip != null) {
                                        NativeEngine.launchClip(trackIndex, scene.engineId)
                                        // The clip first, then the transport:
                                        // start() resets the launcher, and a
                                        // tap already waiting is taken on the
                                        // first block, so the first clip you
                                        // touch sounds immediately.
                                        if (!playing) NativeEngine.transportPlay(0)
                                    }
                                },
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
        BottomBar(
            // The readout sits above the buttons, not below them. It is the
            // one thing that had to move for the two screens' rows to land at
            // the same height, and of the two the row is what a thumb goes
            // looking for.
            readout = {
                BarReadout(
                    if (clipMode) {
                        // One entry per sounding track: which scene it took its
                        // clip from and how far through its own cycle it is. Every
                        // track keeps its own count, which is the whole point, and
                        // is the only place you can read that as a number.
                        val live = song.tracks.indices.mapNotNull { t ->
                            val st = launchStates.getOrElse(t) { LaunchState.idle }
                            if (!st.playing) {
                                null
                            } else {
                                val tpb = song.scenes.getOrNull(st.scene)
                                    ?.let { song.signatureOf(it).ticksPerBar } ?: (4 * PPQN)
                                val sc = song.scenes.getOrNull(st.scene)
                                val cyc = (song.tracks[t].clips[sc?.id]?.bars ?: 1) * (sc?.repeat ?: 1)
                                "%d>%d %d.%d/%d".format(t + 1, st.scene + 1,
                                    st.tickInCycle / tpb + 1, (st.tickInCycle % tpb) / PPQN + 1, cyc)
                            }
                        }
                        if (live.isEmpty()) "clip  -  q:" + quantiseShort(UiPrefs.launchQuantise)
                        else "clip  " + live.joinToString("  ") + "  q:" + quantiseShort(UiPrefs.launchQuantise)
                    } else if (countInBeats > 0) {
                        // The count replaces the position rather than sitting
                        // beside it: while it runs there is no position to read,
                        // and a number counting down is the only thing worth
                        // looking at.
                        "counting in\u2026 %d".format(countInBeats)
                    } else {
                        "S%d/%d %-8s r%d/%d  %d.%d.%03d".format(
                            position.scene + 1, song.scenes.size, scene?.name ?: "-",
                            position.repeat + 1, scene?.repeat ?: 1, bar, beat, tick,
                        )
                    },
                    if (countInBeats > 0) Acid.colors.accent else Acid.colors.textHi,
                )
                BarReadout(diagnostics, Acid.colors.textFaint, size = 10)
            },
        ) {
            // In clip mode stop is a two-stage thing: once to let every
            // clip finish the cycle it is in, again to cut. A launcher
            // that only ever cut would be useless for ending a piece.
            val anyLaunched = clipMode && launchStates.any { it.playing }
            val anyStopping = clipMode && launchStates.any { it.stopping }
            // Panic, alone at the left end. A modular makes a runaway easy
            // to build and a pair of headphones does not forgive one, so it
            // is one tap and never behind a menu - but it is also the one
            // button here you must not hit by accident, so it keeps the
            // whole width of the row between itself and the transport.
            PanicButton(Modifier.width(BarAnchor)) { NativeEngine.panic() }
            if (clipMode) {
                // What a tap waits for. "end" is the musical default: the
                // clip you are replacing finishes what it was doing.
                BarButton("q: " + quantiseShort(UiPrefs.launchQuantise), Modifier.width(BarWord)) {
                    dialog = Dialog.Quantise
                }
            } else {
                BarButton(
                    if (loopScene) "\u27F3 scene" else "\u27F3 song",
                    Modifier.width(BarWord).mappable(MapTargets.action(Action.LoopScene.name)),
                ) { onLoopScene(!loopScene) }
            }
            // The slack pools here, between what the song is doing and what
            // the transport is doing, so the group on the right stays welded
            // to the edge of the screen whatever size the screen is.
            Spacer(Modifier.weight(1f))
            // And the five that end every row in the app, in this order and
            // at this width - see BarAnchor. Undo and redo are the song's
            // here and the clip's in the editor, which is the same rule
            // either way: the undo for whatever this screen edits.
            BarButton(
                "\u21B6", Modifier.width(BarAnchor), enabled = editor.canUndoSong(),
            ) { editor.undoSong() }
            // Mapping mode hangs off a long press of redo rather than a
            // button of its own. It is a mode you step into for a minute and
            // nothing here has a button's width to spend on one.
            BarButton(
                "\u21B7",
                Modifier.width(BarAnchor).onLongPress { UiPrefs.chooseMapMode(!UiPrefs.mapMode) },
                colour = if (UiPrefs.mapMode) Acid.colors.accent else Color.Unspecified,
                enabled = editor.canRedoSong(),
            ) { editor.redoSong() }
            Spacer(Modifier.width(BarIsland))
            BarButton(
                "\u21C5", Modifier.width(BarAnchor),
                colour = if (showMixer) Acid.colors.accent else Color.Unspecified,
            ) { showMixer = !showMixer }
            BarButton(
                if (armed) "\u25CF" else "\u25CB",
                Modifier.width(BarAnchor).mappable(MapTargets.action(Action.RecordArm.name)),
                border = if (armed) Acid.colors.red else null,
            ) { onArm(!armed) }
            BarButton(
                if (playing) "\u25A0" else "\u25B6",
                Modifier.width(BarAnchor).mappable(MapTargets.action(Action.PlayStop.name)),
                colour = if (anyStopping) Acid.colors.red else Color.Unspecified,
            ) {
                when {
                    !playing -> NativeEngine.transportPlay(if (clipMode) 0 else position.scene)
                    clipMode && anyLaunched && !anyStopping -> NativeEngine.stopAllClips()
                    else -> NativeEngine.transportStop()
                }
            }
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
        is Dialog.PickMachine -> MachinePickerDialog(
            current = d.track?.let { song.tracks.getOrNull(it)?.machine?.type },
            onDismiss = { dialog = null },
        ) { type ->
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
        Dialog.Midi -> MidiDialog(song, onDismiss = { dialog = null })
        Dialog.Sampler -> SamplerDialog(onDismiss = { dialog = null })
        Dialog.Settings -> SettingsDialog(song.tracks.map { it.name }, onDismiss = { dialog = null })
        Dialog.Quantise -> QuantiseDialog(
            current = UiPrefs.launchQuantise,
            onPick = { bars ->
                UiPrefs.chooseQuantise(bars)
                NativeEngine.setLaunchQuantise(bars * song.signature.ticksPerBar)
            },
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
    object Midi : Dialog()
    object Sampler : Dialog()
    object Settings : Dialog()
    object Quantise : Dialog()
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
    clipMode: Boolean = false,
    /** Waiting its turn, and playing-but-asking-to-be-let-go. */
    queued: Boolean = false,
    stopping: Boolean = false,
    onLaunch: () -> Unit = {},
    onCancelLaunch: () -> Unit = {},
) {
    // pointerInput keeps the lambdas it was built with, so they are read
    // through rememberUpdatedState or a cell would launch whatever it held
    // when it was first composed.
    val launchNow by rememberUpdatedState(onLaunch)
    val cancelNow by rememberUpdatedState(onCancelLaunch)
    val openNow by rememberUpdatedState(onOpen)
    val settingsNow by rememberUpdatedState(onSettings)
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var lastTap by remember { mutableStateOf(0L) }
    val pulse by rememberInfiniteTransition(label = "queuedclip").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "queuedclip",
    )
    val edge = when {
        queued -> Acid.colors.sceneQueued.copy(alpha = pulse)
        stopping -> Acid.colors.red.copy(alpha = pulse)
        playing -> colour
        else -> Acid.colors.raised
    }
    Box(
        Modifier
            .width(CELL_W).height(CELL_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Acid.colors.card)
            .border(if (queued || stopping) 2.dp else 1.dp, edge, RoundedCornerShape(6.dp))
            .then(
                if (clipMode) {
                    // A tap launches and a double tap edits. Registering a
                    // double-tap handler at all would make Compose sit on
                    // every tap for the double-tap timeout before admitting
                    // it was single - a third of a second of nothing on the
                    // one gesture this screen exists for. So the launch goes
                    // out on the first tap and a second tap *retracts* it:
                    // queue, unqueue, open, and the net effect of a double
                    // tap is that nothing changed and the editor opened.
                    Modifier.pointerInput(clipMode) {
                        detectTapGestures(
                            onLongPress = { settingsNow() },
                            onTap = {
                                val now = System.currentTimeMillis()
                                if (now - lastTap <= doubleTapMs) {
                                    lastTap = 0L
                                    cancelNow()
                                    openNow()
                                } else {
                                    lastTap = now
                                    launchNow()
                                }
                            },
                        )
                    }
                } else {
                    Modifier.combinedClickable(onClick = onOpen, onLongClick = onSettings)
                },
            ),
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
            if (clipMode) {
                val mark = when {
                    stopping -> "\u25A0"
                    queued -> "\u2192"
                    playing -> "\u25B6"
                    else -> ""
                }
                if (mark.isNotEmpty()) {
                    Text(
                        mark,
                        color = if (playing && !stopping) Acid.colors.green else Acid.colors.sceneQueued,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

/** "clip end", or a number of bars, as the dialog lists them. */
internal fun quantiseLabel(bars: Int): String = if (bars <= 0) "clip end" else "$bars bar"

/**
 * The same, for the places with no room to say it in full: a pill in the
 * bottom bar is about sixty dp wide and the readout is one ellipsised line.
 * "end" and "4b" - the vocabulary the scene chips already use.
 */
internal fun quantiseShort(bars: Int): String = if (bars <= 0) "end" else "${bars}b"

/**
 * How long a tapped clip waits. Zero is the musical answer - the clip being
 * replaced finishes the cycle it is in - and the rest are a plain grid for
 * when you want to cut across it.
 */
@Composable
private fun QuantiseDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    PlainDialog(title = "Launch quantise", onDismiss = onDismiss, dismissLabel = "Close") {
        Section("clips start on", "A clip waits for this line before it begins, so a stack stays in step.") {
            for (bars in listOf(0, 1, 2, 4, 8)) {
                Choice(quantiseLabel(bars), bars == current) { onPick(bars); onDismiss() }
            }
        }
    }
}

/**
 * The corner where the scene row meets the track column, which has always
 * been a hole. It is the one place a mode switch belongs: it is part of the
 * grid, it is not part of either axis, and it is nowhere near anything that
 * makes a sound.
 */
@Composable
private fun ModeToggle(clipMode: Boolean, onClipMode: (Boolean) -> Unit) {
    Box(
        Modifier
            .mappable(MapTargets.action(Action.ClipMode.name))
            .width(TRACK_W).height(SCENE_H).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (clipMode) Acid.colors.accentDim else Acid.colors.control)
            .clickable { onClipMode(!clipMode) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (clipMode) "\u25B6 clip" else "\u2630 song",
            color = if (clipMode) Acid.colors.onAccent else Acid.colors.textMid,
            fontSize = 11.sp, maxLines = 1,
        )
    }
}

private val TRACK_W = 96.dp
private val CELL_W = 84.dp
private val CELL_H = 56.dp
private val SCENE_H = 54.dp
