package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.EVENTOR_SLOTS
import com.rm.acidulous.model.eventorUnit
import com.rm.acidulous.model.withEventor
import com.rm.acidulous.model.withEventorBypass
import com.rm.acidulous.model.withEventorParam
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.emptyClipFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The reference sequencer's Edit screen, phone-sized: header, piano roll, footer. The machine
 * panel sits under the roll.
 */
// One slot per eventor, in the order the notes travel through them.
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
    onOpenPatch: () -> Unit = {},
    patchNames: () -> List<String>,
    onSavePatch: (String) -> Unit,
    onLoadPatch: (String) -> com.rm.acidulous.model.Patch?,
    factoryPatchNames: () -> List<String> = { emptyList() },
    userPatchNames: () -> List<String> = { emptyList() },
    onDeletePatch: (String) -> Unit = {},
    onImportSample: (track: Int, pad: Int) -> Unit = { _, _ -> },
    /** Pollen's single sample, which is keyed by name rather than by pad. */
    onImportOneSample: (track: Int) -> Unit = {},
    onImportSoundFont: (track: Int) -> Unit = {},
    onPickPreset: (track: Int) -> Unit = {},
    onImportZoneSamples: (track: Int) -> Unit = {},
    /** For the mixer, which now opens over the editor: the mix is worth
     *  reaching without leaving the machine you are voicing. */
    rackPeaks: FloatArray = FloatArray(16),
    masterPeak: Float = 0f,
    clickOn: Boolean = false,
    onClick: (Boolean) -> Unit = {},
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
    var panel by remember { mutableStateOf(0) } // 0 machine, 1 effects, 2 the mixer - in the same space
    // Which eventor the chips have opened, if any.
    var eventorSlot by remember { mutableStateOf(-1) }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    var scaleDialog by remember { mutableStateOf(false) }
    // Folding the strip is a preference, not a property of this clip, so it
    // is held for the whole app and across launches - see UiPrefs.
    val autoFolded = UiPrefs.automationFolded
    var scaleView by rememberSaveable { mutableStateOf(ScaleView.Dim) }
    // Long clips are paged two bars at a time, as the drum grid is paged one.
    // More than two bars across a phone leaves notes too narrow to grab.
    var page by rememberSaveable(trackIndex, sceneId) { mutableStateOf(0) }

    // The scale lives in a Scale eventor; the chip and its dialog are only a
    // shortcut to the one eventor worth reaching while playing.
    // One slot each, fixed, left to right as the chips are: chord builds the
    // notes, scale corrects them, arp sequences what comes out. Before this
    // the scale took whichever slot was free, which was fine while two of
    // three could run at once and is not now that all three can.
    fun scaleSlot(): Int = EV_SCALE

    fun currentScale(): ScaleSetting {
        val ev = track.eventorAt(scaleSlot())
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
            (if (t.eventorAt(slot).type == "Scale") t else t.withEventor(slot, "Scale"))
                .withEventorParam(slot, "key", s.key / 11f)
                .withEventorParam(slot, "scale", s.scale / 32f)
                .withEventorParam(slot, "mode", if (s.degree) 1f else 0f)
                .withEventorParam(slot, "snap", s.snap / 2f)
                .withEventorBypass(slot, !s.on)
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

    // Two bars at a time in the roll, one in the step views: any more and the
    // notes are too narrow to grab. The count of pages follows from that.
    // Sideways: the same test ui/Cutout.kt uses, which stays right in
    // multi-window and on a fold where the orientation constant does not.
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    // Fewer rows sideways, because a row shorter than about 11dp loses its
    // name and a nameless roll is worth less than a shorter one.
    val rows = if (landscape) ROWS_LAND else ROWS
    val pageBars = if (steps) 1 else if (landscape) PAGE_BARS_LAND else PAGE_BARS
    val pages = ((clip.bars + pageBars - 1) / pageBars).coerceAtLeast(1)
    page = page.coerceIn(0, pages - 1)
    val pageTicks = pageBars * ticksPerBar
    val firstTick = page * pageTicks
    // While playing, follow the playhead onto its own page rather than
    // leaving the editor staring at a bar that is not sounding.
    val playheadPage = if (playhead != null && clipLen > 0) ((playhead % clipLen) / pageTicks).toInt() else -1
    LaunchedEffect(playheadPage, playing) {
        if (playing && playheadPage in 0 until pages) page = playheadPage
    }


    fun preview(pitch: Int) {
        NativeEngine.noteOn(trackIndex, pitch, 100)
        scope.launch { delay(120); NativeEngine.noteOff(trackIndex, pitch) }
    }

    // `modifier` carries the Scaffold's system-bar padding; without it the
    // footer sits under the navigation bar and its taps become Back.
    Column(modifier.fillMaxSize().background(Acid.colors.bg)) {
        // Header: back · track · scene · octave. It lays itself out around
        // the camera hole rather than below it, so on a phone with a cutout
        // this row costs no height at all - see ui/Cutout.kt.
        CutoutRow(
            Modifier.fillMaxWidth(),
            // Thin, because the buttons now fill the band: 2 + 44 + 2 is the
            // 6 + 40 + 2 it was, so a bigger target costs the roll nothing.
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            spacing = 2.dp,
        ) {
            HeaderButton("◀") { onBack() }
            // A frozen clip is playing audio, so nothing edited here is
            // heard until it is thawed - which this does, since the alarm
            // and the way out belong in the same place.
            if (clip.frozen != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val stale = com.rm.acidulous.model.Freeze.stale(song, sceneId, clip)
                HeaderTextButton(
                    "\u2744\uFE0E",
                    color = if (stale) Acid.colors.accent else Acid.colors.teal,
                ) {
                    com.rm.acidulous.model.Freeze.discard(context, song, com.rm.acidulous.model.Freeze.Target(trackIndex, sceneId))
                    editor.editClip(trackIndex, sceneId) { it.copy(frozen = null) }
                }
            }
            Text(
                "${track.name} · ${scene.name} · ${clip.bars}b · ${clip.notes.size}n" +
                    (if (clip.automation.isEmpty()) "" else " · ${clip.automation.values.sumOf { it.points.size }}a"),
                color = Acid.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.flexible().padding(horizontal = 4.dp), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Paging lives here rather than in a row of its own: a whole row of
            // chrome to show one number costs more height than a phone has to
            // spare, and the header already has the two buttons it belongs with.
            if (pages > 1) {
                HeaderButton("◀") { page = (page - 1 + pages) % pages }
                Text(
                    "${page + 1}/$pages", color = Acid.colors.accent, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                )
                HeaderButton("▶") { page = (page + 1) % pages }
            }
            LoadMeter()
            // Only the roll scrolls by octave; the step views have fixed rows.
            if (!steps) {
                HeaderButton("▲") { lowestPitch = (lowestPitch + 12).coerceAtMost(127 - rows) }
                HeaderButton("▼") { lowestPitch = (lowestPitch - 12).coerceAtLeast(0) }
            }
        }
        // The editor is one stack in portrait and two panes in landscape. The
        // pieces are the same either way; only the arrangement differs, so
        // each is written once here and placed below.
        var octave by rememberSaveable(trackIndex) { mutableStateOf(3) }
        val gridSlot: @Composable ColumnScope.() -> Unit = {
        if (steps && kind == MachineKind.Drums) DrumGrid(
            clip = clip,
            ticksPerBar = ticksPerBar,
            voices = voices,
            playheadTick = playhead,
            barIndex = page,
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
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) else PianoRoll(
            clip = clip,
            ticksPerBar = ticksPerBar,
            mode = mode,
            selection = selection,
            playheadTick = playhead,
            lowestPitch = lowestPitch,
            rows = rows,
            scalePitchClasses = Scales.activeFor(track),
            noteSpelling = Scales.spellingFor(track),
            scaleView = scaleView,
            firstTick = firstTick,
            visibleTicks = pageTicks,
            onCycleScaleView = {
                // Dim and fit need a scale to dim or fit to, so before one is
                // set the corner does the only useful thing: asks for one.
                if (Scales.activeFor(track) == null) scaleDialog = true
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
        }
        val automationSlot: @Composable () -> Unit = {
        // Automation: the reference sequencer's parameter strip under the notes.
        AutomationStrip(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            firstTick = firstTick,
            visibleTicks = pageTicks,
            laneKeys = laneKeys,
            nameOf = { com.rm.acidulous.model.laneLabel(track, it) },
            shortOf = { com.rm.acidulous.model.laneShortLabel(track, it) },
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
            collapsed = autoFolded,
            onToggleCollapse = { UiPrefs.foldAutomation(!autoFolded) },
            modifier = Modifier.fillMaxWidth().height(if (autoFolded) 24.dp else 88.dp).padding(top = 4.dp),
        )
        }
        val panelSlot: @Composable () -> Unit = {
        // The machine's face: knobs go to the engine as gestures and into the document as undo steps.
        // Or, behind the fx toggle, the track's two insert slots.
        if (panel == 1) SlotsPanel(SlotKind.Effects, track, trackIndex, editor, Modifier.fillMaxWidth().padding(top = 4.dp))
        else if (panel == 2) MixerPanel(song, editor, rackPeaks, masterPeak, clickOn, onClick, Modifier.fillMaxWidth().padding(top = 4.dp))
        else MachinePanel(
            track, trackIndex, editor, patchNames, onSavePatch, onLoadPatch,
            factoryPatchNames = factoryPatchNames, userPatchNames = userPatchNames, onDeletePatch = onDeletePatch,
            onImportSoundFont = { onImportSoundFont(trackIndex) },
            onPickPreset = { onPickPreset(trackIndex) },
            onImportZoneSamples = { onImportZoneSamples(trackIndex) },
            selectedPad = selectedPad,
            onImportSample = { pad -> onImportSample(trackIndex, pad) },
            onImportOneSample = { onImportOneSample(trackIndex) },
            onClearSample = { pad -> editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), null) } },
            onAssignSample = { pad, rel ->
                editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), rel) }
            },
            onOpenPatch = onOpenPatch,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        }
        // The performance controls live with the keys now: a mod wheel where
        // a mod wheel goes, a bend wheel on the other side, and pressure,
        // the scale and the octave on one strip above them.
        var mod by rememberSaveable(trackIndex) { mutableStateOf(0f) }
        var pressure by remember(trackIndex) { mutableStateOf(0f) }
        var bend by remember(trackIndex) { mutableStateOf(0.5f) }
        val touchable = MachineUi.usesPerformance(track.machine.type)
        LaunchedEffect(trackIndex) { NativeEngine.controlChange(trackIndex, 1, (mod * 127f).toInt()) }

        val keysSlot: @Composable (Dp) -> Unit = { height ->
        if (kind == MachineKind.Drums) DrumPads(trackIndex, voices, selectedPad, { selectedPad = it }, Modifier.fillMaxWidth().height(height - 6.dp).padding(top = 6.dp))
        else Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().height(26.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Equal weights either side so the scale sits on the row's
                // centre line, wherever the stepper's own width lands. The
                // chip is measured first, as the one unweighted child, so it
                // takes only what its text needs.
                if (touchable) {
                    TouchWheel(
                        value = pressure, accent = Acid.colors.pink, vertical = false,
                        springBackTo = 0f, label = "prs",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { v -> pressure = v; NativeEngine.channelPressure(trackIndex, (v * 127f).toInt()) }
                } else {
                    Box(Modifier.weight(1f))
                }
                // The eventors sit either side of the scale chip because
                // they do the same job: they are what happens to a note
                // between playing it and hearing it. A tap says whether one
                // is running; holding opens it. They used to be a button at
                // the foot of the screen that swapped the whole lower pane,
                // which is a long way to go to find out if the arp is on.
                EventorChip("Chord", EV_CHORD, track, trackIndex, editor) { eventorSlot = EV_CHORD }
                ScaleChip(
                    label = Scales.labelFor(track),
                    onToggle = { applyScale(currentScale().let { it.copy(on = !it.on) }) },
                    onOpen = { scaleDialog = true },
                    vertical = false,
                    // A fixed width, not a minimum: the label goes from
                    // "scale" to "C Ionian (Major)" and back, and a chip
                    // that grew by half its width each time would shove the
                    // two eventor chips sideways every time it was touched.
                    modifier = Modifier.width(112.dp).fillMaxHeight(),
                )
                EventorChip("Arp", EV_ARP, track, trackIndex, editor) { eventorSlot = EV_ARP }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    OctaveStepper(octave, { octave = it }, Modifier.fillMaxHeight())
                }
            }
            Row(
                Modifier.fillMaxWidth().height(height - 30.dp).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TouchWheel(
                    value = mod, accent = Acid.colors.accent, vertical = true, label = null,
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) { v -> mod = v; NativeEngine.controlChange(trackIndex, 1, (v * 127f).toInt()) }
                PianoKeys(
                    trackIndex, Scales.activeFor(track), Scales.rootFor(track), octave,
                    Modifier.weight(1f).fillMaxHeight(),
                    noteSpelling = Scales.spellingFor(track),
                )
                // Bend springs back, so it is the one wheel you can let go of
                // in a hurry and know where it landed.
                TouchWheel(
                    value = bend, accent = Acid.colors.teal, vertical = true,
                    springBackTo = 0.5f, centreMark = true, label = null,
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) { v ->
                    bend = v
                    val value14 = ((v * 2f - 1f) * 8192f + 8192f).toInt().coerceIn(0, 16383)
                    NativeEngine.midiEvent(trackIndex, 0xE0, value14 and 0x7f, (value14 shr 7) and 0x7f)
                }
            }
        }
        }
        val footerSlot: @Composable () -> Unit = {
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
                Text("fx", color = if (panel == 1) Acid.colors.accent else Color.Unspecified, fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { panel = if (panel == 2) 0 else 2 }) {
                Text("mix", color = if (panel == 2) Acid.colors.accent else Color.Unspecified, fontSize = 12.sp)
            }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { selection = emptySet(); editor.undo(trackIndex) }, enabled = editor.canUndo(trackIndex)) { Text("↶", fontSize = 12.sp) }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { selection = emptySet(); editor.redo(trackIndex) }, enabled = editor.canRedo(trackIndex)) { Text("↷", fontSize = 12.sp) }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = {
                if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay(song.scenes.indexOf(scene))
            }) { Text(if (playing) "■" else "▶", fontSize = 12.sp) }
            OutlinedButton(modifier = Modifier.weight(1f).defaultMinSize(minWidth = 1.dp), contentPadding = PaddingValues(0.dp), onClick = { onArm(!armed) }) {
                Text(if (armed) "●" else "○", color = if (armed) Acid.colors.red else Color.Unspecified, fontSize = 12.sp)
            }
            Text(
                if (selection.isEmpty()) "" else "${selection.size} sel",
                color = Acid.colors.textMid, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                softWrap = false, maxLines = 1,
            )
        }
        }

        val pad = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
        if (landscape) {
            // Sideways the grid is the point: it takes the whole left side and
            // the full height, and everything that was competing with it for
            // that height stands in a column of its own.
            Row(Modifier.fillMaxWidth().weight(1f).then(pad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    gridSlot()
                    automationSlot()
                    keysSlot(if (kind == MachineKind.Drums) PADS_H_LAND else KEYS_H_LAND)
                }
                Column(Modifier.width(CONTROL_W).fillMaxHeight()) {
                    // The panel is the tall one, so it is what scrolls; the
                    // transport stays put, because stop should never be
                    // somewhere you have to scroll to.
                    Column(Modifier.weight(1f).verticalScrollWithBar(rememberScrollState())) { panelSlot() }
                    footerSlot()
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).then(pad)) {
                gridSlot()
                automationSlot()
                panelSlot()
                keysSlot(if (kind == MachineKind.Drums) PADS_H else KEYS_H)
                footerSlot()
            }
        }
    }

    if (eventorSlot >= 0) {
        SlotDialog(
            SlotKind.Eventors, track, trackIndex, eventorSlot, editor,
            fixedType = if (eventorSlot == EV_CHORD) "Chord" else "Arp",
        ) { eventorSlot = -1 }
    }

    if (scaleDialog) {
        ScaleDialog(
            current = currentScale(),
            onDismiss = { scaleDialog = false },
            onApply = { applyScale(it); scaleDialog = false },
        )
    }
}

// Sixteen rows rather than two full octaves: tall enough that every row can
// carry its name and be hit with a finger, which matters more on a phone
// than seeing the whole range at once. The arrows move the window.
private const val ROWS = 16
private const val ROWS_LAND = 12
private const val PAGE_BARS = 2

// Sideways the roll is about twice as wide, so it shows twice as much clip,
// and the pieces that share the screen with it move into a column of their
// own. The keyboard loses a little height there: it takes whatever the row
// gives it and scales, so the roll gets the difference.
private const val PAGE_BARS_LAND = 4
private val CONTROL_W = 300.dp
private val KEYS_H = 104.dp
private val PADS_H = 72.dp
private val KEYS_H_LAND = 92.dp
private val PADS_H_LAND = 60.dp

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
 * One eventor, as a chip that knows what it is.
 *
 * There are three eventors and three controls, so nothing is ever chosen
 * from a list: chord on the left, scale in the middle, arp on the right,
 * each pinned to its own slot. A tap switches it on or off, a long press
 * opens it - the grammar the scale chip between them already uses.
 *
 * Switching is a *parameter*: bypass is a pseudo-parameter on the eventor
 * unit, so a tap saves, undoes and automates like anything else, and the
 * flag goes straight to the running eventor as well as into the document.
 * Note the inversion - the field is `bypass`, so lit means `bypass == false`.
 * An empty slot is filled on the first tap rather than asking.
 */
@Composable
private fun EventorChip(
    type: String,
    slot: Int,
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    onOpen: () -> Unit,
) {
    val ev = track.eventorAt(slot)
    val loaded = ev.type == type
    SlotChip(
        text = type.lowercase(),
        on = loaded && !ev.bypass,
        onToggle = {
            if (!loaded) {
                // Nothing there yet: the first tap is what puts it there,
                // switched on, because that is plainly what was meant.
                editor.edit(trackIndex) { t -> t.withEventor(slot, type).withEventorBypass(slot, false) }
                NativeEngine.setParam(trackIndex, eventorUnit(slot), "bypass", 0f, record = true)
            } else {
                val bypass = !ev.bypass
                editor.edit(trackIndex) { t -> t.withEventorBypass(slot, bypass) }
                NativeEngine.setParam(trackIndex, eventorUnit(slot), "bypass", if (bypass) 1f else 0f, record = true)
            }
        },
        onOpen = {
            // The selector needs something to select on, so hold fills an
            // empty slot too - bypassed, because holding is "let me look",
            // not "turn it on".
            if (!loaded) {
                editor.edit(trackIndex) { t -> t.withEventor(slot, type).withEventorBypass(slot, true) }
            }
            onOpen()
        },
        vertical = false,
        modifier = Modifier.widthIn(min = 52.dp).fillMaxHeight(),
    )
}
