package com.rm.acidulous.ui

import com.rm.acidulous.util.System

import com.rm.acidulous.util.format

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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
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
import com.rm.acidulous.model.ClipClipboard
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.samplesInUse
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.addScene
import androidx.compose.ui.text.withStyle
import com.rm.acidulous.model.Freeze
import com.rm.acidulous.model.audioLaneCount
import com.rm.acidulous.model.followsTempo
import com.rm.acidulous.model.takeTempoDiffers
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
import com.rm.acidulous.res.*

/**
 * The main screen: the song grid (scene columns x track rows, each cell a clip)
 * with the transport below and the mixer as a slide-up panel.
 *
 * Gestures: tap a scene chip to audition it (that scene loops), long-press for
 * its menu. Tap a cell to edit the clip, long-press for its settings. Tap a
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
    /** Clip mode: the grid as a launcher, one state per track. */
    clipMode: Boolean,
    launchStates: List<LaunchState>,
    onClipMode: (Boolean) -> Unit,
    bpm: Float,
    /** Read by the readout itself, so the numbers changing redraw it and nothing else. */
    diagnostics: () -> String,
    rackPeaks: () -> FloatArray,
    /**
     * Whether the engine is missing its deadline right now, and what each track
     * costs as a fraction of one block's budget.
     *
     * A track is only marked when the engine is in trouble and that track is a
     * big part of why, so on a phone that copes nothing is ever marked. It
     * never interrupts: no dialog, no sound, no stopping.
     */
    straining: Boolean = false,
    rackHot: BooleanArray = BooleanArray(16),
    /** The track a performance on the perform page records into, the last one opened. */
    performTrack: Int = 0,
    /** Empty launcher cells record into themselves, see Looper. */
    looper: Looper? = null,
    masterPeak: () -> Float,
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
    /** A file from outside: a MIDI file, a song bundle, or a sound. */
    onImport: () -> Unit = {},
    /** The open song as a bundle, through the share sheet. */
    onShareSong: () -> Unit = {},
    onShareExport: (ExportState.Done) -> Unit = {},
    exportState: ExportState?,
    onExportCancel: () -> Unit,
    onExportDismiss: () -> Unit,
    /** Render these clips to audio, or discard the renders. */
    onFreeze: (List<Freeze.Target>) -> Unit,
    onThaw: (List<Freeze.Target>) -> Unit,
    /** What freezing is doing now, or null when it isn't. */
    freezeStatus: String?,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    if (freezeStatus != null) {
        // Modal because the audio stream is down while a render runs.
        PlainDialog(title = stringResource(Res.string.main_freezing), onDismiss = {}, dismissLabel = "") {
            Readout(freezeStatus)
        }
    }
    var fileMenu by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }
    // The slide-up panel's two pages: the mixer, and the held effects.
    var panelPage by rememberSaveable { mutableStateOf(0) }
    // What a looper tap did, by cell, so its double tap can undo it.
    val looperUndo = remember { mutableMapOf<Pair<Int, String>, () -> Unit>() }
    // What the perform pages are holding, kept here so a latch survives a tab
    // change. A stop releases everything in the engine, so here too.
    val performState = remember { PerformState() }
    LaunchedEffect(playing) { if (!playing) performState.forgetHeld() }
    var panelH by remember { mutableStateOf(Dp.Unspecified) }

    // In landscape the transport goes in the header, like in the editor. The
    // readout stays at the bottom either way, there's no room for it up there.
    val shape = screenShape()
    val landscape = shape == ScreenShape.Wide
    val scene = song.scenes.getOrNull(position.scene)
    val ticksPerBar = scene?.let { song.signatureOf(it).ticksPerBar } ?: (4 * PPQN)
    val bar = position.tickInIteration / ticksPerBar + 1
    val beat = (position.tickInIteration % ticksPerBar) / PPQN + 1
    val tick = position.tickInIteration % PPQN
    /**
     * Song position and engine load.
     *
     * Its own slot because upright it sits above the buttons in the bar, and
     * sideways the buttons are in the header so it's drawn at the bottom on its
     * own.
     */
    val readoutSlot: @Composable ColumnScope.() -> Unit = {
            val where =
                if (clipMode) {
                    // One entry per sounding track: which scene its clip came
                    // from and how far through its own cycle it is. Every track
                    // keeps its own count.
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
                    if (live.isEmpty()) stringResource(Res.string.main_where_clips_idle, quantiseShort(UiPrefs.launchQuantise))
                    else stringResource(Res.string.main_where_clips, live.joinToString("  "), quantiseShort(UiPrefs.launchQuantise))
                } else if (countInBeats > 0) {
                    // The count-in replaces the position while it runs.
                    stringResource(Res.string.main_counting_in, countInBeats)
                } else {
                    stringResource(
                        Res.string.main_where_song,
                        position.scene + 1, song.scenes.size, scene?.name ?: "-",
                        position.repeat + 1, scene?.repeat ?: 1, bar, beat, tick,
                    )
                }
            val whereColour = if (countInBeats > 0) Acid.colors.accent else Acid.colors.textHi
            // Square screens get one line instead of two, since the grid pays
            // for every line here: the position first, then the engine's
            // numbers cut where they run out.
            if (shape == ScreenShape.Square) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarReadout(where, whereColour)
                    if (UiPrefs.showDiagnostics) Box(Modifier.weight(1f).padding(start = 12.dp)) {
                        LiveReadout(diagnostics, Acid.colors.textFaint, size = 10)
                    }
                }
            } else {
                BarReadout(where, whereColour)
                if (UiPrefs.showDiagnostics) LiveReadout(diagnostics, Acid.colors.textFaint, size = 10)
            }
    }

    /** What the song is doing, as opposed to the transport. */
    val songSlot: @Composable BarScope.() -> Unit = {
        // In clip mode stop has two stages: once to let every clip finish its
        // cycle, again to cut.
        if (clipMode) {
            // What a tap waits for. "end" is the default, the clip being
            // replaced finishes what it was doing. "q:" goes before the value
            // so it's clear what the number means.
            BarButton(
                if (words) stringResource(Res.string.main_quantise_pill, quantiseShort(UiPrefs.launchQuantise)) else quantiseShort(UiPrefs.launchQuantise),
                word,
            ) { dialog = Dialog.Quantise }
        } else {
            // Narrow, it's just the glyph.
            //
            // Two things on one pill. A tap picks what repeats, the song or the
            // current scene. A hold picks whether it repeats at all, so an
            // arrangement can play to the end and stop. ⟳ repeats, ⇥ runs to
            // the end and stops, and the colour shows the same.
            val repeating = song.loopSong
            BarButton(
                label = when {
                    !repeating -> if (words) stringResource(Res.string.main_loop_end) else "\u21E5"
                    !words -> "\u27F3"
                    loopScene -> stringResource(Res.string.main_loop_scene)
                    else -> stringResource(Res.string.main_loop_song)
                },
                modifier = word.mappable(MapTargets.action(Action.LoopScene.name)),
                colour = if (repeating) Acid.colors.teal else Acid.colors.textDim,
                description = stringResource(Res.string.a11y_loop),
                state = stringResource(
                    when {
                        !repeating -> Res.string.a11y_loop_none
                        loopScene -> Res.string.a11y_loop_scene
                        else -> Res.string.a11y_loop_song
                    },
                ),
                holdName = stringResource(if (repeating) Res.string.a11y_loop_stop else Res.string.a11y_loop_again),
                onLongPress = {
                    val on = !repeating
                    editor.replace(song.copy(loopSong = on))
                    // Playing to the end and looping one scene forever
                    // contradict each other, so choosing the ending turns off
                    // the scene loop.
                    if (!on) onLoopScene(false)
                },
            ) {
                // Choosing what repeats also turns repeat on, otherwise a tap
                // while it says "end" would have no visible effect.
                if (!repeating) editor.replace(song.copy(loopSong = true))
                onLoopScene(!loopScene)
            }
        }
    }
    val footerSlot: @Composable () -> Unit = {
            BottomBar(
                inline = landscape,
                // The readout sits above the buttons so the button row lands at
                // the same height as on the other screens.
                readout = readoutSlot,
            ) {
                // In clip mode stop has two stages: once to let every clip
                // finish its cycle, again to cut.
                val anyLaunched = clipMode && launchStates.any { it.playing }
                val anyStopping = clipMode && launchStates.any { it.stopping }
                // Upright the whole row is one bar: panic and the song pill at
                // the left, the transport on the right, and the slack between
                // them.
                //
                // Use barWeight, not Modifier.weight. Modifier.weight here
                // would be ColumnScope's weight on a Row child.
                songSlot()
                if (landscape) {
                    // The song pill, with the same gap to the file menu on one
                    // side as to the transport on the other.
                    Spacer(Modifier.width(HeaderIslandGap))
                } else {
                    Spacer(Modifier.barSpace())
                }
                // The five buttons that end every row in the app, in this order
                // and at this width, see BarAnchor. Undo and redo are the
                // song's here and the clip's in the editor: the undo for
                // whatever this screen edits.
                BarButton(
                    "\u21B6", Modifier.width(BarAnchor), enabled = editor.canUndoSong(),
                    description = stringResource(Res.string.a11y_undo),
                ) { editor.undoSong() }
                // Mapping mode is on a long press of redo instead of its own
                // button, there's no room for one.
                BarButton(
                    "\u21B7",
                    Modifier.width(BarAnchor).onLongPress { UiPrefs.chooseMapMode(!UiPrefs.mapMode) },
                    colour = if (UiPrefs.mapMode) Acid.colors.accent else Color.Unspecified,
                    enabled = editor.canRedoSong(),
                    description = stringResource(Res.string.a11y_redo),
                    actions = listOf(
                        action(stringResource(if (UiPrefs.mapMode) Res.string.a11y_mapping_off else Res.string.a11y_mapping_on)) {
                            UiPrefs.chooseMapMode(!UiPrefs.mapMode)
                        },
                    ),
                ) { editor.redoSong() }
                BarButton(
                    "\u21C5", Modifier.width(BarAnchor),
                    colour = if (showMixer) Acid.colors.accent else Color.Unspecified,
                    description = stringResource(Res.string.a11y_panel),
                    state = stringResource(if (showMixer) Res.string.a11y_open else Res.string.a11y_closed),
                ) { showMixer = !showMixer }
                BarButton(
                    // The glyph shows two states, since the pill has two
                    // controls: a tap arms recording, a long press turns the
                    // click on.
                    (if (armed) "\u25CF" else "\u25CB") + if (clickOn) "\u266A" else "",
                    // Hold for the click, same as in the editor.
                    Modifier.width(BarAnchor).mappable(MapTargets.action(Action.RecordArm.name)),
                    border = if (armed) Acid.colors.red else null,
                    onLongPress = { if (!UiPrefs.mapMode) onClick(!clickOn) },
                    description = stringResource(Res.string.a11y_record),
                    state = stringResource(if (armed) Res.string.a11y_armed else Res.string.a11y_not_armed)
                        .let { if (clickOn) stringResource(Res.string.a11y_with_click, it) else it },
                    holdName = stringResource(if (clickOn) Res.string.a11y_click_off else Res.string.a11y_click_on),
                ) { onArm(!armed) }
                BarButton(
                    if (playing) "\u25A0" else "\u25B6",
                    Modifier.width(BarAnchor).mappable(MapTargets.action(Action.PlayStop.name)),
                    colour = if (anyStopping) Acid.colors.red else Color.Unspecified,
                    // Hold to stop everything: every voice, tail and held note.
                    // Skipped in mapping mode, where mappable uses long press
                    // to clear a mapping.
                    onLongPress = { if (!UiPrefs.mapMode) panicEverything() },
                    description = stringResource(if (playing) Res.string.a11y_stop else Res.string.a11y_play),
                    holdName = stringResource(Res.string.a11y_stop_all),
                ) {
                    when {
                        !playing -> com.rm.acidulous.engine.EngineSync.play(if (clipMode) 0 else position.scene, clipMode)
                        clipMode && anyLaunched && !anyStopping -> NativeEngine.stopAllClips()
                        else -> NativeEngine.transportStop()
                    }
                }
            }
    }

    // Keyboard shortcuts here, each doing what its button on this screen does.
    // See ui/Keys.kt.
    KeyScope(
        KeyAction.PlayStop to {
            val anyLaunched = clipMode && launchStates.any { it.playing }
            val anyStopping = clipMode && launchStates.any { it.stopping }
            when {
                !playing -> com.rm.acidulous.engine.EngineSync.play(if (clipMode) 0 else position.scene, clipMode)
                clipMode && anyLaunched && !anyStopping -> NativeEngine.stopAllClips()
                else -> NativeEngine.transportStop()
            }
        },
        KeyAction.Record to { onArm(!armed) },
        KeyAction.Loop to {
            if (!song.loopSong) editor.replace(song.copy(loopSong = true))
            onLoopScene(!loopScene)
        },
        KeyAction.Undo to { if (editor.canUndoSong()) editor.undoSong() },
        KeyAction.Redo to { if (editor.canRedoSong()) editor.redoSong() },
        KeyAction.Panel to { showMixer = !showMixer },
        KeyAction.Save to { onSave() },
        KeyAction.FileMenu to { fileMenu = true },
        KeyAction.Help to { dialog = Dialog.Help },
    )
    Column(modifier.fillMaxSize().background(Acid.colors.bg)) {
        // --- Header: song, structure undo, file ----------------------------------------
        CutoutRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            spacing = 4.dp,
        ) {
            Text(song.name, color = Acid.colors.text, fontSize = 16.sp, modifier = Modifier.flexible(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The tempo, as plain text like save and the file menu beside it,
            // since it's a readout you tap to change. Lit while the click is
            // running, since the metronome settings live there.
            HeaderTextButton(
                "%.1f".format(bpm),
                description = stringResource(Res.string.a11y_tempo, "%.1f".format(bpm)),
                color = if (clickOn) Acid.colors.accent else Acid.colors.text,
            ) { dialog = Dialog.Tempo }
            HeaderTextButton(stringResource(Res.string.main_save), onClick = onSave)
            // Button and menu in one box because a Popup anchors to its parent
            // layout node. Loose in the row, the parent is the whole header and
            // the menu opens at the far left.
            Box {
                HeaderTextButton(stringResource(Res.string.main_file), description = stringResource(Res.string.a11y_file_menu), color = Acid.colors.accent) { fileMenu = true }
                // A scrollbar, because this menu is taller than a phone held
                // sideways. See ui/Scrollbar.kt.
                val fileScroll = rememberScrollState()
                DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                    ScaledMenu(fileScroll) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_new_song)) }, onClick = { fileMenu = false; dialog = Dialog.NewSong })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_save_as)) }, onClick = { fileMenu = false; dialog = Dialog.SaveAs })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_songs)) }, onClick = { fileMenu = false; dialog = Dialog.Songs })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_import)) }, onClick = { fileMenu = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_export)) }, onClick = { fileMenu = false; onExport() })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_share_song)) }, onClick = { fileMenu = false; onShareSong() })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_midi)) }, onClick = { fileMenu = false; dialog = Dialog.Midi })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_sound)) }, onClick = { fileMenu = false; dialog = Dialog.Sound })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_settings)) }, onClick = { fileMenu = false; dialog = Dialog.Settings })
                        // Help goes above About.
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_help)) }, onClick = { fileMenu = false; dialog = Dialog.Help })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.main_about)) }, onClick = { fileMenu = false; dialog = Dialog.About })
                    }
                }
            }
            // In landscape the transport goes last in the header, at the far
            // right, where the editor puts it too. Upright the bottom bar has
            // it.
            if (landscape) Spacer(Modifier.width(HeaderIslandGap))
            if (landscape) footerSlot()
            // Last, at the far edge, like on the editors. It's a readout, so it
            // goes after the things you press.
            LoadMeter()
        }

        // --- Song section -----------------------------------------------------------------
        // Saveable, so a rotation keeps the scroll position and zoom.
        val vScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val hScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        // Grid zoom, 0 for the default size. Like the roll's zoom it survives
        // rotation but isn't stored in UiPrefs.
        var gridZoom by rememberSaveable { mutableStateOf(0f) }
        // The grid's available width, measured below.
        var gridW by remember { mutableIntStateOf(0) }
        var gridH by remember { mutableIntStateOf(0) }
        // On a tablet, before any pinch, the cells grow to fill the room. Each
        // axis is fitted separately between the base size and three times it,
        // and neither may outgrow the other by more than [FitAspectMax] so a
        // clip stays a tile. A pinch takes over from wherever this left the
        // width. Big screens have a higher ceiling, see [CellMaxWLarge].
        val large = largeScreen()
        val cellMax = if (large) CellMaxWLarge else CellMaxW
        val fit: Pair<Float, Float>? = if (!large || gridZoom > 0f || gridW <= 0 || gridH <= 0) null else {
            with(LocalDensity.current) {
                val top = cellMax / CELL_W
                var zx = (gridW.toDp() * FitSlack / (TRACK_W + CELL_W * (song.scenes.size + 1))).coerceIn(1f, top)
                var zy = (gridH.toDp() * FitSlack / (SCENE_H + CELL_H * (song.tracks.size + 1))).coerceIn(1f, top)
                zx = zx.coerceAtMost(zy * FitAspectMax)
                zy = zy.coerceAtMost(zx * FitAspectMax)
                zx to zy
            }
        }
        val cellW = if (fit != null) CELL_W * fit.first
                    else (CELL_W * (if (gridZoom > 0f) gridZoom else 1f)).coerceIn(CellMinW, cellMax)
        // The zoom factor actually in use, which is the clamped width read
        // back. Every other size follows it, so a cell keeps its shape, unless
        // the tablet fit gave the height its own factor.
        val z = cellW / CELL_W
        val zy = fit?.second ?: z
        val cell = SongCell(TRACK_W * z, cellW, CELL_H * zy, SCENE_H * zy)
        // With TalkBack on, scenes are shown a page at a time instead of
        // scrolling. TalkBack only reads what's on screen and scrolls a
        // container after reading it, so in a sideways-scrolling grid it
        // skipped hidden scenes in every row but the first. A page is as many
        // scenes as fit, and without a scroller between them each track header
        // is read just before its clips.
        val talkBack = rememberTalkBack()
        var scenePage by rememberSaveable { mutableIntStateOf(0) }
        val sceneCount = song.scenes.size.coerceAtLeast(1)
        val perPage = if (!talkBack || gridW == 0) sceneCount else with(LocalDensity.current) {
            ((gridW.toDp() - cell.trackW) / cell.cellW).toInt().coerceAtLeast(1)
        }
        val pages = (sceneCount + perPage - 1) / perPage
        val page = scenePage.coerceIn(0, pages - 1)
        val shownScenes = page * perPage until minOf(song.scenes.size, (page + 1) * perPage)
        // + scene where there's room: always without TalkBack, and on the last
        // page with it. A full last page leaves it out, each scene's menu can
        // add one too.
        val showAddScene = !talkBack || (page == pages - 1 && shownScenes.count() < perPage)
        if (pages > 1) {
            val first = shownScenes.first + 1
            val last = shownScenes.last + 1
            val pageSaid = if (first == last) {
                stringResource(Res.string.a11y_scene_page_one, first, song.scenes.size)
            } else {
                stringResource(Res.string.a11y_scene_page, first, last, song.scenes.size)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                HeaderButton("◀", description = stringResource(Res.string.a11y_prev_scenes)) { scenePage = (page - 1 + pages) % pages }
                Text(
                    stringResource(Res.string.main_scene_page, first, last, song.scenes.size),
                    color = Acid.colors.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    // Announced when it changes, so pressing either arrow says
                    // where it went.
                    modifier = Modifier.semantics {
                        contentDescription = pageSaid
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                HeaderButton("▶", description = stringResource(Res.string.a11y_next_scenes)) { scenePage = (page + 1) % pages }
            }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f)
                .onSizeChanged { gridW = it.width; gridH = it.height }
                // Mouse wheel on desktop (see onWheel). Up/down is the column's
                // own scroll, sideways moves a scene per notch, and Ctrl zooms
                // the cells like a pinch.
                .onWheel { w ->
                    when {
                        w.zoom -> {
                            val was = if (gridZoom > 0f) gridZoom else z
                            gridZoom = (was / w.zoomFactor).coerceIn(CellMinW / CELL_W, cellMax / CELL_W)
                            true
                        }
                        w.across != 0f -> { hScroll.dispatchRawDelta(w.across * cellW.toPx()); true }
                        else -> false
                    }
                }
                // Two fingers move the grid, one finger launches a clip.
                //
                // Watched on the Initial pass (parent to child), because every
                // cell has a combinedClickable or detectTapGestures and both
                // axes scroll, so they would otherwise take the gesture first.
                // Once it commits everything is consumed, so the clip the
                // finger landed on isn't launched on release.
                //
                // TwoFingers and decideTwoFinger are shared with the editors:
                // pan vs pinch is decided once one wins by 24 px and held until
                // the fingers lift.
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
                        // The live factor, compounded here instead of read back
                        // from composition. A pinch sends many events per frame,
                        // and multiplying a value that hasn't been recomposed
                        // yet would lose most of the gesture.
                        var live = z
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            if (event.changes.count { it.pressed } < 2) break
                            val now = TwoFingers.of(event) ?: continue
                            if (mode == TwoFingerMode.Undecided) mode = decideTwoFinger(start, now)
                            when (mode) {
                                TwoFingerMode.Pan -> {
                                    // Both axes at once, unlike the editors,
                                    // since both scroll directions are real
                                    // here.
                                    hScroll.dispatchRawDelta(last.centre.x - now.centre.x)
                                    vScroll.dispatchRawDelta(last.centre.y - now.centre.y)
                                }
                                // Either direction drives the one zoom factor
                                // (see SongCell), so both are handled the same.
                                TwoFingerMode.ZoomTime, TwoFingerMode.ZoomPitch -> {
                                    val wasSpread = if (mode == TwoFingerMode.ZoomTime) last.spreadX else last.spreadY
                                    val nowSpread = if (mode == TwoFingerMode.ZoomTime) now.spreadX else now.spreadY
                                    if (wasSpread > TwoFingers.MinSpread && nowSpread > TwoFingers.MinSpread) {
                                        live *= nowSpread / wasSpread
                                        gridZoom = live.coerceIn(
                                            CellMinW / CELL_W, cellMax / CELL_W,
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
                        hot = straining && rackHot.getOrElse(index) { false },
                        name = track.name, machine = track.machine.type, colour = trackColour(index, track.colour),
                        onChangeMachine = { dialog = Dialog.PickMachine(index) },
                        onSettings = { dialog = Dialog.TrackSettings(index) },
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
                    modifier = Modifier.width(cell.trackW).height(cell.cellH.coerceAtMost(CELL_H * AddButtonMax)).padding(3.dp),
                    contentPadding = PaddingValues(4.dp),
                ) { Text(stringResource(Res.string.main_add_track), fontSize = 11.sp, maxLines = 1) }
            }
            // Scenes, scrolling horizontally.
            Column(if (talkBack) Modifier else Modifier.horizontalScrollWithBar(hScroll)) {
                Row {
                    for (index in shownScenes) {
                        val scene = song.scenes[index]
                        // In clip mode the arranger's playhead is stale, so
                        // reading position here would light the wrong scene. A
                        // header is live when some rack is sounding a clip from
                        // it, and its progress is that rack's own cycle.
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
                            // Which way the tempo ramp goes, if the scene has
                            // one.
                            rampMark = scene.ramp?.let { r -> if (r.toBpm < (scene.tempo?.bpm ?: song.tempo)) "↘" else "↗" },
                            progress = when {
                                onThisScene != null && cycleTicks > 0 ->
                                    onThisScene.second.tickInCycle.toFloat() / cycleTicks
                                !clipMode && isCurrent && iterTicks > 0 ->
                                    position.tickInIteration.toFloat() / iterTicks
                                else -> null
                            },
                            // Repeats, holding and finishing are arranger
                            // things. In clip mode each cell shows its own
                            // queue and stop, so the header shows nothing.
                            repeatIdx = if (isCurrent && !clipMode) position.repeat else null,
                            holding = isCurrent && loopScene && playing && !clipMode,
                            finishing = isCurrent && playing && stopAtEnd && !clipMode,
                            queued = if (clipMode) {
                                launchStates.indices.any { t -> launchStates[t].pending == index }
                            } else {
                                playing && !isCurrent && queuedScene == index
                            },
                            // A tap holds the scene it starts, and the menu
                            // offers the other choice.
                            onAudition = {
                                when {
                                    // Clip mode: a scene chip launches the
                                    // column. Its clips in, every other track
                                    // out, on one tick.
                                    clipMode -> {
                                        NativeEngine.launchScene(scene.engineId)
                                        if (!playing) com.rm.acidulous.engine.EngineSync.play(0, clipMode)
                                    }
                                    // Already running: a tap means "finish the
                                    // remaining repeats and stop", and another
                                    // tap cancels that.
                                    playing && isCurrent -> NativeEngine.stopAtEnd = !stopAtEnd
                                    // Something else is running: queue this one
                                    // instead of cutting in. Tap again to
                                    // unqueue.
                                    playing -> NativeEngine.queuedScene = if (queuedScene == index) -1 else index
                                    // Stopped: start here and do what the
                                    // loop pill says. ⟳ holds this scene, and
                                    // ⇥ end plays on to the end of the song.
                                    else -> { onLoopScene(song.loopSong); com.rm.acidulous.engine.EngineSync.play(index, clipMode) }
                                }
                            },
                            // Looping a scene means repeating, so it turns ⇥
                            // end back to ⟳, as a tap on the pill does.
                            onLoopThis = {
                                if (!song.loopSong) editor.replace(song.copy(loopSong = true))
                                onLoopScene(true)
                                com.rm.acidulous.engine.EngineSync.play(index, clipMode)
                            },
                            onPlayThrough = { onLoopScene(false); com.rm.acidulous.engine.EngineSync.play(index, clipMode) },
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
                    if (showAddScene) OutlinedButton(
                        onClick = { editor.editSong { it.addScene() } },
                        modifier = Modifier.width(cell.cellW).height(cell.sceneH.coerceAtMost(SCENE_H * AddButtonMax)).padding(3.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text(stringResource(Res.string.main_add_scene), fontSize = 11.sp, maxLines = 1) }
                }
                song.tracks.forEachIndexed { trackIndex, track ->
                    Row {
                        for (sceneIndex in shownScenes) {
                            val scene = song.scenes[sceneIndex]
                            val clip = track.clips[scene.id]
                            val launch = launchStates.getOrElse(trackIndex) { LaunchState.idle }
                            val live = if (clipMode) launch.scene == sceneIndex else position.scene == sceneIndex
                            ClipCell(
                                clip = clip,
                                name = stringResource(Res.string.a11y_cell, track.name, scene.name),
                                ticksPerBar = song.signatureOf(scene).ticksPerBar,
                                colour = trackColour(trackIndex, track.colour),
                                playing = playing && live,
                                // Whether it draws a playhead is decided in
                                // composition and rarely changes. Where the
                                // head is changes all the time and is read in
                                // the draw, so it costs no recomposition.
                                progress = if (playing && live && clip != null && !clip.mute &&
                                    song.clipLengthTicks(scene.id, clip) > 0
                                ) {
                                    {
                                        val len = song.clipLengthTicks(scene.id, clip)
                                        // In clip mode each track has its own
                                        // tick.
                                        val at = if (clipMode) launch.tickInCycle else position.tickInIteration
                                        (at % len).toFloat() / len
                                    }
                                } else {
                                    null
                                },
                                onOpen = { onOpenClip(trackIndex, scene.id) },
                                onSettings = { dialog = Dialog.ClipSettings(trackIndex, scene.id) },
                                frozen = clip?.frozen != null,
                                stale = clip != null && Freeze.stale(song, scene.id, clip),
                                audioLanes = clip?.audioLaneCount() ?: 0,
                                audioStale = clip != null && !track.followsTempo() &&
                                    song.takeTempoDiffers(scene.id, clip),
                                clipMode = clipMode,
                                queued = clipMode && launch.pending == sceneIndex,
                                stopping = clipMode && launch.stopping && launch.scene == sceneIndex,
                                loopPhase = if (clipMode) looper?.phaseOf(trackIndex, scene.id) else null,
                                onCancelLaunch = {
                                    val undo = looperUndo.remove(trackIndex to scene.id)
                                    if (undo != null) undo() else NativeEngine.cancelLaunch(trackIndex)
                                },
                                onLaunch = {
                                    // An empty cell, or one looping, goes to the
                                    // looper.
                                    val undo = looper?.tap(song, trackIndex, scene, sceneIndex, launch, playing)
                                    if (undo != null) {
                                        looperUndo[trackIndex to scene.id] = undo
                                    } else if (clip != null) {
                                        NativeEngine.launchClip(trackIndex, scene.engineId)
                                        // The clip first, then the transport.
                                        // start() resets the launcher, and a
                                        // waiting tap is picked up on the first
                                        // block, so the first clip you touch
                                        // sounds immediately.
                                        if (!playing) com.rm.acidulous.engine.EngineSync.play(0, clipMode)
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
            // The tabs are rotated down the left edge so they cost the strips a
            // little width but no height. The perform pages get the mixer's
            // height so switching doesn't move the grid.
            val density = androidx.compose.ui.platform.LocalDensity.current
            // On a square screen the mixer gets a ceiling, otherwise its strips
            // take most of the screen. The strips shorten their faders to fit
            // the room they're given.
            val mixerCap = if (shape == ScreenShape.Square) {
                with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() } * SquareMixerShare
            } else {
                Dp.Unspecified
            }
            // Grouped so TalkBack reads the tabs, then the page, instead of
            // mixing them.
            Row(
                Modifier.fillMaxWidth().background(Acid.colors.panelAlt)
                    .then(if (mixerCap != Dp.Unspecified) Modifier.heightIn(max = mixerCap) else Modifier)
                    .together(),
            ) {
                Column(
                    Modifier.width(PANEL_TAB_W).padding(start = 4.dp, top = 6.dp)
                        .together().semantics { traversalIndex = -1f },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    stringArrayResource(Res.array.main_panel_pages).forEachIndexed { i, label -> PanelTab(label, panelPage == i) { panelPage = i } }
                }
                val pageModifier = Modifier.weight(1f).height(if (panelH == Dp.Unspecified) PERFORM_H else panelH).together()
                when (panelPage) {
                    1 -> HoldPage(song, editor, performTrack, performState, pageModifier)
                    2 -> PadPage(song, editor, performTrack, performState, pageModifier)
                    3 -> LivePage(song, editor, playing, position.scene, pageModifier)
                    else -> MixerPanel(
                        song, editor, rackPeaks, masterPeak, clickOn, onClick,
                        Modifier.weight(1f).onSizeChanged { panelH = with(density) { it.height.toDp() } }.together(),
                        rows = false,
                    )
                }
            }
        }

        // In landscape the pills are in the header and this is just the
        // readout, with the bar behind it so it doesn't read as part of the
        // grid.
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
            SceneSettingsDialog(scene, song.signature, onDismiss = { dialog = null }, bars = song.barsOf(scene), songTempo = song.tempo) { edited ->
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
                onCopy = {
                    dialog = null
                    ClipClipboard.put(
                        current,
                        song.tracks.getOrNull(d.track)?.name ?: "",
                        song.scenes.firstOrNull { it.id == d.sceneId }?.name ?: "",
                    )
                },
                onCut = {
                    dialog = null
                    ClipClipboard.put(
                        current,
                        song.tracks.getOrNull(d.track)?.name ?: "",
                        song.scenes.firstOrNull { it.id == d.sceneId }?.name ?: "",
                    )
                    editor.editClip(d.track, d.sceneId) { it.cleared() }
                },
                onPaste = {
                    dialog = null
                    // Pastes the whole clip, not a merge. That's why pasting
                    // over a clip asks first.
                    ClipClipboard.take()?.let { pasted ->
                        editor.editClip(d.track, d.sceneId) { pasted }
                    }
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
        is Dialog.TrackSettings -> if (d.index in song.tracks.indices) {
            val tunings = remember { com.rm.acidulous.model.TuningStore.all(com.rm.acidulous.engine.EngineAssets.userRoot()) }
            TrackSettingsDialog(song, d.index, tunings, onDismiss = { dialog = null }) { edited ->
                // A song edit, not a track edit, since it's made from the grid
                // and the grid's undo is the song's.
                editor.editSong { s -> s.copy(tracks = s.tracks.mapIndexed { i, t -> if (i == d.index) edited else t }) }
                dialog = null
            }
        }
        is Dialog.RenameTrack -> TextInputDialog(stringResource(Res.string.main_track_name), song.tracks.getOrNull(d.index)?.name ?: "", onDismiss = { dialog = null }) { name ->
            editor.editSong { it.renameTrack(d.index, name) }
            dialog = null
        }
        Dialog.Tempo -> TempoDialog(
            song, onDismiss = { dialog = null },
            tunings = remember { com.rm.acidulous.model.TuningStore.all(com.rm.acidulous.engine.EngineAssets.userRoot()) },
        ) { edited ->
            // The whole page comes back as one edit, so tempo, bar, swing and
            // key are one undo.
            editor.editSong { edited }
            dialog = null
        }
        Dialog.Songs -> SongBrowserDialog(
            names = songNames(), current = song.name,
            onLoad = { name -> onLoad(name); dialog = null },
            onDelete = onDelete,
            onDismiss = { dialog = null },
        )
        Dialog.Midi -> MidiDialog(song, onDismiss = { dialog = null })
        // From the menu there's no machine waiting for a file, so it opens on
        // the library. A machine opens it on record or library and supplies
        // onPick.
        Dialog.Sound -> RecorderDialog(
            editor = editor,
            onDismiss = { dialog = null },
            startOn = RecorderPage.Library,
            inUse = song.samplesInUse(),
        )
        Dialog.Settings -> SettingsDialog(song.tracks.map { it.name }) { dialog = null }
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
        Dialog.SaveAs -> TextInputDialog(stringResource(Res.string.main_save_as_title), song.name, onDismiss = { dialog = null }) { name ->
            onSaveAs(name)
            dialog = null
        }
        Dialog.NewSong -> TextInputDialog(stringResource(Res.string.main_new_song_title), stringResource(Res.string.main_untitled), onDismiss = { dialog = null }) { name ->
            onNew(name)
            dialog = null
        }
    }
    exportState?.let { ExportDialog(it, onCancel = onExportCancel, onDismiss = onExportDismiss, onShare = onShareExport) }
}

private sealed class Dialog {
    data class SceneSettings(val index: Int) : Dialog()
    data class ClipSettings(val track: Int, val sceneId: String) : Dialog()
    data class PickMachine(val track: Int?) : Dialog() // null = new track
    data class RenameTrack(val index: Int) : Dialog()
    data class TrackSettings(val index: Int) : Dialog()
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
    rampMark: String? = null,
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
                    // Both pending states pulse, so a scene about to end or
                    // start isn't mistaken for a settled one.
                    finishing -> Acid.colors.green.copy(alpha = pulse)
                    queued -> Acid.colors.sceneQueued.copy(alpha = pulse)
                    progress != null -> Acid.colors.green
                    else -> Acid.colors.control
                },
            )
            .combinedClickable(onClick = onAudition, onLongClick = { menu = true })
            .button(
                listOfNotNull(
                    stringResource(Res.string.a11y_scene, index + 1, name),
                    pluralStringResource(Res.plurals.a11y_cell_bars, bars, bars),
                    pluralStringResource(Res.plurals.a11y_scene_repeats, repeat, repeat),
                    if (hasTempo) stringResource(Res.string.a11y_scene_tempo) else null,
                    if (rampMark != null) stringResource(Res.string.a11y_scene_ramp) else null,
                ).joinToString(stringResource(Res.string.list_separator)),
                when {
                    finishing -> stringResource(Res.string.a11y_scene_finishing)
                    queued -> stringResource(Res.string.a11y_queued)
                    holding -> stringResource(Res.string.a11y_scene_looping)
                    progress != null -> stringResource(Res.string.a11y_playing)
                    else -> null
                },
                listOf(action(stringResource(Res.string.a11y_scene_menu)) { menu = true }),
            ),
    ) {
        if (progress != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Acid.colors.sceneProgress))
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(
                // The loop mark goes on the scene being held.
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
                    append("×$repeat ").append(stringResource(Res.string.main_bars_short, bars))
                    if (hasTempo) append(" ♩")
                    if (rampMark != null) append(" $rampMark")
                    if (repeatIdx != null) append(" ").append(stringResource(Res.string.main_repeat_short, repeatIdx + 1))
                },
                color = Acid.colors.textMid, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // A scrollbar, since eleven items is taller than a phone held sideways.
        // See ui/Scrollbar.kt.
        val menuScroll = rememberScrollState()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ScaledMenu(menuScroll) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_loop_this_scene)) }, onClick = { menu = false; onLoopThis() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_play_on)) }, onClick = { menu = false; onPlayThrough() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_settings)) }, onClick = { menu = false; onSettings() })
                if (freezable > 0) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.main_freeze_scene, freezable)) }, onClick = { menu = false; onFreeze() })
                }
                if (frozen > 0) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.main_thaw_scene, frozen)) }, onClick = { menu = false; onThaw() })
                }
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_insert_after)) }, onClick = { menu = false; onInsertAfter() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_duplicate)) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_move_left)) }, onClick = { menu = false; onMoveLeft() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_move_right)) }, onClick = { menu = false; onMoveRight() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TrackHeader(
    /**
     * Glows when this track costs a big share of a block while the engine is
     * late.
     */
    hot: Boolean = false,
    name: String, machine: String, colour: Color,
    onChangeMachine: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
    freezable: Int, frozen: Int, onFreeze: () -> Unit, onThaw: () -> Unit,
    /** The track's settings, from a hold on the header or from the menu. */
    onSettings: () -> Unit = {},
) {
    val cell = LocalSongCell.current
    var menu by remember { mutableStateOf(false) }
    // A slow fade in and out instead of a blink, so it reads as a state and not
    // an alarm. It never stops anything.
    val glow by rememberInfiniteTransition(label = "hot").animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        Modifier
            .width(cell.trackW).height(cell.cellH).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hot) Acid.colors.red.copy(alpha = glow) else Acid.colors.control)
            .combinedClickable(onClick = { menu = true }, onLongClick = onSettings)
            .button(
                stringResource(Res.string.a11y_track, name, machine),
                if (hot) stringResource(Res.string.a11y_track_hot) else null,
                listOf(action(stringResource(Res.string.a11y_track_settings)) { onSettings() }),
            ),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (hot) Acid.colors.red else colour))
        Column(Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp)) {
            Text(name, color = Acid.colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machine, color = Acid.colors.textMid, fontSize = 10.sp, maxLines = 1)
        }
        // A scrollbar, since freeze and thaw come and go and the height depends
        // on the song. See ui/Scrollbar.kt.
        val menuScroll = rememberScrollState()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ScaledMenu(menuScroll) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_change_machine)) }, onClick = { menu = false; onChangeMachine() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_settings)) }, onClick = { menu = false; onSettings() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_rename)) }, onClick = { menu = false; onRename() })
                if (freezable > 0) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.main_freeze_track, freezable)) }, onClick = { menu = false; onFreeze() })
                }
                if (frozen > 0) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.main_thaw_track, frozen)) }, onClick = { menu = false; onThaw() })
                }
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_duplicate)) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.main_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ClipCell(
    clip: com.rm.acidulous.model.Clip?, ticksPerBar: Int, colour: Color, playing: Boolean,
    /** Its track and scene, for TalkBack: "Drums, Intro". */
    name: String = "",
    /**
     * How far through its own loop this clip is, 0..1, or null when silent.
     *
     * A lambda read in the draw phase. As a plain Float it changed on every
     * position update and every visible cell recomposed and re-laid out, on the
     * same cores the audio thread needs. Deferred like this, a moving playhead
     * only costs a redraw.
     */
    progress: (() -> Float)?,
    onOpen: () -> Unit, onSettings: () -> Unit,
    frozen: Boolean = false,
    /** Frozen, but at another tempo, so the machine is playing after all. */
    stale: Boolean = false,
    /** How many tape lanes hold a recording here, and whether any is off-tempo. */
    audioLanes: Int = 0,
    audioStale: Boolean = false,
    clipMode: Boolean = false,
    /** Waiting to start, or playing but asked to stop. */
    queued: Boolean = false,
    stopping: Boolean = false,
    onLaunch: () -> Unit = {},
    /** Where this cell's loop is, if it's being recorded. See Looper. */
    loopPhase: Looper.Phase? = null,
    onCancelLaunch: () -> Unit = {},
) {
    // pointerInput keeps the lambdas it was built with, so they're read through
    // rememberUpdatedState, otherwise a cell would launch whatever it held when
    // first composed.
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
    val recording = loopPhase == Looper.Phase.Open || loopPhase == Looper.Phase.Overdub
    // What TalkBack says: which cell, what's in it, and what it's doing.
    val said = listOfNotNull(
        name,
        if (clip == null) stringResource(Res.string.a11y_cell_empty)
        else pluralStringResource(Res.plurals.a11y_cell_bars, clip.bars, clip.bars),
        if (clip?.playMode == com.rm.acidulous.model.PlayMode.OneShot) stringResource(Res.string.a11y_cell_once) else null,
        if (clip?.mute == true) stringResource(Res.string.a11y_cell_muted) else null,
        if (frozen) stringResource(if (stale) Res.string.a11y_cell_stale else Res.string.a11y_cell_frozen) else null,
        if (audioLanes > 0) pluralStringResource(Res.plurals.a11y_cell_takes, audioLanes, audioLanes) else null,
    ).joinToString(stringResource(Res.string.list_separator))
    val doing = when {
        recording -> stringResource(Res.string.a11y_looping)
        queued -> stringResource(Res.string.a11y_queued)
        stopping -> stringResource(Res.string.a11y_stopping)
        playing -> stringResource(Res.string.a11y_playing)
        else -> null
    }
    val a11yActions = listOfNotNull(
        action(stringResource(Res.string.a11y_clip_settings)) { settingsNow() },
        if (clipMode && clip != null) action(stringResource(Res.string.a11y_open_editor)) { openNow() } else null,
    )
    val edge = when {
        loopPhase == Looper.Phase.Open -> Acid.colors.red.copy(alpha = pulse)
        loopPhase == Looper.Phase.Overdub -> Acid.colors.red
        queued -> Acid.colors.sceneQueued.copy(alpha = pulse)
        stopping -> Acid.colors.red.copy(alpha = pulse)
        playing -> colour
        else -> Acid.colors.line
    }
    Box(
        Modifier
            .width(cell.cellW).height(cell.cellH).padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Acid.colors.card)
            .border(if (queued || stopping || recording) 2.dp else 1.dp, edge, RoundedCornerShape(6.dp))
            .then(
                if (clipMode) {
                    // A tap launches and a double tap edits. A double-tap
                    // handler would make Compose delay every tap by the
                    // double-tap timeout, so instead the launch goes out on the
                    // first tap and the second tap undoes it: queue, unqueue,
                    // open. A double tap ends up changing nothing and opening
                    // the editor.
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
            )
            // A tap launches in the launcher, which the gesture detector
            // doesn't tell TalkBack, so the click is declared here.
            .button(said, doing, a11yActions, onClick = if (clipMode) ({ launchNow() }) else null, keyFocus = false),
    ) {
        if (clip == null) {
            Text("+", color = Acid.colors.textFaint, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            ClipThumbnail(clip, ticksPerBar, colour, Modifier.fillMaxSize())
            // A clip shorter than its scene comes round more than once, so the
            // scene's progress bar can't show it.
            if (progress != null) {
                // One draw and no layout: the shade behind the played part and
                // the line at its head, from a value read here.
                val overlay = Acid.colors.overlay
                val head = Acid.colors.accent
                Box(
                    Modifier.matchParentSize().drawBehind {
                        val at = progress().coerceIn(0f, 1f)
                        drawRect(overlay, size = Size(size.width * at, size.height))
                        val x = (size.width - 6.dp.toPx()) * at
                        drawRect(head, topLeft = Offset(x, 0f), size = Size(2.dp.toPx(), size.height))
                    },
                )
            }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(stringResource(Res.string.main_bars_short, clip.bars))
                    if (clip.playMode == com.rm.acidulous.model.PlayMode.OneShot) append(" " + stringResource(Res.string.main_clip_once_short))
                    if (clip.mute) append(" " + stringResource(Res.string.main_clip_mute_short))
                    // How many takes are layered here, in amber when one was
                    // recorded at another tempo and the track isn't following it.
                    if (audioLanes > 0) {
                        append(" ")
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = if (audioStale) Acid.colors.accent else Acid.colors.teal,
                            ),
                        ) { append("\u266A$audioLanes") }
                    }
                },
                color = Acid.colors.textHi, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            )
            // A frozen clip plays audio, not notes. It gets a corner mark since
            // the notes in it haven't changed.
            if (frozen) {
                Text(
                    // U+FE0E asks for the text form of the snowflake. Without
                    // it Android draws the blue emoji and the teal/amber
                    // colours are lost.
                    "\u2744\uFE0E", color = if (stale) Acid.colors.accent else Acid.colors.teal, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 3.dp),
                )
            }
            // A loop being recorded is drawn over everything so the playhead
            // shading can't hide it.
            if (recording) {
                Text("\u25CF", color = Acid.colors.red, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
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
@Composable
internal fun quantiseLabel(bars: Int): String =
    if (bars <= 0) stringResource(Res.string.quantise_clip_end) else stringResource(Res.string.quantise_bars, bars)

/**
 * The short form, for places without room: a bottom bar pill is about 60 dp
 * wide and the readout is one ellipsised line. "end" and "4b", like the scene
 * chips.
 */
@Composable
internal fun quantiseShort(bars: Int): String =
    if (bars <= 0) stringResource(Res.string.quantise_end_short) else stringResource(Res.string.main_bars_short, bars)

/**
 * How long a tapped clip waits. 0 means the clip being replaced finishes its
 * cycle, the rest are a grid for cutting in.
 */
@Composable
private fun QuantiseDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    PlainDialog(title = stringResource(Res.string.quantise_title), onDismiss = onDismiss, dismissLabel = stringResource(Res.string.close)) {
        Section(stringResource(Res.string.quantise_clips_start), stringResource(Res.string.quantise_clips_start_note, Res.string.quantise_clips_start_note_mouse)) {
            for (bars in listOf(0, 1, 2, 4, 8)) {
                Choice(quantiseLabel(bars), bars == current) { onPick(bars); onDismiss() }
            }
        }
        // How long an empty cell records into itself when tapped.
        Section(stringResource(Res.string.quantise_loops_record)) {
            for (bars in listOf(0, 1, 2, 4, 8)) {
                Choice(if (bars == 0) stringResource(Res.string.quantise_until_tapped, Res.string.quantise_until_tapped_mouse) else pluralStringResource(Res.plurals.bars, bars, bars), bars == UiPrefs.loopBars) {
                    UiPrefs.chooseLoopBars(bars)
                    onDismiss()
                }
            }
        }
    }
}

/**
 * The corner where the scene row meets the track column. The mode switch goes
 * here: it's part of the grid but not either axis, and nowhere near anything
 * that makes a sound.
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
            .clickable { onClipMode(!clipMode) }
            .button(
                stringResource(Res.string.a11y_grid_mode),
                stringResource(if (clipMode) Res.string.a11y_grid_clips else Res.string.a11y_grid_song),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The same colour in both modes, the background shows which. onAccent
        // is near black and accentDim is dark olive in the dark theme, so
        // onAccent here would be unreadable.
        Text(
            stringResource(if (clipMode) Res.string.main_mode_clip else Res.string.main_mode_song),
            color = Acid.colors.textMid,
            fontSize = 11.sp, maxLines = 1,
        )
    }
}

private val TRACK_W = 96.dp
/**
 * The gap either side of the song pill in the header, the same on both sides so
 * it reads as its own group. The row's normal spacing isn't enough.
 */
private val HeaderIslandGap = 16.dp

private val CELL_W = 84.dp
private val CELL_H = 56.dp
private val SCENE_H = 54.dp

/**
 * How wide a clip cell can be drawn, which limits the pinch.
 *
 * A width rather than a multiplier range, like DrumGrid's MinRow/MaxRow in
 * ui/GridMetrics.kt. Below the floor a cell isn't worth tapping. Above the
 * ceiling one clip takes a quarter of a phone. Both are dp, so they scale with
 * the interface setting.
 */
private val CellMinW = 48.dp
/** How much wider than tall a tablet's fitted cell can get, and the reverse. */
private const val FitAspectMax = 1.6f
/**
 * How much + track and + scene grow with the cells. They're pills, and a pill
 * the height of a tablet cell looks wrong.
 */
private const val AddButtonMax = 1.25f
/** The fit leaves a little over so the last row isn't cut off by rounding. */
private const val FitSlack = 0.98f
private val CellMaxW = 168.dp
/**
 * The ceiling on a big screen, fitted or pinched: three times the base size.
 */
private val CellMaxWLarge = 252.dp

/**
 * The four sizes the song grid is drawn at, after a pinch.
 *
 * One factor for all of them instead of one per axis like the roll, since a
 * cell is a tile and scaling one side turns it into a sliver.
 *
 * A composition local instead of four more parameters because only the four
 * cell composables below read it, same as LocalHeaderBand and
 * LocalPanelStacked.
 */
private data class SongCell(val trackW: Dp, val cellW: Dp, val cellH: Dp, val sceneH: Dp)

private val LocalSongCell = compositionLocalOf { SongCell(TRACK_W, CELL_W, CELL_H, SCENE_H) }

private val PANEL_TAB_W = 26.dp
private val PANEL_TAB_H = 64.dp
/** The perform page's height before the mixer has been measured, about a strip's. */
private val PERFORM_H = 320.dp

/** One of the slide-up panel's pages, as a rotated label. */
@Composable
private fun PanelTab(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.width(22.dp).height(PANEL_TAB_H).clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .choice(label, on, tab = true),
        contentAlignment = Alignment.Center,
    ) { SideText(label, if (on) c.accent else c.textMid, 11.sp, length = PANEL_TAB_H) }
}

/** How much of a square screen the mixer and the perform pages may take. */
private const val SquareMixerShare = 0.55f

/** A readout that reads its own text, so only it redraws as the numbers change. */
@Composable
private fun LiveReadout(text: () -> String, color: androidx.compose.ui.graphics.Color, size: Int) {
    BarReadout(text(), color, size = size)
}
