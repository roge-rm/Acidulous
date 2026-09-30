package com.rm.acidulous.ui

import com.rm.acidulous.io.*

import com.rm.acidulous.util.IO

import com.rm.acidulous.util.format

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.model.Zone
import com.rm.acidulous.model.Zones
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.withPatch
import com.rm.acidulous.model.samplesInUse
import com.rm.acidulous.model.BIAS_LANES
import com.rm.acidulous.model.bpmOf
import com.rm.acidulous.model.followsTempo
import com.rm.acidulous.model.takeForWholeFile
import com.rm.acidulous.model.withTake
import com.rm.acidulous.model.withSetting
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.DrawbarBlack
import com.rm.acidulous.ui.theme.DrawbarBrown
import com.rm.acidulous.ui.theme.DrawbarWhite
import com.rm.acidulous.res.*

/**
 * The machine panel in the Edit screen.
 *
 * Every machine uses the same layout: one scrolling row of titled [Group]
 * cards, knobs and switches from the shared helpers below, three colours with
 * fixed meanings, and a [SectionChips] row only when a machine has more groups
 * than fit in one row. Follow that when adding a machine.
 *
 * Knob values live in three places that must agree: the engine (live,
 * smoothed), the document (saved, on the track) and the panel. A turn goes to
 * the engine at once as a user gesture (recordable) and into the document as
 * one undo step. Between turns the panel follows the engine, so lanes move the
 * knobs.
 */
@Composable
fun MachinePanel(
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    patchNames: () -> List<String>,
    onSavePatch: (String) -> Unit,
    onLoadPatch: (String) -> Patch?,
    factoryPatchNames: () -> List<com.rm.acidulous.model.Patch> = { emptyList() },
    userPatchNames: () -> List<String> = { emptyList() },
    onDeletePatch: (String) -> Unit = {},
    onImportSoundFont: () -> Unit = {},
    onPickPreset: () -> Unit = {},
    onImportZoneSamples: () -> Unit = {},
    selectedPad: Int = 0,
    onImportSample: (pad: Int) -> Unit = {},
    onImportKit: (pad: Int) -> Unit = {},
    onImportSlice: () -> Unit = {},
    onOpenSample: (pad: Int) -> Unit = {},
    onClearSample: (pad: Int) -> Unit = {},
    /** Clears every pad and the slice source at once, see the clear action. */
    onClearKit: () -> Unit = {},
    /** A sample already in the app's own folder, chosen instead of imported. */
    onAssignSample: (pad: Int, relative: String) -> Unit = { _, _ -> },
    /** Nexus has its graph on a separate screen. */
    onOpenPatch: () -> Unit = {},
    /** Pollen holds one sample of its own, under the plain key. */
    onImportOneSample: () -> Unit = {},
    /**
     * Which cell is open, for the tape machine, whose material is in the clips.
     *
     * Every other panel is about the machine and the same for any cell. A
     * tape's takes belong to the clip, so its panel needs to know the scene.
     * Empty for every other machine.
     */
    sceneId: String = "",
    /**
     * Which half to draw.
     *
     * Upright the header and the cards are drawn together. In landscape the
     * header runs down the left edge and the cards are on the right with the
     * roll between them, so each half is placed and drawn separately.
     */
    bar: Boolean = true,
    body: Boolean = true,
    /** The header reads downwards and the cards stack. */
    vertical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val type = track.machine.type
    // Folded, the panel is just its title row and the roll gets the rest. Kept
    // in UiPrefs because the two halves can't share a flag that one of them
    // owns.
    val minimized = UiPrefs.panelFolded
    val info = remember(type) { NativeEngine.machineParamInfo(type) }
    val binding = rememberParamBinding(trackIndex, type, info, editor)

    androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides vertical) {
    Column(modifier.background(Acid.colors.panel).padding(6.dp)) {
        val loadPatch: (String) -> Unit = { name ->
            onLoadPatch(name)?.let { patch ->
                // What this machine keeps across a patch change, see
                // MachineUi.patchKeeps. Empty for everything but Bias.
                val keep = MachineUi.patchKeeps(type)
                val params = if (keep.isEmpty()) patch.params
                             else patch.params + track.machine.params.filterKeys { it in keep }
                editor.edit(trackIndex) { t ->
                    var next = t.withPatch(params)
                    for ((k, v) in patch.settings) next = next.withSetting(k, v)
                    // Set last, so a patch that happens to carry these keys
                    // can't rename itself. Stored in settings so the name
                    // survives a reopen, the arrows know where they are in the
                    // list, and the edited star has something to compare
                    // against.
                    //
                    // The stamp comes from next, not patch.params, since
                    // withPatch replaces the whole map and what the machine now
                    // holds is what counts.
                    next.withSetting(kPatchName, name).withSetting(kPatchStamp, stampOf(next.machine.params))
                }
                binding.applyAll(params)
            }
        }
        // Saving under a name makes the machine that patch: same name, same
        // knobs, no star until a knob is turned.
        val savePatch: (String) -> Unit = { name ->
            onSavePatch(name)
            editor.edit(trackIndex) { t ->
                t.withSetting(kPatchName, name).withSetting(kPatchStamp, stampOf(t.machine.params))
            }
        }
        // Show when the patch has been edited. Only parameters count, loading
        // samples onto pads doesn't.
        //
        // Remembered against the params map, which is a new object on every
        // edit, since the panel recomposes on every frame of a knob drag and
        // this walks every parameter.
        val stamp = track.machine.settings[kPatchStamp]
        val nowStamp = remember(track.machine.params) { stampOf(track.machine.params) }
        if (bar) PatchBar(
            type, patchNames, savePatch, loadPatch, factoryPatchNames, userPatchNames, onDeletePatch,
            current = track.machine.settings[kPatchName],
            edited = stamp != null && stamp != nowStamp,
            minimized = minimized, onToggleMinimized = { UiPrefs.foldPanel(!minimized) },
            vertical = vertical,
        )
        if (body && !minimized) when (type) {
            "Reflux" -> RefluxPanel(binding)
            "Hexbeat" -> HexbeatPanel(binding)
            "Genesis" -> GenesisPanel(binding)
            "Resonance" -> ResonancePanel(binding, selectedPad)
            "Dice" -> DicePanel(binding, track, trackIndex, editor, selectedPad, onImportOneSample)
            "Molt" -> MoltPanel(binding, track, trackIndex, editor, onImportOneSample)
            "Diction" -> DictionPanel(binding, track, trackIndex, editor)
            "Trinity" -> TrinityPanel(binding)
            "Ratio" -> RatioPanel(binding)
            "Manual" -> ManualPanel(binding)
            "Cipher" -> CipherPanel(binding)
            "Cumulus" -> CumulusPanel(binding)
            "Formulate" -> FormulatePanel(binding, track, trackIndex, editor)
            "Filament" -> FilamentPanel(binding)
            "Brazen" -> BrazenPanel(binding)
            "Timber" -> TimberPanel(binding)
            "Nexus" -> NexusPanel(binding, track, onOpenPatch)
            "Pollen" -> PollenPanel(binding, track, trackIndex, editor, onImportOneSample)
            "Bias" -> BiasPanel(binding, track, trackIndex, sceneId, editor)
            "Mosaic" -> MosaicPanel(binding, track, trackIndex, editor, onImportSoundFont, onPickPreset, onImportZoneSamples)
            "Forage" -> ForagePanel(
                binding, track, selectedPad, onImportSample, onClearSample, onAssignSample,
                onImportKit, onImportSlice, onOpenSample, onClearKit,
                inUse = editor.song.samplesInUse(), editor = editor,
                // How many pads the slice covers, so the pads and grid can show
                // which are playing a piece of it.
                onSliceApplied = { count ->
                    editor.edit(trackIndex) { t -> t.withSetting("slice_count", count.toString()) }
                },
            )
            else -> GenericPanel(binding)
        }
    }
    }
}

/** Live values and the plumbing to change them. */
class ParamBinding(
    val trackIndex: Int,
    val info: List<ParamInfo>,
    private val editor: SongEditor,
    private val values: androidx.compose.runtime.MutableState<Map<String, Float>>,
    private val dragging: androidx.compose.runtime.MutableState<String?>,
    /**
     * Every control's value when this panel opened, or null until the first
     * poll has returned. See [reset].
     */
    private val opened: androidx.compose.runtime.MutableState<Map<String, Float>?>,
    /** Which unit on the rack the names address: "machine", "effect1", "effect2". */
    val unit: String = "machine",
    /** How a value lands in the document. */
    private val apply: (Track, String, Float) -> Track = { t, n, v -> t.withParam(n, v) },
) {
    fun value(name: String): Float {
        val knob = values.value[name] ?: info.firstOrNull { it.name == name }?.defaultNormalized ?: 0f
        if (!LockEdit.on(trackIndex)) return knob
        // Locking: the knob shows the selected step's lock, or its own value
        // where the step has none, which is what it would play.
        return com.rm.acidulous.model.Locks.at(
            LockEdit.lanes[com.rm.acidulous.model.laneKey(unit, name)], LockEdit.spans.first().first, LockEdit.clipTicks,
        )?.value ?: knob
    }

    /**
     * Writes a lock on the selected steps instead of moving the knob, and
     * returns whether it did. A parameter with an automation curve refuses,
     * since the two would fight, and its knob stays put.
     */
    private fun lock(name: String, v: Float?, gesture: Boolean): Boolean {
        if (!LockEdit.on(trackIndex)) return false
        val key = com.rm.acidulous.model.laneKey(unit, name)
        if (com.rm.acidulous.model.Locks.isDrawn(LockEdit.lanes[key])) return true
        val spans = LockEdit.spans
        val ticks = LockEdit.clipTicks
        val f: (com.rm.acidulous.model.Clip) -> com.rm.acidulous.model.Clip = { c ->
            val was = c.automation[key]
            c.withLane(
                key,
                if (v == null) com.rm.acidulous.model.Locks.clear(was, spans, ticks)
                else com.rm.acidulous.model.Locks.set(was, spans, v, ticks),
            )
        }
        if (gesture) editor.updateGestureClip(LockEdit.sceneId, f = f) else editor.editClip(trackIndex, LockEdit.sceneId, f = f)
        return true
    }
    fun display(name: String): String = info.firstOrNull { it.name == name }?.format(value(name)) ?: ""
    fun infoOf(name: String): ParamInfo? = info.firstOrNull { it.name == name }

    fun start(name: String) { dragging.value = name; editor.beginGesture(trackIndex) }
    fun change(name: String, v: Float) {
        if (lock(name, v, gesture = true)) return
        values.value = values.value + (name to v)
        NativeEngine.setParam(trackIndex, unit, name, v, record = true)
        editor.updateGesture { t -> apply(t, name, v) }
    }
    fun end() { dragging.value = null; editor.endGesture() }

    /** A tap on a stepped control: one undo step, no gesture. */
    fun set(name: String, v: Float) {
        if (lock(name, v, gesture = false)) return
        values.value = values.value + (name to v)
        NativeEngine.setParam(trackIndex, unit, name, v, record = true)
        editor.edit(trackIndex) { t -> apply(t, name, v) }
    }

    /**
     * A batch, as one undo step and one autosave.
     *
     * Slicing writes 26 parameters at once and levelling 13. Through set that
     * would be 26 undo entries for one button.
     */
    fun setMany(batch: Map<String, Float>) {
        if (batch.isEmpty()) return
        values.value = values.value + batch
        for ((n, v) in batch) NativeEngine.setParam(trackIndex, unit, n, v, record = true)
        editor.edit(trackIndex) { t ->
            var next = t
            for ((n, v) in batch) next = apply(next, n, v)
            next
        }
    }

    fun applyAll(params: Map<String, Float>) {
        val full = info.associate { it.name to (params[it.name] ?: it.defaultNormalized) }
        values.value = full
        for ((n, v) in full) NativeEngine.setParam(trackIndex, unit, n, v, record = false)
    }

    /**
     * Put everything back to how it was when the panel opened.
     *
     * This is a window's Cancel. The controls write live so you can hear them
     * as you turn, so there's nothing for OK to apply. Cancel resets everything
     * to the long-press baseline in one undo step.
     */
    fun resetAll() {
        val was = opened.value ?: return
        val changed = was.filter { (n, v) -> value(n) != v }
        if (changed.isNotEmpty()) setMany(changed)
    }

    /**
     * Put one control back where it was when the panel opened, not to its
     * factory default: back means how it sounded when you came in here.
     * Reopening the panel takes a new reading.
     *
     * Returns false when there's nothing to go back to, which only happens
     * before the first poll has answered.
     */
    fun reset(name: String): Boolean {
        // Held while locking: the selected steps lose their lock.
        if (lock(name, null, gesture = false)) return true
        val was = opened.value?.get(name) ?: return false
        if (was == value(name)) return true // already there, a no-op, not a failure
        set(name, was)
        return true
    }

    /** Whether this control has moved since the panel opened. */
    fun moved(name: String): Boolean {
        val was = opened.value?.get(name) ?: return false
        return was != value(name)
    }

    val draggingName: String? get() = dragging.value
}

@Composable
fun rememberParamBinding(
    trackIndex: Int, type: String, info: List<ParamInfo>, editor: SongEditor,
    unit: String = "machine",
    apply: (Track, String, Float) -> Track = { t, n, v -> t.withParam(n, v) },
): ParamBinding {
    // Read from the engine now, not left at the defaults for the poll below.
    // The first frame showed every knob at its default and they jumped to
    // their real values a few frames later.
    val values = remember(trackIndex, type, unit) {
        mutableStateOf(info.associate { p ->
            p.name to (NativeEngine.paramNormalized(trackIndex, unit, p.name).takeIf { it >= 0f } ?: p.defaultNormalized)
        })
    }
    val dragging = remember { mutableStateOf<String?>(null) }
    /**
     * Every value when this panel opened, for a long press to reset to. Keyed
     * on the binding so reopening a panel takes a fresh reading.
     */
    val opened = remember(trackIndex, type, unit) { mutableStateOf<Map<String, Float>?>(values.value) }
    val binding = remember(trackIndex, type, unit) {
        ParamBinding(trackIndex, info, editor, values, dragging, opened, unit, apply)
    }
    LaunchedEffect(trackIndex, type, unit) {
        while (true) {
            val d = dragging.value
            values.value = info.associate { p ->
                p.name to (if (p.name == d) values.value[p.name] ?: p.defaultNormalized
                else NativeEngine.paramNormalized(trackIndex, unit, p.name).takeIf { it >= 0f } ?: values.value[p.name] ?: p.defaultNormalized)
            }
            if (opened.value == null) opened.value = values.value
            delay(100)
        }
    }
    return binding
}

/**
 * A small tappable mark in the patch bar. Not a TextButton, since Material
 * makes those 58 dp wide regardless of content, and this row has to fit a
 * machine name, a patch name and four marks on a phone.
 */
private val BarIconV = 16.dp

/**
 * The gap between marks in the landscape header column. A rotated BarIcon is
 * only 16 dp tall, so the marks need a finger's worth of space between them.
 * The two names are the only exception, 3 dp apart so they read as a pair.
 * Upright the marks are 24 dp wide and there's room, so no gap is needed.
 */
private val SideMarkGap = 10.dp

@Composable
private fun BarIcon(glyph: String, tint: Color, vertical: Boolean = false, said: String = glyph, onClick: () -> Unit) {
    Box(
        // In landscape the box turns too (28 x 16), losing a little length so
        // five marks and both names fit in the column.
        Modifier.size(width = if (vertical) 28.dp else 24.dp, height = if (vertical) BarIconV else 28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .button(said),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PatchBar(
    type: String, patchNames: () -> List<String>, onSave: (String) -> Unit, onLoad: (String) -> Unit,
    factoryPatches: () -> List<com.rm.acidulous.model.Patch>, userNames: () -> List<String>,
    onDelete: (String) -> Unit, current: String?, edited: Boolean,
    minimized: Boolean, onToggleMinimized: () -> Unit,
    /** Down the left edge instead of across the top. */
    vertical: Boolean = false,
) {
    /**
     * Step one patch through the list without opening it.
     *
     * The names are read here instead of held, since reading them touches the
     * disk and the bar recomposes with every knob. With nothing loaded, forward
     * starts at the first and back at the last, and it wraps, so holding an
     * arrow goes through the whole bank.
     */
    fun step(by: Int) {
        val names = patchNames()
        if (names.isEmpty()) return
        val at = names.indexOf(current)
        val next = if (at < 0) {
            if (by > 0) 0 else names.size - 1
        } else {
            ((at + by) % names.size + names.size) % names.size
        }
        onLoad(names[next])
    }
    // The left half takes the weight so it's what shrinks. A Spacer(weight(1f))
    // before the right-hand group only works with slack, and a phone has none
    // here, so the arrows moved with the length of the patch name.
    //
    // In landscape it's the same list read upwards, with the give still on the
    // names so the arrows don't move. Labels in the column are rotated
    // anticlockwise (start of word at the bottom), so the row is laid out with
    // its left end at the bottom: machine, patch, save, browse, the two steps,
    // the fold. The fold points at the edge: ◂ puts it away, ▸ brings it back.
    if (vertical) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The fold sits at the head of the column like at the end of the
            // row upright, but it puts away the cards on the right of the roll,
            // not this strip, so it points that way: ▸ hides them, ◂ brings
            // them back. The strip itself always stays, which is why it can
            // keep the mark.
            BarIcon(if (minimized) "\u25C2" else "\u25B8", Acid.colors.textMid, vertical = true, said = stringResource(if (minimized) Res.string.a11y_unfold else Res.string.a11y_fold)) {
                onToggleMinimized()
            }
            Spacer(Modifier.height(SideMarkGap))
            BarIcon("\u203A", Acid.colors.accent, vertical = true, said = stringResource(Res.string.a11y_next_patch)) { step(1) }
            Spacer(Modifier.height(SideMarkGap))
            BarIcon("\u2039", Acid.colors.accent, vertical = true, said = stringResource(Res.string.a11y_prev_patch)) { step(-1) }
            // The names sit together with the slack above them, like upright
            // where the pair shares the weighted half of the row.
            //
            // Each name is as long as its own text. Centred in equal boxes
            // they'd have a gap between them. So the lengths are measured from
            // the text, and only shared out in proportion when both don't fit.
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val style = androidx.compose.ui.text.TextStyle(fontSize = SideNameSp)
            fun want(t: String): Dp = with(density) {
                measurer.measure(t, style, softWrap = false, maxLines = 1).size.width.toDp()
            } + 6.dp
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val forNames = (maxHeight - BarIconV * 2 - SideMarkGap * 2 - 3.dp)
                    .coerceAtLeast(24.dp)
                val wantType = want(type)
                val wantPatch = want(patchLabel(current, edited))
                val shrink = ((forNames - 3.dp) / (wantType + wantPatch)).coerceAtMost(1f)
                val typeH = wantType * shrink
                val patchH = wantPatch * shrink
                // No spacedBy, since the marks need a finger's gap and the
                // names want to sit together, so the gaps are written where
                // they differ.
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    // The picker puts browse over save over the name, which
                    // reads upwards as the upright order. The names carry the
                    // weights so the marks above don't move with the length of
                    // a patch name.
                    PatchPicker(
                        type, patchNames, onSave, onLoad, factoryPatches, userNames, onDelete,
                        current, edited, vertical = true, modifier = Modifier.height(patchH),
                    )
                    Spacer(Modifier.height(3.dp))
                    // Half each, so neither name gets squeezed to a few
                    // characters.
                    SideText(type, Acid.colors.text, SideNameSp, Modifier.height(typeH))
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(type, color = Acid.colors.text, fontSize = 13.sp, maxLines = 1)
            PatchPicker(type, patchNames, onSave, onLoad, factoryPatches, userNames, onDelete, current, edited)
        }
        BarIcon("\u2039", Acid.colors.accent, said = stringResource(Res.string.a11y_prev_patch)) { step(-1) }
        BarIcon("\u203A", Acid.colors.accent, said = stringResource(Res.string.a11y_next_patch)) { step(1) }
        // A gap before the fold since it isn't part of the step arrows, which
        // do something very different.
        Spacer(Modifier.width(18.dp))
        BarIcon(if (minimized) "▴" else "▾", Acid.colors.textMid, said = stringResource(if (minimized) Res.string.a11y_unfold else Res.string.a11y_fold)) { onToggleMinimized() }
    }
}

/**
 * Text size of the two names in the landscape patch column.
 *
 * Smaller than portrait's 11 sp, because the column is only about 80 dp tall
 * for the two names. At 11 or 10 common names got cut off. 9 sp matches the
 * note lane and automation gutters' rotated labels.
 */
private val SideNameSp = 9.sp

/**
 * What the patch button says: its name once it has one, otherwise "patch".
 *
 * Shared because the landscape column needs the word's length before it can
 * decide how much space to give it, and computing it twice would let the box
 * and the text disagree.
 */
@Composable
internal fun patchLabel(current: String?, edited: Boolean): String {
    // Show the patch name once there is one. A star means the sound has been
    // edited since the patch was loaded, and "save as..." is right next to it.
    val shown = current?.ifBlank { null }
    return when {
        shown == null -> stringResource(Res.string.machine_patch)
        edited -> stringResource(Res.string.machine_patch_edited, shown)
        else -> shown
    }
}

/**
 * The three controls that choose a patch: pick one, save this, browse.
 *
 * Pulled out of the machine panel's title row so an effect slot can use it too.
 * Nothing about it is machine-specific ([title] is only the word at the top of
 * the browser window), so effects didn't need their own preset mechanism.
 */
@Composable
internal fun PatchPicker(
    title: String, patchNames: () -> List<String>, onSave: (String) -> Unit, onLoad: (String) -> Unit,
    factoryPatches: () -> List<com.rm.acidulous.model.Patch>, userNames: () -> List<String>,
    onDelete: (String) -> Unit,
    /** The patch showing, if one was chosen. The button shows its name. */
    current: String? = null,
    /** Its knobs have moved since it was loaded. */
    edited: Boolean = false,
    /** Read downwards, in the landscape patch column. */
    vertical: Boolean = false,
    /** Only the vertical form uses this, the row sizes itself. */
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var listRev by remember { mutableStateOf(0) } // bumps after a delete so the browser re-reads
    val label = patchLabel(current, edited)
    if (vertical) {
        // Browse, save, then the name: the upright row's order read from the
        // bottom. See [PatchBar].
        BarIcon("\u2630", Acid.colors.textMid, true, said = stringResource(Res.string.a11y_browse_patches)) { browsing = true }
        Spacer(Modifier.height(SideMarkGap))
        BarIcon("\u21A7", Acid.colors.textMid, true, said = stringResource(Res.string.a11y_save_patch)) { saving = true }
        Spacer(Modifier.height(SideMarkGap))
        // No Material button around it in landscape. One is 58 dp wide
        // regardless of content, which is the whole column.
        Box(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { menu = true },
            // No "▾" after it in landscape, it would cost 10 dp of the name's
            // length.
        ) { SideText(label, Acid.colors.accent, SideNameSp) }
    } else {
        TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(
                stringResource(Res.string.machine_patch_menu, label),
                color = Acid.colors.accent, fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
    // Marks instead of words, since two Material TextButtons take 116 dp before
    // any text, on a row that also has the machine name, a patch name, two step
    // arrows and the fold. Down arrow into a line for save, a list for browse.
    if (!vertical) {
        BarIcon("\u21A7", Acid.colors.textMid, said = stringResource(Res.string.a11y_save_patch)) { saving = true }
        BarIcon("\u2630", Acid.colors.textMid, said = stringResource(Res.string.a11y_browse_patches)) { browsing = true }
    }
    val menuScroll = rememberScrollState()
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        ScaledMenu(menuScroll) {
            for (n in patchNames()) DropdownMenuItem(text = { Text(n, fontSize = 12.sp) }, onClick = { menu = false; onLoad(n) })
        }
    }
    if (saving) TextInputDialog(stringResource(Res.string.machine_patch_name), "", onDismiss = { saving = false }) { name -> onSave(name); saving = false }
    if (browsing) {
        val factory = remember(title) { factoryPatches() }
        val user = remember(title, listRev) { userNames() }
        PatchBrowserDialog(
            machine = title, factory = factory, user = user,
            onLoad = { n -> onLoad(n); browsing = false },
            onDelete = { n -> onDelete(n); listRev++ },
            onDismiss = { browsing = false },
        )
    }
}

private const val kPatchName = com.rm.acidulous.model.PatchMark.NAME
private const val kPatchStamp = com.rm.acidulous.model.PatchMark.STAMP
private fun stampOf(params: Map<String, Float>): String = com.rm.acidulous.model.PatchMark.stampOf(params)

// The panel palette. Teal is the ordinary control, amber marks the knob that
// gives a group its character, pink marks drive and output.
internal val PanelTeal: Color @Composable get() = Acid.colors.teal
internal val PanelAmber: Color @Composable get() = Acid.colors.accent
internal val PanelPink: Color @Composable get() = Acid.colors.pink

/**
 * The lanes in the clip the editor has open, as lane keys ("machine:cutoff").
 *
 * A knob a lane moves won't stay where it's put, so the editor sets this and
 * every [PanelKnob] and [PanelStepKnob] draws ∿ on the dial when its key is in
 * it.
 */
object AutomationMarks {
    var lanes by androidx.compose.runtime.mutableStateOf(emptySet<String>())
    /** The lanes among them that are step locks, marked ◆ instead of ∿. */
    var locks by androidx.compose.runtime.mutableStateOf(emptySet<String>())
}

/**
 * The steps the panel's knobs lock onto. Set by the editor in lock mode with
 * steps selected, empty otherwise.
 *
 * Global state instead of a parameter, like [AutomationMarks], since every knob
 * on every machine and effect panel reads it and they're built in dozens of
 * places that know nothing about the editor.
 */
object LockEdit {
    var trackIndex by androidx.compose.runtime.mutableStateOf(-1)
    var sceneId by androidx.compose.runtime.mutableStateOf("")
    /** Tick spans, from the first to the last tick a lock covers. */
    var spans by androidx.compose.runtime.mutableStateOf(emptyList<IntRange>())
    var clipTicks by androidx.compose.runtime.mutableStateOf(0)
    var lanes by androidx.compose.runtime.mutableStateOf(emptyMap<String, com.rm.acidulous.model.Lane>())

    fun on(track: Int) = spans.isNotEmpty() && trackIndex == track

    fun clear() {
        spans = emptyList()
        trackIndex = -1
    }
}

/** A clip with [key]'s lane replaced, or removed when [lane] is null. */
internal fun com.rm.acidulous.model.Clip.withLane(key: String, lane: com.rm.acidulous.model.Lane?) =
    copy(automation = if (lane == null) automation - key else automation + (key to lane))

@Composable
internal fun PanelKnob(b: ParamBinding, name: String, label: String = name, accent: Color = PanelTeal) {
    val key = com.rm.acidulous.model.laneKey(b.unit, name)
    Knob(
        label = panelWord(label), value = b.value(name), display = b.display(name), accent = accent,
        automated = key in AutomationMarks.lanes && key !in AutomationMarks.locks,
        locked = key in AutomationMarks.locks,
        modifier = Modifier.mappable(MapTargets.param(b.trackIndex, b.unit, name)).then(panelKnobWidth()),
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
        onReset = { b.reset(name) },
    )
}

/**
 * How wide a knob is in a stacked card, so that two fit a line.
 *
 * A fixed width so the dials line up in columns. Upright a knob is as wide as
 * its dial or label, which is fine in a single row. 58 fits the 52 dp dial with
 * a little air and cuts a label at about ten characters, one more than the
 * longest a panel uses.
 */
internal val StackedKnobW = 58.dp

/** How many controls a stacked card puts on a line. */
internal const val StackedPerLine = 2

/**
 * How many a stacked card puts on a line where it's drawn: two in a phone's
 * side column (two knobs wide) and four in a tablet's.
 */
internal val LocalStackedPerLine = androidx.compose.runtime.compositionLocalOf { StackedPerLine }

/** A fixed width when stacked, upright a knob is whatever width it needs. */
@Composable
internal fun panelKnobWidth(): Modifier =
    if (LocalPanelStacked.current) Modifier.width(StackedKnobW) else Modifier

@Composable
internal fun PanelSwitch(b: ParamBinding, name: String, labels: List<String>, label: String = name) {
    val info = b.infoOf(name) ?: return
    val idx = info.map(b.value(name)).toInt().coerceIn(0, labels.size - 1)
    SwitchGrid(
        panelWord(label), panelWords(labels), idx,
        Modifier.mappable(MapTargets.param(b.trackIndex, b.unit, name)),
    ) { i -> b.set(name, if (labels.size > 1) i.toFloat() / (labels.size - 1) else 0f) }
}

/**
 * A switch for a choice that isn't a machine parameter, like the generators'
 * step sizes and drum voices, which sit in cards next to knobs and need to look
 * like the switches beside them. [selected] out of range lights nothing, which
 * makes a one-cell grid a button.
 */
@Composable
internal fun SwitchGrid(
    label: String,
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    /** Cells per row. By default at most two rows. */
    columns: Int = 0,
    /** Which cells can be pressed, all of them when null. */
    enabled: List<Boolean>? = null,
    onPick: (Int) -> Unit,
) {
    val idx = selected
    // At most two rows, one column when there are only two options. A switch
    // sits next to knobs that are taller than it, so a second row is free and
    // halves the width.
    val cols = if (columns > 0) columns else if (labels.size <= 2) 1 else (labels.size + 1) / 2
    Column(
        modifier
            // Fill the row's height, but no more than a control's worth.
            // Without the cap this and Group's IntrinsicSize.Max row ask each
            // other how tall to be and can agree on something absurd (a
            // three-way switch came out 490 dp tall and pushed the roll off
            // screen). The cap is a knob's height.
            .heightIn(max = PanelControlH).fillMaxHeight().together(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Announced as part of each cell's name instead.
        Text(label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.silent())
        // IntrinsicSize.Max, then a weight on every cell: the grid takes the
        // width of its widest row and the weights divide it evenly, so every
        // cell is the size of the longest label and the selected one doesn't
        // move.
        Column(
            Modifier.width(IntrinsicSize.Max).weight(1f)
                .clip(RoundedCornerShape(4.dp)).background(Acid.colors.card),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            labels.chunked(cols).forEachIndexed { row, cells ->
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    cells.forEachIndexed { col, l ->
                        val i = row * cols + col
                        val on = i == idx
                        val live = enabled?.getOrNull(i) ?: true
                        // A switch with no label of its own (a pair of buttons
                        // in a card that already says what they're for) is
                        // announced as its cells alone.
                        val name = if (label.isEmpty()) l else stringResource(Res.string.a11y_named, label, l)
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(if (on) Acid.colors.green else Acid.colors.control)
                                .clickable(enabled = live) { onPick(i) }
                                // Nothing lit means a row of actions, one lit means a
                                // choice.
                                .then(if (idx >= 0) Modifier.choice(name, on) else Modifier.button(name)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                l, color = if (on) Acid.colors.onAccent else if (live) Acid.colors.textMid else Acid.colors.textFaint,
                                fontSize = 10.sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                    // An odd count leaves a gap in the last row. It has to be a
                    // weighted box, or the row divides its width between fewer
                    // cells and the grid comes out ragged.
                    repeat(cols - cells.size) { Box(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

/**
 * Whether the cards stack instead of standing in a row.
 *
 * A composition local instead of a parameter because GroupRow and Group are
 * called from every machine panel and effect face, none of which cares about
 * the screen shape. MachinePanel provides it, these two read it.
 */
internal val LocalPanelStacked = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Stacked cards sized to their controls instead of the line, so small ones can
 * share a line, as in a square phone's window. See [WindowCards].
 */
internal val LocalCardsPacked = androidx.compose.runtime.compositionLocalOf { false }

/**
 * The panel body every machine uses: one horizontally scrolling row of
 * [Group]s. Machines with more groups than fit put a [SectionChips] row above
 * it and show one section at a time.
 */
@Composable
internal fun GroupRow(content: @Composable () -> Unit) {
    // What a screen shows of the row is built first and the rest a frame
    // later, so a machine opens without waiting for cards off the edge.
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val later = remember { LaterCards((widthDp / LaterCardW).toInt() + 1) }
    LaunchedEffect(later) { withFrameNanos { }; later.all = true }
    CompositionLocalProvider(LocalLaterCards provides later) { GroupRowCards(content) }
}

/** Which of a row's cards wait for the second frame. */
private class LaterCards(val first: Int) {
    private var count = 0
    fun next() = count++
    var all by mutableStateOf(false)
}

private val LocalLaterCards = androidx.compose.runtime.compositionLocalOf<LaterCards?> { null }

/** Roughly a card's width, for guessing how many show and holding a waiting card's place. */
private val LaterCardW = 160.dp

@Composable
private fun GroupRowCards(content: @Composable () -> Unit) {
    if (LocalPanelStacked.current) {
        // The cards go down the column and it scrolls that way. Whatever places
        // this must not also scroll vertically. See EditScreen's landscape
        // branch, which passes the height directly.
        Column(
            Modifier.fillMaxWidth().verticalScrollWithBar(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/**
 * A machine's body: its section tabs, then the chosen section's cards.
 *
 * Upright the tabs are a row over a row of cards. In landscape the cards are a
 * column and the tabs run down its left edge, since a full-width row of tabs
 * would cost too much height.
 *
 * Only one section shows at a time, on every screen size. A big screen's column
 * is two knobs wide like a phone's, and the roll gets the rest.
 *
 * [above] is a line shown over a section's cards (Dice's slice number, Mosaic's
 * zone map). [section] is a section's cards.
 */
@Composable
internal fun PanelSections(
    names: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    above: (@Composable (Int) -> Unit)? = null,
    section: @Composable (Int) -> Unit,
) {
    val chosen = selected.coerceIn(0, (names.size - 1).coerceAtLeast(0))
    if (!LocalPanelStacked.current) {
        Column {
            SectionChips(names, chosen, onSelect)
            above?.invoke(chosen)
            GroupRow { section(chosen) }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionChips(names, chosen, onSelect)
        GroupRow { above?.invoke(chosen); section(chosen) }
    }
}

/** Which section is showing. Only machines too big for one row need it. */
@Composable
internal fun SectionChips(
    english: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    // A panel's section names are English and get translated, a window's arrive translated and
    // pass through.
    val labels = panelWords(english)
    if (LocalPanelStacked.current) {
        SectionChipsSide(labels, selected, onSelect)
        return
    }
    // Equal shares until there isn't room for them. In landscape the panel is a
    // ~300 dp column and some machines have eight sections, which would make
    // each chip too narrow to read or hit. Below the floor it switches to
    // SectionChipsScrolling. Measured from the container, not the screen.
    val styled = labels.map { androidx.compose.ui.text.AnnotatedString(it) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / labels.size.coerceAtLeast(1) < kChipFloor) {
            SectionChipsScrolling(labels, selected, onSelect)
        } else {
            SectionChipsStyled(styled, selected, onSelect)
        }
    }
}

/**
 * The same tabs, rotated, down the left edge of the cards.
 *
 * All of them are on screen at once. They share the column's height the way
 * portrait's share its width, and fall back to scrolling by the same rule when
 * a share is too short for a word. Same chip, same colours, same equal shares,
 * with the text read bottom to top via SideText.
 */
@Composable
private fun SectionChipsSide(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.width(SideChipW).fillMaxHeight()) {
        val share = maxHeight / labels.size.coerceAtLeast(1)
        val scrolls = share < kChipFloor
        Column(
            Modifier.fillMaxHeight()
                .then(if (scrolls) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            labels.forEachIndexed { i, l ->
                val on = i == selected
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (scrolls) Modifier.height(kChipFloor) else Modifier.weight(1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) Acid.colors.green else Acid.colors.control)
                        .clickable { onSelect(i) }
                        .choice(l, on, tab = true),
                    contentAlignment = Alignment.Center,
                ) {
                    SideText(
                        l, if (on) Color.White else Acid.colors.textMid, 10.sp,
                        family = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** How wide the rotated tab column is: a 10 sp line and room to hit it. */
private val SideChipW = 22.dp

/**
 * The narrowest a chip can be before the row scrolls instead. Below this a
 * four-letter word at 10 sp gets cut off.
 */
private val kChipFloor = 56.dp

/**
 * The same, for a label with a styled word inside it. A separate name instead
 * of an overload, since both erase to List on the JVM.
 */
@Composable
internal fun SectionChipsStyled(
    labels: List<androidx.compose.ui.text.AnnotatedString>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    if (LocalPanelStacked.current) {
        SectionChipsSide(labels.map { it.text }, selected, onSelect)
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                // Equal shares of the full width, since these are the machine's
                // tabs.
                Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                    .background(if (on) Acid.colors.green else Acid.colors.control)
                    .clickable { onSelect(i) }.choice(l.text, on, tab = true).padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l, color = if (on) Color.White else Acid.colors.textMid, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip, softWrap = false,
                )
            }
        }
    }
}

/**
 * The same chips, sized to their text and scrolling when there are too many.
 *
 * [SectionChipsStyled] gives every chip an equal share, which works for the
 * machine picker's four groups but not for seven or more: in the ~320 dp patch
 * browser, seven families get 43 dp each and "ensemble" needs 48. So these size
 * to their text and the row scrolls, with the usual scrollbar.
 */
@Composable
internal fun SectionChipsScrolling(
    english: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val labels = panelWords(english)
    Row(
        Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (on) Acid.colors.green else Acid.colors.control)
                    .clickable { onSelect(i) }
                    .choice(l, on, tab = true)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l, color = if (on) Color.White else Acid.colors.textMid, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/**
 * A stepped parameter with too many values for [PanelSwitch]: a knob that shows
 * the step's name instead of a number. Four values or fewer go in a switch,
 * more go here.
 */
@Composable
internal fun PanelStepKnob(b: ParamBinding, name: String, labels: List<String>, label: String = name, accent: Color = PanelTeal) {
    val info = b.infoOf(name) ?: return
    Knob(
        label = panelWord(label), value = b.value(name), accent = accent,
        automated = com.rm.acidulous.model.laneKey(b.unit, name).let { it in AutomationMarks.lanes && it !in AutomationMarks.locks },
        locked = com.rm.acidulous.model.laneKey(b.unit, name) in AutomationMarks.locks,
        display = panelWords(labels).getOrElse(info.map(b.value(name)).toInt().coerceIn(0, labels.size - 1)) { "" },
        steps = labels.size,
        modifier = Modifier.mappable(MapTargets.param(b.trackIndex, b.unit, name)).then(panelKnobWidth()),
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
        onReset = { b.reset(name) },
    )
}

/**
 * How tall one control in a panel is: a knob's label, its 52 dp dial and its
 * value, 24 + 52 + 24 at a 9 sp line. Switches are capped to it so a row can't
 * be taller than its controls.
 */
internal val PanelControlH = 100.dp

/**
 * A column of buttons in a group, where knobs would otherwise go.
 *
 * Stacked, they fill the height of a knob and the card is narrow, which suits a
 * row that scrolls sideways. They can't be bare TextButtons: a Group aligns its
 * row to the bottom and sizes to its tallest child, so a TextButton takes
 * whatever width is left and wraps its label one character per line.
 */
@Composable
private fun PanelActions(vararg actions: Triple<String, Color, () -> Unit>) {
    // Built like PanelSwitch since it sits next to one: one card, cells divided
    // by a hairline, each filling its share of the height. Two rows once there
    // are more than two.
    val cols = if (actions.size <= 2) 1 else (actions.size + 1) / 2
    Column(
        // A fixed height, not a filled one. Group sizes its row from its
        // children's intrinsic height, and fillMaxHeight has none, so a group
        // with only these in it (the kit group) collapsed onto the text. A
        // stated height is also the intrinsic one.
        Modifier.height(PanelControlH).width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(4.dp)).background(Acid.colors.card),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        actions.toList().chunked(cols).forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for ((label, tint, onClick) in row) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(Acid.colors.control)
                            .clickable(onClick = onClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = tint, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// Cards made in a loop must each be wrapped in key. Unkeyed, a section of six
// or more cards crashed on its first recomposition ("Boolean cannot be cast to
// MutableState" inside a knob) because Compose mixed up the cards' slots.
internal fun Group(
    title: String,
    /**
     * How many controls per line when the card is stacked.
     *
     * Two for the side panel, which is two knobs wide. A dialog is the width of
     * the screen and fits four.
     */
    perLine: Int = LocalStackedPerLine.current,
    /** Centre the controls in the card instead of packing them to the left. */
    centred: Boolean = false,
    /**
     * The card's colour. The default is the panel's background colour, which is
     * also a window's, so cards in a window need a different one to be visible.
     */
    background: Color = Acid.colors.card,
    content: @Composable () -> Unit,
) {
    val later = LocalLaterCards.current
    val index = remember { later?.next() ?: 0 }
    if (later != null && !later.all && index >= later.first) {
        Spacer(Modifier.size(LaterCardW, 1.dp))
        return
    }
    val stacked = LocalPanelStacked.current
    val packed = stacked && LocalCardsPacked.current
    Column(
        Modifier.then(if (stacked && !packed) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(6.dp)).background(background)
            // Packed (a square phone) uses less vertical padding.
            .padding(horizontal = 6.dp, vertical = if (packed) 4.dp else 6.dp).together(),
    ) {
        // A panel's English title is translated here, a window's card title
        // arrives translated and passes through.
        Text(
            panelWord(title), color = Acid.colors.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            // A card's title is a heading, so TalkBack can jump card to card.
            modifier = Modifier.semantics { heading() },
        )
        // IntrinsicSize.Max so the row knows its tallest control (almost always
        // a knob) and anything that wants can fill that height. Switches do, so
        // their cells line up with the knobs. Taken from the children so it
        // still holds when the text scale changes a knob's label height.
        if (stacked) {
            // In landscape a knob looks exactly like upright, and the card
            // wraps instead: two per line in a scrolling column. Six knobs is
            // three lines.
            //
            // No IntrinsicSize.Max here. A flow row measures each line from its
            // own children, and PanelSwitch still has its own PanelControlH
            // cap.
            FlowRow(
                // Packed, the card may be stretched past its controls to fill a
                // line, and they stay centred.
                if (packed) Modifier.align(Alignment.CenterHorizontally) else Modifier.fillMaxWidth(),
                horizontalArrangement = if (centred) {
                    Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                } else {
                    Arrangement.spacedBy(6.dp)
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = perLine,
            ) { content() }
            return@Column
        }
        Row(
            Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) { content() }
    }
}

/** Reflux: the classic layer left to right, then the open layer. */
@Composable
private fun RefluxPanel(b: ParamBinding) {
    GroupRow {
        Group("osc") { PanelSwitch(b, "wave", listOf("saw", "pulse")); PanelKnob(b, "pw"); PanelKnob(b, "sub"); PanelKnob(b, "tune") }
        Group("filter") { PanelKnob(b, "cutoff", accent = PanelAmber); PanelKnob(b, "resonance", "reso", PanelAmber); PanelKnob(b, "envmod", accent = PanelAmber); PanelKnob(b, "decay", accent = PanelAmber); PanelSwitch(b, "mode", listOf("lp", "bp")) }
        Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "slide"); PanelKnob(b, "velocity", "vel") }
        Group("out") { PanelKnob(b, "drive", accent = PanelPink); PanelKnob(b, "volume") }
    }
}

/** Hexbeat: a group per voice family, in kit order. */
@Composable
private fun HexbeatPanel(b: ParamBinding) {
    val hot = PanelAmber
    GroupRow {
        Group("kick") { PanelKnob(b, "kick_tune", "tune", hot); PanelKnob(b, "kick_decay", "decay"); PanelKnob(b, "kick_punch", "punch"); PanelKnob(b, "kick_level", "level") }
        Group("snare") { PanelKnob(b, "snare_tune", "tune", hot); PanelKnob(b, "snare_decay", "decay"); PanelKnob(b, "snare_snappy", "snappy"); PanelKnob(b, "snare_tone", "tone"); PanelKnob(b, "snare_level", "level") }
        Group("toms") { PanelKnob(b, "tom_lo_tune", "lo", hot); PanelKnob(b, "tom_mid_tune", "mid", hot); PanelKnob(b, "tom_hi_tune", "hi", hot); PanelKnob(b, "tom_decay", "decay"); PanelKnob(b, "tom_level", "level") }
        Group("hats") { PanelKnob(b, "hat_tune", "tune", hot); PanelKnob(b, "hat_closed_decay", "closed"); PanelKnob(b, "hat_open_decay", "open"); PanelKnob(b, "hat_tone", "tone"); PanelKnob(b, "hat_level", "level") }
        Group("cymbals") { PanelKnob(b, "cym_decay", "crash"); PanelKnob(b, "cym_tone", "tone"); PanelKnob(b, "cym_level", "level"); PanelKnob(b, "ride_decay", "ride"); PanelKnob(b, "ride_level", "level") }
        Group("perc") { PanelKnob(b, "clap_decay", "clap"); PanelKnob(b, "clap_tone", "tone"); PanelKnob(b, "clap_level", "level"); PanelKnob(b, "rim_tune", "rim", hot); PanelKnob(b, "rim_level", "level") }
        Group("bell / clave") { PanelKnob(b, "bell_tune", "bell", hot); PanelKnob(b, "bell_decay", "decay"); PanelKnob(b, "bell_level", "level"); PanelKnob(b, "clave_tune", "clave", hot); PanelKnob(b, "clave_level", "level") }
        Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "velocity", "vel"); PanelKnob(b, "volume") }
    }
}

/** Forage: the selected pad's sample and its controls. Tap a pad to select it. */
@Composable
private fun ForagePanel(b: ParamBinding, track: Track, pad: Int, onImport: (Int) -> Unit,
                        onClear: (Int) -> Unit, onAssign: (Int, String) -> Unit,
                        onImportKit: (Int) -> Unit, onImportSlice: () -> Unit,
                        onOpenSample: (Int) -> Unit, onClearKit: () -> Unit,
                        onSliceApplied: (Int) -> Unit, inUse: Set<String>, editor: SongEditor) {
    val p = pad.coerceIn(0, 12)
    fun n(name: String) = "p%02d_%s".format(p, name)
    val rel = track.machine.settings[n("sample")]
    var info by remember(p, rel) { mutableStateOf("") }
    LaunchedEffect(p, rel) {
        while (true) { info = NativeEngine.sampleInfo(b.trackIndex, p); delay(400) }
    }
    // The loudest sample in this pad's file. Files from different sources
    // arrive at different levels, which is what match is for.
    val peak = info.split('|').getOrNull(3)?.toFloatOrNull() ?: 0f
    val peakDb = if (peak > 1e-5f) "%.1f dB".format(20.0 * kotlin.math.log10(peak.toDouble())) else "-"

    /**
     * Set every loaded pad's level so the kit comes out even.
     *
     * Referenced to the median loaded sample. Using the quietest would drag the
     * whole kit down to it, beyond what volume can make up. Using the loudest
     * would push every ratio above one.
     *
     * Against the median the loud half comes down, the quiet half goes up, and
     * the kit keeps its overall level. level goes up to 4 for this, with a
     * ceiling of 1 the loud half would have nowhere to go.
     */
    fun matchLevels() {
        val peaks = (0 until 13).map { i ->
            NativeEngine.sampleInfo(b.trackIndex, i).split('|').getOrNull(3)?.toFloatOrNull() ?: 0f
        }
        val loaded = peaks.filter { it > 1e-5f }.sorted()
        if (loaded.isEmpty()) return
        val median = loaded[loaded.size / 2]
        val batch = mutableMapOf<String, Float>()
        peaks.forEachIndexed { i, pk ->
            if (pk <= 1e-5f) return@forEachIndexed
            val name = "p%02d_level".format(i)
            // Through the parameter's own table, since set wants 0..1 and level
            // has its own units. The range lives in the engine, don't copy it
            // here.
            val def = b.info.firstOrNull { it.name == name } ?: return@forEachIndexed
            batch[name] = def.unmap(median / pk)
        }
        b.setMany(batch)
    }
    val hot = Acid.colors.accent
    var picking by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    /**
     * Put a pad's start and end back to the full sample.
     *
     * Slicing closes the pads it didn't use (start and end both 0), because a
     * pad without its own sample reads the shared file. A sample loaded into a
     * closed pad would then be silent, so taking a pad over resets its trim.
     */
    fun resetTrim(pad: Int) {
        val batch = mutableMapOf<String, Float>()
        b.infoOf("p%02d_start".format(pad))?.let { batch[it.name] = it.defaultNormalized }
        b.infoOf("p%02d_end".format(pad))?.let { batch[it.name] = it.defaultNormalized }
        b.setMany(batch)
    }

    // Slicing one file across the pads. The file is its own setting, not a
    // pad's, since all 13 pads read the same copy.
    val scope = rememberCoroutineScope()
    val sliceRel = track.machine.settings["slice_sample"]
    var slicing by remember { mutableStateOf(false) }
    var sliceBusy by remember { mutableStateOf(false) }
    // Picking the file is a step towards slicing, so when the file comes back
    // the slice dialog opens.
    var awaitingPick by remember { mutableStateOf(false) }
    LaunchedEffect(sliceRel) {
        if (awaitingPick && sliceRel != null) { awaitingPick = false; slicing = true }
    }
    if (slicing) SliceDialog(
        name = sliceRel?.substringAfterLast('/').orEmpty(),
        onChoose = { slicing = false; awaitingPick = true; onImportSlice() },
        onDismiss = { slicing = false },
        onApply = { mode, count ->
            slicing = false
            val rel = sliceRel ?: return@SliceDialog
            sliceBusy = true
            scope.launch {
                val abs = com.rm.acidulous.io.File(
                    com.rm.acidulous.engine.EngineAssets.userRoot(), rel,
                ).absolutePath
                val points = withContext(Dispatchers.IO) { NativeEngine.slicePoints(abs, mode, count) }
                sliceBusy = false
                if (points.size < 2) return@launch
                val n = minOf(count, 13, points.size - 1)
                val batch = mutableMapOf<String, Float>()
                for (i in 0 until 13) {
                    // Through the parameter table instead of assuming 0..1,
                    // same as match.
                    val startDef = b.info.firstOrNull { it.name == "p%02d_start".format(i) } ?: continue
                    val endDef = b.info.firstOrNull { it.name == "p%02d_end".format(i) } ?: continue
                    // Pads past the last slice are closed. A pad without its
                    // own sample reads the shared file, so leaving it would
                    // play the whole break.
                    val from = if (i < n) points[i] else 0f
                    val to = if (i < n) points[i + 1] else 0f
                    batch[startDef.name] = startDef.unmap(from)
                    batch[endDef.name] = endDef.unmap(to)
                }
                b.setMany(batch)
                onSliceApplied(count)
            }
        },
    )
    if (picking) RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Library,
        inUse = inUse,
        onPick = { rel -> picking = false; resetTrim(p); onAssign(p, rel) },
        onDismiss = { picking = false },
    )
    // Clearing the kit asks first. It's next to match, and hand-picked samples
    // are settings that would be lost before anyone found undo.
    if (clearing) PlainDialog(
        title = stringResource(Res.string.kit_clear_title),
        onDismiss = { clearing = false },
        confirmLabel = stringResource(Res.string.kit_clear),
        onConfirm = {
            clearing = false
            onClearKit()
            // The samples are settings and the trim is parameters, so resetting
            // the pads needs both. Slicing closed the unused pads, and a
            // cleared kit that stays closed would be silent.
            val batch = mutableMapOf<String, Float>()
            for (i in 0 until 13) {
                b.infoOf("p%02d_start".format(i))?.let { batch[it.name] = it.defaultNormalized }
                b.infoOf("p%02d_end".format(i))?.let { batch[it.name] = it.defaultNormalized }
            }
            b.setMany(batch)
        },
    ) {
        Text(
            stringResource(Res.string.kit_clear_note),
            color = Acid.colors.textMid, fontSize = 12.sp,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.kit_pad, p + 1), color = hot, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                if (info.isEmpty()) (rel?.let { stringResource(Res.string.kit_not_loaded, it) } ?: stringResource(Res.string.kit_no_sample))
                else stringResource(
                    Res.string.kit_sample_info,
                    info.substringBefore('|'),
                    info.split('|').getOrNull(1)?.toIntOrNull()?.let { "%.2fs".format(it / 48000f) } ?: "",
                    stringResource(if (info.endsWith("|1")) Res.string.kit_stereo_short else Res.string.kit_mono),
                ),
                color = Acid.colors.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (rel != null) Text(peakDb, color = Acid.colors.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            else if (sliceRel != null && p < (track.machine.settings["slice_count"]?.toIntOrNull() ?: 0)) {
                val from = b.infoOf(n("start"))?.map(b.value(n("start"))) ?: 0f
                val to = b.infoOf(n("end"))?.map(b.value(n("end"))) ?: 0f
                Text(
                    stringResource(Res.string.kit_slice_of, p + 1, track.machine.settings["slice_count"].orEmpty(), from * 100, to * 100),
                    color = Acid.colors.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
            // Only per-pad controls here. The three kit-wide ones are in the
            // group row below, since they didn't all fit across a phone.
            TextButton(onClick = { resetTrim(p); onImport(p) }) { Text(stringResource(Res.string.kit_load), color = hot, fontSize = 11.sp) }
            TextButton(onClick = { picking = true }) { Text(stringResource(Res.string.kit_samples), color = hot, fontSize = 11.sp) }
            // Only when there's something to trim. An empty pad has no sample
            // to show.
            if (info.isNotEmpty()) {
                TextButton(onClick = { onOpenSample(p) }) { Text(stringResource(Res.string.kit_edit), color = hot, fontSize = 11.sp) }
            }
            if (rel != null) TextButton(onClick = { resetTrim(p); onClear(p) }) { Text(stringResource(Res.string.kit_clear_pad), color = Acid.colors.textMid, fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Group("sample") { PanelKnob(b, n("start"), "start"); PanelKnob(b, n("end"), "end"); PanelKnob(b, n("pitch"), "pitch", hot); PanelSwitch(b, n("reverse"), listOf("fwd", "rev"), "reverse"); PanelSwitch(b, n("play"), listOf("once", "loop", "hold"), "play") }
            Group("amp") { PanelKnob(b, n("decay"), "decay"); PanelKnob(b, n("level"), "level"); PanelKnob(b, n("pan"), "pan"); PanelSwitch(b, n("choke"), listOf("-", "1", "2", "3", "4"), "choke") }
            Group("tone") { PanelKnob(b, n("cutoff"), "cutoff", hot); PanelKnob(b, n("reso"), "reso", hot); PanelSwitch(b, n("mode"), listOf("lp", "bp"), "mode"); PanelKnob(b, n("crush"), "crush", Acid.colors.pink) }
            Group("punch") { PanelKnob(b, n("penv"), "pitch env"); PanelKnob(b, n("pdecay"), "decay") }
            Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "velocity", "vel"); PanelKnob(b, "volume", "volume", hot) }
            // The kit-wide controls.
            Group("kit") {
                PanelActions(
                    Triple(stringResource(Res.string.kit_kit), hot) { onImportKit(p) },
                    Triple(stringResource(if (sliceBusy) Res.string.kit_slicing else Res.string.kit_slice), hot) {
                        if (sliceRel == null) { awaitingPick = true; onImportSlice() } else slicing = true
                    },
                    Triple(stringResource(Res.string.kit_match), Acid.colors.textMid) { matchLevels() },
                    Triple(stringResource(Res.string.kit_clear_kit), Acid.colors.textMid) { clearing = true },
                )
            }
        }
    }
}

/**
 * Cutting one file across the pads.
 *
 * Forage already has per-pad start and end, so a slice is just 13 pads reading
 * one file between two points. The file lives in its own slot (see
 * Forage::kSharedSlot), and this works out where the cuts go.
 */
@Composable
private fun SliceDialog(name: String, onChoose: () -> Unit, onDismiss: () -> Unit,
                        onApply: (mode: Int, count: Int) -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf(13) }
    PlainDialog(
        title = stringResource(Res.string.slice_title),
        onDismiss = onDismiss,
        confirmLabel = if (name.isEmpty()) "" else stringResource(Res.string.slice_confirm),
        confirmEnabled = name.isNotEmpty(),
        onConfirm = if (name.isEmpty()) null else ({ onApply(mode, count) }),
        spacing = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                name.ifEmpty { stringResource(Res.string.slice_no_file) },
                color = if (name.isEmpty()) Acid.colors.textDim else Acid.colors.textHi,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            TextButton(onClick = onChoose) {
                Text(stringResource(if (name.isEmpty()) Res.string.slice_choose else Res.string.slice_change), color = Acid.colors.accent, fontSize = 12.sp)
            }
        }
        Text(stringResource(Res.string.slice_where), color = Acid.colors.textDim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stringArrayResource(Res.array.slice_modes).forEachIndexed { i, label ->
                TextButton(onClick = { mode = i }) {
                    Text(label, color = if (mode == i) Acid.colors.accent else Acid.colors.textMid, fontSize = 12.sp)
                }
            }
        }
        Text(
            stringResource(if (mode == 0) Res.string.slice_transients_note else Res.string.slice_even_note),
            color = Acid.colors.textDim, fontSize = 11.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.slice_count), color = Acid.colors.textDim, fontSize = 11.sp)
            TextButton(onClick = { count = (count - 1).coerceAtLeast(2) }) {
                Text("−", color = Acid.colors.accent, fontSize = 15.sp)
            }
            Text("$count", color = Acid.colors.textHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { count = (count + 1).coerceAtMost(13) }) {
                Text("+", color = Acid.colors.accent, fontSize = 15.sp)
            }
        }
        Text(
            stringResource(Res.string.slice_count_note, count),
            color = Acid.colors.textDim, fontSize = 11.sp,
        )
    }
}

/** Any machine without its own panel: every parameter as a knob. */
@Composable
private fun GenericPanel(b: ParamBinding) {
    Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in b.info) PanelKnob(b, p.name)
    }
}

// --- Trinity ---------------------------------------------------------------------
//
// 178 parameters won't fit on a phone at once, so the panel is sectioned and
// the repeating sections (oscillator, envelope, LFO, matrix slot) have their
// own selector. Names mirror engine/machine/trinity/Trinity.cpp.

val TRINITY_WAVES = listOf("saw", "square", "tri", "sine", "Sweep", "Glass", "Vowel", "Bell", "Comb", "Fold", "Grit", "Organ")
val TRINITY_FILTERS = listOf("LP6", "LP12", "LP18", "LP24", "HP6", "HP12", "HP18", "HP24", "BP6", "BP12", "notch", "peak")
val TRINITY_DRIVES = listOf("clean", "valve", "diode", "clip", "fold", "crush")
val TRINITY_LFO_WAVES = listOf("sine", "tri", "saw+", "saw-", "sqr", "s&h", "rand", "step8", "step16")
val TRINITY_LFO_SYNC = listOf("free", "1/16", "1/8", "1/4", "1/2", "1 bar", "2", "4", "8")
val TRINITY_SOURCES = listOf("off", "on", "mod", "after", "vel", "key", "rand", "envA", "envF", "env3", "env4", "env5", "env6", "lfo1", "lfo2", "lfo3")
val TRINITY_DESTS = listOf(
    "off", "pitch", "pitch1", "pitch2", "pitch3", "pos1", "pos2", "pos3", "lvl1", "lvl2", "lvl3",
    "pw1", "pw2", "pw3", "sync1", "sync2", "sync3", "detune", "noise", "ring12", "ring23", "fm21", "fm32",
    "f1freq", "f2freq", "f1res", "f2res", "balance", "drive", "amp", "pan", "l1rate", "l2rate", "l3rate",
)
private val TRINITY_ENVS = listOf("amp env" to "a", "filter env" to "f", "env 3" to "e3", "env 4" to "e4", "env 5" to "e5", "env 6" to "e6")

@Composable
private fun TrinityPanel(b: ParamBinding) {
    // Too many groups for one row, so sections pick which groups show. Inside a
    // section it's the usual Group row.
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("osc", "mix", "filter", "env", "lfo", "mod", "voice"), section, { section = it }) { sec ->
        when (sec) {
            0 -> for (o in 1..3) key(o) { Group("osc $o") {
                val p = "o${o}_"
                PanelStepKnob(b, p + "wave", TRINITY_WAVES, "wave", PanelAmber)
                PanelKnob(b, p + "pos", "pos", PanelAmber)
                PanelKnob(b, p + "warp", "warp", PanelAmber)
                PanelKnob(b, p + "coarse", "coarse")
                PanelKnob(b, p + "fine", "fine")
                PanelKnob(b, p + "density", "density")
                PanelKnob(b, p + "detune", "detune")
                PanelKnob(b, p + "sync", "sync")
                PanelKnob(b, p + "hard", "hard")
                PanelKnob(b, p + "pw", "pw")
                PanelKnob(b, p + "drift", "drift")
                PanelKnob(b, p + "level", "level")
            } }
            1 -> {
                Group("ring") { PanelKnob(b, "ring12", "1·2", PanelAmber); PanelKnob(b, "ring23", "2·3", PanelAmber) }
                Group("fm") { PanelKnob(b, "fm21", "2→1", PanelAmber); PanelKnob(b, "fm32", "3→2", PanelAmber) }
                Group("noise") { PanelKnob(b, "noise", "level"); PanelKnob(b, "noisecol", "colour") }
            }
            2 -> {
                Group("routing") { PanelSwitch(b, "route", listOf("serial", "para", "split")); PanelKnob(b, "balance", "balance") }
                for (f in 1..2) key(f) { Group("filter $f") {
                    val p = "f${f}_"
                    PanelStepKnob(b, p + "type", TRINITY_FILTERS, "type", PanelAmber)
                    PanelKnob(b, p + "freq", "freq", PanelAmber)
                    PanelKnob(b, p + "res", "reso", PanelAmber)
                    PanelStepKnob(b, p + "drivetype", TRINITY_DRIVES, "drive", PanelPink)
                    PanelKnob(b, p + "drive", "amount", PanelPink)
                    PanelKnob(b, p + "env", "envmod")
                    PanelKnob(b, p + "key", "key")
                } }
            }
            3 -> for ((title, prefix) in TRINITY_ENVS) key(prefix) { Group(title) {
                PanelKnob(b, prefix + "_delay", "delay")
                PanelKnob(b, prefix + "_attack", "attack", PanelAmber)
                PanelKnob(b, prefix + "_decay", "decay", PanelAmber)
                PanelKnob(b, prefix + "_sustain", "sustain", PanelAmber)
                PanelKnob(b, prefix + "_release", "release", PanelAmber)
                PanelSwitch(b, prefix + "_repeat", listOf("once", "loop"), "repeat")
            } }
            4 -> for (l in 1..3) key(l) { Group("lfo $l") {
                val p = "l${l}_"
                PanelStepKnob(b, p + "wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                PanelKnob(b, p + "rate", "rate", PanelAmber)
                PanelStepKnob(b, p + "sync", TRINITY_LFO_SYNC, "sync", PanelAmber)
                PanelKnob(b, p + "delay", "delay")
                PanelKnob(b, p + "phase", "phase")
                PanelKnob(b, p + "slew", "slew")
                PanelSwitch(b, p + "keysync", listOf("free", "key"), "trig")
                PanelSwitch(b, p + "oneshot", listOf("cycle", "once"), "run")
            } }
            5 -> for (m in 1..12) key(m) { Group("mod $m") {
                val p = "m%02d_".format(m)
                PanelStepKnob(b, p + "src", TRINITY_SOURCES, "from", PanelAmber)
                PanelStepKnob(b, p + "src2", TRINITY_SOURCES, "× from")
                PanelStepKnob(b, p + "dest", TRINITY_DESTS, "to", PanelAmber)
                PanelKnob(b, p + "depth", "depth", PanelAmber)
            } }
            else -> {
                Group("voice") {
                    PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg", "uni"), "mode")
                    PanelKnob(b, "glide", "glide")
                    PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                    PanelKnob(b, "bend", "bend")
                    PanelKnob(b, "mpetimbre", "slide")
                    PanelKnob(b, "mpepressure", "press")
                    PanelKnob(b, "wheel", "wheel")
                }
                Group("unison") {
                    PanelKnob(b, "unison", "voices", PanelAmber)
                    PanelKnob(b, "unidetune", "detune", PanelAmber)
                    PanelKnob(b, "unispread", "spread", PanelAmber)
                }
                Group("tuning") { PanelKnob(b, "octave", "octave"); PanelKnob(b, "transpose", "transpose") }
                Group("out") { PanelKnob(b, "volume", "volume"); PanelKnob(b, "pan", "pan"); PanelKnob(b, "velamt", "vel") }
            }
        }
    }
}

// --- Ratio -----------------------------------------------------------------------
//
// Six operators, two algorithms and a morph between them. Same sectioned
// layout as Trinity. Names mirror engine/machine/ratio/Ratio.cpp and the
// algorithm list mirrors Algorithms.h, keep them in step.

val RATIO_WAVES = listOf(
    "sine", "sin12", "sin8", "half", "rect", "quart", "tri", "saw",
    "square", "pulse", "1+2", "1+3", "1+2+3", "odd", "s&h", "noise",
)
val RATIO_MODES = listOf("fm", "ring", "filter", "filtFM", "fold", "sync", "phase", "crush")
val RATIO_SNAP = listOf("free", "harm", "sub", "odd", "semi", "bell")
val RATIO_ALGOS = listOf(
    "6-5-4-3-2-1", "6-5-4-3-2 1", "6-5-4-3 2-1", "6-5-4 3-2-1", "6-5 4-3-2-1",
    "6-5-4 : 3-2-1", "6-5 : 4-3 : 2-1", "6-5-4-3 : 2-1", "6-4 5-4 4-3-2-1", "6-5-3 4-3 3-2-1",
    "6 - 1,2,3,4,5", "6,5 - 1,2,3,4", "6-5 - 1,2,3,4", "5,6 - 4 : 3 - 1,2",
    "2,3,4,5,6 - 1", "4,5,6 - 1", "5-4 6-4 4-1", "6-5 5-2 4-3",
    "6-5 : 4-3 : 2-1 w", "6-5 4-5 : 3-2", "6-4 5-3 : 2-1", "6-3 5-2 4-1",
    "all six", "5 carriers", "6-1 : 2,3,4,5", "6-5-4 : 3 : 2 : 1",
    "ring loop", "6-5..2-1 6-1", "fan 3 / 3", "6-2 6-4 5-1 5-3", "6-5-4-2 3-2", "6-5-1 4-3-1",
)
val RATIO_SOURCES = listOf(
    "off", "on", "mod", "prs", "vel", "key", "rand", "eg1", "eg2", "eg3", "fenv", "lfo1", "lfo2", "lfo3",
)
val RATIO_DESTS = listOf(
    "off", "pitch", "morph", "skew",
    "lvl1", "lvl2", "lvl3", "lvl4", "lvl5", "lvl6",
    "ratio1", "ratio2", "ratio3", "ratio4", "ratio5", "ratio6",
    "fb", "f.freq", "f.res", "amp", "pan", "l1rate", "l2rate", "l3rate",
)

@Composable
private fun RatioPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("op", "algo", "filter", "env", "lfo", "mod", "voice"), section, { section = it }) { sec ->
        when (sec) {
            0 -> for (o in 1..6) key(o) { Group("op $o") {
                val p = "o${o}_"
                PanelStepKnob(b, p + "wave", RATIO_WAVES, "wave", PanelAmber)
                PanelStepKnob(b, p + "mode", RATIO_MODES, "mode", PanelAmber)
                PanelKnob(b, p + "ratio", "ratio", PanelAmber)
                PanelKnob(b, p + "fine", "fine")
                PanelSwitch(b, p + "fixed", listOf("ratio", "Hz"), "pitch")
                PanelKnob(b, p + "level", "level", PanelAmber)
                PanelKnob(b, p + "fb", "fb", PanelPink)
                PanelKnob(b, p + "attack", "attack")
                PanelKnob(b, p + "decay", "decay")
                PanelKnob(b, p + "sustain", "sustain")
                PanelKnob(b, p + "release", "release")
                PanelKnob(b, p + "vel", "vel")
                PanelKnob(b, p + "key", "key")
                PanelKnob(b, p + "pan", "pan")
            } }
            1 -> {
                Group("algorithm") {
                    PanelStepKnob(b, "algoa", RATIO_ALGOS, "A", PanelAmber)
                    PanelStepKnob(b, "algob", RATIO_ALGOS, "B", PanelAmber)
                    PanelKnob(b, "morph", "morph", PanelAmber)
                }
                Group("ratios") {
                    PanelStepKnob(b, "snap", RATIO_SNAP, "snap", PanelAmber)
                    PanelKnob(b, "skew", "skew", PanelAmber)
                }
            }
            2 -> {
                Group("filter") {
                    PanelStepKnob(b, "f_type", TRINITY_FILTERS, "type", PanelAmber)
                    PanelKnob(b, "f_freq", "freq", PanelAmber)
                    PanelKnob(b, "f_res", "reso", PanelAmber)
                    PanelKnob(b, "f_env", "envmod")
                    PanelKnob(b, "f_key", "key")
                }
                Group("filter env") {
                    PanelKnob(b, "f_attack", "attack")
                    PanelKnob(b, "f_decay", "decay")
                    PanelKnob(b, "f_sustain", "sustain")
                    PanelKnob(b, "f_release", "release")
                }
            }
            3 -> for (e in 1..3) key(e) { Group("env $e") {
                val p = "e${e}_"
                PanelKnob(b, p + "attack", "attack", PanelAmber)
                PanelKnob(b, p + "decay", "decay", PanelAmber)
                PanelKnob(b, p + "sustain", "sustain", PanelAmber)
                PanelKnob(b, p + "release", "release", PanelAmber)
            } }
            4 -> for (l in 1..3) key(l) { Group("lfo $l") {
                val p = "l${l}_"
                PanelStepKnob(b, p + "wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                PanelKnob(b, p + "rate", "rate", PanelAmber)
                PanelStepKnob(b, p + "sync", TRINITY_LFO_SYNC, "sync", PanelAmber)
                PanelKnob(b, p + "delay", "delay")
                PanelKnob(b, p + "phase", "phase")
                PanelSwitch(b, p + "keysync", listOf("free", "key"), "trig")
            } }
            5 -> for (m in 1..10) key(m) { Group("mod $m") {
                val p = "m%02d_".format(m)
                PanelStepKnob(b, p + "src", RATIO_SOURCES, "from", PanelAmber)
                PanelStepKnob(b, p + "src2", RATIO_SOURCES, "× from")
                PanelStepKnob(b, p + "dest", RATIO_DESTS, "to", PanelAmber)
                PanelKnob(b, p + "depth", "depth", PanelAmber)
            } }
            else -> {
                Group("voice") {
                    PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg"), "mode")
                    PanelKnob(b, "glide", "glide")
                    PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                    PanelKnob(b, "bend", "bend")
                    PanelKnob(b, "mpetimbre", "slide")
                    PanelKnob(b, "mpepressure", "press")
                }
                Group("tuning") { PanelKnob(b, "octave", "octave"); PanelKnob(b, "transpose", "transpose") }
                Group("out") { PanelKnob(b, "volume", "volume"); PanelKnob(b, "pan", "pan"); PanelKnob(b, "velamt", "vel") }
            }
        }
    }
}

// --- Manual -----------------------------------------------------------------
//
// The drawbars are drawn as drawbars, since a registration is read as a shape
// (88 8000 000) and a row of knobs can't show that. The rest of the panel is
// the usual style. Two registrations are shown since the machine morphs
// between them.

private val MANUAL_MODELS = listOf("wheel", "combo", "pipe", "reed")
private val MANUAL_VIB = listOf("V1", "V2", "V3", "C1", "C2", "C3")
private val MANUAL_ROT = listOf("brake", "slow", "fast")
private val MANUAL_SYNC = listOf("free", "1/1", "1/2", "1/4", "1/8", "1/8T")
private val MANUAL_SOURCES = listOf(
    "off", "on", "mod", "prs", "vel", "key", "rand", "eg1", "eg2", "lfo1", "lfo2", "horn", "drum", "scan", "wind",
)
private val MANUAL_DESTS = listOf(
    "off", "morph", "spray", "drive", "volume", "pan", "rotor", "perc", "click", "wind", "treble", "vib", "chiff",
    "pitch", "upper", "lower", "16", "5⅓", "8", "4", "2⅔", "2", "1⅗", "1⅓", "1",
)
private val MANUAL_BARS = listOf("16", "5⅓", "8", "4", "2⅔", "2", "1⅗", "1⅓", "1")
// Hammond drawbar colours: fundamentals white, harmonics black, the two quints
// brown.
private val BAR_COLOURS = listOf(
    DrawbarBrown, DrawbarBrown, DrawbarWhite, DrawbarWhite,
    DrawbarBrown, DrawbarWhite, DrawbarBlack, DrawbarBlack, DrawbarWhite,
)

@Composable
private fun Drawbars(b: ParamBinding, prefix: String, names: List<String>, colours: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        names.forEachIndexed { i, label ->
            val name = prefix + label.replace("5⅓", "513").replace("2⅔", "223")
                .replace("1⅗", "135").replace("1⅓", "113")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Acid.colors.textDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                VerticalFader(
                    value = b.value(name),
                    modifier = Modifier.width(18.dp).height(64.dp),
                    accent = colours[i % colours.size],
                    onStart = { b.start(name) },
                    onChange = { v -> b.change(name, v) },
                    onEnd = { b.end() },
                    onReset = { b.reset(name) },
                )
                Text("%d".format((b.value(name) * 8f).roundToInt()), color = PanelAmber, fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ManualPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("bars", "perc", "vib", "rotary", "voice", "wind", "mod", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("upper A") { Drawbars(b, "ua_", MANUAL_BARS, BAR_COLOURS) }
                Group("upper B") { Drawbars(b, "ub_", MANUAL_BARS, BAR_COLOURS) }
                Group("morph") {
                    PanelKnob(b, "morph", "A→B", PanelAmber)
                    PanelStepKnob(b, "morphsrc", MANUAL_SOURCES, "from", PanelAmber)
                    PanelKnob(b, "morphamt", "amount")
                }
                Group("lower A") { Drawbars(b, "la_", MANUAL_BARS, BAR_COLOURS) }
                Group("lower B") { Drawbars(b, "lb_", MANUAL_BARS, BAR_COLOURS) }
                Group("pedal") {
                    Drawbars(b, "pa_", listOf("1", "2"), listOf(DrawbarWhite))
                    Drawbars(b, "pb_", listOf("1", "2"), listOf(DrawbarBrown))
                }
                Group("spray") {
                    PanelKnob(b, "spray", "spray", PanelPink)
                    PanelKnob(b, "sprayrate", "rate")
                    PanelKnob(b, "spraywide", "width")
                    PanelStepKnob(b, "spraypat", listOf("up", "down", "fan"), "shape")
                }
            }
            1 -> {
                Group("percussion") {
                    PanelSwitch(b, "perc", listOf("off", "on"), "perc")
                    PanelSwitch(b, "percharm", listOf("3rd", "2nd"), "harm")
                    PanelKnob(b, "perclvl", "level", PanelAmber)
                    PanelSwitch(b, "percfast", listOf("slow", "fast"), "decay")
                    PanelKnob(b, "percdec", "time", PanelAmber)
                }
                Group("beyond") {
                    PanelSwitch(b, "percpoly", listOf("first", "every"), "trigger")
                    PanelKnob(b, "perckey", "keytrack")
                    PanelSwitch(b, "percsteal", listOf("keep", "steal"), "1' bar")
                }
                Group("click") {
                    PanelKnob(b, "click", "on", PanelAmber)
                    PanelKnob(b, "clickoff", "off")
                    PanelKnob(b, "contacts", "spread")
                }
                Group("generator") {
                    PanelStepKnob(b, "model", MANUAL_MODELS, "model", PanelAmber)
                    PanelKnob(b, "age", "age", PanelPink)
                    PanelKnob(b, "leakage", "leakage")
                    PanelKnob(b, "hum", "hum")
                }
            }
            2 -> {
                Group("scanner") {
                    PanelStepKnob(b, "vibtype", MANUAL_VIB, "type", PanelAmber)
                    PanelKnob(b, "vibrate", "rate", PanelAmber)
                    PanelKnob(b, "vibdepth", "depth", PanelAmber)
                    PanelKnob(b, "vibwide", "width")
                }
                Group("routing") {
                    PanelSwitch(b, "vibup", listOf("dry", "vib"), "upper")
                    PanelSwitch(b, "viblow", listOf("dry", "vib"), "lower")
                }
                Group("lfo 1") {
                    PanelStepKnob(b, "lfo1wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "lfo1rate", "rate", PanelAmber)
                    PanelStepKnob(b, "lfo1sync", MANUAL_SYNC, "sync")
                    PanelKnob(b, "lfo1depth", "depth")
                    PanelKnob(b, "lfo1phase", "phase")
                }
                Group("lfo 2") {
                    PanelStepKnob(b, "lfo2wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "lfo2rate", "rate", PanelAmber)
                    PanelStepKnob(b, "lfo2sync", MANUAL_SYNC, "sync")
                    PanelKnob(b, "lfo2depth", "depth")
                    PanelKnob(b, "lfo2phase", "phase")
                }
            }
            3 -> {
                Group("cabinet") {
                    PanelSwitch(b, "rotary", listOf("off", "on"), "rotary")
                    PanelStepKnob(b, "rotspeed", MANUAL_ROT, "speed", PanelAmber)
                    PanelStepKnob(b, "rotsync", MANUAL_SYNC, "sync")
                }
                Group("horn") {
                    PanelKnob(b, "hornslow", "slow", PanelAmber)
                    PanelKnob(b, "hornfast", "fast", PanelAmber)
                }
                Group("drum") {
                    PanelKnob(b, "drumslow", "slow", PanelAmber)
                    PanelKnob(b, "drumfast", "fast", PanelAmber)
                }
                Group("ramp") {
                    PanelKnob(b, "rampup", "up")
                    PanelKnob(b, "rampdown", "down")
                }
                Group("mics") {
                    PanelKnob(b, "micdist", "distance", PanelAmber)
                    PanelKnob(b, "micangle", "angle")
                    PanelKnob(b, "rotwide", "width")
                }
            }
            4 -> {
                Group("manuals") {
                    PanelKnob(b, "split", "split", PanelAmber)
                    PanelKnob(b, "pedsplit", "pedal at", PanelAmber)
                    PanelSwitch(b, "loweron", listOf("off", "on"), "lower")
                    PanelSwitch(b, "pedalon", listOf("off", "on"), "pedals")
                }
                Group("balance") {
                    PanelKnob(b, "upper", "upper")
                    PanelKnob(b, "lower", "lower")
                    PanelKnob(b, "pedal", "pedal")
                    PanelKnob(b, "pedsus", "ped sus")
                }
                Group("pipes") {
                    PanelKnob(b, "principal", "principal", PanelAmber)
                    PanelKnob(b, "flute", "flute", PanelAmber)
                    PanelKnob(b, "string", "string", PanelAmber)
                    PanelKnob(b, "reed", "reed", PanelAmber)
                    PanelKnob(b, "mixture", "mixture", PanelAmber)
                    PanelKnob(b, "chiff", "chiff")
                    PanelKnob(b, "tracker", "tracker")
                }
                Group("combo") {
                    PanelStepKnob(b, "combowave", listOf("square", "pulse", "saw"), "wave", PanelAmber)
                    PanelKnob(b, "tab16", "16'")
                    PanelKnob(b, "tab8", "8'")
                    PanelKnob(b, "tab4", "4'")
                    PanelKnob(b, "tab2", "2'")
                    PanelKnob(b, "tab2r", "II")
                    PanelKnob(b, "tab4r", "IV")
                    PanelKnob(b, "reedy", "reedy", PanelPink)
                    PanelKnob(b, "comboatk", "attack")
                }
                Group("reeds") {
                    PanelKnob(b, "pressure", "pressure", PanelAmber)
                    PanelKnob(b, "buzz", "buzz", PanelPink)
                    PanelKnob(b, "reedtrem", "tremolo")
                }
            }
            5 -> {
                Group("wind") {
                    PanelKnob(b, "windsag", "sag", PanelAmber)
                    PanelKnob(b, "windresp", "response", PanelAmber)
                    PanelKnob(b, "windnoise", "noise")
                }
                Group("tremulant") {
                    PanelKnob(b, "tremrate", "rate", PanelAmber)
                    PanelKnob(b, "tremdepth", "depth", PanelAmber)
                }
                Group("envelope") {
                    PanelKnob(b, "attack", "attack")
                    PanelKnob(b, "release", "release")
                }
                Group("eg 1") {
                    PanelKnob(b, "eg1atk", "attack"); PanelKnob(b, "eg1dec", "decay")
                    PanelKnob(b, "eg1sus", "sustain"); PanelKnob(b, "eg1rel", "release")
                }
                Group("eg 2") {
                    PanelKnob(b, "eg2atk", "attack"); PanelKnob(b, "eg2dec", "decay")
                    PanelKnob(b, "eg2sus", "sustain"); PanelKnob(b, "eg2rel", "release")
                }
            }
            6 -> for (m in 1..8) key(m) { Group("mod $m") {
                PanelStepKnob(b, "m${m}_src", MANUAL_SOURCES, "from", PanelAmber)
                PanelStepKnob(b, "m${m}_dst", MANUAL_DESTS, "to", PanelAmber)
                PanelKnob(b, "m${m}_amt", "amount", PanelAmber)
            } }
            else -> {
                Group("amp") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "bias", "bias", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
                Group("tone") {
                    PanelKnob(b, "bass", "bass", PanelAmber)
                    PanelKnob(b, "mid", "mid", PanelAmber)
                    PanelKnob(b, "treble", "treble", PanelAmber)
                }
                Group("tuning") {
                    PanelKnob(b, "octave", "octave"); PanelKnob(b, "transpose", "transpose")
                    PanelKnob(b, "fine", "fine"); PanelKnob(b, "bend", "bend")
                }
                Group("touch") {
                    PanelKnob(b, "vel", "velocity")
                    PanelStepKnob(b, "express", listOf("off", "mod", "prs"), "swell", PanelAmber)
                }
            }
        }
    }
}

// --- Cipher -----------------------------------------------------------------
//
// The bank first, then the map. Everything between analysing the modulator
// and applying it to the carrier is under "map", which is where this differs
// from an ordinary vocoder.

private val CIPHER_REMAP = listOf("direct", "reverse", "mirror", "odd/even", "shuffle", "fold")
private val CIPHER_ROLE = listOf("in speaks", "in sings")
private val CIPHER_WAVES = listOf("saw", "pulse", "super", "noise", "ring")
private val CIPHER_SOURCES = listOf(
    "off", "on", "mod", "prs", "vel", "key", "eg1", "eg2", "lfo1", "lfo2", "loud", "bright", "pitch",
)
private val CIPHER_DESTS = listOf(
    "off", "shift", "stretch", "remap", "freeze", "smear", "q", "pitch", "mix", "noise", "feedback",
    "drive", "volume", "pan", "low", "high", "gate",
)
private val CIPHER_SYNC = listOf("free", "1/1", "1/2", "1/4", "1/8", "1/8T")

/**
 * The microphone control, for machines that listen. A vocoder or an input
 * exciter with nothing coming in is silent, so the panel has the switch instead
 * of a menu.
 */
@Composable
private fun InputListen() {
    var running by remember { mutableStateOf(NativeEngine.inputRunning) }
    var level by remember { mutableStateOf(0f) }
    val permissions = rememberPermissions { ok -> if (ok) running = NativeEngine.startInput() }
    LaunchedEffect(Unit) {
        while (true) {
            running = NativeEngine.inputRunning
            if (running) level = NativeEngine.inputPeak()
            delay(100)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.machine_input), color = Acid.colors.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            if (running) {
                NativeEngine.stopInput()
                running = false
            } else if (permissions.has(Permissions.RECORD_AUDIO)) {
                running = NativeEngine.startInput()
            } else {
                permissions.ask(Permissions.RECORD_AUDIO)
            }
        }) { Text(stringResource(if (running) Res.string.machine_listening else Res.string.machine_open), color = if (running) PanelTeal else PanelAmber, fontSize = 11.sp) }
        Meter(level, Modifier.width(70.dp).height(8.dp), vertical = false)
    }
}

@Composable
private fun CipherPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("bank", "map", "carrier", "voice", "mod", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("listen") { InputListen() }
                Group("bank") {
                    PanelKnob(b, "bands", "bands", PanelAmber)
                    PanelKnob(b, "low", "low", PanelAmber)
                    PanelKnob(b, "high", "high", PanelAmber)
                    PanelKnob(b, "q", "width")
                    PanelSwitch(b, "slope", listOf("2 pole", "4 pole"), "slope")
                }
                Group("follow") {
                    PanelKnob(b, "attack", "attack", PanelAmber)
                    PanelKnob(b, "release", "release", PanelAmber)
                    PanelKnob(b, "smear", "smear", PanelPink)
                }
                Group("gate") {
                    PanelKnob(b, "gate", "threshold")
                    PanelKnob(b, "gatedepth", "depth")
                }
                Group("consonants") {
                    PanelKnob(b, "sibilance", "detect", PanelAmber)
                    PanelKnob(b, "sibhz", "above")
                    PanelKnob(b, "siblevel", "level")
                }
            }
            1 -> {
                Group("remap") {
                    PanelStepKnob(b, "remap", CIPHER_REMAP, "order", PanelAmber)
                    PanelKnob(b, "remapamt", "amount", PanelAmber)
                    PanelKnob(b, "seed", "seed")
                }
                Group("formant") {
                    PanelKnob(b, "shift", "shift", PanelAmber)
                    PanelKnob(b, "stretch", "stretch", PanelAmber)
                }
                Group("freeze") {
                    PanelSwitch(b, "freeze", listOf("live", "hold"), "hold")
                    PanelKnob(b, "frzmorph", "morph", PanelAmber)
                    PanelKnob(b, "frzdecay", "decay")
                }
                Group("feedback") {
                    PanelKnob(b, "feedback", "amount", PanelPink)
                    PanelKnob(b, "fbtone", "tone")
                }
            }
            2 -> {
                Group("carrier") {
                    PanelStepKnob(b, "wave a", CIPHER_WAVES, "wave a", PanelAmber)
                    PanelStepKnob(b, "wave b", CIPHER_WAVES, "wave b", PanelAmber)
                    PanelKnob(b, "mix", "mix", PanelAmber)
                    PanelKnob(b, "detune", "detune")
                    PanelKnob(b, "pw", "width")
                    PanelKnob(b, "sub", "sub")
                    PanelKnob(b, "noise", "noise")
                    // How much of the carrier is replaced by noise when the
                    // modulator is unvoiced, which turns a sung vowel into a
                    // whisper.
                    PanelKnob(b, "unvoiced", "unvoiced", PanelAmber)
                    PanelKnob(b, "cardrive", "drive", PanelPink)
                }
                Group("envelope") {
                    PanelKnob(b, "ampatk", "attack"); PanelKnob(b, "ampdec", "decay")
                    PanelKnob(b, "ampsus", "sustain"); PanelKnob(b, "amprel", "release")
                }
            }
            3 -> {
                Group("roles") {
                    PanelStepKnob(b, "role", CIPHER_ROLE, "input is", PanelAmber)
                    PanelKnob(b, "dry", "dry")
                    PanelKnob(b, "wet", "wet", PanelAmber)
                }
                Group("tracking") {
                    PanelSwitch(b, "track", listOf("off", "on"), "follow")
                    PanelKnob(b, "trackamt", "amount", PanelAmber)
                    PanelKnob(b, "trackglide", "glide")
                }
                Group("keys") {
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "bend", "bend")
                    PanelKnob(b, "octave", "octave")
                    PanelKnob(b, "transpose", "transpose")
                    PanelKnob(b, "fine", "fine")
                    PanelKnob(b, "vel", "velocity")
                }
            }
            4 -> {
                Group("lfo 1") {
                    PanelStepKnob(b, "lfo1wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "lfo1rate", "rate", PanelAmber)
                    PanelStepKnob(b, "lfo1sync", CIPHER_SYNC, "sync")
                    PanelKnob(b, "lfo1depth", "depth")
                }
                Group("lfo 2") {
                    PanelStepKnob(b, "lfo2wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "lfo2rate", "rate", PanelAmber)
                    PanelStepKnob(b, "lfo2sync", CIPHER_SYNC, "sync")
                    PanelKnob(b, "lfo2depth", "depth")
                }
                Group("eg 1") {
                    PanelKnob(b, "eg1atk", "attack"); PanelKnob(b, "eg1dec", "decay")
                    PanelKnob(b, "eg1sus", "sustain"); PanelKnob(b, "eg1rel", "release")
                }
                Group("eg 2") {
                    PanelKnob(b, "eg2atk", "attack"); PanelKnob(b, "eg2dec", "decay")
                    PanelKnob(b, "eg2sus", "sustain"); PanelKnob(b, "eg2rel", "release")
                }
                for (m in 1..8) key(m) { Group("mod $m") {
                    PanelStepKnob(b, "m${m}_src", CIPHER_SOURCES, "from", PanelAmber)
                    PanelStepKnob(b, "m${m}_dst", CIPHER_DESTS, "to", PanelAmber)
                    PanelKnob(b, "m${m}_amt", "amount", PanelAmber)
                } }
            }
            else -> {
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
}

// --- Cumulus ---------------------------------------------------------------------

// The vowels the profile's formants interpolate through, and the shimmer
// intervals. Names mirror engine/machine/cumulus/Cloud.cpp.
private val CUMULUS_SHIMMER = listOf("5th", "8ve", "8ve+5", "2 8ve")
private val CUMULUS_FILTERS = listOf("LP6", "LP12", "LP18", "LP24", "HP6", "HP12", "HP18", "HP24", "BP6", "BP12", "notch", "peak")
private val CUMULUS_LFO = listOf("sine", "tri", "saw↑", "saw↓", "square", "s&h", "smooth", "8 step", "16 step")

/**
 * Cumulus's panel, in two halves. The "cloud" and "morph to" sections build the
 * tables (each knob there is an inverse transform of about 250k points, done
 * off the audio thread once it settles) and everything else is live. They are
 * separate sections for that reason, and the build knobs are amber.
 */
@Composable
private fun CumulusPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("cloud", "morph to", "play", "shape", "env", "mod", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("partials") {
                    PanelKnob(b, "partials", "count", PanelAmber)
                    PanelKnob(b, "tilt", "tilt", PanelAmber)
                    PanelKnob(b, "odd", "odd/even", PanelAmber)
                    PanelKnob(b, "stretch", "stretch", PanelAmber)
                }
                Group("band") {
                    PanelKnob(b, "bandwidth", "width", PanelAmber)
                    PanelKnob(b, "bwscale", "· up the series", PanelAmber)
                    PanelKnob(b, "seed", "seed", PanelAmber)
                }
                Group("scallop") {
                    PanelKnob(b, "comb", "depth", PanelAmber)
                    PanelKnob(b, "combperiod", "every", PanelAmber)
                }
                Group("vowel") {
                    PanelKnob(b, "vowel", "a e i o u", PanelAmber)
                    PanelKnob(b, "vowelamount", "amount", PanelAmber)
                }
            }
            1 -> {
                Group("the far end") {
                    PanelKnob(b, "btilt", "tilt", PanelAmber)
                    PanelKnob(b, "bbandwidth", "width", PanelAmber)
                    PanelKnob(b, "bstretch", "stretch", PanelAmber)
                    PanelKnob(b, "bodd", "odd/even", PanelAmber)
                    PanelKnob(b, "bcomb", "scallop", PanelAmber)
                    PanelKnob(b, "bvowel", "vowel", PanelAmber)
                }
                Group("morph") {
                    PanelKnob(b, "morph", "position", PanelPink)
                    PanelKnob(b, "morphkey", "· by key")
                }
            }
            2 -> {
                Group("copies") {
                    PanelStepKnob(b, "spread", listOf("1", "2", "3"), "how many", PanelAmber)
                    PanelKnob(b, "detune", "detune", PanelAmber)
                    PanelKnob(b, "spreadwidth", "apart")
                }
                Group("cloud") {
                    PanelKnob(b, "scatter", "scatter", PanelAmber)
                    PanelKnob(b, "drift", "drift", PanelAmber)
                    PanelKnob(b, "driftrate", "· rate")
                    PanelKnob(b, "width", "width")
                }
                Group("shimmer") {
                    PanelKnob(b, "shimmer", "amount", PanelAmber)
                    PanelStepKnob(b, "shimmerint", CUMULUS_SHIMMER, "up a")
                }
            }
            3 -> {
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", CUMULUS_FILTERS, "type")
                    PanelKnob(b, "filterenv", "env", PanelAmber)
                    PanelKnob(b, "filterkey", "key")
                    PanelKnob(b, "filterdrive", "drive", PanelPink)
                }
            }
            4 -> {
                Group("amp") {
                    PanelKnob(b, "ampattack", "A")
                    PanelKnob(b, "ampdecay", "D")
                    PanelKnob(b, "ampsustain", "S")
                    PanelKnob(b, "amprelease", "R")
                }
                Group("filter env") {
                    PanelKnob(b, "filtattack", "A")
                    PanelKnob(b, "filtdecay", "D")
                    PanelKnob(b, "filtsustain", "S")
                    PanelKnob(b, "filtrelease", "R")
                }
            }
            5 -> {
                Group("lfo 1") {
                    PanelStepKnob(b, "lfo1wave", CUMULUS_LFO, "wave")
                    PanelKnob(b, "lfo1rate", "rate", PanelAmber)
                    PanelSwitch(b, "lfo1sync", listOf("free", "sync"), "clock")
                    PanelKnob(b, "lfo1morph", "→ morph", PanelAmber)
                    PanelKnob(b, "lfo1pitch", "→ pitch")
                }
                Group("lfo 2") {
                    PanelStepKnob(b, "lfo2wave", CUMULUS_LFO, "wave")
                    PanelKnob(b, "lfo2rate", "rate", PanelAmber)
                    PanelSwitch(b, "lfo2sync", listOf("free", "sync"), "clock")
                    PanelKnob(b, "lfo2cutoff", "→ cutoff")
                    PanelKnob(b, "lfo2pan", "→ pan")
                }
            }
            else -> {
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
                Group("voice") {
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "velocity", "velocity")
                    PanelStepKnob(b, "bendrange", (0..24).map { "$it" }, "bend")
                    PanelKnob(b, "mpetimbre", "slide"); PanelKnob(b, "mpepressure", "press")
                }
                Group("tune") {
                    PanelStepKnob(b, "octave", (-3..3).map { "$it" }, "octave")
                    PanelStepKnob(b, "transpose", (-12..12).map { "$it" }, "semis")
                    PanelKnob(b, "fine", "fine")
                }
            }
        }
    }
}

// --- Formulate -------------------------------------------------------------------

private val FORMULATE_WAVES = listOf("pulse", "tri", "saw", "noise", "off")
private val FORMULATE_MODES = listOf("off", "replace", "ring", "gate", "xor")

/** Example expressions to start from, all original. */
private val FORMULA_EXAMPLES = listOf(
    "x" to "the chip, untouched",
    "x & (255 << (a >> 5))" to "crush it with knob a",
    "x * sin(t) >> 7" to "ring it against a sine",
    "t * (t >> 5 & a >> 4)" to "buzz - the classic shape",
    "(t >> 3) * (t >> 5 & 7)" to "stairs",
    "t & t >> b >> 4 | t >> a >> 4" to "two shifts arguing",
    "r & (t >> 6 & 15 ? 255 : 0)" to "noise, gated by the clock",
    "sin(t + sin(t >> 2) ) + 128" to "a sine bent by itself",
)

@Composable
private fun FormulatePanel(b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor) {
    var section by rememberSaveable { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val error = com.rm.acidulous.engine.EngineSync.formulaErrors[trackIndex].orEmpty()
    PanelSections(listOf("chip", "formula", "tables", "shape", "env", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("oscillator") {
                    PanelStepKnob(b, "wave", FORMULATE_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "duty", "duty", PanelAmber)
                    PanelKnob(b, "pwmdepth", "pwm")
                    PanelKnob(b, "pwmrate", "· rate")
                    PanelKnob(b, "sub", "sub 8ve")
                    PanelSwitch(b, "noiseshort", listOf("long", "short"), "noise")
                }
                Group("hardware") {
                    PanelStepKnob(b, "bits", (1..8).map { "$it" }, "bits", PanelPink)
                    PanelKnob(b, "crush", "sample rate", PanelPink)
                    PanelKnob(b, "smooth", "smooth")
                }
            }
            1 -> {
                Group("expression") {
                    FormulaButton(track, error) { editing = true }
                }
                Group("how much") {
                    PanelKnob(b, "formula", "amount", PanelAmber)
                    PanelStepKnob(b, "formulamode", FORMULATE_MODES, "against x")
                }
                Group("its clock") {
                    PanelSwitch(b, "timekeyed", listOf("free", "keyed"), "t follows")
                    PanelKnob(b, "timescale", "rate", PanelAmber)
                }
                Group("macros") {
                    PanelKnob(b, "a", "a", PanelAmber)
                    PanelKnob(b, "b", "b", PanelAmber)
                    PanelKnob(b, "c", "c", PanelAmber)
                }
            }
            2 -> {
                Group("steps") {
                    FormulaButton(track, error) { editing = true }
                }
                Group("clock") {
                    PanelKnob(b, "framerate", "rate", PanelAmber)
                    PanelSwitch(b, "framesync", listOf("free", "16ths"), "sync")
                    PanelSwitch(b, "tableretrig", listOf("keep", "restart"), "per note")
                }
            }
            3 -> {
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", CUMULUS_FILTERS, "type")
                }
            }
            4 -> {
                Group("amp") {
                    PanelKnob(b, "ampattack", "A")
                    PanelKnob(b, "ampdecay", "D")
                    PanelKnob(b, "ampsustain", "S")
                    PanelKnob(b, "amprelease", "R")
                }
            }
            else -> {
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
                Group("voice") {
                    PanelSwitch(b, "mono", listOf("poly", "mono"), "voices")
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "velocity", "velocity")
                    PanelStepKnob(b, "bendrange", (0..24).map { "$it" }, "bend")
                }
                Group("tune") {
                    PanelStepKnob(b, "octave", (-3..3).map { "$it" }, "octave")
                    PanelStepKnob(b, "transpose", (-12..12).map { "$it" }, "semis")
                    PanelKnob(b, "fine", "fine")
                }
            }
        }
    }
    if (editing) {
        FormulaDialog(track, error, onDismiss = { editing = false }) { formula, arp, duty, vol ->
            editor.edit(trackIndex) { t ->
                t.withSetting("formula", formula).withSetting("arp", arp)
                    .withSetting("duty", duty).withSetting("vol", vol)
            }
            editing = false
        }
    }
}

/** The formula as text, and whether it parses. */
@Composable
private fun FormulaButton(track: Track, error: String, onEdit: () -> Unit) {
    val c = Acid.colors
    val text = track.machine.settings["formula"].orEmpty()
    Column(Modifier.widthIn(min = 150.dp, max = 300.dp)) {
        Text(
            text.ifEmpty { stringResource(Res.string.formula_none) },
            color = if (text.isEmpty()) c.textDim else c.textHi,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (error.isNotEmpty()) {
            Text(error, color = c.red, fontSize = 10.sp, maxLines = 2)
        }
        TextButton(onClick = onEdit) { Text(stringResource(Res.string.formula_edit), color = Acid.colors.accent, fontSize = 12.sp) }
    }
}

/**
 * Where the machine is programmed: one expression and three step tables.
 * Applied on OK so a half-typed formula isn't compiled on every keystroke.
 */
@Composable
private fun FormulaDialog(
    track: Track,
    error: String,
    onDismiss: () -> Unit,
    onApply: (formula: String, arp: String, duty: String, vol: String) -> Unit,
) {
    val c = Acid.colors
    var formula by remember { mutableStateOf(track.machine.settings["formula"].orEmpty()) }
    var arp by remember { mutableStateOf(track.machine.settings["arp"].orEmpty()) }
    var duty by remember { mutableStateOf(track.machine.settings["duty"].orEmpty()) }
    var vol by remember { mutableStateOf(track.machine.settings["vol"].orEmpty()) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A window, see DialogShell.
        KeyScope(window = true)
        WindowKeys()
        ScaledWindow {
            androidx.compose.material3.Surface(
                Modifier.padding(horizontal = 10.dp).widthIn(max = 720.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = c.card,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.formula_title), color = c.text, fontSize = 20.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = formula, onValueChange = { formula = it },
                        label = { Text(stringResource(Res.string.formula_expression)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.typing() then Modifier.fillMaxWidth(),
                    )
                    if (error.isNotEmpty()) Text(error, color = c.red, fontSize = 12.sp)
                    Text(stringResource(Res.string.formula_examples), color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Column(
                        Modifier.heightIn(max = 180.dp).verticalScrollWithBar(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for ((example, what) in FORMULA_EXAMPLES) {
                            Row(
                                Modifier.fillMaxWidth().clickable { formula = example }.padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(example, color = c.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f), maxLines = 1)
                                Text(panelWord(what), color = c.textDim, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Text(stringResource(Res.string.formula_tables), color = c.teal, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                    androidx.compose.material3.OutlinedTextField(
                        value = arp, onValueChange = { arp = it }, label = { Text(stringResource(Res.string.formula_arp)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        singleLine = true, modifier = Modifier.typing() then Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = duty, onValueChange = { duty = it }, label = { Text(stringResource(Res.string.formula_duty)) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            singleLine = true, modifier = Modifier.typing() then Modifier.weight(1f),
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = vol, onValueChange = { vol = it }, label = { Text(stringResource(Res.string.formula_volume)) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            singleLine = true, modifier = Modifier.typing() then Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                        TextButton(onClick = { onApply(formula, arp, duty, vol) }) { Text(stringResource(Res.string.ok)) }
                    }
                }
            }
        }
    }
}

// --- Genesis ---------------------------------------------------------------------

/**
 * Genesis's panel. Every voice has its own group, and the bus is at the end,
 * since the compressor and duck are part of the sound.
 */
@Composable
private fun GenesisPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("kick", "snare", "toms", "metal", "bus"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("kick") {
                    PanelKnob(b, "kicktune", "tune", PanelAmber)
                    PanelKnob(b, "kickdecay", "decay", PanelAmber)
                    PanelKnob(b, "kickpunch", "punch", PanelAmber)
                    PanelKnob(b, "kicksweep", "sweep")
                    PanelKnob(b, "kickclick", "click")
                    PanelKnob(b, "kickdrive", "drive", PanelPink)
                    PanelKnob(b, "kicklevel", "level")
                }
            }
            1 -> {
                Group("snare") {
                    PanelKnob(b, "snaretune", "tune", PanelAmber)
                    PanelKnob(b, "snaredecay", "decay", PanelAmber)
                    PanelKnob(b, "snaresnap", "snap", PanelAmber)
                    PanelKnob(b, "snaretone", "tone")
                    PanelKnob(b, "snarelevel", "level")
                }
                Group("clap") {
                    PanelKnob(b, "clapspread", "hands", PanelAmber)
                    PanelKnob(b, "clapdecay", "room")
                    PanelKnob(b, "claptone", "tone")
                    PanelKnob(b, "claplevel", "level")
                }
                Group("rim") {
                    PanelKnob(b, "rimtune", "tune")
                    PanelKnob(b, "rimdecay", "decay")
                    PanelKnob(b, "rimlevel", "level")
                }
            }
            2 -> {
                Group("toms") {
                    PanelKnob(b, "tomlotune", "low", PanelAmber)
                    PanelKnob(b, "tommidtune", "mid", PanelAmber)
                    PanelKnob(b, "tomhitune", "high", PanelAmber)
                    PanelKnob(b, "tomdecay", "decay")
                    PanelKnob(b, "tombend", "bend")
                    PanelKnob(b, "tomlevel", "level")
                }
                Group("cowbell") {
                    PanelKnob(b, "belltune", "tune")
                    PanelKnob(b, "belldecay", "decay")
                    PanelKnob(b, "belllevel", "level")
                }
            }
            3 -> {
                Group("hats") {
                    PanelKnob(b, "hattune", "tune", PanelAmber)
                    PanelKnob(b, "hatclosed", "closed", PanelAmber)
                    PanelKnob(b, "hatopen", "open", PanelAmber)
                    PanelKnob(b, "hattone", "tone")
                    PanelKnob(b, "hatlevel", "level")
                }
                Group("crash") {
                    PanelKnob(b, "crashdecay", "decay")
                    PanelKnob(b, "crashtone", "tone")
                    PanelKnob(b, "crashlevel", "level")
                }
                Group("ride") {
                    PanelKnob(b, "ridedecay", "decay")
                    PanelKnob(b, "ridetone", "tone")
                    PanelKnob(b, "ridebell", "bell")
                    PanelKnob(b, "ridelevel", "level")
                }
            }
            else -> {
                Group("the room") {
                    PanelKnob(b, "comp", "compress", PanelAmber)
                    PanelKnob(b, "compattack", "attack")
                    PanelKnob(b, "comprelease", "release")
                    PanelKnob(b, "duck", "kick ducks", PanelAmber)
                }
                Group("circuit") {
                    PanelKnob(b, "drift", "drift", PanelAmber)
                    PanelKnob(b, "accent", "accent")
                }
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
}

// --- Resonance -------------------------------------------------------------------

private val RESONANCE_SHAPES = listOf("membrane", "bar", "plate", "tube", "bowl", "metal")

/**
 * Resonance's panel: one object at a time, chosen with the pads, since eight
 * sets of fourteen knobs is too many. The kit-wide controls are at the end,
 * including coupling, which ties the objects into one kit.
 */
@Composable
private fun ResonancePanel(b: ParamBinding, pad: Int) {
    val p = pad.coerceIn(0, 7)
    fun n(name: String) = "p%02d_%s".format(p, name)
    val hot = Acid.colors.accent
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.resonance_object, p + 1), color = hot, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                stringResource(Res.string.resonance_about),
                color = Acid.colors.textDim, fontSize = 10.sp, maxLines = 1,
            )
        }
        GroupRow {
            Group("object") {
                PanelStepKnob(b, n("kind"), RESONANCE_SHAPES, "shape", hot)
                PanelKnob(b, n("tune"), "tune", hot)
                PanelKnob(b, n("inharm"), "stiffness", hot)
            }
            Group("ring") {
                PanelKnob(b, n("decay"), "decay", hot)
                PanelKnob(b, n("damp"), "damping", hot)
            }
            Group("strike") {
                PanelKnob(b, n("hit"), "where", hot)
                PanelKnob(b, n("hard"), "hardness", hot)
                PanelKnob(b, n("noise"), "grit")
            }
            Group("bend") {
                PanelKnob(b, n("bend"), "amount")
                PanelKnob(b, n("bendtime"), "time")
            }
            Group("out") {
                PanelKnob(b, n("drive"), "drive", Acid.colors.pink)
                PanelKnob(b, n("level"), "level")
                PanelKnob(b, n("pan"), "pan")
                PanelKnob(b, n("couple"), "listens", hot)
            }
            Group("kit") {
                PanelKnob(b, "coupling", "coupling", hot)
                PanelStepKnob(b, "modes", listOf("4", "8", "12", "16", "20", "24"), "modes")
                PanelKnob(b, "humanise", "humanise")
                PanelKnob(b, "accent", "accent")
                PanelKnob(b, "volume", "volume")
                PanelKnob(b, "pan", "pan")
            }
        }
    }
}

// --- Dice ------------------------------------------------------------------------

private val DICE_CUTS = listOf("onsets", "grid")

/**
 * Molt: the take across the top, then what's done to it.
 *
 * The take comes first with its name (or "no take") and the two ways to get
 * one. There's no harmony section or key, the notes in the clip provide both.
 */
@Composable
private fun MoltPanel(
    b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor, onImport: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val c = Acid.colors
    val sample = track.machine.settings["sample"].orEmpty()
    if (recording) RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Record,
        inUse = editor.song.samplesInUse(),
        onPick = { rel ->
            recording = false
            editor.edit(trackIndex) { t -> t.withSetting("sample", rel) }
        },
        onDismiss = { recording = false },
    )
    PanelSections(listOf("take", "tune", "voice", "tone"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("take") {
                    Column(Modifier.widthIn(min = 150.dp, max = 280.dp)) {
                        Text(
                            sample.substringAfterLast('/').ifEmpty { stringResource(Res.string.machine_no_take) },
                            color = if (sample.isEmpty()) c.textDim else c.textHi,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Row {
                            TextButton(onClick = onImport) { Text(stringResource(Res.string.machine_import), color = c.textMid, fontSize = 11.sp) }
                            TextButton(onClick = { picking = true }) { Text(stringResource(Res.string.machine_samples), color = c.textMid, fontSize = 11.sp) }
                        }
                    }
                }
                // Recording goes through the record window, like every other
                // machine's material, as a file that can be trimmed and
                // levelled first.
                Group("sing") {
                    PanelActions(
                        Triple(stringResource(Res.string.machine_record), PanelAmber) { recording = true },
                    )
                }
                Group("phrase") {
                    PanelKnob(b, "start", "start", PanelAmber)
                    PanelSwitch(b, "loop", listOf("loop"))
                }
            }
            1 -> {
                Group("pull") {
                    PanelKnob(b, "tune", "amount", PanelAmber)
                    PanelKnob(b, "rate", "rate", PanelAmber)
                    PanelSwitch(b, "robot", listOf("robot"))
                }
                Group("note") {
                    PanelKnob(b, "glide", "glide")
                    PanelStepKnob(b, "bendrange", (0..24).map { "$it" }, "bend")
                    PanelStepKnob(b, "octave", (-3..3).map { "$it" }, "oct")
                    PanelStepKnob(b, "transpose", (-12..12).map { "$it" }, "semi")
                }
            }
            2 -> {
                Group("voice") {
                    PanelKnob(b, "formant", "formant", PanelAmber)
                    PanelKnob(b, "mega", "mega", PanelAmber)
                }
                Group("amp") {
                    PanelKnob(b, "ampattack", "att")
                    PanelKnob(b, "ampdecay", "dec")
                    PanelKnob(b, "ampsustain", "sus")
                    PanelKnob(b, "amprelease", "rel")
                    PanelKnob(b, "velocity", "vel")
                }
            }
            else -> {
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", TRINITY_FILTERS, "type")
                }
                Group("out") {
                    PanelKnob(b, "drive", "drive")
                    PanelKnob(b, "volume", "vol")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
    if (picking) RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Library,
        inUse = editor.song.samplesInUse(),
        onPick = { rel ->
            picking = false
            editor.edit(trackIndex) { t -> t.withSetting("sample", rel) }
        },
        onDismiss = { picking = false },
    )
}

/**
 * Dice's panel: the loop and where it's cut, the dice, then one slice at a
 * time, chosen with the pads like Forage chooses a pad.
 */
@Composable
private fun DicePanel(
    b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor, pad: Int, onImport: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val c = Acid.colors
    val p = pad.coerceIn(0, 15)
    fun n(name: String) = "s%02d_%s".format(p, name)
    val sample = track.machine.settings["sample"].orEmpty()
    // What the loop is to the song: the engine's guess at its bars (the bars
    // knob can override it) and its length, and so its tempo.
    val shape by produceState<Pair<Float, Float>?>(null, sample) {
        value = if (sample.isEmpty()) null else withContext(Dispatchers.IO) {
            NativeEngine.loopShape(com.rm.acidulous.io.File(com.rm.acidulous.engine.EngineAssets.userRoot(), sample).absolutePath)
        }
    }
    val barsAt = b.infoOf("bars")?.map(b.value("bars"))?.toInt() ?: 0
    val loopBars = if (barsAt == 0) shape?.first ?: 0f else DICE_BARS.getOrElse(barsAt - 1) { 0f }
    val loopBpm = shape?.second?.takeIf { it > 0f && loopBars > 0f }?.let { loopBars * 4f * 60f / it }
    PanelSections(
        listOf("loop", "dice", "slice", "tone"), section, { section = it },
        above = { sec ->
            if (sec == 2) {
                Text(
                    stringResource(Res.string.dice_slice, p + 1),
                    color = Acid.colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        },
    ) { sec ->
        when (sec) {
            0 -> {
                Group("loop") {
                    Column(Modifier.widthIn(min = 150.dp, max = 280.dp)) {
                        Text(
                            sample.substringAfterLast('/').ifEmpty { stringResource(Res.string.machine_no_loop) },
                            color = if (sample.isEmpty()) c.textDim else c.textHi,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (shape != null) {
                            Text(
                                if (loopBpm == null) stringResource(Res.string.dice_loop_no_tempo)
                                else pluralStringResource(
                                    Res.plurals.dice_loop_tempo, if (loopBars <= 1f) 1 else 2,
                                    if (loopBars < 1f) "½" else loopBars.toInt().toString(), kotlin.math.round(loopBpm).toInt(),
                                ),
                                color = if (loopBpm == null) c.accent else c.textMid,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            )
                        }
                        Row {
                            TextButton(onClick = onImport) { Text(stringResource(Res.string.machine_import), color = c.textMid, fontSize = 11.sp) }
                            TextButton(onClick = { picking = true }) { Text(stringResource(Res.string.machine_samples), color = c.textMid, fontSize = 11.sp) }
                        }
                    }
                }
                // Follow plays the loop at the song's tempo, stretched to keep
                // its pitch. Bars is what its own tempo is worked out from, and
                // auto is the engine's guess.
                Group("tempo") {
                    PanelSwitch(b, "follow", listOf("own", "song"), "plays at")
                    PanelStepKnob(b, "bars", listOf("auto", "½", "1", "2", "4", "8", "16"), "bars", PanelAmber)
                }
                Group("cut") {
                    PanelSwitch(b, "cut", DICE_CUTS, "at")
                    PanelStepKnob(b, "slices", (2..16).map { "$it" }, "how many", PanelAmber)
                }
                Group("play") {
                    PanelKnob(b, "gate", "gate", PanelAmber)
                    PanelKnob(b, "rate", "rate", PanelAmber)
                    PanelKnob(b, "pitch", "pitch")
                    PanelKnob(b, "fine", "fine")
                    PanelKnob(b, "accent", "accent")
                }
            }
            1 -> {
                Group("the odds") {
                    PanelKnob(b, "swap", "swap", PanelAmber)
                    PanelKnob(b, "reverse", "reverse", PanelAmber)
                    PanelKnob(b, "drop", "drop", PanelAmber)
                }
                Group("stutter") {
                    PanelKnob(b, "stutter", "chance", PanelAmber)
                    PanelStepKnob(b, "stutterdiv", (2..8).map { "$it" }, "times")
                }
                Group("jump") {
                    PanelKnob(b, "jump", "chance", PanelAmber)
                    PanelStepKnob(b, "jumprange", (1..12).map { "$it" }, "up to")
                }
                Group("the same roll") {
                    PanelSwitch(b, "hold", listOf("free", "hold"), "dice")
                    PanelStepKnob(b, "seed", (0..63).map { "$it" }, "seed")
                }
            }
            2 -> {
                // The group title is used in the lane list, so it stays
                // constant. The selected slice is shown above.
                Group("slice") {
                    PanelKnob(b, n("level"), "level", PanelAmber)
                    PanelKnob(b, n("pan"), "pan")
                    PanelKnob(b, n("pitch"), "pitch", PanelAmber)
                    PanelKnob(b, n("decay"), "decay", PanelAmber)
                    PanelSwitch(b, n("dir"), listOf("fwd", "rev"), "play")
                }
            }
            else -> {
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", CUMULUS_FILTERS, "type")
                }
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
    if (picking) RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Library,
        inUse = editor.song.samplesInUse(),
        onPick = { rel ->
            picking = false
            editor.edit(trackIndex) { t -> t.withSetting("sample", rel) }
        },
        onDismiss = { picking = false },
    )
}

/** The bars knob values past auto, as Dice's engine reads them. */
private val DICE_BARS = listOf(0.5f, 1f, 2f, 4f, 8f, 16f)

// --- Pollen ----------------------------------------------------------------------

private val POLLEN_SOURCES = listOf("sample", "live")
private val POLLEN_WINDOWS = listOf("hann", "tukey", "decay", "swell")
private val POLLEN_SCATTER = listOf("free", "8ve", "5ths", "triad", "scale")
private val POLLEN_SCALES = com.rm.acidulous.model.Scales.names
private val POLLEN_KEYS = com.rm.acidulous.model.Scales.keyNames

/**
 * Pollen's panel. The source section decides the machine (a file or the
 * microphone) and everything else shapes the cloud.
 *
 * It shows what the buffer currently holds, and warns when the source is live,
 * since a live buffer isn't saved in the song and won't be in an export.
 */
@Composable
private fun PollenPanel(b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor, onImport: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val c = Acid.colors
    val sample = track.machine.settings["sample"].orEmpty()
    val live = (b.value("source") ?: 0f) >= 0.5f
    PanelSections(listOf("source", "cloud", "spray", "pollen", "tone", "voice"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("buffer") {
                    PanelSwitch(b, "source", POLLEN_SOURCES, "read from")
                    PanelKnob(b, "buffer", "length", PanelAmber)
                    PanelSwitch(b, "freeze", listOf("roll", "hold"), "live")
                    MomentaryButton(b, "capture", "capture")
                }
                Group("file") {
                    Column(Modifier.widthIn(min = 140.dp, max = 260.dp)) {
                        Text(
                            sample.substringAfterLast('/').ifEmpty { stringResource(Res.string.machine_no_file) },
                            color = if (sample.isEmpty()) c.textDim else c.textHi,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (live) {
                            Text(
                                stringResource(Res.string.pollen_live),
                                color = c.accent, fontSize = 9.sp, maxLines = 2,
                            )
                        }
                        Row {
                            TextButton(onClick = onImport) { Text(stringResource(Res.string.machine_import), color = c.textMid, fontSize = 11.sp) }
                            TextButton(onClick = { picking = true }) { Text(stringResource(Res.string.machine_samples), color = c.textMid, fontSize = 11.sp) }
                        }
                    }
                }
                Group("listen") { InputListen() }
                Group("in") {
                    PanelKnob(b, "ingain", "gain", PanelAmber)
                    PanelKnob(b, "feedback", "feedback", PanelPink)
                    PanelKnob(b, "dry", "thru")
                }
            }
            1 -> {
                Group("grains") {
                    PanelKnob(b, "size", "size", PanelAmber)
                    PanelKnob(b, "sizespread", "· spread")
                    PanelKnob(b, "density", "density", PanelAmber)
                    PanelKnob(b, "jitter", "· jitter")
                }
                Group("shape") {
                    PanelStepKnob(b, "window", POLLEN_WINDOWS, "window")
                    PanelKnob(b, "skew", "skew")
                    PanelKnob(b, "reverse", "reverse")
                }
                Group("stereo") {
                    PanelKnob(b, "panspread", "spread", PanelAmber)
                    PanelKnob(b, "width", "width")
                }
            }
            2 -> {
                Group("where") {
                    PanelKnob(b, "position", "position", PanelAmber)
                    PanelKnob(b, "scan", "scan", PanelAmber)
                    PanelKnob(b, "spray", "spray", PanelAmber)
                    PanelKnob(b, "snap", "onset snap", PanelPink)
                }
                Group("pitch") {
                    PanelKnob(b, "pitch", "pitch", PanelAmber)
                    PanelKnob(b, "fine", "fine")
                    PanelKnob(b, "keytrack", "key track")
                }
                Group("scatter") {
                    PanelKnob(b, "spread", "amount", PanelAmber)
                    PanelStepKnob(b, "scatter", POLLEN_SCATTER, "onto")
                    PanelStepKnob(b, "scale", POLLEN_SCALES, "scale")
                    PanelStepKnob(b, "key", POLLEN_KEYS, "key")
                }
            }
            3 -> {
                Group("pollination") {
                    PanelKnob(b, "bloom", "bloom", PanelAmber)
                    PanelStepKnob(b, "generations", (1..6).map { "$it" }, "depth", PanelAmber)
                }
                Group("what changes") {
                    PanelKnob(b, "drift", "drift")
                    PanelKnob(b, "mutate", "mutate", PanelAmber)
                }
            }
            4 -> {
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", CUMULUS_FILTERS, "type")
                }
                Group("lo-fi") {
                    PanelStepKnob(b, "bits", (1..16).map { "$it" }, "bits", PanelPink)
                    PanelKnob(b, "crush", "rate", PanelPink)
                    PanelKnob(b, "wobble", "wobble")
                    PanelKnob(b, "wobblerate", "· rate")
                }
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
            else -> {
                Group("amp") {
                    PanelKnob(b, "ampattack", "A")
                    PanelKnob(b, "ampdecay", "D")
                    PanelKnob(b, "ampsustain", "S")
                    PanelKnob(b, "amprelease", "R")
                }
                Group("voice") {
                    PanelSwitch(b, "mono", listOf("poly", "mono"), "voices")
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "velocity", "velocity")
                    PanelStepKnob(b, "bendrange", (0..24).map { "$it" }, "bend")
                }
                Group("tune") {
                    PanelStepKnob(b, "octave", (-3..3).map { "$it" }, "octave")
                    PanelStepKnob(b, "transpose", (-12..12).map { "$it" }, "semis")
                }
            }
        }
    }
    if (picking) RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Library,
        inUse = editor.song.samplesInUse(),
        onPick = { rel ->
            picking = false
            editor.edit(trackIndex) { t -> t.withSetting("sample", rel) }
        },
        onDismiss = { picking = false },
    )
}

/**
 * A control that's an action instead of a value: it sets the parameter and
 * releases it. The engine acts on the edge and reads the raw target instead of
 * the smoothed value, so a pulse can't be smoothed away.
 */
@Composable
private fun MomentaryButton(b: ParamBinding, name: String, label: String) {
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        b.set(name, 1f)
        scope.launch { delay(120); b.set(name, 0f) }
    }) { Text(panelWord(label), color = Acid.colors.accent, fontSize = 12.sp) }
}

// --- Bias -------------------------------------------------------------------------

/**
 * The four-track's panel: a card per lane, and each lane's card holds its
 * recording.
 *
 * It's the one panel that depends on the cell as well as the machine. Each card
 * is split: the level and mute are parameters, so they automate, map and record
 * into lanes, and the take is part of the clip.
 *
 * Four cards and no section chips, since a tape has four lanes.
 */
@Composable
private fun BiasPanel(b: ParamBinding, track: Track, trackIndex: Int, sceneId: String, editor: SongEditor) {
    var section by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val c = Acid.colors
    val song = editor.song
    val clip = track.clips[sceneId]
    val sceneBpm = if (sceneId.isEmpty()) song.tempo else song.bpmOf(sceneId)
    // What's on the tape, what the tape is, and tempo. The patch bar sets the
    // medium section wholesale.
    PanelSections(listOf("lanes", "medium", "tempo"), section, { section = it }) { sec ->
        if (sec >= 1) {
          if (sec == 1) {
            Group("band") {
                PanelKnob(b, "lowcut", "low cut", PanelAmber)
                PanelKnob(b, "highcut", "high cut", PanelAmber)
                PanelKnob(b, "bump", "head bump")
                PanelKnob(b, "bumpfreq", "· at")
            }
            Group("noise") {
                PanelKnob(b, "hiss", "hiss", PanelPink)
                PanelKnob(b, "hisstone", "· tone")
                PanelKnob(b, "drop", "dropouts", PanelPink)
                PanelKnob(b, "bleed", "bleed", PanelPink)
            }
            Group("transport") {
                PanelKnob(b, "wow", "wow", PanelAmber)
                PanelKnob(b, "flutter", "flutter", PanelAmber)
                PanelKnob(b, "speed", "· rate")
            }
            Group("level") {
                PanelKnob(b, "sat", "saturate", PanelPink)
                PanelKnob(b, "comp", "squash", PanelPink)
            }
            Group("digital") {
                PanelStepKnob(b, "bits", (4..24).map { "$it" }, "bits", PanelPink)
                PanelKnob(b, "rate", "rate", PanelPink)
                PanelKnob(b, "smear", "smear")
            }
            Group("out") {
                PanelKnob(b, "width", "width")
                PanelKnob(b, "gain", "gain", PanelAmber)
            }
        } else if (sec == 2) {
            Group("tempo") {
                PanelSwitch(b, "stretch", listOf("as sung", "follow"), "takes")
            }
            Group("input") {
                PanelKnob(b, "monitor", "monitor", PanelPink)
                InputListen()
            }
            // The song's input effects, which are printed into the take. They
            // belong to the song, not this track, but this is where you are
            // when recording.
            Group("printed in") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InputChainChips(editor)
                }
            }
          }
            return@PanelSections
        }
        if (sceneId.isNotEmpty()) Group("flatten") {
            CompButton(trackIndex, sceneId, editor, scope) { message, args ->
                com.rm.acidulous.engine.EngineSync.onProblem?.invoke(message, args)
            }
        }
        for (lane in 0 until BIAS_LANES) {
            val take = clip?.audio?.lane(lane)
            Group("lane ${lane + 1}") {
                Column(Modifier.widthIn(min = 116.dp, max = 200.dp)) {
                    Text(
                        take?.file?.substringAfterLast('/') ?: "empty",
                        color = if (take == null) c.textDim else c.textHi,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    // The tempo it was recorded at, when it differs from the
                    // current tempo and the track isn't following it. This
                    // explains a take drifting off the beat.
                    if (take != null && !track.followsTempo() &&
                        kotlin.math.abs(take.bpm - sceneBpm) > 0.05f) {
                        Text(
                            stringResource(Res.string.bias_take_bpm, take.bpm), color = c.accent,
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        )
                    }
                    Row {
                        TextButton(onClick = { picking = lane }) {
                            Text(stringResource(Res.string.bias_audio), color = c.textMid, fontSize = 11.sp)
                        }
                        if (take != null) TextButton(onClick = {
                            editor.editClip(trackIndex, sceneId) { cl -> cl.withTake(lane, null) }
                        }) { Text(stringResource(Res.string.bias_clear), color = c.textMid, fontSize = 11.sp) }
                    }
                }
                PanelKnob(b, "lane${lane + 1}", "level")
                // Labelled for the automation list, otherwise inside a card
                // titled "lane 2" the list would read "lane 2 lane".
                PanelSwitch(b, "mute${lane + 1}", listOf("on", "mute"), "mute")
            }
        }
    }
    if (picking >= 0) TakePicker(trackIndex, sceneId, picking, editor, scope) { picking = -1 }
}

// --- Filament ---------------------------------------------------------------
//
// The exciter first, since that's the instrument choice here (the same string
// plucked, struck, bowed or blown), then the string, then its surroundings.

private val FILAMENT_EXCITERS = listOf("pluck", "pick", "hammer", "bow", "breath", "input")
private val FILAMENT_SYM = listOf("octaves", "fifths", "major", "minor", "harmonic", "course")
private val FILAMENT_SOURCES = listOf(
    "off", "on", "mod", "prs", "vel", "key", "rand", "eg1", "eg2", "lfo1", "lfo2", "ring",
)
private val FILAMENT_DESTS = listOf(
    "off", "pitch", "sustain", "tone", "bright", "position", "pressure", "damper at", "damper",
    "rattle", "stiffness", "tension", "sympathy", "detune", "body", "drive", "volume", "pan",
)
private val FILAMENT_SYNC = listOf("free", "1/1", "1/2", "1/4", "1/8", "1/8T")

@Composable
private fun FilamentPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("exciter", "string", "prepare", "around", "mod", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("exciter") {
                    PanelStepKnob(b, "exciter", FILAMENT_EXCITERS, "kind", PanelAmber)
                    PanelKnob(b, "position", "where", PanelAmber)
                    PanelKnob(b, "hardness", "hardness")
                    PanelKnob(b, "length", "length")
                    PanelKnob(b, "grit", "grit", PanelPink)
                }
                Group("sustained") {
                    PanelKnob(b, "pressure", "pressure", PanelAmber)
                    PanelKnob(b, "speed", "speed", PanelAmber)
                    PanelKnob(b, "in gain", "in gain")
                }
                // The input exciter plays the string with the incoming audio,
                // so it needs an input.
                Group("listen") { InputListen() }
                Group("touch") {
                    PanelKnob(b, "velocity", "velocity", PanelAmber)
                    PanelSwitch(b, "on release", listOf("ring", "damp"), "let go")
                    PanelKnob(b, "damp time", "damp time")
                }
            }
            1 -> {
                Group("string") {
                    PanelKnob(b, "sustain", "sustain", PanelAmber)
                    PanelKnob(b, "sustainkey", "· by key")
                    PanelKnob(b, "tone", "tone", PanelAmber)
                    PanelKnob(b, "tonekey", "· by key")
                }
                Group("stiffness") {
                    PanelKnob(b, "stiffness", "amount", PanelAmber)
                    PanelKnob(b, "stages", "stages")
                    PanelKnob(b, "tension", "tension", PanelPink)
                }
                Group("course") {
                    PanelKnob(b, "detune", "detune", PanelAmber)
                    PanelKnob(b, "couple", "couple")
                    PanelKnob(b, "spread", "spread")
                }
            }
            2 -> {
                Group("damper") {
                    PanelKnob(b, "damper at", "where", PanelAmber)
                    PanelKnob(b, "damper", "pressure", PanelAmber)
                }
                Group("rattle") {
                    PanelKnob(b, "rattle", "amount", PanelPink)
                    PanelKnob(b, "rattle at", "above")
                }
                Group("sympathy") {
                    PanelSwitch(b, "sympathy", listOf("off", "on"), "strings")
                    PanelStepKnob(b, "symtune", FILAMENT_SYM, "tuned", PanelAmber)
                    PanelKnob(b, "symlevel", "level", PanelAmber)
                    PanelKnob(b, "symsustain", "sustain")
                    PanelKnob(b, "symwide", "spread")
                }
            }
            3 -> {
                Group("body") {
                    PanelSwitch(b, "body", listOf("off", "on"), "body")
                    PanelKnob(b, "size", "size", PanelAmber)
                    PanelKnob(b, "bodymix", "mix", PanelAmber)
                    PanelKnob(b, "bodydamp", "damp")
                }
                Group("tuning") {
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "bend", "bend")
                    PanelKnob(b, "mpetimbre", "slide")
                    PanelKnob(b, "mpepressure", "press")
                    PanelKnob(b, "octave", "octave")
                    PanelKnob(b, "transpose", "transpose")
                    PanelKnob(b, "fine", "fine")
                }
            }
            4 -> {
                Group("lfo 1") {
                    PanelStepKnob(b, "lfo1wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "lfo1rate", "rate", PanelAmber)
                    PanelStepKnob(b, "lfo1sync", FILAMENT_SYNC, "sync")
                    PanelKnob(b, "lfo1depth", "depth")
                }
                Group("lfo 2") {
                    PanelStepKnob(b, "lfo2wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, "lfo2rate", "rate", PanelAmber)
                    PanelStepKnob(b, "lfo2sync", FILAMENT_SYNC, "sync")
                    PanelKnob(b, "lfo2depth", "depth")
                }
                Group("eg 1") {
                    PanelKnob(b, "eg1atk", "attack"); PanelKnob(b, "eg1dec", "decay")
                    PanelKnob(b, "eg1sus", "sustain"); PanelKnob(b, "eg1rel", "release")
                }
                Group("eg 2") {
                    PanelKnob(b, "eg2atk", "attack"); PanelKnob(b, "eg2dec", "decay")
                    PanelKnob(b, "eg2sus", "sustain"); PanelKnob(b, "eg2rel", "release")
                }
                for (m in 1..8) key(m) { Group("mod $m") {
                    PanelStepKnob(b, "m${m}_src", FILAMENT_SOURCES, "from", PanelAmber)
                    PanelStepKnob(b, "m${m}_dst", FILAMENT_DESTS, "to", PanelAmber)
                    PanelKnob(b, "m${m}_amt", "amount", PanelAmber)
                } }
            }
            else -> {
                Group("out") {
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "exciter out", "exciter")
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
}

// --- Brazen -----------------------------------------------------------------
//
// The horn first, since it's the instrument: tube size, bell, and mute. Then
// the player: lips, air and effort. Then the section, which has no equivalent
// in a sampled brass library.

private val BRAZEN_MUTES = listOf("open", "straight", "cup", "harmon")

@Composable
private fun BrazenPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("horn", "player", "section", "shape", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("tube") {
                    PanelKnob(b, "size", "tuba→trpt", PanelAmber)
                    PanelKnob(b, "bell", "bell", PanelAmber)
                    PanelKnob(b, "loss", "loss")
                }
                Group("mute") {
                    PanelStepKnob(b, "mute", BRAZEN_MUTES, "kind", PanelAmber)
                    PanelKnob(b, "mutetone", "tone")
                }
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", CUMULUS_FILTERS, "type")
                }
            }
            1 -> {
                Group("lips") {
                    PanelKnob(b, "tension", "tension", PanelAmber)
                    PanelKnob(b, "lipdamp", "damping", PanelAmber)
                    PanelKnob(b, "bite", "bite", PanelPink)
                }
                Group("air") {
                    PanelKnob(b, "pressure", "pressure", PanelAmber)
                    PanelKnob(b, "breath", "breath")
                    PanelKnob(b, "mpetimbre", "slide")
                    PanelKnob(b, "brass", "brassiness", PanelPink)
                }
                Group("growl") {
                    PanelKnob(b, "growl", "growl", PanelPink)
                    PanelKnob(b, "growlrate", "rate")
                }
            }
            2 -> {
                Group("players") {
                    PanelStepKnob(b, "players", listOf("1", "2", "3", "4"), "how many", PanelAmber)
                    PanelKnob(b, "spread", "spread", PanelAmber)
                    PanelKnob(b, "scatter", "scatter")
                    PanelKnob(b, "width", "width")
                }
                Group("listening") {
                    PanelKnob(b, "lock", "lock", PanelPink)
                    PanelKnob(b, "drift", "drift", PanelPink)
                }
            }
            3 -> {
                Group("envelope") {
                    PanelKnob(b, "attack", "attack"); PanelKnob(b, "decay", "decay")
                    PanelKnob(b, "sustain", "sustain"); PanelKnob(b, "release", "release")
                }
                Group("vibrato") {
                    PanelKnob(b, "vibrato", "depth", PanelAmber)
                    PanelKnob(b, "vibratorate", "rate")
                    PanelKnob(b, "vibratodelay", "delay")
                }
                Group("tuning") {
                    PanelSwitch(b, "mono", listOf("poly", "mono"), "mode")
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "bendrange", "bend")
                    PanelKnob(b, "octave", "octave")
                    PanelKnob(b, "transpose", "transpose")
                    PanelKnob(b, "fine", "fine")
                }
            }
            else -> {
                Group("out") {
                    PanelKnob(b, "velocity", "velocity", PanelAmber)
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
}

// --- Timber -----------------------------------------------------------------
//
// The pipe first, since the excitation and bore shape between them pick the
// woodwind family. Then the mouth, then the tone holes.

/** The vowels Diction sweeps, back of the mouth to the front. Sounds, not words, so not translated. */
private val VOWEL_NAMES = listOf("oo", "oh", "ah", "eh", "ee")
/** In the order of model.lyrics.Accent. */
private val DICTION_ACCENTS = listOf("prairie", "central", "american")

/**
 * The vowel is a knob rather than a switch so it can sweep between them, and
 * it says which it's on, or which two it's between.
 */
@Composable
private fun PanelVowelKnob(b: ParamBinding, name: String, label: String = name) {
    val key = com.rm.acidulous.model.laneKey(b.unit, name)
    val at = b.value(name) * (VOWEL_NAMES.size - 1)
    val lower = at.toInt().coerceIn(0, VOWEL_NAMES.size - 2)
    val t = at - lower
    val shown = when {
        t < 0.15f -> VOWEL_NAMES[lower]
        t > 0.85f -> VOWEL_NAMES[lower + 1]
        else -> VOWEL_NAMES[lower] + "–" + VOWEL_NAMES[lower + 1]
    }
    Knob(
        label = panelWord(label), value = b.value(name), display = shown, accent = PanelAmber,
        automated = key in AutomationMarks.lanes && key !in AutomationMarks.locks,
        locked = key in AutomationMarks.locks,
        modifier = Modifier.mappable(MapTargets.param(b.trackIndex, b.unit, name)).then(panelKnobWidth()),
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
        onReset = { b.reset(name) },
    )
}

/** Where a recorded voice's consonant is taken from: sung between ahs, ees or oos, or the word's nearest. */
private val DICTION_FROM = listOf("ah", "ee", "oo", "word")

/**
 * Which of a recorded voice's takes each consonant is formed from, a knob
 * each, in three pages by how the consonant is made. Saved with the track.
 */
@Composable
private fun DictionConsonantsWindow(b: ParamBinding, onDismiss: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    // Written out, not looped, so the label generator sees every knob.
    TabbedDialog(
        title = stringResource(Res.string.diction_consonants_title),
        selected = page,
        onDismiss = onDismiss,
        pageNames = listOf(panelWord("stops"), panelWord("hisses"), panelWord("hums & glides")),
        onSelectPage = { page = it },
        pages = listOf(
            {
                WindowCards {
                    WindowCard(panelWord("stops")) {
                        PanelStepKnob(b, "fromp", DICTION_FROM, "p", PanelAmber)
                        PanelStepKnob(b, "fromb", DICTION_FROM, "b", PanelAmber)
                        PanelStepKnob(b, "fromt", DICTION_FROM, "t", PanelAmber)
                        PanelStepKnob(b, "fromd", DICTION_FROM, "d", PanelAmber)
                        PanelStepKnob(b, "fromk", DICTION_FROM, "k", PanelAmber)
                        PanelStepKnob(b, "fromg", DICTION_FROM, "g", PanelAmber)
                        PanelStepKnob(b, "fromch", DICTION_FROM, "ch", PanelAmber)
                        PanelStepKnob(b, "fromjh", DICTION_FROM, "j", PanelAmber)
                    }
                }
            },
            {
                WindowCards {
                    // The two th's by a word with each.
                    WindowCard(panelWord("hisses")) {
                        PanelStepKnob(b, "fromf", DICTION_FROM, "f", PanelAmber)
                        PanelStepKnob(b, "fromv", DICTION_FROM, "v", PanelAmber)
                        PanelStepKnob(b, "fromth", DICTION_FROM, "thin", PanelAmber)
                        PanelStepKnob(b, "fromdh", DICTION_FROM, "this", PanelAmber)
                        PanelStepKnob(b, "froms", DICTION_FROM, "s", PanelAmber)
                        PanelStepKnob(b, "fromz", DICTION_FROM, "z", PanelAmber)
                        PanelStepKnob(b, "fromsh", DICTION_FROM, "sh", PanelAmber)
                        PanelStepKnob(b, "fromzh", DICTION_FROM, "zh", PanelAmber)
                    }
                    WindowCard(panelWord("breath")) { PanelStepKnob(b, "fromhh", DICTION_FROM, "h", PanelAmber) }
                }
            },
            {
                WindowCards {
                    WindowCard(panelWord("hums")) {
                        PanelStepKnob(b, "fromm", DICTION_FROM, "m", PanelAmber)
                        PanelStepKnob(b, "fromn", DICTION_FROM, "n", PanelAmber)
                        PanelStepKnob(b, "fromng", DICTION_FROM, "ng", PanelAmber)
                    }
                    WindowCard(panelWord("glides")) {
                        PanelStepKnob(b, "froml", DICTION_FROM, "l", PanelAmber)
                        PanelStepKnob(b, "fromr", DICTION_FROM, "r", PanelAmber)
                        PanelStepKnob(b, "fromw", DICTION_FROM, "w", PanelAmber)
                        PanelStepKnob(b, "fromy", DICTION_FROM, "y", PanelAmber)
                    }
                }
            },
        ),
    )
}

/** Which voice a Diction sings in: its own, or one recorded in the Sound window. */
@Composable
private fun DictionVoiceKnob(track: Track, trackIndex: Int, editor: SongEditor) {
    val names = remember { com.rm.acidulous.model.voice.VoiceBank.all(EngineAssets.userRoot()).map { it.name } }
    val choices = listOf(stringResource(Res.string.diction_builtin)) + names
    val setting = track.machine.settings[com.rm.acidulous.model.voice.VoiceBank.SETTING].orEmpty()
    val index = names.indexOf(com.rm.acidulous.model.voice.VoiceBank.nameOf(setting)) + 1
    val last = (choices.size - 1).coerceAtLeast(1)
    Knob(
        label = panelWord("voice"), value = index.toFloat() / last, display = choices.getOrElse(index) { choices[0] },
        steps = choices.size, accent = PanelAmber, modifier = panelKnobWidth(),
        onChange = { v ->
            val i = (v * last).roundToInt().coerceIn(0, choices.size - 1)
            val want = if (i == 0) null else com.rm.acidulous.model.voice.VoiceBank.settingOf(names[i - 1])
            if (want.orEmpty() != setting) editor.edit(trackIndex) { t -> t.withSetting(com.rm.acidulous.model.voice.VoiceBank.SETTING, want) }
        },
    )
}

@Composable
private fun DictionPanel(b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor) {
    var section by rememberSaveable { mutableStateOf(0) }
    var forming by remember { mutableStateOf(false) }
    if (forming) DictionConsonantsWindow(b) { forming = false }
    PanelSections(listOf("voice", "character", "expression", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("voice") {
                    DictionVoiceKnob(track, trackIndex, editor)
                    PanelVowelKnob(b, "vowel", "vowel")
                    PanelKnob(b, "formant", "formant", PanelAmber)
                    PanelKnob(b, "track", "track")
                }
                Group("words") {
                    PanelKnob(b, "consonants", "consonants", PanelPink)
                    PanelKnob(b, "consonantlevel", "level")
                    PanelStepKnob(b, "accent", DICTION_ACCENTS, "accent", PanelAmber)
                    PanelActions(Triple(stringResource(Res.string.diction_from), PanelAmber) { forming = true })
                }
            }
            1 -> {
                Group("air") {
                    PanelKnob(b, "breath", "breath")
                    PanelKnob(b, "clean", "clean", PanelPink)
                    PanelKnob(b, "whisper", "whisper", PanelAmber)
                }
                Group("tone") {
                    PanelKnob(b, "effort", "effort", PanelAmber)
                    PanelKnob(b, "rasp", "rasp")
                    PanelKnob(b, "growl", "growl", PanelPink)
                }
                Group("choir") {
                    PanelKnob(b, "singers", "singers", PanelAmber)
                    PanelKnob(b, "spread", "spread")
                    PanelSwitch(b, "harmony", listOf("off", "on"), "harmony")
                }
            }
            2 -> {
                Group("vibrato") {
                    PanelKnob(b, "vibrato", "depth", PanelAmber)
                    PanelKnob(b, "vibratorate", "rate")
                    PanelKnob(b, "vibratodelay", "delay")
                }
                Group("singer") {
                    PanelKnob(b, "drift", "drift", PanelPink)
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "scoop", "scoop", PanelAmber)
                }
            }
            else -> {
                Group("envelope") {
                    PanelKnob(b, "attack", "attack")
                    PanelKnob(b, "release", "release")
                    PanelKnob(b, "velocity", "velocity")
                }
                Group("tuning") {
                    PanelKnob(b, "bendrange", "bend")
                    PanelKnob(b, "octave", "octave")
                    PanelKnob(b, "transpose", "transpose")
                }
                Group("out") {
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
}

private val TIMBER_FAMILY = listOf("reed", "double", "air")
private val TIMBER_BORE = listOf("cylinder", "cone")
private val TIMBER_REGISTER = listOf("natural", "register", "altissimo")

@Composable
private fun TimberPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections(listOf("pipe", "mouth", "holes", "tongue", "shape", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("pipe") {
                    PanelStepKnob(b, "family", TIMBER_FAMILY, "started by", PanelAmber)
                    PanelStepKnob(b, "bore", TIMBER_BORE, "shape", PanelAmber)
                    PanelKnob(b, "body", "lowest", PanelAmber)
                }
                Group("end") {
                    PanelKnob(b, "bell", "bell")
                    PanelKnob(b, "loss", "loss")
                }
                Group("filter") {
                    PanelKnob(b, "cutoff", "cutoff", PanelAmber)
                    PanelKnob(b, "resonance", "reso", PanelAmber)
                    PanelStepKnob(b, "filtertype", CUMULUS_FILTERS, "type")
                }
            }
            1 -> {
                Group("reed") {
                    PanelKnob(b, "reed", "stiffness", PanelAmber)
                    PanelKnob(b, "embouchure", "embouchure", PanelAmber)
                }
                Group("air") {
                    PanelKnob(b, "pressure", "pressure", PanelAmber)
                    PanelKnob(b, "breath", "breath")
                    PanelKnob(b, "mpetimbre", "slide")
                }
                Group("jet") {
                    PanelKnob(b, "jet", "crossing", PanelPink)
                    PanelKnob(b, "aim", "aim", PanelPink)
                }
            }
            2 -> {
                Group("lattice") {
                    PanelKnob(b, "lattice", "cutoff", PanelAmber)
                    PanelKnob(b, "holes", "holes")
                    PanelStepKnob(b, "register", TIMBER_REGISTER, "vent", PanelAmber)
                }
                Group("below") {
                    PanelKnob(b, "fingering", "forked", PanelPink)
                    PanelKnob(b, "below", "length", PanelPink)
                    PanelKnob(b, "answer", "answers", PanelPink)
                }
            }
            3 -> {
                Group("tongue") {
                    PanelKnob(b, "tongue", "depth", PanelAmber)
                    PanelKnob(b, "tonguetime", "time")
                }
                Group("flutter") {
                    PanelKnob(b, "flutter", "amount", PanelPink)
                    PanelKnob(b, "flutterrate", "rate")
                }
                Group("keys") { PanelKnob(b, "keys", "key noise") }
            }
            4 -> {
                Group("envelope") {
                    PanelKnob(b, "attack", "attack"); PanelKnob(b, "decay", "decay")
                    PanelKnob(b, "sustain", "sustain"); PanelKnob(b, "release", "release")
                }
                Group("vibrato") {
                    PanelKnob(b, "vibrato", "depth", PanelAmber)
                    PanelKnob(b, "vibratorate", "rate")
                    PanelKnob(b, "vibratodelay", "delay")
                }
                Group("tuning") {
                    PanelSwitch(b, "mono", listOf("poly", "mono"), "mode")
                    PanelKnob(b, "glide", "glide")
                    PanelKnob(b, "bendrange", "bend")
                    PanelKnob(b, "octave", "octave")
                    PanelKnob(b, "transpose", "transpose")
                    PanelKnob(b, "fine", "fine")
                }
            }
            else -> {
                Group("out") {
                    PanelKnob(b, "velocity", "velocity", PanelAmber)
                    PanelKnob(b, "drive", "drive", PanelPink)
                    PanelKnob(b, "volume", "volume")
                    PanelKnob(b, "pan", "pan")
                }
            }
        }
    }
}

// --- Nexus ------------------------------------------------------------------
//
// The panel holds what you reach for while playing (the macros, the morph,
// the output) and a way through to the graph canvas.

@Composable
private fun NexusPanel(b: ParamBinding, track: Track, onOpenPatch: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(0) }
    val patch = remember(track.machine.settings["nexus"]) {
        com.rm.acidulous.model.NexusPatch.decode(track.machine.settings["nexus"])
    }
    PanelSections(listOf("patch", "macros", "voice", "out"), section, { section = it }) { sec ->
        when (sec) {
            0 -> {
                Group("patch") {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            listOf(
                                pluralStringResource(Res.plurals.nexus_modules, patch.modules.size, patch.modules.size),
                                pluralStringResource(Res.plurals.nexus_cables, patch.cables.size, patch.cables.size),
                            ).joinToString(" · "),
                            color = Acid.colors.text, fontSize = 11.sp, maxLines = 1)
                        Text(patch.modules.take(6).joinToString(" ") { it.type },
                            color = Acid.colors.textDim, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1)
                        TextButton(onClick = onOpenPatch) {
                            Text(stringResource(Res.string.nexus_patch), color = PanelAmber, fontSize = 11.sp)
                        }
                    }
                }
                Group("morph") { PanelKnob(b, "morph", "A→B", PanelAmber) }
            }
            1 -> Group("macros") { for (i in 1..8) PanelKnob(b, "macro$i", "$i", PanelAmber) }
            2 -> Group("voice") {
                PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg"), "mode")
                PanelKnob(b, "glide", "glide")
                PanelKnob(b, "bend", "bend")
                PanelKnob(b, "octave", "octave")
                PanelKnob(b, "transpose", "transpose")
                PanelKnob(b, "fine", "fine")
            }
            else -> Group("out") {
                PanelKnob(b, "volume", "volume")
                PanelKnob(b, "velocity", "vel")
                PanelKnob(b, "pan", "pan")
                PanelKnob(b, "drive", "drive", PanelPink)
            }
        }
    }
}

// --- Mosaic ----------------------------------------------------------------------
//
// The map section shows the key-by-velocity map above its group row. The
// other sections are ordinary Groups.

val MOSAIC_LOOP = listOf("file", "off", "forward")
val MOSAIC_ENVFROM = listOf("panel", "file")
val MOSAIC_SOURCES = listOf("off", "on", "mod", "prs", "vel", "key", "rand", "aeg", "feg", "eg1", "eg2", "lfo1", "lfo2")
val MOSAIC_DESTS = listOf(
    "off", "pitch", "scan", "start", "g.pos", "g.rate", "g.size", "g.dens", "g.spray", "g.pitch",
    "f.freq", "f.res", "amp", "pan", "l1rate", "l2rate",
)

@Composable
private fun MosaicPanel(
    b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor,
    onImportSoundFont: () -> Unit, onPickPreset: () -> Unit, onImportZoneSamples: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(0) }
    var selectedZone by rememberSaveable(trackIndex) { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var pickingZone by remember { mutableStateOf(false) }
    val zones = remember(track.machine.settings["zones"]) { Zones.decode(track.machine.settings["zones"]) }
    val sf2 = track.machine.settings["sf2"].orEmpty()
    var info by remember(trackIndex) { mutableStateOf("") }
    LaunchedEffect(trackIndex, sf2, zones.size) {
        while (true) { info = NativeEngine.sampleMapInfo(trackIndex); delay(500) }
    }
    // A sample recorded in the app is added as a zone like an imported one. A
    // SoundFont owns the whole map, so it's not offered then.
    if (pickingZone) RecorderDialog(
        editor = editor,
        startOn = RecorderPage.Library,
        inUse = editor.song.samplesInUse(),
        onPick = { rel ->
            pickingZone = false
            editor.edit(trackIndex) { t ->
                t.withSetting("sf2", null).withSetting("sf2preset", null)
                    .withSetting("zones", Zones.encode(zones + com.rm.acidulous.model.Zone(path = rel)))
            }
        },
        onDismiss = { pickingZone = false },
    )

    fun putZones(list: List<Zone>) =
        editor.edit(trackIndex) { t -> t.withSetting("zones", if (list.isEmpty()) null else Zones.encode(list)) }

    PanelSections(
        listOf("map", "sample", "grain", "filter", "env", "lfo", "mod", "voice"), section, { section = it },
        above = { sec ->
            if (sec == 0) {
                if (sf2.isEmpty()) {
                    ZoneMapView(zones, selectedZone, { i -> selectedZone = i; editing = true },
                        Modifier.fillMaxWidth().height(96.dp).padding(bottom = 4.dp))
                } else {
                    // A SoundFont preset brings its own map, so it's shown instead
                    // of edited.
                    Text(
                        stringResource(Res.string.mosaic_soundfont, sf2.substringAfterLast('/'), info.ifEmpty { stringResource(Res.string.mosaic_loading) }),
                        color = Acid.colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp), maxLines = 1,
                    )
                }
            }
        },
    ) { sec ->
        when (sec) {
            0 -> {
                Group("instrument") {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(info.split('|').firstOrNull().orEmpty().ifEmpty { stringResource(Res.string.mosaic_nothing) },
                            color = Acid.colors.text, fontSize = 11.sp, maxLines = 1)
                        Text(
                            info.split('|').let { f ->
                                if (f.size >= 4) stringResource(Res.string.mosaic_preset_info, f[1], f[2], f[3].toFloatOrNull() ?: 0f)
                                else " "
                            },
                            color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        )
                        Row {
                            TextButton(onClick = onImportSoundFont) { Text(stringResource(Res.string.mosaic_import_soundfont), color = PanelAmber, fontSize = 10.sp) }
                            if (sf2.isNotEmpty()) TextButton(onClick = onPickPreset) { Text(stringResource(Res.string.mosaic_preset), color = PanelAmber, fontSize = 10.sp) }
                            TextButton(onClick = onImportZoneSamples) { Text(stringResource(Res.string.mosaic_samples), color = Acid.colors.textMid, fontSize = 10.sp) }
                            TextButton(onClick = { pickingZone = true }) { Text(stringResource(Res.string.mosaic_samples), color = Acid.colors.textMid, fontSize = 10.sp) }
                        }
                    }
                }
                if (sf2.isEmpty()) Group("zones") {
                    Column {
                        Text(pluralStringResource(Res.plurals.mosaic_zones, zones.size, zones.size), color = Acid.colors.text, fontSize = 11.sp)
                        Row {
                            TextButton(
                                onClick = {
                                    // Spread them evenly and set each root to the middle of its
                                    // span.
                                    if (zones.isNotEmpty()) {
                                        val step = 128f / zones.size
                                        putZones(zones.mapIndexed { i, z ->
                                            val lo = (i * step).toInt()
                                            val hi = if (i == zones.lastIndex) 127 else ((i + 1) * step).toInt() - 1
                                            z.copy(lowKey = lo, highKey = hi, rootKey = (lo + hi) / 2)
                                        })
                                    }
                                },
                                enabled = zones.size > 1,
                            ) { Text(stringResource(Res.string.mosaic_spread), color = PanelAmber, fontSize = 10.sp) }
                            TextButton(onClick = { putZones(emptyList()) }, enabled = zones.isNotEmpty()) {
                                Text(stringResource(Res.string.mosaic_clear), color = Acid.colors.red, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Group("blend") {
                    PanelKnob(b, "keyfade", "key xf", PanelAmber)
                    PanelKnob(b, "velfade", "vel xf", PanelAmber)
                    PanelKnob(b, "scan", "scan", PanelAmber)
                    PanelKnob(b, "scanamt", "amount", PanelAmber)
                }
            }
            1 -> {
                Group("playback") {
                    PanelKnob(b, "start", "start", PanelAmber)
                    PanelSwitch(b, "loop", MOSAIC_LOOP, "loop")
                    PanelSwitch(b, "reverse", listOf("fwd", "rev"), "dir")
                    PanelSwitch(b, "envfrom", MOSAIC_ENVFROM, "env from")
                    PanelSwitch(b, "filemod", listOf("ignore", "honour"), "file mod")
                }
                Group("tuning") {
                    PanelKnob(b, "coarse", "coarse")
                    PanelKnob(b, "fine", "fine")
                    PanelKnob(b, "octave", "octave")
                    PanelKnob(b, "transpose", "transpose")
                }
            }
            2 -> Group("grains") {
                PanelSwitch(b, "grain", listOf("off", "on"), "cloud")
                PanelKnob(b, "gpos", "position", PanelAmber)
                PanelKnob(b, "grate", "rate", PanelAmber)
                PanelKnob(b, "gsize", "size", PanelAmber)
                PanelKnob(b, "gdensity", "density", PanelAmber)
                PanelKnob(b, "gspray", "spray", PanelAmber)
                PanelKnob(b, "gpitch", "pitch", PanelAmber)
            }
            3 -> {
                Group("filter") {
                    PanelStepKnob(b, "f_type", TRINITY_FILTERS, "type", PanelAmber)
                    PanelKnob(b, "f_freq", "freq", PanelAmber)
                    PanelKnob(b, "f_res", "reso", PanelAmber)
                    PanelKnob(b, "f_env", "envmod")
                    PanelKnob(b, "f_key", "key")
                    PanelKnob(b, "veltofilter", "vel")
                }
                Group("filter env") {
                    PanelKnob(b, "f_attack", "attack")
                    PanelKnob(b, "f_decay", "decay")
                    PanelKnob(b, "f_sustain", "sustain")
                    PanelKnob(b, "f_release", "release")
                }
            }
            4 -> {
                Group("amp env") {
                    PanelKnob(b, "a_attack", "attack", PanelAmber)
                    PanelKnob(b, "a_decay", "decay", PanelAmber)
                    PanelKnob(b, "a_sustain", "sustain", PanelAmber)
                    PanelKnob(b, "a_release", "release", PanelAmber)
                }
                for (e in 1..2) key(e) { Group("env $e") {
                    PanelKnob(b, "e${e}_attack", "attack")
                    PanelKnob(b, "e${e}_decay", "decay")
                    PanelKnob(b, "e${e}_sustain", "sustain")
                    PanelKnob(b, "e${e}_release", "release")
                } }
            }
            5 -> for (l in 1..2) key(l) { Group("lfo $l") {
                val p = "l${l}_"
                PanelStepKnob(b, p + "wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                PanelKnob(b, p + "rate", "rate", PanelAmber)
                PanelStepKnob(b, p + "sync", TRINITY_LFO_SYNC, "sync", PanelAmber)
                PanelKnob(b, p + "delay", "delay")
                PanelKnob(b, p + "phase", "phase")
                PanelSwitch(b, p + "keysync", listOf("free", "key"), "trig")
            } }
            6 -> for (m in 1..8) key(m) { Group("mod $m") {
                val p = "m%02d_".format(m)
                PanelStepKnob(b, p + "src", MOSAIC_SOURCES, "from", PanelAmber)
                PanelStepKnob(b, p + "src2", MOSAIC_SOURCES, "× from")
                PanelStepKnob(b, p + "dest", MOSAIC_DESTS, "to", PanelAmber)
                PanelKnob(b, p + "depth", "depth", PanelAmber)
            } }
            else -> {
                Group("voice") {
                    PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg"), "mode")
                    PanelKnob(b, "glide", "glide")
                    PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                    PanelKnob(b, "bend", "bend")
                    PanelKnob(b, "mpetimbre", "slide"); PanelKnob(b, "mpepressure", "press")
                }
                Group("out") { PanelKnob(b, "volume", "volume"); PanelKnob(b, "pan", "pan"); PanelKnob(b, "velamt", "vel") }
            }
        }
    }
    if (editing && selectedZone in zones.indices) {
        ZoneDialog(
            zone = zones[selectedZone],
            onDismiss = { editing = false },
            onConfirm = { z -> putZones(zones.toMutableList().also { it[selectedZone] = z }); editing = false },
            onDelete = { putZones(zones.filterIndexed { i, _ -> i != selectedZone }); editing = false },
        )
    }
}
