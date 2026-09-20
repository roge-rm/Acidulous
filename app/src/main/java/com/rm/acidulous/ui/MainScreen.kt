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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.LaunchState
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.model.Action
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.samplesInUse
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.addScene
import com.rm.acidulous.model.Freeze
import com.rm.acidulous.model.cleared
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

    // **Sideways the transport stands in the header** - the same move the
    // editor makes, and for the same reason: turned, a header is a song
    // name and six hundred dp of nothing, while the bar at the foot costs
    // the scene grid a row. Dan: "that will bring parity with the other
    // screens". The readout stays at the bottom either way; it is two
    // lines of numbers and there is no room for it up there.
    val landscape = isLandscape()
    val scene = song.scenes.getOrNull(position.scene)
    val ticksPerBar = scene?.let { song.signatureOf(it).ticksPerBar } ?: (4 * PPQN)
    val bar = position.tickInIteration / ticksPerBar + 1
    val beat = (position.tickInIteration % ticksPerBar) / PPQN + 1
    val tick = position.tickInIteration % PPQN
    /**
     * Where the song is, and how the engine is coping.
     *
     * Its own slot because upright it rides above the buttons in the
     * bar and sideways the buttons are not there - they are in the
     * header - so it is drawn at the foot on its own. Two lines of
     * numbers is not something the header has room for beside a song
     * name and eight pills.
     */
    val readoutSlot: @Composable ColumnScope.() -> Unit = {
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
    }

    /** What the *song* is doing, as against what the transport is doing. */
    val songSlot: @Composable BarScope.() -> Unit = {
        // In clip mode stop is a two-stage thing: once to let every clip
        // finish the cycle it is in, again to cut. A launcher that only ever
        // cut would be useless for ending a piece.
        if (clipMode) {
            // What a tap waits for. "end" is the musical default: the clip
            // you are replacing finishes what it was doing.
            // The "q:" goes before the word does: what the number means is
            // guessable from a launcher's own corner, and the number is not.
            BarButton(
                (if (words) "q: " else "") + quantiseShort(UiPrefs.launchQuantise),
                word,
            ) { dialog = Dialog.Quantise }
        } else {
            // Narrow, it is the glyph alone: which of the two it is reads
            // from the symbol being lit rather than from the word beside it,
            // and a row that has run out of width has nowhere to put a word.
            BarButton(
                if (!words) "\u27F3" else if (loopScene) "\u27F3 scene" else "\u27F3 song",
                word.mappable(MapTargets.action(Action.LoopScene.name)),
            ) { onLoopScene(!loopScene) }
        }
    }
    val footerSlot: @Composable () -> Unit = {
            BottomBar(
                inline = landscape,
                // The readout sits above the buttons, not below them. It is the
                // one thing that had to move for the two screens' rows to land at
                // the same height, and of the two the row is what a thumb goes
                // looking for.
                readout = readoutSlot,
            ) {
                // In clip mode stop is a two-stage thing: once to let every
                // clip finish the cycle it is in, again to cut. A launcher
                // that only ever cut would be useless for ending a piece.
                val anyLaunched = clipMode && launchStates.any { it.playing }
                val anyStopping = clipMode && launchStates.any { it.stopping }
                // Upright the whole row is one bar: panic and the song's own
                // pill at the left end, the transport welded to the right,
                // and the slack pooled between them.
                //
                // `barWeight`, not `Modifier.weight`. This read the latter
                // and compiled only because the footer stood inside the
                // screen's outer `Column` - so it was ColumnScope's weight,
                // applied to a child of a Row, working by the accident that
                // both write the same parent data. Lifting the bar out of
                // that Column turned the accident into an error, which is
                // the better outcome.
                songSlot()
                if (landscape) {
                    // An island: what the song is doing, set the same
                    // distance from the file menu on one side as from the
                    // transport on the other, so it reads as neither.
                    Spacer(Modifier.width(HeaderIslandGap))
                } else {
                    Spacer(Modifier.barSpace())
                }
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
                BarButton(
                    "\u21C5", Modifier.width(BarAnchor),
                    colour = if (showMixer) Acid.colors.accent else Color.Unspecified,
                ) { showMixer = !showMixer }
                BarButton(
                    // The glyph carries two states, because the pill carries two
                    // controls: a tap arms, a long press turns the click on, and
                    // the red ring is already spoken for by the first of them.
                    // Dan: "it's hard to tell whether just recording is on or
                    // whether both record and metronome are on".
                    (if (armed) "\u25CF" else "\u25CB") + if (clickOn) "\u266A" else "",
                    // Hold it for the click - see the same gesture in the editor.
                    Modifier.width(BarAnchor).mappable(MapTargets.action(Action.RecordArm.name)),
                    border = if (armed) Acid.colors.red else null,
                    onLongPress = { if (!UiPrefs.mapMode) onClick(!clickOn) },
                ) { onArm(!armed) }
                BarButton(
                    if (playing) "\u25A0" else "\u25B6",
                    Modifier.width(BarAnchor).mappable(MapTargets.action(Action.PlayStop.name)),
                    colour = if (anyStopping) Acid.colors.red else Color.Unspecified,
                    // Hold it to stop *everything* - every voice, every tail,
                    // every held note - which is what the panic pill used to
                    // be. Guarded on mapping mode, because `mappable` claims
                    // a long press there to forget what drives a control, and
                    // one gesture must not do both.
                    onLongPress = { if (!UiPrefs.mapMode) panicEverything() },
                ) {
                    when {
                        !playing -> NativeEngine.transportPlay(if (clipMode) 0 else position.scene)
                        clipMode && anyLaunched && !anyStopping -> NativeEngine.stopAllClips()
                        else -> NativeEngine.transportStop()
                    }
                }
            }
    }

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
            // Lit while the click is running: a long press of rec turns the
            // metronome on and off, and this is where its settings live, so
            // this is where it says so. Nothing else on the screen would.
            HeaderTextButton(
                "%.1f".format(bpm),
                color = if (clickOn) Acid.colors.accent else Acid.colors.text,
            ) { dialog = Dialog.Tempo }
            HeaderTextButton("save", onClick = onSave)
            // Button and menu in one box on purpose: a Popup anchors to its
            // parent layout node, and left loose in the row that parent is
            // the whole header - which opened the menu at the far left,
            // nowhere near the button that was pressed. Boxed, the anchor is
            // the button, and the menu drops under it against the right edge.
            Box {
                HeaderTextButton("file ▾", color = Acid.colors.accent) { fileMenu = true }
                // A position bar, because this menu scrolls - nine items is
                // taller than a phone held sideways, and until it had one the
                // last of them looked like the last there was. See
                // ui/Scrollbar.kt; every scrolling list in the app has one.
                val fileScroll = rememberScrollState()
                DropdownMenu(
                    expanded = fileMenu,
                    onDismissRequest = { fileMenu = false },
                    modifier = Modifier.scrollbar(fileScroll, color = Acid.colors.scrollbar),
                    scrollState = fileScroll,
                ) {
                    ScaledWindow {
                        DropdownMenuItem(text = { Text("New song…") }, onClick = { fileMenu = false; dialog = Dialog.NewSong })
                        DropdownMenuItem(text = { Text("Save as…") }, onClick = { fileMenu = false; dialog = Dialog.SaveAs })
                        DropdownMenuItem(text = { Text("Songs…") }, onClick = { fileMenu = false; dialog = Dialog.Songs })
                        DropdownMenuItem(text = { Text("Export…") }, onClick = { fileMenu = false; onExport() })
                        DropdownMenuItem(text = { Text("MIDI…") }, onClick = { fileMenu = false; dialog = Dialog.Midi })
                        DropdownMenuItem(text = { Text("Sound…") }, onClick = { fileMenu = false; dialog = Dialog.Sound })
                        DropdownMenuItem(text = { Text("Settings…") }, onClick = { fileMenu = false; dialog = Dialog.Settings })
                        // Above About, because one of these is a thing you
                        // need while using the app and the other is a thing you
                        // read once.
                        DropdownMenuItem(text = { Text("Help…") }, onClick = { fileMenu = false; dialog = Dialog.Help })
                        DropdownMenuItem(text = { Text("About…") }, onClick = { fileMenu = false; dialog = Dialog.About })
                        // Not the fast path - holding play is - but the only
                        // thing on screen that *names* it, which is what a
                        // gesture otherwise has no way to be found by.
                        DropdownMenuItem(
                            text = { Text("Panic · stop all sound") },
                            onClick = { fileMenu = false; panicEverything() },
                        )
                    }
                }
            }
            // **The transport, sideways, and last.** Dan: the tempo, save and
            // the file menu go to the left of the pills "so the pills are the
            // same place in every screen" - which is the far right of the
            // header, where the editor already puts them. Upright this is
            // empty and the bar at the foot has them.
            if (landscape) Spacer(Modifier.width(HeaderIslandGap))
            if (landscape) footerSlot()
            // Last, at the far edge, as it is on the editor and the patch
            // editor: a reading rather than a control, so it sits past the
            // things you press. This is the one header that never had it,
            // because it had the panic pill with the meter drawn behind the
            // word instead - see panicEverything in ui/BottomBar.kt.
            LoadMeter()
        }

        // --- Song section -----------------------------------------------------------------
        // Saveable, so a rotation keeps where you were and how close in - the
        // grid lost both before, because these were plain `remember`.
        val vScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val hScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        // **How close in the grid is, nought for "as stated".** Kept the way
        // the roll keeps its own zoom - saved but not stored in `UiPrefs` -
        // because it is how you are working rather than anything about the song.
        var gridZoom by rememberSaveable { mutableStateOf(0f) }
        val cellW = (CELL_W * (if (gridZoom > 0f) gridZoom else 1f)).coerceIn(CellMinW, CellMaxW)
        // The factor actually in force, which is the clamped width read back.
        // Everything else follows it, so a cell keeps its shape.
        val z = cellW / CELL_W
        val cell = SongCell(TRACK_W * z, cellW, CELL_H * z, SCENE_H * z)
        Row(
            Modifier.fillMaxWidth().weight(1f)
                // **Two fingers move the grid; one still launches a clip.**
                //
                // Watched on the Initial pass, which travels parent to child,
                // for the drum grid's reason rather than the roll's: every cell
                // here has a `combinedClickable` or a `detectTapGestures` and
                // both axes scroll, so all of them would otherwise have taken
                // the gesture before this saw it. From the moment it commits
                // everything is eaten, so the finger that landed on a clip does
                // not launch it on the way up.
                //
                // The vocabulary is M44's, unchanged: `TwoFingers` asks the
                // same questions, and `decideTwoFinger` settles pan against
                // pinch once one of them is winning by 24px and then holds that
                // answer until the fingers lift.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var second = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.count { it.pressed }
                            if (down == 0) break
                            if (down >= 2) { second = true; break }
                        }
                        if (!second) return@awaitEachGesture
                        val start = TwoFingers.of(currentEvent) ?: return@awaitEachGesture
                        var last = start
                        var mode = TwoFingerMode.Undecided
                        // The live factor, compounded here rather than read
                        // back out of the composition: a pinch sends a dozen
                        // events before the next frame, and multiplying a value
                        // that has not been recomposed yet gives the same answer
                        // a dozen times over. That is the fault M44 measured on
                        // the roll - a doubled finger spread moving a row by
                        // 1.7dp instead of 40.
                        var live = z
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            if (event.changes.count { it.pressed } < 2) break
                            val now = TwoFingers.of(event) ?: continue
                            if (mode == TwoFingerMode.Undecided) mode = decideTwoFinger(start, now)
                            when (mode) {
                                TwoFingerMode.Pan -> {
                                    // Both axes at once, unlike either editor:
                                    // the grid is a plane and both of its
                                    // scrolls are real.
                                    hScroll.dispatchRawDelta(last.centre.x - now.centre.x)
                                    vScroll.dispatchRawDelta(last.centre.y - now.centre.y)
                                }
                                // Either direction drives the one factor - see
                                // SongCell - so the two arms are one.
                                TwoFingerMode.ZoomTime, TwoFingerMode.ZoomPitch -> {
                                    val wasSpread = if (mode == TwoFingerMode.ZoomTime) last.spreadX else last.spreadY
                                    val nowSpread = if (mode == TwoFingerMode.ZoomTime) now.spreadX else now.spreadY
                                    if (wasSpread > TwoFingers.MinSpread && nowSpread > TwoFingers.MinSpread) {
                                        live *= nowSpread / wasSpread
                                        gridZoom = live.coerceIn(
                                            CellMinW / CELL_W, CellMaxW / CELL_W,
                                        )
                                        live = gridZoom
                                    }
                                }
                                TwoFingerMode.Undecided -> {}
                            }
                            last = now
                        }
                    }
                }
                .verticalScrollWithBar(vScroll),
        ) {
        CompositionLocalProvider(LocalSongCell provides cell) {
            // Track headers, fixed on the left.
            Column(Modifier.width(cell.trackW)) {
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
                    modifier = Modifier.width(cell.trackW).height(cell.cellH).padding(3.dp),
                    contentPadding = PaddingValues(4.dp),
                ) { Text("+ track", fontSize = 11.sp, maxLines = 1) }
            }
            // Scenes, scrolling horizontally.
            Column(Modifier.horizontalScrollWithBar(hScroll)) {
                Row {
                    song.scenes.forEachIndexed { index, scene ->
                        // In clip mode the arranger's playhead is stale - it
                        // stopped where the song was when the mode changed -
                        // so reading `position` here lit up whichever scene
                        // had been playing rather than the one you launched.
                        // A header is live when some rack is actually
                        // sounding a clip from it, and its progress is that
                        // rack's own cycle, which is the only clock a column
                        // has in clip mode.
                        val onThisScene = if (!clipMode) null else song.tracks.indices.firstNotNullOfOrNull { t ->
                            launchStates.getOrElse(t) { LaunchState.idle }
                                .takeIf { it.playing && it.scene == index }?.let { t to it }
                        }
                        val isCurrent = if (clipMode) onThisScene != null else playing && position.scene == index
                        val bars = song.barsOf(scene)
                        val iterTicks = bars * song.signatureOf(scene).ticksPerBar
                        // bars x repeat, the same cycle the launcher counts.
                        val cycleTicks = if (onThisScene == null) 0 else {
                            (song.tracks[onThisScene.first].clips[scene.id]?.bars ?: bars) *
                                song.signatureOf(scene).ticksPerBar * scene.repeat
                        }
                        SceneHeader(
                            index = index, name = scene.name, repeat = scene.repeat, bars = bars,
                            hasTempo = scene.tempo != null,
                            progress = when {
                                onThisScene != null && cycleTicks > 0 ->
                                    onThisScene.second.tickInCycle.toFloat() / cycleTicks
                                !clipMode && isCurrent && iterTicks > 0 ->
                                    position.tickInIteration.toFloat() / iterTicks
                                else -> null
                            },
                            // Repeats, holding and finishing all belong to the
                            // arranger; in clip mode a cell shows its own
                            // queue and stop, so the header says nothing.
                            repeatIdx = if (isCurrent && !clipMode) position.repeat else null,
                            holding = isCurrent && loopScene && playing && !clipMode,
                            finishing = isCurrent && playing && stopAtEnd && !clipMode,
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
                        modifier = Modifier.width(cell.cellW).height(cell.sceneH).padding(3.dp),
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
        }

        if (showMixer) {
            MixerPanel(song, editor, rackPeaks, masterPeak, clickOn, onClick, Modifier.fillMaxWidth())
        }

        // Sideways the pills are up in the header and this is the readout
        // alone - which still earns the bar behind it, because two lines of
        // dim monospace on the same ground as the grid reads as part of it.
        // Sideways every pill is up in the header and this is the readout
        // alone - which still earns the bar behind it, because two lines of
        // dim monospace on the grid's own ground read as part of the grid.
        if (landscape) {
            Column(
                Modifier.fillMaxWidth().background(Acid.colors.bar)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) { readoutSlot() }
        } else {
            footerSlot()
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
                onClear = {
                    dialog = null
                    editor.editClip(d.track, d.sceneId) { it.cleared() }
                },
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
        // From the menu there is no machine waiting for the file, so it opens
        // on the library - which is the page that makes sense with no
        // question to answer. A machine opens it on record or on library and
        // supplies `onPick`.
        Dialog.Sound -> RecorderDialog(
            onDismiss = { dialog = null },
            startOn = RecorderPage.Library,
            inUse = song.samplesInUse(),
        )
        Dialog.Settings -> SettingsDialog(onDismiss = { dialog = null })
        Dialog.Help -> HelpDialog(onDismiss = { dialog = null })
        Dialog.About -> AboutDialog(onDismiss = { dialog = null })
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
    object Sound : Dialog()
    object Settings : Dialog()
    object Help : Dialog()
    object About : Dialog()
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
    val cell = LocalSongCell.current
    var menu by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "finishing").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "finishing",
    )
    Box(
        Modifier
            .width(cell.cellW).height(cell.sceneH).padding(3.dp)
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
        // A position bar, as every scrolling list in the app has - eleven items
        // is taller than a phone held sideways. See ui/Scrollbar.kt.
        val menuScroll = rememberScrollState()
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.scrollbar(menuScroll, color = Acid.colors.scrollbar),
            scrollState = menuScroll,
        ) {
            ScaledWindow {
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
}

@Composable
private fun TrackHeader(
    name: String, machine: String, colour: Color,
    onChangeMachine: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
    freezable: Int, frozen: Int, onFreeze: () -> Unit, onThaw: () -> Unit,
) {
    val cell = LocalSongCell.current
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(cell.trackW).height(cell.cellH).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Acid.colors.control)
            .combinedClickable(onClick = { menu = true }),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(colour))
        Column(Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp)) {
            Text(name, color = Acid.colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machine, color = Acid.colors.textMid, fontSize = 10.sp, maxLines = 1)
        }
        // A position bar, as every scrolling list in the app has. Shorter than
        // the scene menu but freeze and thaw come and go, so how tall it is
        // depends on the song. See ui/Scrollbar.kt.
        val menuScroll = rememberScrollState()
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.scrollbar(menuScroll, color = Acid.colors.scrollbar),
            scrollState = menuScroll,
        ) {
            ScaledWindow {
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
    val cell = LocalSongCell.current
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
            .width(cell.cellW).height(cell.cellH).padding(3.dp)
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
                        .offset(x = (cell.cellW - 6.dp) * progress.coerceIn(0f, 1f))
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
        Section("clips start on", "A tapped clip waits for this line.") {
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
    val cell = LocalSongCell.current
    Box(
        Modifier
            .mappable(MapTargets.action(Action.ClipMode.name))
            .width(cell.trackW).height(cell.sceneH).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (clipMode) Acid.colors.accentDim else Acid.colors.control)
            .clickable { onClipMode(!clipMode) },
        contentAlignment = Alignment.Center,
    ) {
        // The same colour in both modes: the background says which one this
        // is. `onAccent` is near black and goes on `accent`, and this is
        // `accentDim`, which in the dark theme is a dark olive - so the label
        // read as dark text on a dark ground and was the one thing on the
        // button nobody could make out.
        Text(
            if (clipMode) "\u25B6 clip" else "\u2630 song",
            color = Acid.colors.textMid,
            fontSize = 11.sp, maxLines = 1,
        )
    }
}

private val TRACK_W = 96.dp
/**
 * What sets the song's own pill apart from its neighbours in the header.
 *
 * The same on both sides on purpose - Dan asked for "the gap between them and
 * the file pulldown the same as the gap between them and the transport
 * buttons" - so `⟳ song` reads as a thing of its own rather than as the last
 * of the file controls or the first of the transport. Four dp more than the
 * row's own spacing would not say it; sixteen does.
 */
private val HeaderIslandGap = 16.dp

private val CELL_W = 84.dp
private val CELL_H = 56.dp
private val SCENE_H = 54.dp

/**
 * How wide a clip cell may be drawn, which is what bounds the pinch.
 *
 * Stated as a width rather than as a range of multipliers, which is how
 * `DrumGrid` bounds its own rows (`MinRow`/`MaxRow` in ui/GridMetrics.kt): the
 * floor is where a cell stops being worth tapping, and the ceiling is where
 * one clip is taking a quarter of a phone and the grid has stopped being a
 * grid. Both scale with the interface setting, because both are `dp`.
 */
private val CellMinW = 48.dp
private val CellMaxW = 168.dp

/**
 * The four sizes the song grid is drawn at, after a pinch.
 *
 * One factor for all of them rather than one per axis, unlike the roll, whose
 * two axes are pitch and time and genuinely different. A cell here is a tile
 * with a thumbnail and a corner mark in it, and scaling one side alone turns a
 * clip into a sliver.
 *
 * A composition local rather than four more parameters because the readers are
 * the four cell composables below and nothing between here and them has an
 * opinion - the same reason `LocalHeaderBand` and `LocalPanelStacked` exist.
 */
private data class SongCell(val trackW: Dp, val cellW: Dp, val cellH: Dp, val sceneH: Dp)

private val LocalSongCell = compositionLocalOf { SongCell(TRACK_W, CELL_W, CELL_H, SCENE_H) }
