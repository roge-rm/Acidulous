package com.rm.acidulous.ui

import com.rm.acidulous.util.Math

import com.rm.acidulous.util.format

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest
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
import com.rm.acidulous.model.Action
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.audioLaneCount
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.MODIFIER_SLOTS
import com.rm.acidulous.model.modifierUnit
import com.rm.acidulous.model.withModifier
import com.rm.acidulous.model.withModifierBypass
import com.rm.acidulous.model.withModifierParam
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.emptyClipFor
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors
import com.rm.acidulous.res.*

/**
 * The edit screen: header, piano roll, footer, and the machine panel under the
 * roll.
 */
// One slot per modifier, in the order the notes travel through them.
private const val EV_CHORD = 0
private const val EV_SCALE = 1
private const val EV_ARP = 2

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
    /** Edit another track's clip in this scene, by index (previous and next track). */
    onTrack: (Int) -> Unit = {},
    onOpenPatch: () -> Unit = {},
    patchNames: () -> List<String>,
    /** The name, and the note range the keyboard was showing, which a saved patch remembers. */
    onSavePatch: (name: String, low: Int, high: Int) -> Unit,
    onLoadPatch: (String) -> com.rm.acidulous.model.Patch?,
    factoryPatchNames: () -> List<com.rm.acidulous.model.Patch> = { emptyList() },
    userPatchNames: () -> List<String> = { emptyList() },
    onDeletePatch: (String) -> Unit = {},
    onImportSample: (track: Int, pad: Int) -> Unit = { _, _ -> },
    onImportKit: (track: Int, pad: Int) -> Unit = { _, _ -> },
    onImportSlice: (track: Int) -> Unit = {},
    /** One pad's sample, on its own page with a picture of it. */
    onOpenSample: (pad: Int) -> Unit = {},
    /** Pollen's single sample, which is keyed by name instead of by pad. */
    onImportOneSample: (track: Int) -> Unit = {},
    onImportSoundFont: (track: Int) -> Unit = {},
    onPickPreset: (track: Int) -> Unit = {},
    onImportZoneSamples: (track: Int) -> Unit = {},
    /** For the mixer, which opens over the editor. */
    rackPeaks: FloatArray = FloatArray(16),
    masterPeak: Float = 0f,
    clickOn: Boolean = false,
    onClick: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = song.tracks.getOrNull(trackIndex) ?: return
    val scene = song.scenes.firstOrNull { it.id == sceneId } ?: return
    val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
    // Which of the panel's knobs a lane in this clip moves; see AutomationMarks.
    val markedLanes = clip.automation.keys
    androidx.compose.runtime.SideEffect { AutomationMarks.lanes = markedLanes }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { AutomationMarks.lanes = emptySet() } }
    val ticksPerBar = song.signatureOf(scene).ticksPerBar
    val clipLen = song.clipLengthTicks(sceneId, clip)

    var mode by remember { mutableStateOf(EditMode.Draw) }

    // Step parameter locks. The ◆ in the header makes a tap on the grid choose
    // steps, and while steps are chosen every knob on the panel writes a lock
    // onto them instead of moving (see LockEdit and model/Locks.kt). It's a
    // mode rather than hold-a-step-and-turn because long press on a drum hit is
    // already accent.
    var lockMode by remember { mutableStateOf(false) }
    // Steps chosen in the drum grid and the step row, by tick. The roll uses
    // its own selection.
    var lockTicks by remember(trackIndex, sceneId) { mutableStateOf(emptySet<Int>()) }
    val lockedTicks = remember(clip, clipLen) { com.rm.acidulous.model.Locks.startTicks(clip, clipLen) }
    androidx.compose.runtime.SideEffect {
        AutomationMarks.locks = clip.automation.filterValues { com.rm.acidulous.model.Locks.isLocks(it) }.keys
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            AutomationMarks.locks = emptySet()
            LockEdit.clear()
        }
    }
    val kind = MachineUi.kindOf(track.machine.type)
    // The machine's alternate editor over the same clip. Drum machines open on
    // the grid, keyboard machines on the roll.
    var steps by remember(trackIndex, kind) { mutableStateOf(kind == MachineKind.Drums) }
    val voices = MachineUi.voicesOf(track.machine.type, track.machine.settings)
    var selectedPad by remember(trackIndex) { mutableStateOf(0) }
    // Only Reflux can switch views, between a roll and a step row. Drum
    // machines start on the grid (steps is true) and stay there.
    val hasSteps = track.machine.type == "Reflux"
    var laneKey by remember { mutableStateOf<String?>(null) }
    val slotTypes = track.effects.map { it.type } + track.modifiers.map { it.type }
    val laneKeys = remember(track.machine.type, slotTypes) { automationKeysFor(track) }
    var panel by remember { mutableStateOf(0) } // 0 machine, 1 effects, 2 mixer, all in the same space
    // Which modifier the chips have opened, if any.
    var modifierSlot by remember { mutableStateOf(-1) }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    var scaleDialog by remember { mutableStateOf(false) }
    var generateDialog by remember { mutableStateOf(false) }
    var quantiseDialog by remember { mutableStateOf(false) }
    // What the knobs lock onto: a grid step on drums and Reflux, a note's own
    // length in the roll.
    val lockSpans: List<IntRange> = when {
        !lockMode -> emptyList()
        lockTicks.isNotEmpty() -> lockTicks.sorted().map { it..(it + clip.grid.coerceAtLeast(1) - 1).coerceAtMost(clipLen - 1) }
        else -> selection.mapNotNull { clip.notes.getOrNull(it) }.map { it.tick..(it.tick + maxOf(1, it.length) - 1).coerceAtMost(clipLen - 1) }
    }
    androidx.compose.runtime.SideEffect {
        if (lockSpans.isEmpty()) {
            LockEdit.clear()
        } else {
            LockEdit.trackIndex = trackIndex
            LockEdit.sceneId = sceneId
            LockEdit.spans = lockSpans
            LockEdit.clipTicks = clipLen
            LockEdit.lanes = clip.automation
        }
    }
    // Folding the strip is an app-wide preference, see UiPrefs.
    val shape = screenShape()
    // Tablet: the side column is twice as wide, the keys are held to an
    // instrument's height, and upright they're taller.
    val large = largeScreen()
    val wide = shape == ScreenShape.Wide
    val landscape = wide
    // Square: the roll on top, and under it the keyboard or the panel, there
    // isn't room for both. See the square body below.
    val square = shape == ScreenShape.Square
    // What the square editor's lower half is showing.
    var squareKeys by rememberSaveable { mutableStateOf(true) }
    // Each lane has separate fold flags for landscape and portrait, since a
    // lane takes a big share of the height sideways and starts folded there. A
    // square screen is as short as a turned one, so it uses the landscape
    // flags.
    val autoFolded = if (landscape || square) UiPrefs.automationFoldedLand else UiPrefs.automationFolded
    val noteFolded = if (landscape || square) UiPrefs.noteLaneFoldedLand else UiPrefs.noteLaneFolded
    // Which note property the lane shows. Per track, like the roll's zoom, and
    // not saved in the song.
    var noteProp by remember(trackIndex) { mutableStateOf(NoteProp.Velocity) }
    // Which pitch the note lane shows, or every pitch. Keyed on the track so
    // the filter doesn't carry over to another machine.
    var notePitch by remember(trackIndex) { mutableStateOf<Int?>(null) }
    var scaleView by rememberSaveable { mutableStateOf(ScaleView.Dim) }
    // The roll's own slot, measured, since it's a weight(1f) of whatever the
    // rest leaves. Same reason DrumGrid measures.
    //
    // Zoom and scroll are kept per track, not per clip. A zoom of 0 means use
    // the default for the screen, so turning the phone still gets the landscape
    // default until you pinch.
    var rollPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // How much taller than its stated height the instrument has been dragged,
    // upright. Live while the finger is down and saved when it lifts, like the
    // landscape divider.
    var keysStretch by remember { androidx.compose.runtime.mutableFloatStateOf(UiPrefs.keysStretch) }
    val scale = LocalUiScale.current
    // Each track keeps its own zoom, even after the editor closes. See
    // UiPrefs.zoomOf.
    var zoomRows by rememberSaveable(track.id) { mutableStateOf(UiPrefs.zoomOf(track.id).second) }
    var zoomTicks by rememberSaveable(track.id) { mutableStateOf(UiPrefs.zoomOf(track.id).first) }
    // Saved once it settles instead of on every pinch step, and on the way out.
    LaunchedEffect(track.id) {
        androidx.compose.runtime.snapshotFlow { zoomTicks to zoomRows }.collectLatest { (ticks, rows) ->
            kotlinx.coroutines.delay(400)
            UiPrefs.chooseZoom(track.id, ticks, rows)
        }
    }
    val zoomNow by androidx.compose.runtime.rememberUpdatedState(zoomTicks to zoomRows)
    androidx.compose.runtime.DisposableEffect(track.id) { onDispose { UiPrefs.chooseZoom(track.id, zoomNow.first, zoomNow.second) } }
    var scrollTick by rememberSaveable(trackIndex, sceneId) { mutableStateOf(0f) }

    // Each modifier has a fixed slot, left to right like the chips: chord
    // builds the notes, scale corrects them, arp sequences the result. The
    // scale chip and its dialog are a shortcut to the Scale modifier.
    fun scaleSlot(): Int = EV_SCALE

    fun currentScale(): ScaleSetting {
        val ev = track.modifierAt(scaleSlot())
        return ScaleSetting(
            on = ev.type == "Scale" && !ev.bypass,
            key = Math.round((ev.params["key"] ?: 0f) * 11f),
            scale = Math.round((ev.params["scale"] ?: 0f) * 32f),
            degree = (ev.params["mode"] ?: 0f) >= 0.5f,
            snap = Math.round((ev.params["snap"] ?: 0f) * 2f),
        )
    }

    fun applyScale(s: ScaleSetting) {
        val slot = scaleSlot()
        editor.edit(trackIndex) { t ->
            (if (t.modifierAt(slot).type == "Scale") t else t.withModifier(slot, "Scale"))
                .withModifierParam(slot, "key", s.key / 11f)
                .withModifierParam(slot, "scale", s.scale / 32f)
                .withModifierParam(slot, "mode", if (s.degree) 1f else 0f)
                .withModifierParam(slot, "snap", s.snap / 2f)
                .withModifierBypass(slot, !s.on)
        }
    }
    var lowestPitch by remember {
        val lowest = clip.notes.minOfOrNull { it.pitch } ?: 36
        mutableStateOf((lowest - 3).coerceIn(0, 127 - ROWS))
    }
    val scope = rememberCoroutineScope()

    val playhead = if (playing && song.scenes.getOrNull(position.scene)?.id == sceneId) {
        position.tickInIteration % clipLen
    } else null

    /**
     * The same position counted across the scene's repeats instead of reset at
     * each one.
     *
     * This is what a tape plays against (SceneScheduler::rackCycleTick), so
     * it's what its editor draws against. It counts in the scene's repeats and
     * wraps on the clip's cycle, the same way the scheduler does.
     */
    val cycleTicks = (clipLen * scene.repeat).coerceAtLeast(1)
    val cyclePlayhead = playhead?.let {
        val iteration = song.barsOf(scene) * ticksPerBar
        ((position.repeat.toLong() * iteration + position.tickInIteration) % cycleTicks).toInt()
    }

    // Two bars at a time in the roll, one in the step views, so notes stay wide
    // enough to grab.
    //
    // The row count comes from the available height (see RowTarget), so
    // landscape gets fewer rows automatically. Until the slot is measured the
    // default counts are used for one frame, like DrumGrid.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rollDp = with(density) { rollPx.toDp().value }
    val windowDp = with(density) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp().value
    }
    // The maximum is a physical size written as a row count, so it's scaled by
    // the interface scale.
    val maxRows = (MaxRows / scale).roundToInt().coerceAtLeast(MinRows + 1)
    val base = if (landscape) ROWS_LAND else ROWS
    val defaultRows = if (rollPx == 0) base.coerceAtMost(maxRows)
    else rowsForSlot(rollDp, windowDp, scale, base, MinRows, maxRows)
    val rows = (if (zoomRows > 0f) zoomRows else defaultRows.toFloat())
        .toInt().coerceIn(MinRows, maxRows)
    val defaultBars = if (steps) 1 else if (wide) PAGE_BARS_LAND else PAGE_BARS
    val defaultTicks = (defaultBars * ticksPerBar).toFloat()
    // A pinch never shows less than a beat or more than the whole clip. A
    // tape's lanes always show all of it, so its automation does too (once
    // per pass, see AutomationStrip), or the two playheads would be in
    // different places.
    val pageTicks = (if (kind == MachineKind.Audio) clipLen.toFloat() else if (zoomTicks > 0f) zoomTicks else defaultTicks)
        .coerceIn(PPQN.toFloat(), clipLen.toFloat().coerceAtLeast(PPQN.toFloat()))
    val maxScroll = (clipLen - pageTicks).coerceAtLeast(0f)
    scrollTick = scrollTick.coerceIn(0f, maxScroll)
    // The drum grid's window is whole steps, starting on a step. Otherwise a
    // pinch or drag could leave the cells between the hits and the grid would
    // show none. The lane and automation strip use the same window so they stay
    // under the cells.
    val stepTicks = clip.grid.coerceAtLeast(1)
    val drumSteps = steps && kind == MachineKind.Drums
    val firstTick = if (drumSteps) scrollTick.toInt() / stepTicks * stepTicks else scrollTick.toInt()
    val visibleTicks = if (drumSteps) (pageTicks / stepTicks).roundToInt().coerceAtLeast(1) * stepTicks
    else pageTicks.toInt()
    val pages = kotlin.math.ceil(clipLen / pageTicks).toInt().coerceAtLeast(1)
    val page = (scrollTick / pageTicks).toInt().coerceIn(0, pages - 1)
    // While playing, follow the playhead onto its own page.
    val playheadPage = if (playhead != null && clipLen > 0) (playhead / pageTicks).toInt() else -1
    LaunchedEffect(playheadPage, playing) {
        if (playing && playheadPage in 0 until pages) scrollTick = playheadPage * pageTicks
    }

    fun preview(pitch: Int) {
        NativeEngine.noteOn(trackIndex, pitch, 100)
        scope.launch { delay(120); NativeEngine.noteOff(trackIndex, pitch) }
    }

    // The editor's keyboard shortcuts, each the same as its button here. Undo
    // is the clip's, like the undo button. See ui/Keys.kt.
    KeyScope(
        KeyAction.PlayStop to {
            if (playing) NativeEngine.transportStop()
            else com.rm.acidulous.engine.EngineSync.play(song.scenes.indexOf(scene), UiPrefs.clipMode)
        },
        KeyAction.Record to { onArm(!armed) },
        KeyAction.Undo to { if (editor.canUndo(trackIndex)) { selection = emptySet(); editor.undo(trackIndex) } },
        KeyAction.Redo to { if (editor.canRedo(trackIndex)) { selection = emptySet(); editor.redo(trackIndex) } },
        KeyAction.Panel to {
            if (square && squareKeys) { squareKeys = false; panel = 2 } else panel = if (panel == 2) 0 else 2
        },
        KeyAction.PagePrev to { if (pages > 1) scrollTick = ((page - 1 + pages) % pages) * pageTicks },
        KeyAction.PageNext to { if (pages > 1) scrollTick = ((page + 1) % pages) * pageTicks },
        KeyAction.TrackPrev to { onTrack((trackIndex - 1 + song.tracks.size) % song.tracks.size) },
        KeyAction.TrackNext to { onTrack((trackIndex + 1) % song.tracks.size) },
        KeyAction.EditMode to { if (!steps) mode = if (mode == EditMode.Draw) EditMode.Select else EditMode.Draw },
        KeyAction.StepView to { if (hasSteps) steps = !steps },
        KeyAction.LockSteps to {
            if (kind != MachineKind.Audio) { lockMode = !lockMode; lockTicks = emptySet(); selection = emptySet() }
        },
        KeyAction.Generate to { if (kind != MachineKind.Audio) generateDialog = true },
        KeyAction.Quantise to { if (kind != MachineKind.Audio) quantiseDialog = true },
        KeyAction.FoldPanel to { UiPrefs.foldPanel(!UiPrefs.panelFolded) },
        KeyAction.FoldKeys to { UiPrefs.foldKeys(!UiPrefs.keysFolded) },
        KeyAction.Back to { onBack() },
    )
    // modifier carries the Scaffold's system bar padding. Without it the footer
    // sits under the navigation bar and its taps become Back.
    Column(modifier.fillMaxSize().background(Acid.colors.bg)) {
        val footerSlot: @Composable () -> Unit = {
        // The bottom bar: this screen's view buttons, then mix, rec and play at
        // the same width and order as the arranger's, so they don't move when
        // you open a clip. See ui/BottomBar.kt. Undo and redo are anchors at
        // the arranger's width, see BarAnchor. In landscape the bar is a row in
        // the header, and anchor means the right size either way, see BarScope.
        //
        // The view buttons on the left take an anchor's width each, with a
        // spacer holding the right-hand group in place. Only when there are
        // three or more do they share the width, since that many won't fit at
        // anchor width.
        //
        // The strength toggle is one pill for whichever surface this machine
        // plays with, keys or pads.
        val padToggle = kind == MachineKind.Drums
        // Audio tracks have neither pads nor keys, so no strength toggle.
        val hasStrength = kind != MachineKind.Audio
        val fullStrength = if (padToggle) UiPrefs.padsFullStrength else UiPrefs.keysFullStrength
        // Fill only shows when the clip has a fill trig, since the row is
        // already full on a phone. While performing, Action.Fill and controller
        // pads reach it.
        val hasFill = clip.notes.any {
            it.trig == com.rm.acidulous.model.Trig.Fill || it.trig == com.rm.acidulous.model.Trig.NotFill
        }
        val hasKeysPill = square && kind != MachineKind.Audio
        val views = 1 + (if (hasStrength) 1 else 0) + (if (hasSteps) 1 else 0) +
            (if (!steps) 1 else 0) + (if (hasFill) 1 else 0) + (if (hasKeysPill) 1 else 0)
        BottomBar(
            // In landscape it sits in the header as a row, with no fold of its
            // own.
            inline = landscape,
        ) {
            val view = if (views >= 3) Modifier.barWeight() else anchor
            // Square only: the lower half shows the instrument or the panel,
            // and this toggles it. Lit while the instrument is up. fx and the
            // mixer bring the panel up.
            if (hasKeysPill) BarButton(
                stringResource(if (kind == MachineKind.Drums) Res.string.edit_pads else Res.string.edit_keys), view,
                colour = if (squareKeys) Acid.colors.accent else Color.Unspecified,
            ) { squareKeys = !squareKeys }
            BarButton(
                stringResource(Res.string.edit_fx), view,
                colour = if (panel == 1 && !(square && squareKeys)) Acid.colors.accent else Color.Unspecified,
            ) {
                if (square && squareKeys) { squareKeys = false; panel = 1 }
                else panel = if (panel == 1) 0 else 1
            }
            // Velocity mode: a wedge for velocity from where it's struck, a
            // solid block for full strength everywhere.
            if (hasStrength) BarButton(
                if (fullStrength) "\u25A0" else "\u25E2", view,
                description = stringResource(Res.string.a11y_velocity),
                state = if (fullStrength) stringResource(Res.string.a11y_velocity_full) else stringResource(Res.string.a11y_velocity_touch, Res.string.a11y_velocity_touch_mouse),
                colour = if (fullStrength) Acid.colors.accent else Color.Unspecified,
            ) {
                if (padToggle) UiPrefs.choosePadsFullStrength(!fullStrength)
                else UiPrefs.chooseKeysFullStrength(!fullStrength)
            }
            if (hasFill) {
                BarHoldButton(
                    stringResource(Res.string.edit_fill),
                    view.mappable(MapTargets.action(com.rm.acidulous.model.Action.Fill.name)),
                    held = UiPrefs.fillHeld,
                ) { UiPrefs.holdFill(it) }
            }
            if (hasSteps) {
                BarButton(
                    if (steps) "\u25A6" else "\u25A4", view,
                    description = stringResource(Res.string.a11y_step_view),
                    state = stringResource(if (steps) Res.string.a11y_on else Res.string.a11y_off),
                ) { steps = !steps }
            }
            if (!steps) {
                BarButton(
                    if (mode == EditMode.Draw) "\u270E" else "\u2B1A", view,
                    description = stringResource(Res.string.a11y_edit_mode),
                    state = stringResource(if (mode == EditMode.Draw) Res.string.a11y_draw else Res.string.a11y_select),
                ) {
                    mode = if (mode == EditMode.Draw) EditMode.Select else EditMode.Draw
                }
            }
            // The gap that pushes the transport to the far end. Not in
            // landscape, where a weighted spacer would swallow the header.
            if (!landscape && views < 3) Spacer(Modifier.barSpace())
            BarButton(
                "\u21B6", anchor, enabled = editor.canUndo(trackIndex),
                description = stringResource(Res.string.a11y_undo),
            ) { selection = emptySet(); editor.undo(trackIndex) }
            // Mapping mode on a long press of redo, same as the arranger.
            BarButton(
                "\u21B7",
                anchor.onLongPress { UiPrefs.chooseMapMode(!UiPrefs.mapMode) },
                colour = if (UiPrefs.mapMode) Acid.colors.accent else Color.Unspecified,
                enabled = editor.canRedo(trackIndex),
                description = stringResource(Res.string.a11y_redo),
                actions = listOf(
                    action(stringResource(if (UiPrefs.mapMode) Res.string.a11y_mapping_off else Res.string.a11y_mapping_on)) {
                        UiPrefs.chooseMapMode(!UiPrefs.mapMode)
                    },
                ),
            ) { selection = emptySet(); editor.redo(trackIndex) }
            // One glyph each so they read as one group at any width.
            BarButton(
                "\u21C5", anchor,
                description = stringResource(Res.string.a11y_panel),
                state = stringResource(if (panel == 2) Res.string.a11y_open else Res.string.a11y_closed),
                colour = if (panel == 2 && !(square && squareKeys)) Acid.colors.accent else Color.Unspecified,
            ) {
                if (square && squareKeys) { squareKeys = false; panel = 2 }
                else panel = if (panel == 2) 0 else 2
            }
            BarButton(
                // The glyph shows two states, since the pill has two controls:
                // a tap arms recording, a long press turns the click on.
                (if (armed) "\u25CF" else "\u25CB") + if (clickOn) "\u266A" else "",
                // Hold for the click. Skipped in mapping mode, where mappable
                // uses long press to clear a mapping.
                anchor.mappable(MapTargets.action(Action.RecordArm.name)),
                border = if (armed) Acid.colors.red else null,
                onLongPress = { if (!UiPrefs.mapMode) onClick(!clickOn) },
                description = stringResource(Res.string.a11y_record),
                state = stringResource(if (armed) Res.string.a11y_armed else Res.string.a11y_not_armed)
                    .let { if (clickOn) stringResource(Res.string.a11y_with_click, it) else it },
                holdName = stringResource(if (clickOn) Res.string.a11y_click_off else Res.string.a11y_click_on),
            ) { onArm(!armed) }
            BarButton(
                if (playing) "\u25A0" else "\u25B6",
                anchor.mappable(MapTargets.action(Action.PlayStop.name)),
                // Hold to stop everything: every voice, tail and held note.
                // Same as the arranger's, and skipped in mapping mode for the
                // same reason.
                onLongPress = { if (!UiPrefs.mapMode) panicEverything() },
                description = stringResource(if (playing) Res.string.a11y_stop else Res.string.a11y_play),
                holdName = stringResource(Res.string.a11y_stop_all),
            ) {
                if (playing) NativeEngine.transportStop() else com.rm.acidulous.engine.EngineSync.play(song.scenes.indexOf(scene), UiPrefs.clipMode)
            }
        }
        }

        // Header: back, track, scene, octave. It lays itself out around the
        // camera hole, so on a phone with a cutout this row costs no height.
        // See ui/Cutout.kt.
        CutoutRow(
            Modifier.fillMaxWidth(),
            // Thin padding because the buttons fill the band.
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            spacing = 2.dp,
        ) {
            // Narrow, since the title beside it goes back too.
            HeaderButton("◀", width = 30.dp, description = stringResource(Res.string.a11y_back)) { onBack() }
            // A frozen clip plays audio, so edits here aren't heard until it's
            // thawed. This button thaws it.
            if (clip.frozen != null) {
                val stale = com.rm.acidulous.model.Freeze.stale(song, sceneId, clip)
                HeaderTextButton(
                    "\u2744\uFE0E",
                    color = if (stale) Acid.colors.accent else Acid.colors.teal,
                ) {
                    com.rm.acidulous.model.Freeze.discard(song, com.rm.acidulous.model.Freeze.Target(trackIndex, sceneId))
                    editor.editClip(trackIndex, sceneId) { it.copy(frozen = null) }
                }
            }
            // The title goes back too, so the ◀ and everything up to the paging
            // is one wide back button.
            //
            // Spoken in words for TalkBack instead of "4b · 56n".
            val titleSaid = listOfNotNull(
                track.name, scene.name, pluralStringResource(Res.plurals.a11y_cell_bars, clip.bars, clip.bars),
                if (kind == MachineKind.Audio) clip.audioLaneCount().let { pluralStringResource(Res.plurals.clip_lanes, it, it) } else null,
                if (selection.isEmpty()) null else stringResource(Res.string.edit_selected, selection.size),
            ).joinToString(", ")
            Text(
                listOfNotNull(
                    track.name, scene.name, stringResource(Res.string.main_bars_short, clip.bars),
                    // A tape holds takes, not notes, so count lanes.
                    if (kind == MachineKind.Audio) clip.audioLaneCount().let { pluralStringResource(Res.plurals.clip_lanes, it, it) } else null,
                    // Note and lane point counts, diagnostics only.
                    if (!UiPrefs.showDiagnostics || kind == MachineKind.Audio) null else "${clip.notes.size}n",
                    if (!UiPrefs.showDiagnostics || clip.automation.isEmpty()) null else "${clip.automation.values.sumOf { it.points.size }}a",
                    // The selection count goes here, with the screen's other
                    // readings, not in the bar.
                    if (selection.isEmpty()) null else stringResource(Res.string.edit_selected, selection.size),
                ).joinToString(" · "),
                color = Acid.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                // The band's own height instead of fillMaxHeight, since the row
                // is a SubcomposeLayout and doesn't give children a bounded
                // height.
                modifier = Modifier.flexible()
                    .height(LocalHeaderBand.current)
                    .clickable(onClick = onBack)
                    .button(titleSaid)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // The generators. In the header because the bar is full, and it's
            // about the clip like the title.
            if (kind != MachineKind.Audio) HeaderButton("\u2684", description = stringResource(Res.string.a11y_generate)) { generateDialog = true }
            if (kind != MachineKind.Audio) HeaderButton("\u229E", description = stringResource(Res.string.a11y_quantise)) { quantiseDialog = true }
            // Lock mode, lit while on. Leaving it clears the chosen steps.
            if (kind != MachineKind.Audio) HeaderButton(
                "\u25C6", color = if (lockMode) Acid.colors.pink else null,
                description = stringResource(Res.string.a11y_lock),
                state = stringResource(if (lockMode) Res.string.a11y_on else Res.string.a11y_off),
            ) {
                lockMode = !lockMode
                lockTicks = emptySet()
                selection = emptySet()
            }
            // Paging lives in the header, a row of its own would cost too much
            // height.
            if (pages > 1) {
                val pageSaid = stringResource(Res.string.a11y_page, page + 1, pages)
                HeaderButton("◀", description = stringResource(Res.string.a11y_prev_page)) { scrollTick = ((page - 1 + pages) % pages) * pageTicks }
                Text(
                    "${page + 1}/$pages", color = Acid.colors.accent, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                    modifier = Modifier.semantics { contentDescription = pageSaid },
                )
                HeaderButton("▶", description = stringResource(Res.string.a11y_next_page)) { scrollTick = ((page + 1) % pages) * pageTicks }
            }
            // In landscape the transport goes in the header, which has spare
            // width. Upright it stays in the bottom bar.
            if (landscape) footerSlot()
            // Last, at the far edge, like on the patch editor. It's a readout,
            // so it goes after the things you press.
            LoadMeter()
        }
        // The editor is one stack in portrait and two panes in landscape. The
        // pieces are the same, so each is written once here and placed below.
        var octave by rememberSaveable(trackIndex) { mutableStateOf(3) }
        // Typed notes start where the on-screen keys do (the first key is note
        // 12 x (octave + 1), which is KeyHub's octave too), and Z and X move
        // both.
        androidx.compose.runtime.SideEffect { KeyHub.follow(octave) { octave = it } }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { KeyHub.follow(0, null) } }
        // A pink border while choosing steps for locks.
        val lockEdge = if (lockMode) Modifier.border(1.dp, Acid.colors.pink) else Modifier
        val gridSlot: @Composable ColumnScope.() -> Unit = {
        if (kind == MachineKind.Audio) AudioLanes(
            clip = clip,
            ticksPerBar = ticksPerBar,
            // The whole cycle, not one pass, since a tape plays through the
            // scene's repeats.
            cycleTicks = cycleTicks,
            playheadTick = cyclePlayhead,
            trackIndex = trackIndex,
            sceneId = sceneId,
            editor = editor,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) else if (steps && kind == MachineKind.Drums) DrumGrid(
            clip = clip,
            ticksPerBar = ticksPerBar,
            voices = voices,
            playheadTick = playhead,
            firstTick = firstTick,
            visibleTicks = visibleTicks,
            onScrollTime = { ticks -> scrollTick = (scrollTick + ticks).coerceIn(0f, maxScroll) },
            onZoomTime = { scale ->
                val was = if (zoomTicks > 0f) zoomTicks else defaultTicks
                val span = clipLen.toFloat().coerceAtLeast(PPQN.toFloat())
                val centre = scrollTick + was / 2f
                val want = (was * scale).coerceIn(PPQN.toFloat(), span)
                zoomTicks = want
                scrollTick = (centre - want / 2f).coerceIn(0f, (clipLen - want).coerceAtLeast(0f))
            },
            onSetHit = { tick, note, hit ->
                editor.editClip(trackIndex, sceneId) { c ->
                    val others = c.notes.filter { !(it.tick == tick && it.pitch == note) }
                    c.copy(notes = (if (hit != null) others + hit else others).sortedBy { it.tick })
                }
            },
            lockMode = lockMode,
            selectedTicks = lockTicks,
            lockedTicks = lockedTicks,
            onSelectStep = { t -> lockTicks = if (t in lockTicks) lockTicks - t else lockTicks + t },
            modifier = Modifier.fillMaxWidth().weight(1f).then(lockEdge),
        ) else if (steps) StepEditor(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            barIndex = page,
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
            lockMode = lockMode,
            selectedTicks = lockTicks,
            lockedTicks = lockedTicks,
            onSelectStep = { t -> lockTicks = if (t in lockTicks) lockTicks - t else lockTicks + t },
            modifier = Modifier.fillMaxWidth().weight(1f).then(lockEdge),
        ) else PianoRoll(
            clip = clip,
            ticksPerBar = ticksPerBar,
            // Choosing notes to lock uses select mode.
            mode = if (lockMode) EditMode.Select else mode,
            selection = selection,
            playheadTick = playhead,
            lowestPitch = lowestPitch,
            rows = rows,
            scalePitchClasses = Scales.activeFor(song, track),
            noteSpelling = Scales.spellingFor(song, track),
            scaleView = scaleView,
            firstTick = firstTick,
            visibleTicks = visibleTicks,
            onCycleScaleView = {
                // Dim and fit need a scale, so without one this opens the scale
                // dialog.
                if (Scales.activeFor(song, track) == null) scaleDialog = true
                else scaleView = when (scaleView) {
                    ScaleView.Chromatic -> ScaleView.Dim
                    ScaleView.Dim -> ScaleView.Fold
                    ScaleView.Fold -> ScaleView.Chromatic
                }
            },
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
            onAudition = { pitch -> preview(pitch) },
            lockedTicks = lockedTicks,
            // Same clamp as the octave buttons, so the window can't run off
            // either end of the keyboard.
            onScrollPitch = { delta -> lowestPitch = (lowestPitch + delta).coerceIn(0, 127 - rows) },
            onScrollTime = { ticks -> scrollTick = (scrollTick + ticks).coerceIn(0f, maxScroll) },
            // Zoom compounds in the state itself, never in rows or pageTicks.
            // Those are computed during composition, and a pinch sends many
            // events per frame, so multiplying them would lose most of the
            // gesture.
            onZoom = { pitchScale, timeScale ->
                if (pitchScale != 1f) {
                    val was = if (zoomRows > 0f) zoomRows else defaultRows.toFloat()
                    val want = (was * pitchScale).coerceIn(MinRows.toFloat(), maxRows.toFloat())
                    // Around the middle of the screen, so the row you were
                    // looking at stays put.
                    val centre = lowestPitch + was.toInt() / 2
                    zoomRows = want
                    lowestPitch = (centre - want.toInt() / 2).coerceIn(0, 127 - want.toInt())
                }
                if (timeScale != 1f) {
                    val was = if (zoomTicks > 0f) zoomTicks else defaultTicks
                    val span = clipLen.toFloat().coerceAtLeast(PPQN.toFloat())
                    val centre = scrollTick + was / 2f
                    val want = (was * timeScale).coerceIn(PPQN.toFloat(), span)
                    zoomTicks = want
                    scrollTick = (centre - want / 2f).coerceIn(0f, (clipLen - want).coerceAtLeast(0f))
                }
            },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            onMove = { indices, dTick, dPitch ->
                editor.updateGestureClip(sceneId) { base ->
                    base.copy(notes = base.notes.mapIndexed { i, n ->
                        if (i in indices) n.copy(
                            tick = (n.tick + dTick).coerceIn(0, clipLen - 1),
                            pitch = (n.pitch + dPitch).coerceIn(0, 127),
                            // The played position moves with it, so "as played"
                            // keeps the feel.
                            rawTick = n.rawTick?.let { (it + dTick).coerceIn(0, clipLen - 1) },
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
            // The slot the rows are shared over, see RowTarget. Measured here
            // because this is where the count is worked out.
            modifier = Modifier.fillMaxWidth().weight(1f)
                .onSizeChanged { rollPx = it.height }.then(lockEdge),
        )
        }
        val noteLaneSlot: @Composable (Dp) -> Unit = { open ->
        NoteLane(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            firstTick = firstTick,
            visibleTicks = visibleTicks,
            prop = noteProp,
            onProp = { noteProp = it },
            // The drum grid gives every hit a whole cell, the roll draws the
            // note's length. The lane centres its bars on whichever is above
            // it.
            cellWide = steps && kind == MachineKind.Drums,
            pitchFilter = notePitch,
            onPitchFilter = { notePitch = it },
            // A drum voice by its short name, anything else by the note name
            // spelled like the roll's gutter.
            pitchName = { p ->
                voices.firstOrNull { it.note == p }?.short
                    ?: noteName(p, Scales.spellingFor(song, track))
            },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            // Absolute, not relative: the gesture is applied to the base the
            // editor captured, so sweeping back over a note settles on the last
            // value instead of adding up.
            onSet = { values ->
                editor.updateGestureClip(sceneId) { base ->
                    val notes = base.notes.toMutableList()
                    for ((i, v) in values) {
                        val n = notes.getOrNull(i) ?: continue
                        notes[i] = when (noteProp) {
                            NoteProp.Velocity -> n.copy(velocity = (v * 127f).roundToInt().coerceIn(1, 127))
                            NoteProp.Chance -> n.copy(chance = (v * 100f).roundToInt().coerceIn(0, 100))
                            NoteProp.Ratchet -> n.copy(ratchet = (v * 8f).roundToInt().coerceIn(1, 8))
                            NoteProp.Nudge ->
                                n.copy(nudge = ((v - 0.5f) * 2f * NUDGE_RANGE).roundToInt()
                                    .coerceIn(-NUDGE_RANGE, NUDGE_RANGE))
                            // The same y mapping as the rest of the lane: the
                            // bottom is Always and the top is the last of the
                            // Nth family.
                            NoteProp.Cond -> {
                                val all = com.rm.acidulous.model.Trig.inOrder
                                val at = (v * (all.size - 1)).roundToInt().coerceIn(0, all.size - 1)
                                n.copy(trig = all[at])
                            }
                        }
                    }
                    base.copy(notes = notes)
                }
            },
            onGestureEnd = { editor.endGesture() },
            collapsed = noteFolded,
            onToggleCollapse = {
                if (landscape) UiPrefs.foldNoteLaneLand(!noteFolded) else UiPrefs.foldNoteLane(!noteFolded)
            },
            // The same height as the automation strip below it.
            modifier = Modifier.fillMaxWidth().height(if (noteFolded) 24.dp else open).padding(top = 4.dp),
        )
        }

        val automationSlot: @Composable (Dp) -> Unit = { open ->
        val resources = AppStrings
        val laneWord: (String) -> String = { resources.panelWord(it) }
        // Automation: the parameter strip under the notes.
        AutomationStrip(
            clip = clip,
            ticksPerBar = ticksPerBar,
            // Under a tape, every pass of the scene side by side, like the
            // lanes above, so the playheads are in the same place.
            playheadTick = if (kind == MachineKind.Audio) cyclePlayhead?.toLong() else playhead,
            firstTick = firstTick,
            visibleTicks = visibleTicks,
            passes = if (kind == MachineKind.Audio) scene.repeat.coerceAtLeast(1) else 1,
            laneKeys = laneKeys,
            nameOf = { com.rm.acidulous.model.laneLabel(track, it, laneWord) },
            shortOf = { com.rm.acidulous.model.laneShortLabel(track, it, laneWord) },
            selected = laneKey,
            onSelect = { laneKey = it },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            onDraw = { key, points ->
                editor.updateGestureClip(sceneId) { base ->
                    val pedal = com.rm.acidulous.model.isPedalLane(key)
                    var lane = base.automation[key]
                        ?: com.rm.acidulous.model.newLaneFor(key, points.keys.minOrNull() ?: 0)
                    // A pedal is up or down, so drawing snaps to one or the other.
                    for ((t, v) in points) lane = lane.withPoint(t, if (pedal) (if (v >= 0.5f) 1f else 0f) else v)
                    // Only keep the points where the pedal changes.
                    if (pedal) lane = lane.copy(points = lane.points.filterIndexed { i, p -> i == 0 || lane.points[i - 1].value != p.value })
                    base.copy(automation = base.automation + (key to lane))
                }
            },
            onGestureEnd = { editor.endGesture() },
            onClear = { key -> editor.editClip(trackIndex, sceneId) { c -> c.copy(automation = c.automation - key) } },
            collapsed = autoFolded,
            onToggleCollapse = {
                if (landscape) UiPrefs.foldAutomationLand(!autoFolded) else UiPrefs.foldAutomation(!autoFolded)
            },
            modifier = Modifier.fillMaxWidth().height(if (autoFolded) 24.dp else open).padding(top = 4.dp),
            baseOf = { key -> com.rm.acidulous.engine.EngineSync.documentValue(track, key) },
        )
        }
        // [bar] and [body] are the machine panel's two halves. Upright they're
        // drawn together. Sideways the header runs down the left edge and the
        // cards are on the right with the roll between, so each is placed
        // separately. The fx slots and the mixer have their own headers and are
        // all body.
        val panelSlot: @Composable (bar: Boolean, body: Boolean) -> Unit = { bar, body ->
        // The machine panel (knobs go to the engine as gestures and into the document as undo
        // steps), or behind the fx toggle the track's two insert slots.
        if (panel == 1) { if (body) SlotsPanel(SlotKind.Effects, track, trackIndex, editor, Modifier.fillMaxWidth().padding(top = 4.dp)) }
        else if (panel == 2) { if (body) MixerPanel(song, editor, rackPeaks, masterPeak, clickOn, onClick, Modifier.fillMaxWidth().padding(top = 4.dp)) }
        else MachinePanel(
            track, trackIndex, editor, patchNames,
            // A patch knows the notes it's for. Loading one moves the
            // keyboard's bottom C to the bottom of its range so the first key
            // pressed is a note the instrument has. Saving one keeps where the
            // keyboard was.
            onSavePatch = { name -> onSavePatch(name, 12 * (octave + 1), 12 * (octave + 3)) },
            onLoadPatch = { name ->
                onLoadPatch(name)?.also { p ->
                    // The keyboard follows the patch, the roll doesn't. The
                    // roll is where you were looking at your own music, and
                    // auditioning patches shouldn't scroll it.
                    if (p.low >= 0) octave = (p.low / 12 - 1).coerceIn(0, 8)
                }
            },
            factoryPatchNames = factoryPatchNames, userPatchNames = userPatchNames, onDeletePatch = onDeletePatch,
            onImportSoundFont = { onImportSoundFont(trackIndex) },
            onPickPreset = { onPickPreset(trackIndex) },
            onImportZoneSamples = { onImportZoneSamples(trackIndex) },
            selectedPad = selectedPad,
            onImportSample = { pad -> onImportSample(trackIndex, pad) },
            onImportKit = { pad -> onImportKit(trackIndex, pad) },
            onImportSlice = { onImportSlice(trackIndex) },
            onOpenSample = onOpenSample,
            onImportOneSample = { onImportOneSample(trackIndex) },
            onClearSample = { pad -> editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), null) } },
            // The whole kit in one edit, so clearing it is one undo and a
            // half-cleared kit can't be autosaved. Only the settings here, the
            // pads' trim is parameters, which the panel resets itself.
            onClearKit = {
                editor.edit(trackIndex) { t ->
                    var out = t
                    for (pad in 0 until 13) out = out.withSetting("p%02d_sample".format(pad), null)
                    out.withSetting("slice_sample", null).withSetting("slice_count", null)
                }
            },
            onAssignSample = { pad, rel ->
                editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), rel) }
            },
            onOpenPatch = onOpenPatch,
            sceneId = sceneId,
            bar = bar, body = body, vertical = landscape,
            modifier = Modifier.fillMaxWidth()
                .then(if (landscape) Modifier.fillMaxHeight() else Modifier.padding(top = 4.dp)),
        )
        }
        // The performance controls are with the keys: a mod wheel, a bend
        // wheel, and pressure, scale and octave on a strip above them.
        var mod by rememberSaveable(trackIndex) { mutableStateOf(0f) }
        var pressure by remember(trackIndex) { mutableStateOf(0f) }
        var bend by remember(trackIndex) { mutableStateOf(0.5f) }
        val touchable = MachineUi.usesPerformance(track.machine.type)
        // Not a gesture, so opening a track doesn't write a lane point.
        LaunchedEffect(trackIndex) { NativeEngine.controlChange(trackIndex, 1, (mod * 127f).toInt(), record = false) }

        // [grip] is the drag that moves the divider above the keys. There's no
        // visible divider, the blank gaps in the performance row are the
        // handle. Empty in landscape, where there's no divider.
        val keysSlot: @Composable (Dp, Modifier) -> Unit = { height, grip ->
        val keysFolded = UiPrefs.keysFolded
        // A tape has no instrument and no performance row, so the lanes get the
        // whole screen.
        if (kind == MachineKind.Audio) Unit
        else if (kind == MachineKind.Drums) Column(Modifier.fillMaxWidth().height(height)) {
            // The pads have no performance row, so the gap above them is the
            // handle.
            //
            // It has to be its own box, not padding on the pads. A pointerInput
            // after padding only covers the inside, so the drag would land on
            // the pads, which consume their own touches.
            //
            // It also holds the fold mark at the right-hand end, like the
            // keyboard's row.
            Box(
                Modifier.fillMaxWidth().height(PadsGrip).then(grip),
                contentAlignment = Alignment.CenterEnd,
            ) { FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) } }
            if (!keysFolded) DrumPads(
                trackIndex, voices, selectedPad, { selectedPad = it },
                Modifier.fillMaxWidth().weight(1f),
                track.machine.type,
                onEmpty = { pad -> if (MachineUi.acceptsSamples(track.machine.type)) onImportSample(trackIndex, pad) },
            )
        }
        else Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            // The whole row is the handle. Upright there's no blank space left
            // in it, so the drag is watched underneath the row and the controls
            // in it win where they are: the wheel and stepper consume at the
            // slop and cancel this, and a chip's tap detector never sees a
            // drag. See keysGrip.
            BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp).then(grip)) {
            // The chip labels switch to glyphs when the row gets too narrow
            // (see PerfWideMin). At larger interface scales the chips at their
            // fixed widths squeezed the pressure wheel and octave stepper to
            // nothing. Glyphs give about 120 dp back to them.
            val icons = maxWidth < PerfWideMin
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Equal weights either side so the scale chip sits on the row's
                // centre line. The chip is measured first as the one unweighted
                // child, so it only takes what its text needs.
                //
                // The wheel is weighted for position and capped for size
                // (PressureW), so it doesn't grow to fill a landscape row.
                Box(
                    Modifier.weight(1f).fillMaxHeight().then(grip),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (touchable) TouchWheel(
                        value = pressure, accent = Acid.colors.pink, vertical = false,
                        // No label, the ridges and its place in the row say
                        // what it is.
                        springBackTo = 0f, label = null, said = stringResource(Res.string.a11y_pressure),
                        modifier = Modifier.widthIn(max = PressureW).fillMaxHeight(),
                    ) { v -> pressure = v; NativeEngine.channelPressure(trackIndex, (v * 127f).toInt()) }
                }
                // The modifiers sit either side of the scale chip since they
                // all change notes between playing and hearing them. A tap
                // toggles one, holding opens it.
                ModifierChip(
                    "Chord", EV_CHORD, track, trackIndex, editor,
                    icon = if (icons) "\u2261" else null,
                ) { modifierSlot = EV_CHORD }
                ScaleChip(
                    label = Scales.labelFor(track),
                    onToggle = { applyScale(currentScale().let { it.copy(on = !it.on) }) },
                    onOpen = { scaleDialog = true },
                    vertical = false,
                    // A fixed width, not a minimum, since the label changes
                    // from "scale" to "C Ionian (Major)" and would push the
                    // other chips around.
                    modifier = Modifier.width(if (icons) PerfIconW else 112.dp).fillMaxHeight(),
                    icon = if (icons) "\u266F" else null,
                )
                ModifierChip(
                    "Arp", EV_ARP, track, trackIndex, editor,
                    icon = if (icons) "\u266B" else null,
                ) { modifierSlot = EV_ARP }
                // A fixed width (see OctaveW). The wheel at the other end is
                // the one that stretches. This puts the scale chip slightly off
                // centre, which is the lesser problem.
                Box(
                    Modifier.width(OctaveW).fillMaxHeight().then(grip),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    OctaveStepper(octave, { octave = it }, Modifier.fillMaxHeight())
                }
                // The fold mark, last in the row.
                //
                // It has its own slot. Inside the octave's weighted box it had
                // no width and overflowed the screen margin upright.
                //
                // There's no balancing spacer at the start of the row, so the
                // scale chip is about 11 dp left of centre. A spacer would cost
                // the octave stepper its ▶ arrow.
                FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) }
            }
            }
            if (keysFolded) return@Column
            Row(
                // 30 dp is the performance row above. Clamp at 0 since a narrow
                // pane (landscape at the largest scale, or split screen) can
                // make this negative.
                Modifier.fillMaxWidth().height((height - 30.dp).coerceAtLeast(0.dp)).padding(top = 4.dp),
                // No spacing. The keyboard draws its own 3 dp either side and
                // takes touches there, so a finger just missing the outermost
                // key still plays it instead of grabbing the wheel. See
                // EdgeGrab in ui/PianoKeys.kt.
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TouchWheel(
                    value = mod, accent = Acid.colors.accent, vertical = true, label = null,
                    said = stringResource(Res.string.a11y_mod_wheel),
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) { v -> mod = v; NativeEngine.controlChange(trackIndex, 1, (v * 127f).toInt()) }
                PianoKeys(
                    trackIndex, Scales.activeFor(song, track), Scales.rootFor(song, track), octave,
                    Modifier.weight(1f).fillMaxHeight(),
                    noteSpelling = Scales.spellingFor(song, track),
                )
                // Bend springs back, so it's the wheel you can let go of
                // quickly.
                TouchWheel(
                    value = bend, accent = Acid.colors.teal, vertical = true,
                    springBackTo = 0.5f, centreMark = true, label = null,
                    said = stringResource(Res.string.a11y_bend_wheel),
                    state = kotlin.math.round((bend * 2f - 1f) * 100f).toInt().let { amount ->
                        when {
                            kotlin.math.abs(amount) < 2 -> stringResource(Res.string.a11y_centre)
                            amount > 0 -> stringResource(Res.string.a11y_bend_up, amount)
                            else -> stringResource(Res.string.a11y_bend_down, -amount)
                        }
                    },
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) { v ->
                    bend = v
                    val value14 = ((v * 2f - 1f) * 8192f + 8192f).toInt().coerceIn(0, 16383)
                    NativeEngine.midiEvent(trackIndex, 0xE0, value14 and 0x7f, (value14 shr 7) and 0x7f)
                }
            }
        }
        }
        val pad = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
        if (landscape) {
            // Landscape: every row from portrait becomes a column, and the
            // keyboard takes the whole bottom. PianoKeys shows two octaves
            // there from its own measured width. Above it four columns: the
            // machine header on the left edge, the roll and lanes, the
            // machine's cards, and the transport on the right edge.
            //
            // Each edge column folds towards its edge.
            val panelFolded = UiPrefs.panelFolded
            // No fixed heights in landscape. The keyboard takes a share of the
            // window (dragged with the divider under the roll and remembered in
            // UiPrefs), the lanes take a share of what's left, and the roll
            // takes the rest.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val total = maxHeight
                // Live while the finger is down, saved when it lifts, so the
                // pref isn't written every frame.
                var frac by remember { mutableFloatStateOf(UiPrefs.keysFractionLand) }
                // Folded, it's just its control row, the share doesn't apply
                // and there's no grip.
                val keysFolded = UiPrefs.keysFolded
                val foldedH = if (kind == MachineKind.Drums) PADS_FOLDED_H else KEYS_FOLDED_H
                // No instrument means the lanes get its height. On a tablet the
                // share is capped at an instrument's height, otherwise a third
                // of a landscape tablet is a huge keyboard and the roll runs
                // short of rows.
                val keysH = if (kind == MachineKind.Audio) 0.dp
                            else if (keysFolded) foldedH
                            else if (large) (total * frac).coerceAtMost(LARGE_KEYS_LAND_MAX)
                            else total * frac
                // An open lane gets about a quarter of what's above the
                // keyboard, with a floor at the height a finger needs to set a
                // value.
                val laneH = ((total - keysH) * 0.26f).coerceAtLeast(LaneMinH)
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f).then(pad),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Only a machine has a patch header, the fx slots and
                        // the mixer carry their own and are all body. It's
                        // never folded itself and holds the mark that folds the
                        // cards.
                        if (panel == 0) Column(
                            Modifier.width(PATCH_W).fillMaxHeight(),
                        ) { panelSlot(true, false) }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            gridSlot()
                            // A tape has no notes, so no note lane. Its mute
                            // automation is in the strip below, which stays.
                            if (kind != MachineKind.Audio) noteLaneSlot(laneH)
                            automationSlot(laneH)
                        }
                        // The cards do their own scrolling (see GroupRow), so
                        // there's no scroller here. Folded, they're gone and
                        // the roll gets the width, and the strip on the left
                        // keeps the fold mark.
                        //
                        // Two knobs wide on every screen size.
                        if (!panelFolded || panel != 0) Column(
                            Modifier.width(CONTROL_W).fillMaxHeight(),
                        ) { panelSlot(false, true) }
                    }
                    keysSlot(
                        keysH,
                        if (keysFolded) Modifier else keysGrip(
                            onDrag = { dy -> frac = ((keysH - dy) / total).coerceIn(KeysFractionMin, KeysFractionMax) },
                            onEnd = { UiPrefs.chooseKeysFraction(frac) },
                        ),
                    )
                }
            }
        } else if (square) {
            // Square: the roll on top and one thing under it. The lanes start
            // folded, and the keyboard and panel take turns in the lower part
            // (the keys pill in the footer toggles, fx and the mixer bring the
            // panel up). The roll keeps the rest, never less than
            // [SquareRollMin].
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val total = maxHeight
                val keysFolded = UiPrefs.keysFolded
                val keysShown = squareKeys && kind != MachineKind.Audio
                val keysH = if (keysFolded) {
                    if (kind == MachineKind.Drums) PADS_FOLDED_H else KEYS_FOLDED_H
                } else {
                    (total * SquareKeysShare).coerceAtMost((total - SquareRollMin).coerceAtLeast(0.dp))
                }
                Column(Modifier.fillMaxSize().then(pad)) {
                    gridSlot()
                    if (kind != MachineKind.Audio) noteLaneSlot(LaneH)
                    automationSlot(LaneH)
                    if (keysShown) {
                        keysSlot(keysH, Modifier)
                    } else {
                        Box(Modifier.fillMaxWidth().heightIn(max = (total - SquareRollMin).coerceAtLeast(0.dp))) {
                            Column { panelSlot(true, true) }
                        }
                    }
                }
            }
            footerSlot()
        } else {
            // The bar is outside the padding so it reaches the screen edges
            // like the arranger's. Everything above keeps its margins.
            Column(Modifier.fillMaxWidth().weight(1f).then(pad)) {
                gridSlot()
                if (kind != MachineKind.Audio) noteLaneSlot(LaneH)
                automationSlot(LaneH)
                panelSlot(true, true)
                // Upright the instrument can be dragged taller too, with the
                // same handle as in landscape. The height comes from the roll,
                // so the drag stops when the roll hits its floor. It's a
                // multiplier on the stated height, see UiPrefs.keysStretch.
                //
                // The base height is divided by the interface scale so the
                // keyboard stays the same physical size and the roll gets the
                // extra height, as taller rows. At 1.0 this changes nothing. A
                // tablet's keys are 1.5x taller.
                val keysBase = (if (kind == MachineKind.Drums) PADS_H else KEYS_H) / scale * (if (large) LARGE_KEYS else 1f)
                val keysH = if (kind == MachineKind.Audio) 0.dp else keysBase * keysStretch
                keysSlot(
                    if (UiPrefs.keysFolded) {
                        if (kind == MachineKind.Drums) PADS_FOLDED_H else KEYS_FOLDED_H
                    } else {
                        keysH
                    },
                    if (UiPrefs.keysFolded) Modifier else keysGrip(
                        onDrag = { dy ->
                            // From the state, not the composed height. A drag
                            // sends many events per frame and keysH is computed
                            // during composition, so subtracting from it would
                            // lose most of the drag. Same rule as the zoom.
                            val live = keysBase * keysStretch
                            // What the roll can spare, no more.
                            val spare = (rollDp.dp - RollMinH).coerceAtLeast(0.dp)
                            val want = (live - dy).coerceAtMost(live + spare)
                            keysStretch = (want / keysBase).coerceIn(KeysStretchMin, KeysStretchMax)
                        },
                        onEnd = { UiPrefs.chooseKeysStretch(keysStretch) },
                    ),
                )
            }
            footerSlot()
        }
    }

    if (modifierSlot >= 0) {
        SlotDialog(
            SlotKind.Modifiers, track, trackIndex, modifierSlot, editor,
            fixedType = if (modifierSlot == EV_CHORD) "Chord" else "Arp",
        ) { modifierSlot = -1 }
    }

    if (generateDialog) {
        GenerateDialog(
            editor = editor,
            trackIndex = trackIndex,
            sceneId = sceneId,
            base = clip,
            clipTicks = clipLen,
            ticksPerBeat = PPQN * 4 / song.signatureOf(scene).unit,
            pitchClasses = Scales.activeFor(song, track),
            voices = if (kind == MachineKind.Drums) voices else emptyList(),
            spelling = Scales.spellingFor(song, track),
            onDismiss = { generateDialog = false; selection = emptySet() },
        )
    }
    if (quantiseDialog) {
        QuantiseDialog(
            song = song,
            editor = editor,
            trackIndex = trackIndex,
            sceneId = sceneId,
            base = clip,
            clipTicks = clipLen,
            which = selection.takeIf { it.isNotEmpty() },
            onDismiss = { quantiseDialog = false; selection = emptySet() },
        )
    }
    if (scaleDialog) {
        ScaleDialog(
            current = currentScale(),
            onDismiss = { scaleDialog = false },
            onApply = { applyScale(it); scaleDialog = false },
        )
    }
}

/**
 * The smallest roll worth leaving, which stops the keyboard drag. Below this
 * the grid is three rows and a ruler. If you want the instrument bigger, fold
 * the roll.
 */
private val RollMinH = 96.dp

/** The smallest roll the square editor leaves, whatever is under it. */
private val SquareRollMin = 140.dp

/**
 * The keyboard's share of the square editor's height, not counting header and
 * footer.
 */
private const val SquareKeysShare = 0.40f

/** Narrow, because upright the row it ends has about 20 dp to spare. */
private val FoldMarkW = 22.dp

/**
 * Width of a performance row chip when it shows a glyph. An anchor's width,
 * same as the footer, sized for a finger.
 */
private val PerfIconW = 44.dp

/**
 * The narrowest row that can still show the words "chord", "scale" and "arp".
 *
 * The three chips at 52, 112 and 52, the fold mark, the five gaps, the octave
 * stepper's width, and enough for the pressure wheel. A 393 dp phone just fits
 * at 1.0 and doesn't at any larger scale. Below this the chips show glyphs.
 */
private val PerfWideMin = 52.dp + 112.dp + 52.dp + FoldMarkW + 20.dp + OctaveW + 32.dp

/**
 * The mark that folds the instrument away and brings it back. The same as the
 * note lane's and automation strip's: an 11 sp chevron, ▾ to put it away and
 * ▴ to bring it back. It points down because the keyboard is on the bottom
 * edge.
 */
@Composable
private fun FoldMark(folded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.width(FoldMarkW).clickable(onClick = onClick)
            .button(stringResource(if (folded) Res.string.a11y_unfold_keys else Res.string.a11y_fold_keys)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (folded) "\u25B4" else "\u25BE", color = Acid.colors.textMid, fontSize = 11.sp)
    }
}

/**
 * The drag that moves the edge between the roll and the keyboard.
 *
 * A Modifier since there's nothing to draw. It goes on the blank parts of the
 * performance row and on the gap above the drum pads.
 *
 * [onDrag] has to be re-read, not captured. pointerInput(Unit) builds its block
 * once, and where the edge is changes every frame of the drag, hence
 * rememberUpdatedState.
 */
@Composable
private fun keysGrip(onDrag: (Dp) -> Unit, onEnd: () -> Unit): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    return Modifier.pointerInput(Unit) {
        // The wheel and stepper inside this get the touch first and consume at
        // the slop, which cancels this. So a finger on the wheel rolls it and a
        // finger on the blank beside it moves the edge.
        detectDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { change, amount ->
            change.consume()
            drag(with(density) { amount.y.toDp() })
        }
    }
}

/**
 * The blank above the drum pads, which is their drag handle. 18 dp is enough to
 * catch without looking like a band. The keyboard uses the gaps in its
 * performance row instead.
 */
private val PadsGrip = 18.dp

/**
 * What's left at the bottom when the instrument is folded: its control row
 * only. For the keyboard that's the performance strip (6 dp padding and a 26 dp
 * row), for the pads the strip above them. The roll gets the rest.
 */
private val KEYS_FOLDED_H = 32.dp
private val PADS_FOLDED_H = PadsGrip

/**
 * The shortest an open lane can be. Below this it's too short to set a value by
 * dragging. Upright lanes are [LaneH] and never get here, sideways they take a
 * share of what the keyboard leaves and can.
 */
private val LaneMinH = 56.dp

/** Lane height upright, where there's room. */
private val LaneH = 88.dp

/**
 * The widest the pressure wheel gets, in either orientation. It's rolled with
 * one thumb, and past this the extra width isn't reachable anyway.
 */
private val PressureW = 88.dp

/**
 * How many rows the roll shows at the base interface scale.
 *
 * 16 rather than two full octaves, so every row can show its name and be hit
 * with a finger. The arrows move the window. Fewer in landscape, since a row
 * shorter than about 11 dp loses its name.
 *
 * This is a base, not the answer. The chrome around the roll grows with the
 * interface scale, so rowsForSlot in ui/UiScale.kt keeps the roll's share and
 * gives fewer, taller rows above 1.0.
 */
private const val ROWS = 16
private const val ROWS_LAND = 12
private const val PAGE_BARS = 2

// Pinch limits. 6 rows is half an octave. 36 is three octaves, at which a row
// is about 4 dp on a phone.
private const val MinRows = 6
private const val MaxRows = 36

// Landscape shows twice as much clip since the roll is about twice as wide.
private const val PAGE_BARS_LAND = 4
/**
 * How wide the machine's cards are in landscape: two portrait knobs.
 *
 * 58 each plus 6 between, 12 of Group's padding, 12 of the panel's own, 22 for
 * the rotated section tabs plus 4 beside them, and the rest for the scrollbar
 * and slack. Any narrower and the second knob wraps to its own line.
 */
private val CONTROL_W = 180.dp
private val LARGE_KEYS_LAND_MAX = 180.dp

/**
 * The left edge column in landscape. Exactly a rotated BarIcon plus the panel's
 * padding, the same 40 dp the bar is tall in portrait, so the strip is the same
 * thickness either way. The fold in it puts away the cards on the right of the
 * roll, not this strip, so the strip has no folded width.
 */
private val PATCH_W = 40.dp
private val KEYS_H = 104.dp
private const val LARGE_KEYS = 1.5f
// Pads are sized like the keyboard, since they're a playing surface. Smaller
// and the rows get too small for a finger.
private val PADS_H = 132.dp
private val KEYS_H_LAND = 92.dp
// Landscape pad rows come out at 44.5 dp, just under the usual minimum, since
// the grid gets first claim on the ~360 dp of height.
private val PADS_H_LAND = 104.dp

@Composable
private fun KeyboardKey(note: Int, rack: Int, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) Acid.colors.teal else Acid.colors.knobPointer)
            .pointerInput(note, rack) {
                var down = false
                try {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            val now = currentEvent.changes.any { it.pressed }
                            if (now != down) {
                                down = now
                                pressed = now
                                if (now) NativeEngine.noteOn(rack, note, 100) else NativeEngine.noteOff(rack, note)
                            }
                        }
                    }
                } finally {
                    // A cancelled gesture never reports the finger lifting.
                    if (down) NativeEngine.noteOff(rack, note)
                    pressed = false
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(note.toString(), color = Acid.colors.onAccent, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/**
 * One modifier, as a chip.
 *
 * There are three modifiers and three chips, so nothing is chosen from a list:
 * chord on the left, scale in the middle, arp on the right, each on its own
 * slot. A tap toggles it, a long press opens it.
 *
 * Toggling is a parameter: bypass is a pseudo-parameter on the modifier unit,
 * so a tap saves, undoes and automates like anything else, and goes straight to
 * the running modifier as well as the document. Note the field is bypass, so
 * lit means bypass == false. An empty slot is filled on the first tap.
 */
@Composable
private fun ModifierChip(
    type: String,
    slot: Int,
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    /** A glyph instead of the word, when the row is too narrow. */
    icon: String? = null,
    onOpen: () -> Unit,
) {
    val ev = track.modifierAt(slot)
    val loaded = ev.type == type
    SlotChip(
        text = icon ?: type.lowercase(),
        said = panelWord(type.lowercase()),
        on = loaded && !ev.bypass,
        onToggle = {
            if (!loaded) {
                // Nothing there yet, so the first tap adds it, switched on.
                editor.edit(trackIndex) { t -> t.withModifier(slot, type).withModifierBypass(slot, false) }
                NativeEngine.setParam(trackIndex, modifierUnit(slot), "bypass", 0f, record = true)
            } else {
                val bypass = !ev.bypass
                editor.edit(trackIndex) { t -> t.withModifierBypass(slot, bypass) }
                NativeEngine.setParam(trackIndex, modifierUnit(slot), "bypass", if (bypass) 1f else 0f, record = true)
            }
        },
        onOpen = {
            // Hold also fills an empty slot so there's something to open, but
            // bypassed, since holding means "let me look", not "turn it on".
            if (!loaded) {
                editor.edit(trackIndex) { t -> t.withModifier(slot, type).withModifierBypass(slot, true) }
            }
            onOpen()
        },
        vertical = false,
        icon = icon != null,
        modifier = (if (icon != null) Modifier.width(PerfIconW) else Modifier.widthIn(min = 52.dp))
            .fillMaxHeight(),
    )
}
