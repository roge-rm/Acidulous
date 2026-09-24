package com.rm.acidulous.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R
import androidx.compose.ui.res.pluralStringResource

/**
 * The machine's face in the Edit screen.
 *
 * Every machine is laid out the same way - one scrolling row of titled
 * [Group] cards, knobs and switches from the shared helpers below, three
 * colours with fixed meanings, and a [SectionChips] row only when a machine
 * has more groups than one row can carry. Follow that shape when adding a
 * machine rather than inventing a layout. Knob values live in three places
 * that must agree: the engine (live, smoothed), the document (persisted, on
 * the track), and the panel. A turn goes to the engine at once as a user
 * gesture (recordable), and into the document as one undo step; between
 * turns the panel follows the engine, so lanes move the knobs.
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
    /** Every pad and the slice source at once - see the `clear` action. */
    onClearKit: () -> Unit = {},
    /** A sample already in the app's own folder, chosen rather than imported. */
    onAssignSample: (pad: Int, relative: String) -> Unit = { _, _ -> },
    /** Nexus keeps its graph on a screen of its own. */
    onOpenPatch: () -> Unit = {},
    /** Pollen holds one sample of its own, under the plain key. */
    onImportOneSample: () -> Unit = {},
    /**
     * Which cell is open, for the one machine whose material is in the clips.
     *
     * Every other panel here is about the machine and the same whichever cell
     * is showing. A tape's takes belong to the clip - four windows into files,
     * per scene - so its panel has to know which scene it is looking at. Empty
     * for every other machine, which asks nothing of it.
     */
    sceneId: String = "",
    /**
     * Which half to draw.
     *
     * Upright the panel is its header over its cards and both are drawn
     * together. Turned, the header stands down the left edge of the screen
     * and the cards stand on the right, **with the roll between them** - so
     * the two halves are placed separately and each instance draws one.
     */
    bar: Boolean = true,
    body: Boolean = true,
    /** The header reads downwards and the cards stack - see M43. */
    vertical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val type = track.machine.type
    // Folded away, the panel is just its title row: the roll takes the rest.
    // In UiPrefs rather than here because the two halves above cannot share a
    // flag that one of them owns - and because it says how somebody works.
    val minimized = UiPrefs.panelFolded
    val info = remember(type) { NativeEngine.machineParamInfo(type) }
    val binding = rememberParamBinding(trackIndex, type, info, editor)

    androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides vertical) {
    Column(modifier.background(Acid.colors.panel).padding(6.dp)) {
        val loadPatch: (String) -> Unit = { name ->
            onLoadPatch(name)?.let { patch ->
                // What this machine keeps across a patch change - see
                // MachineUi.patchKeeps. Empty for everything but Bias.
                val keep = MachineUi.patchKeeps(type)
                val params = if (keep.isEmpty()) patch.params
                             else patch.params + track.machine.params.filterKeys { it in keep }
                editor.edit(trackIndex) { t ->
                    var next = t.withPatch(params)
                    for ((k, v) in patch.settings) next = next.withSetting(k, v)
                    // Last, so a patch that happens to carry these keys cannot
                    // rename itself. Settings rather than state held here, so
                    // the name survives a reopen, the arrows know where in the
                    // list they are standing, and the star knows what the
                    // patch sounded like when it arrived.
                    //
                    // The stamp is taken from `next`, not from `patch.params`:
                    // `withPatch` replaces the whole map, so what the machine
                    // now holds is the truth and what the patch file said is
                    // only where it came from.
                    next.withSetting(kPatchName, name).withSetting(kPatchStamp, stampOf(next.machine.params))
                }
                binding.applyAll(params)
            }
        }
        // Saving under a name makes the machine *be* that patch: same name,
        // same knobs, no star until the next one is turned.
        val savePatch: (String) -> Unit = { name ->
            onSavePatch(name)
            editor.edit(trackIndex) { t ->
                t.withSetting(kPatchName, name).withSetting(kPatchStamp, stampOf(t.machine.params))
            }
        }
        // A name that has stopped being true says so. Only the parameters
        // count: loading a sample onto a pad is how Forage is *used*, and a
        // kit marked edited the moment it was built would mark everything.
        //
        // Remembered against the map itself, which is a new object on every
        // edit: the panel recomposes with each frame of a knob drag and this
        // walks every parameter the machine has.
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
                // How many pads the slice covers, so the pads and the grid can
                // say which of them are playing a piece of it.
                onSliceApplied = { count ->
                    editor.edit(trackIndex) { t -> t.withSetting("slice_count", count.toString()) }
                },
            )
            else -> GenericPanel(binding)
        }
    }
    }
}


/** Live values + the plumbing to change them. */
class ParamBinding(
    val trackIndex: Int,
    val info: List<ParamInfo>,
    private val editor: SongEditor,
    private val values: androidx.compose.runtime.MutableState<Map<String, Float>>,
    private val dragging: androidx.compose.runtime.MutableState<String?>,
    /**
     * Every control's value as it was when this panel opened, or null until
     * the first poll has said what they are. See [reset].
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
        // Locking: the knob shows the selected step's lock, or the knob
        // itself where that step has none - which is what it would play.
        return com.rm.acidulous.model.Locks.at(
            LockEdit.lanes[com.rm.acidulous.model.laneKey(unit, name)], LockEdit.spans.first().first, LockEdit.clipTicks,
        )?.value ?: knob
    }

    /**
     * Writes a lock on the selected steps instead of moving the knob, and
     * says whether it did. A parameter with a drawn or recorded curve refuses
     * - the two would fight over it (Dan's choice) - and its knob stays put.
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
     * Slicing writes twenty-six parameters at once and levelling thirteen;
     * through `set` that is twenty-six document edits, which is twenty-six
     * entries in the undo history for one button.
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
     * Put one control back where it was when the panel was opened.
     *
     * **Where it was, not its factory default.** A default is a fact about the
     * machine; this is a fact about what you were doing - you turned four
     * knobs, you want one of them back, and back means how it sounded a minute
     * ago. Reopening the panel takes a new reading, so "a minute ago" is
     * always the last time you came in here.
     *
     * Returns false when there is nothing to go back to, which is only the
     * case before the first poll has answered - a window opened and
     * long-pressed inside a tenth of a second.
     */
    /**
     * Put **everything** back to how it was when the panel opened.
     *
     * What a window's Cancel does. The controls in these windows write live -
     * a knob is heard as it turns, and it has to be, because voicing anything
     * by ear means hearing it - so there is nothing held back for an OK to
     * apply. Cancel is the other half of that bargain: the same baseline the
     * long press uses, applied to the lot, in one undo step.
     */
    fun resetAll() {
        val was = opened.value ?: return
        val changed = was.filter { (n, v) -> value(n) != v }
        if (changed.isNotEmpty()) setMany(changed)
    }

    fun reset(name: String): Boolean {
        // Held while locking: the selected steps lose their lock on this.
        if (lock(name, null, gesture = false)) return true
        val was = opened.value?.get(name) ?: return false
        if (was == value(name)) return true // already there; a no-op, not a failure
        set(name, was)
        return true
    }

    /** Has this control been moved since the panel opened? */
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
    val values = remember(trackIndex, type, unit) { mutableStateOf(info.associate { it.name to it.defaultNormalized }) }
    val dragging = remember { mutableStateOf<String?>(null) }
    /**
     * What everything read when this panel opened, for a long press to go back
     * to.
     *
     * **Taken on the first poll, not at composition.** At composition `values`
     * is every parameter's *default*, because the panel has not asked the
     * engine anything yet - so a baseline captured here would send a long
     * press to the factory setting and call it "where it was". Keyed with the
     * binding, so reopening a panel takes a fresh reading, which is what "when
     * this panel was opened" has to mean.
     */
    val opened = remember(trackIndex, type, unit) { mutableStateOf<Map<String, Float>?>(null) }
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
 * A small tappable mark in the patch bar.
 *
 * Not a TextButton: Material gives one of those 58 dp of width whatever is
 * written in it, and this row already holds a machine name, a patch name and
 * four marks. Twenty-two dp each is what let them all fit on a phone.
 */
private val BarIconV = 16.dp

/**
 * How far apart two marks stand in the turned header column.
 *
 * A turned `BarIcon` is sixteen dp of height, which is a small thing to put a
 * finger on when the next one begins where it ends: Dan, over the two step
 * arrows, "these are too close for finger use". Ten dp is what already
 * separated the fold from them, and he asked for that gap - so it is every
 * gap between marks now, and the only number in the column that is not one is
 * the three between the two names, which are meant to read as a pair.
 *
 * Upright there is no equivalent and none needed: the marks are twenty-four
 * dp wide there and a row has width to spare, which a short column has not.
 */
private val SideMarkGap = 10.dp

@Composable
private fun BarIcon(glyph: String, tint: Color, vertical: Boolean = false, said: String = glyph, onClick: () -> Unit) {
    Box(
        // Turned, the box turns with it - 24 x 28 is a target measured for a
        // row of marks and a column of them wants the long side across - and
        // it loses four dp of length on the way. Five marks single file is a
        // hundred and twenty dp of a column that has about two hundred and
        // forty, and what is left over is the two names above them; at 24 the
        // patch name came out a character short of "patch".
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
    /** Down the left edge instead of across the top - see M43. */
    vertical: Boolean = false,
) {
    /**
     * One patch along the list, without opening it.
     *
     * The names are read here rather than held, because reading them touches
     * the disk and the bar recomposes with every knob: a click is the only
     * moment the answer is wanted. Nothing loaded yet means the ends of the
     * list - forward starts at the first, back at the last - and the walk
     * wraps, so holding one arrow goes through the whole bank and round.
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
    // **The right-hand group is weighed against, not spaced away from.**
    //
    // This was a flat row ending in a Spacer(weight(1f)), on the theory that
    // the spacer would pin the arrows to the edge. It only does that while
    // there is slack, and there is none: a name, three Material TextButtons
    // (58 dp wide apiece whatever is in them) and three marks fill a phone.
    // So the arrows moved with the length of the patch name - fifty pixels
    // between `Init` and `Bell Keys` - and the button under your finger was
    // whichever one the last patch's name had left there. Putting the left
    // half in the weight instead makes it the part that gives way.
    // **Turned, it is the same list read downwards**, with the give still on
    // the name so the arrows do not move with the length of a patch name.
    //
    // The fold points at the edge it folds towards rather than up or down:
    // against the left edge, `◂` puts it away and `▸` brings it back.
    //
    // **It reads upwards, because the words do.**
    //
    // Every label in this column is turned anticlockwise, which puts the
    // start of a word at the bottom. Run the controls the other way and the
    // column contradicts itself: the machine's name reads up from the floor
    // while the bar it belongs to reads down from the ceiling. Dan, looking
    // at it: "machine name in bottom left corner and then patch selector,
    // save, load, etc".
    //
    // So the portrait row is laid down the left edge with its left-hand end
    // at the *bottom*. Reading up from the floor you get exactly the row you
    // get reading left to right upright - the machine, the patch, save,
    // browse, the two steps, the fold - which is why the gap that separates
    // the fold from the pair is above the arrows here rather than below.
    if (vertical) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            // **The fold stays here and folds something else.** It is at the
            // head of the column as it is at the end of the row upright, but
            // what it puts away is the cards to the right of the roll, not
            // this strip - so it points that way: `▸` sends them off the
            // right edge, `◂` brings them back. The strip itself is always
            // there, which is what lets it keep the mark that brings them
            // back without needing a folded state of its own.
            BarIcon(if (minimized) "\u25C2" else "\u25B8", Acid.colors.textMid, vertical = true, said = stringResource(if (minimized) R.string.a11y_unfold else R.string.a11y_fold)) {
                onToggleMinimized()
            }
            Spacer(Modifier.height(SideMarkGap))
            BarIcon("\u203A", Acid.colors.accent, vertical = true, said = stringResource(R.string.a11y_next_patch)) { step(1) }
            Spacer(Modifier.height(SideMarkGap))
            BarIcon("\u2039", Acid.colors.accent, vertical = true, said = stringResource(R.string.a11y_prev_patch)) { step(-1) }
            // **The names sit together, and the slack is above them.**
            //
            // They were a weight each, which spread them to the ends of the
            // column with a hand's width of nothing between - Dan: "on
            // portrait the Machine name and Patch pulldown are closer". They
            // are, and for a reason worth copying rather than a spacing
            // accident: upright the pair sits inside the weighted half of the
            // row, so the *pair* absorbs the give and the two names stay
            // beside each other. Turned, that is a bottom-aligned column of
            // stated lengths, with what is left over standing above the
            // marks - which is where the gap is upright, between browse and
            // the step arrows.
            // **And each name is as long as its own word.** Giving them a
            // box each and centring the text in it put the gap back: "patch"
            // in sixty-four dp of box is twenty-five dp of word and thirty-
            // nine of air, half of it between the two names. So the lengths
            // are measured from the text, and only shared out in proportion
            // when both together will not fit.
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
                // No `spacedBy` here: the marks want a finger's worth of gap
                // between them and the two names want to sit together, so the
                // gaps are written where they differ rather than averaged
                // into one number that is wrong for both.
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    // The picker puts browse over save over the name, which
                    // upright is the name, then save, then browse. The two
                    // names carry the weights, so the give is on them and the
                    // marks above do not move with the length of a patch
                    // name - the same rule the row has.
                    PatchPicker(
                        type, patchNames, onSave, onLoad, factoryPatches, userNames, onDelete,
                        current, edited, vertical = true, modifier = Modifier.height(patchH),
                    )
                    Spacer(Modifier.height(3.dp))
                    // Half each. The patch name had two thirds while the
                    // column was the whole screen; once the keyboard took its
                    // share a third of what was left came to four characters,
                    // and a machine called "Formulate" reading "Form.."
                    // next to a patch called "patch" with room to spare is
                    // the split being wrong rather than the column short.
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
        BarIcon("\u2039", Acid.colors.accent, said = stringResource(R.string.a11y_prev_patch)) { step(-1) }
        BarIcon("\u203A", Acid.colors.accent, said = stringResource(R.string.a11y_next_patch)) { step(1) }
        // A gap before the fold, because it is not one of the pair. The three
        // touch targets were flush against each other, so the arrow that
        // steps a patch and the one that hides the whole panel were a
        // thumb's width apart and did very different things.
        Spacer(Modifier.width(18.dp))
        BarIcon(if (minimized) "▴" else "▾", Acid.colors.textMid, said = stringResource(if (minimized) R.string.a11y_unfold else R.string.a11y_fold)) { onToggleMinimized() }
    }
}

/**
 * How big the two names in the turned patch column are set.
 *
 * Two points under portrait's eleven, because sideways they are not
 * competing with a row's width but with a column's height, and the column's
 * height is whatever the keyboard divider leaves - about eighty dp for the
 * two names once the marks above them have been paid for. At eleven
 * "MyPatch" came out "MyP.." while "Trinity" - same seven letters, three of
 * them narrow - fitted, which is the kind of difference that reads as a bug
 * rather than as a proportional font; at ten the longest machine names
 * clipped as well. Nine sp is what the note lane and automation gutters
 * set their turned labels at, so it is not a size this app is short of
 * precedent for.
 */
private val SideNameSp = 9.sp

/**
 * What the patch button says: its name once it has one, or what it is for.
 *
 * Shared, because the turned column has to know how long the word is before
 * it can decide how much of the column to give it, and computing it twice in
 * two files is how the box and the text it holds come to disagree.
 */
@Composable
internal fun patchLabel(current: String?, edited: Boolean): String {
    // The name once there is one. "patch" told you what the button was for
    // and nothing about what you were listening to, and a rack of eight
    // machines all saying "patch" is a rack that has forgotten where its
    // sounds came from.
    // The star is not a warning, it is an accuracy: the sound is no longer
    // the one that name refers to, and "save as..." is next to it.
    val shown = current?.ifBlank { null }
    return when {
        shown == null -> stringResource(R.string.machine_patch)
        edited -> stringResource(R.string.machine_patch_edited, shown)
        else -> shown
    }
}

/**
 * The three words that choose a patch: pick one, save this, look through them.
 *
 * Extracted from the machine panel's title row so an effect slot can have the
 * same one. Nothing about it is machine-shaped - [title] is only the word the
 * browser puts at the top of its window - which is the whole reason effects
 * did not need a preset mechanism of their own, only a place to put this.
 */
@Composable
internal fun PatchPicker(
    title: String, patchNames: () -> List<String>, onSave: (String) -> Unit, onLoad: (String) -> Unit,
    factoryPatches: () -> List<com.rm.acidulous.model.Patch>, userNames: () -> List<String>,
    onDelete: (String) -> Unit,
    /** The patch showing, if one was chosen: the button says its name. */
    current: String? = null,
    /** Its knobs have moved since, so the name is where it came from. */
    edited: Boolean = false,
    /** Read downwards, in the patch column - see M43. */
    vertical: Boolean = false,
    /** Only the vertical form uses this; the row sizes itself. */
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var listRev by remember { mutableStateOf(0) } // bumps after a delete so the browser re-reads
    val label = patchLabel(current, edited)
    if (vertical) {
        // Browse, save, then the name - which is the name, then save, then
        // browse of the row upright, read from the bottom. See [PatchBar].
        BarIcon("\u2630", Acid.colors.textMid, true, said = stringResource(R.string.a11y_browse_patches)) { browsing = true }
        Spacer(Modifier.height(SideMarkGap))
        BarIcon("\u21A7", Acid.colors.textMid, true, said = stringResource(R.string.a11y_save_patch)) { saving = true }
        Spacer(Modifier.height(SideMarkGap))
        // No Material button around it sideways: one is 58 dp of *width*
        // whatever is in it, and in a column that width is the column.
        Box(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { menu = true },
            // No "▾" after it sideways: the mark costs ten dp of the length
            // the name has, and in a column the thing under your finger is
            // plainly the patch name whether or not it carries an arrow.
        ) { SideText(label, Acid.colors.accent, SideNameSp) }
    } else {
        TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(
                stringResource(R.string.machine_patch_menu, label),
                color = Acid.colors.accent, fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
    // Marks rather than words. "save as..." and "browse..." were two Material
    // TextButtons - 116 dp between them before a letter is drawn - on a row
    // that also has to hold a machine name, a patch name that can be long,
    // two step arrows and the fold. Down arrow into a line for putting one
    // away, a list for looking through them.
    if (!vertical) {
        BarIcon("\u21A7", Acid.colors.textMid, said = stringResource(R.string.a11y_save_patch)) { saving = true }
        BarIcon("\u2630", Acid.colors.textMid, said = stringResource(R.string.a11y_browse_patches)) { browsing = true }
    }
    val menuScroll = rememberScrollState()
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        ScaledMenu(menuScroll) {
            for (n in patchNames()) DropdownMenuItem(text = { Text(n, fontSize = 12.sp) }, onClick = { menu = false; onLoad(n) })
        }
    }
    if (saving) TextInputDialog(stringResource(R.string.machine_patch_name), "", onDismiss = { saving = false }) { name -> onSave(name); saving = false }
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

// The panel palette. Teal is the ordinary control; amber marks the knob that
// gives a group its character; pink marks drive and output. See the style
// note at the bottom of this file.
internal val PanelTeal: Color @Composable get() = Acid.colors.teal
internal val PanelAmber: Color @Composable get() = Acid.colors.accent
internal val PanelPink: Color @Composable get() = Acid.colors.pink

/**
 * The lanes in the clip the editor has open, as lane keys ("machine:cutoff").
 *
 * A knob a lane moves will not stay where it is put, and until this nothing
 * on the panel said why - only the mixer marked its automated controls. The
 * editor sets it; every [PanelKnob] and [PanelStepKnob] reads it and draws a
 * ∿ on the dial when its own key is in it.
 */
object AutomationMarks {
    var lanes by androidx.compose.runtime.mutableStateOf(emptySet<String>())
    /** The lanes among them that are step locks, marked ◆ rather than ∿. */
    var locks by androidx.compose.runtime.mutableStateOf(emptySet<String>())
}

/**
 * The steps the panel's knobs lock onto: set by the editor while it is in
 * lock mode with steps selected, and empty otherwise.
 *
 * State here rather than threaded through every panel, for the same reason
 * as [AutomationMarks]: every knob on every machine and effect panel reads it,
 * and they are built in forty places that know nothing about the editor.
 */
object LockEdit {
    var trackIndex by androidx.compose.runtime.mutableStateOf(-1)
    var sceneId by androidx.compose.runtime.mutableStateOf("")
    /** Tick spans, the first to the last tick a lock covers. */
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
 * How wide a knob stands in a stacked card, so that two fit a line.
 *
 * **Stated, because a column of knobs whose dials wander is a list.** Upright
 * a knob takes its own width - a 52 dp dial or the label above it, whichever
 * is wider - and in a row that scrolls sideways nobody can tell. Stacked two
 * to a line the difference lands under your eye: `pw` would be 52 wide and
 * `density` 40 dp wider, and the second column of dials would move down the
 * card. Fifty-eight fits the dial with a little air and ellipsises a label at
 * about ten characters, which is one more than the longest a panel carries.
 */
internal val StackedKnobW = 58.dp

/** How many controls a stacked card puts on a line. Dan picked two. */
internal const val StackedPerLine = 2

/** A stated width stacked; upright a knob is still whatever it needs. */
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
 * A switch's face, for a choice that is not a machine parameter - the
 * generators' step sizes and drum voices sit in the same cards as knobs and
 * have to look like the switches beside them. [selected] out of range lights
 * nothing, which makes a one-cell grid a button.
 */
@Composable
internal fun SwitchGrid(
    label: String,
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    /** Cells to a row; by default two rows at most. */
    columns: Int = 0,
    /** Which cells can be pressed; all of them when null. */
    enabled: List<Boolean>? = null,
    onPick: (Int) -> Unit,
) {
    val idx = selected
    // Two rows at most, one column when there are only two options.
    //
    // A Material TextButton is 58dp wide whatever is written in it, and this
    // was a row of them: `saw` and `pulse`, forty-three dp of words, in a
    // hundred and nineteen. Meanwhile the row a switch sits in is bottom
    // aligned against knobs that are a hundred dp tall to its seventy-two,
    // so there was space going spare directly above it. The second row is
    // free, and paying for it halves the width.
    val cols = if (columns > 0) columns else if (labels.size <= 2) 1 else (labels.size + 1) / 2
    Column(
        modifier
            // Fill the row's height, but never more than a control's worth of
            // it. Without the ceiling this asks its parent how tall to be
            // while the parent is asking it the same question - Group's row
            // is IntrinsicSize.Max - and the two can agree on an absurd
            // answer: a Filter's three-way mode switch came out 490dp tall
            // and pushed the piano roll and the keyboard clean off the
            // screen. The cap is what a knob measures, which is what the
            // filling was for in the first place.
            .heightIn(max = PanelControlH).fillMaxHeight().together(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Said as part of each cell's name instead.
        Text(label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.silent())
        // IntrinsicSize.Max, then a weight on every cell: the grid takes the
        // width of its widest row and the weights divide it evenly, so all
        // the cells in one switch are the size of its longest label and the
        // selected one never moves as you change it.
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
                        val name = stringResource(R.string.a11y_named, label, l)
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(if (on) Acid.colors.green else Acid.colors.control)
                                .clickable(enabled = live) { onPick(i) }
                                // Nothing lit is a row of actions; one lit is a choice.
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
                    // An odd count leaves a hole in the last row. It has to be
                    // a weighted box and not nothing, or the row above it
                    // divides its width between fewer cells and the grid comes
                    // out ragged.
                    repeat(cols - cells.size) { Box(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

/**
 * The panel body every machine uses: one horizontally scrolling row of
 * [Group]s. Machines with more groups than fit comfortably put a
 * [SectionChips] row above it and show one section at a time.
 */
/**
 * Whether the cards stack instead of standing in a row.
 *
 * A composition local rather than a parameter because `GroupRow` and `Group`
 * are called from nineteen machine panels and every effect face, none of
 * which has an opinion about the shape of the screen - threading a flag
 * through all of them would be nineteen signatures changed to say the same
 * thing. `MachinePanel` provides it; these two read it.
 */
internal val LocalPanelStacked = androidx.compose.runtime.compositionLocalOf { false }

@Composable
internal fun GroupRow(content: @Composable () -> Unit) {
    if (LocalPanelStacked.current) {
        // The cards go down the column and it scrolls that way. Whatever
        // places this must not also be a vertical scroller - see EditScreen's
        // landscape branch, which hands it the height directly.
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
 * A machine's body: its section tabs, then its cards.
 *
 * Upright those are a row of chips over a row of cards and this is a plain
 * `Column`, which is what every panel wrote before this existed. Sideways the
 * cards are a column, so the chips stand **down the left edge of them** -
 * Dan's call, against putting them back across the top: full width they would
 * be a faithful port of portrait's row, and they would also be twenty-eight
 * dp of a three-hundred-and-ninety-three dp screen spent on tabs.
 *
 * It is a container rather than a flag passed to `SectionChips` because the
 * two children have to be laid out *together* - the chips beside the cards,
 * not above them - and only whatever holds them both can say so. Fifteen
 * panels write `PanelSections { SectionChips(...); GroupRow { ... } }` and
 * none of them has to know which way the phone is held.
 */
@Composable
internal fun PanelSections(content: @Composable () -> Unit) {
    if (LocalPanelStacked.current) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        return
    }
    Column { content() }
}

/** Which group of groups is showing. Only machines too big for one row need it. */
@Composable
internal fun SectionChips(english: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // A panel's own sections are English words; a window's arrive translated and pass through.
    val labels = panelWords(english)
    if (LocalPanelStacked.current) {
        SectionChipsSide(labels, selected, onSelect)
        return
    }
    // **Equal shares until there is not room for them.**
    //
    // An equal share is right at four chips and wrong at seven - the note on
    // SectionChipsScrolling says so, and until now every machine asked for
    // equal shares regardless. Sideways the panel is a column about three
    // hundred dp wide, and Mosaic and Genesis have eight sections each: that
    // is thirty-seven dp a chip, which is not a word and not a target.
    //
    // Measured rather than counted, so it is right on a tablet too, and
    // decided here rather than at fifteen call sites. The measurement is of
    // what the chips are *in* and not of the screen: sideways the window is
    // eight hundred dp wide and the column they stand in is three hundred.
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
 * The same tabs, turned, down the left edge of the cards.
 *
 * Every one of them on screen at once, which the horizontal row in a hundred
 * dp column could not manage - it showed two of Trinity's seven and you
 * scrolled for the rest, which is what "the portrait elements haven't been
 * faithfully ported" was about. A tab you cannot see is not a tab.
 *
 * So they share the column's height the way portrait's share its width, and
 * fall through to scrolling by the same measurement when a share would be
 * too short to read a word in. The chip is the same chip - same colours,
 * same corner, same equal shares - with its word read bottom to top, which
 * `SideText` is for.
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

/**
 * How wide the turned tab column is: a ten sp line box and room to hit it.
 */
private val SideChipW = 22.dp

/**
 * The narrowest a chip may be before the row scrolls instead.
 *
 * Under this a four letter word at ten sp clips, which is what makes a row of
 * them unreadable rather than merely tight.
 */
private val kChipFloor = 56.dp


/**
 * The same, for a label with a word set differently inside it. A separate
 * name rather than an overload: both erase to List on the JVM.
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
                // Equal shares of the full width: these are the machine's
                // tabs, and a row of tabs that stops half way reads as broken.
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
 * The same chips, sized to their words and scrolling when there are too many.
 *
 * [SectionChipsStyled] gives every chip an equal share of the width, which is
 * right for the machine picker's four groups and wrong as soon as there are
 * seven: the patch browser's dialog is about 320 dp across, so seven families
 * get 43 dp each and "ensemble" wants 48 - and Cumulus has *ten* families,
 * which would be 30 dp apiece. A tab you cannot read is worse than a tab you
 * have to scroll to, so these size to their text and the row scrolls, with the
 * position bar every scrolling row in this app carries.
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
 * A stepped parameter with too many values for [PanelSwitch]: a knob that
 * names its step instead of showing a number. Four values or fewer belong in
 * a switch; more belong here.
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
 * How tall one control in a panel stands: a knob's label, its 52dp dial and
 * its value, which at a 9sp line box is 24 + 52 + 24. Switches are capped to
 * it so that a row of controls cannot be taller than the controls in it.
 */
internal val PanelControlH = 100.dp

/**
 * A column of buttons in a group, where knobs would otherwise go.
 *
 * Three of them side by side in a card as tall as a knob is mostly empty
 * card - a knob is a hundred device-independent pixels tall and a button is
 * twenty. Stacked, they fill the height they are given and the card is narrow
 * instead of wide, which is the right shape for a row that scrolls sideways.
 *
 * They cannot be bare TextButtons either way: a `Group` aligns its row to the
 * bottom and sizes to its tallest child, so a TextButton takes whatever width
 * is left over and wraps its label a character at a time. That is what turned
 * `match` into a column of single letters.
 */
@Composable
private fun PanelActions(vararg actions: Triple<String, Color, () -> Unit>) {
    // Built like PanelSwitch, because it stands next to one.
    //
    // These were small pills floating in the middle of a group that is a
    // hundred dp tall, which read as neither a knob nor a switch and left
    // most of the space empty. A switch solves the same problem - several
    // small labels in one control's worth of room - so this is the same
    // shape: one card, cells divided by a hairline, each filling its share
    // of the height. Two rows once there are more than two, so a cell is
    // half a control tall rather than a quarter.
    val cols = if (actions.size <= 2) 1 else (actions.size + 1) / 2
    Column(
        // A stated height, not a filled one, and that is the whole of why
        // these came out small. `Group` sizes its row from its children's
        // *intrinsic* height, and `fillMaxHeight` has none to offer: every
        // other group has a knob in it bringing a hundred dp, but the kit
        // group is nothing but these, so the row collapsed onto the text and
        // the cells had a control's worth of nothing to fill. Saying the
        // height outright makes it the intrinsic one too.
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
internal fun Group(
    title: String,
    /**
     * How many controls to a line when the card is stacked.
     *
     * Two is right for the side panel it was written for, which is as wide as
     * two knobs. A dialog is the width of the screen and fits four, and a card
     * that used two there would be tall and half empty.
     */
    perLine: Int = StackedPerLine,
    /** Centre the controls in the card rather than packing them to the left. */
    centred: Boolean = false,
    /**
     * The card's own colour. The default is what a panel sits on, and a
     * *window* is already that colour - so a card drawn in it would be
     * invisible and the grouping would be titles and nothing else.
     */
    background: Color = Acid.colors.card,
    content: @Composable () -> Unit,
) {
    val stacked = LocalPanelStacked.current
    Column(
        Modifier.then(if (stacked) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(6.dp)).background(background).padding(6.dp).together(),
    ) {
        // A panel's own English title is translated here; a window's card
        // arrives translated already and passes through untouched.
        Text(
            panelWord(title), color = Acid.colors.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            // A card's title is a heading, so TalkBack can jump card to card.
            modifier = Modifier.semantics { heading() },
        )
        // IntrinsicSize.Max so the row knows how tall its tallest control is
        // - a knob, almost always - and anything that wants to can fill it.
        // Switches do, so their cells line up with the knobs beside them
        // instead of leaving a gap above. Taken from the children rather
        // than written down as a number, so it still holds when the text
        // scale changes the height of a knob's label.
        if (stacked) {
            // **The card wraps; the knob does not change.** Dan, over the two
            // orientations side by side: the portrait elements had not been
            // faithfully ported. A turned knob - name and value rotated down
            // either side of the dial - was narrower and it was a different
            // instrument to look at, and the panel is the place in the app
            // where knowing where a control is by its shape matters most.
            //
            // So a knob sideways is exactly the knob upright, and the *card*
            // is what gives way: two to a line, wrapping, in a column that
            // scrolls. Six knobs is three lines rather than one long row.
            //
            // No `IntrinsicSize.Max` here: a flow row measures each line from
            // its own children, which is what the intrinsic height meant in
            // the single row, and `PanelSwitch` still carries its own ceiling
            // of `PanelControlH` - the scar from the 490 dp three-way switch.
            FlowRow(
                Modifier.fillMaxWidth(),
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

/** Reflux: the classic layer left to right, the open layer after it. */
@Composable
private fun RefluxPanel(b: ParamBinding) {
    GroupRow {
        Group("osc") { PanelSwitch(b, "wave", listOf("saw", "pulse")); PanelKnob(b, "pw"); PanelKnob(b, "sub"); PanelKnob(b, "tune") }
        Group("filter") { PanelKnob(b, "cutoff", accent = PanelAmber); PanelKnob(b, "resonance", "reso", PanelAmber); PanelKnob(b, "envmod", accent = PanelAmber); PanelKnob(b, "decay", accent = PanelAmber); PanelSwitch(b, "mode", listOf("lp", "bp")) }
        Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "slide") }
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
        Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "volume") }
    }
}

/** Forage: the selected pad's sample and its controls; tap a pad to select it. */
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
    // The loudest sample in this pad's file, which is not something the player
    // chose and is the reason `match` exists: thirteen files from thirteen
    // places arrive at thirteen different levels, and the only remedy before
    // this was thirteen level knobs set by ear.
    val peak = info.split('|').getOrNull(3)?.toFloatOrNull() ?: 0f
    val peakDb = if (peak > 1e-5f) "%.1f dB".format(20.0 * kotlin.math.log10(peak.toDouble())) else "-"

    /**
     * Set every loaded pad's level so the kit comes out even.
     *
     * Referenced to the **median** loaded sample, not the quietest and not the
     * loudest. Referencing the quietest would match every pad exactly and take
     * the whole kit down with the worst of them - one quiet shaker and the
     * other twelve drop twenty decibels, which `volume` cannot get back.
     * Referencing the loudest pushes every ratio above one at once.
     *
     * Against the median the loud half comes down and the quiet half goes up,
     * and the kit keeps the level it had. `level` reaches four for this: at a
     * ceiling of one the loud half had nowhere to move to and a ragged kit
     * came out as far apart as it went in.
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
            // Through the parameter's own table: `set` wants 0..1 and `level`
            // is in its own units, and writing the conversion out here would
            // be a second copy of a range that lives in the engine.
            val def = b.info.firstOrNull { it.name == name } ?: return@forEachIndexed
            batch[name] = def.unmap(median / pk)
        }
        b.setMany(batch)
    }
    val hot = Acid.colors.accent
    var picking by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    /**
     * Put a pad's start and end back where they started.
     *
     * Slicing closes the pads it did not use - start and end both at zero -
     * because a pad with no sample of its own reads the shared file, and
     * "leave it alone" would mean playing the whole break. That leaves a trap
     * for the pad's next owner: load a sample into a closed pad and it is
     * silent for a reason nothing on screen explains. So taking a pad over
     * resets its trim, which is what anybody would expect of a new sample
     * anyway - a trim belongs to the sound it was set for.
     */
    fun resetTrim(pad: Int) {
        val batch = mutableMapOf<String, Float>()
        b.infoOf("p%02d_start".format(pad))?.let { batch[it.name] = it.defaultNormalized }
        b.infoOf("p%02d_end".format(pad))?.let { batch[it.name] = it.defaultNormalized }
        b.setMany(batch)
    }

    // Slicing one file across the pads. The file is a setting of its own, not
    // a pad's, because all thirteen read the same copy of it.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sliceRel = track.machine.settings["slice_sample"]
    var slicing by remember { mutableStateOf(false) }
    var sliceBusy by remember { mutableStateOf(false) }
    // Picking the file is a step on the way to slicing, not an end in itself,
    // so the file coming back opens the dialog rather than dropping the player
    // back on the panel with nothing to show for it.
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
                val abs = java.io.File(
                    com.rm.acidulous.engine.EngineAssets.userRoot(context), rel,
                ).absolutePath
                val points = withContext(Dispatchers.IO) { NativeEngine.slicePoints(abs, mode, count) }
                sliceBusy = false
                if (points.size < 2) return@launch
                val n = minOf(count, 13, points.size - 1)
                val batch = mutableMapOf<String, Float>()
                for (i in 0 until 13) {
                    // Through the parameter table rather than assuming 0..1,
                    // the same reason `match` does.
                    val startDef = b.info.firstOrNull { it.name == "p%02d_start".format(i) } ?: continue
                    val endDef = b.info.firstOrNull { it.name == "p%02d_end".format(i) } ?: continue
                    // Pads past the last slice are closed rather than left
                    // alone: a pad with no sample of its own reads the shared
                    // file, so "leave it" means "play the whole break", which
                    // is not what slicing into eight can possibly have meant.
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
    // Emptying the kit asks first. It is one tap away from `match` in a row
    // of four, and thirteen samples chosen by hand is not something to lose
    // to a fat finger - the settings would be gone before the undo was found.
    if (clearing) PlainDialog(
        title = stringResource(R.string.kit_clear_title),
        onDismiss = { clearing = false },
        confirmLabel = stringResource(R.string.kit_clear),
        onConfirm = {
            clearing = false
            onClearKit()
            // The samples are settings and the trim is parameters, so putting
            // the pads back takes both: slicing closed the pads it did not
            // use, and a cleared kit that stays closed is silently deaf.
            val batch = mutableMapOf<String, Float>()
            for (i in 0 until 13) {
                b.infoOf("p%02d_start".format(i))?.let { batch[it.name] = it.defaultNormalized }
                b.infoOf("p%02d_end".format(i))?.let { batch[it.name] = it.defaultNormalized }
            }
            b.setMany(batch)
        },
    ) {
        Text(
            stringResource(R.string.kit_clear_note),
            color = Acid.colors.textMid, fontSize = 12.sp,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.kit_pad, p + 1), color = hot, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                if (info.isEmpty()) (rel?.let { stringResource(R.string.kit_not_loaded, it) } ?: stringResource(R.string.kit_no_sample))
                else stringResource(
                    R.string.kit_sample_info,
                    info.substringBefore('|'),
                    info.split('|').getOrNull(1)?.toIntOrNull()?.let { "%.2fs".format(it / 48000f) } ?: "",
                    stringResource(if (info.endsWith("|1")) R.string.kit_stereo_short else R.string.kit_mono),
                ),
                color = Acid.colors.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (rel != null) Text(peakDb, color = Acid.colors.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            else if (sliceRel != null && p < (track.machine.settings["slice_count"]?.toIntOrNull() ?: 0)) {
                val from = b.infoOf(n("start"))?.map(b.value(n("start"))) ?: 0f
                val to = b.infoOf(n("end"))?.map(b.value(n("end"))) ?: 0f
                Text(
                    stringResource(R.string.kit_slice_of, p + 1, track.machine.settings["slice_count"].orEmpty(), from * 100, to * 100),
                    color = Acid.colors.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
            // Only what belongs to *this pad*. The three that act on the whole
            // kit moved into the group row below: seven controls and a line of
            // text do not fit across a phone, and what gave way was the last
            // one - `match` came out as a column of single letters.
            TextButton(onClick = { resetTrim(p); onImport(p) }) { Text(stringResource(R.string.kit_load), color = hot, fontSize = 11.sp) }
            TextButton(onClick = { picking = true }) { Text(stringResource(R.string.kit_samples), color = hot, fontSize = 11.sp) }
            // Only where there is something to trim - the page is a picture of
            // a sample and an empty pad has none.
            if (info.isNotEmpty()) {
                TextButton(onClick = { onOpenSample(p) }) { Text(stringResource(R.string.kit_edit), color = hot, fontSize = 11.sp) }
            }
            if (rel != null) TextButton(onClick = { resetTrim(p); onClear(p) }) { Text(stringResource(R.string.kit_clear_pad), color = Acid.colors.textMid, fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Group("sample") { PanelKnob(b, n("start"), "start"); PanelKnob(b, n("end"), "end"); PanelKnob(b, n("pitch"), "pitch", hot); PanelSwitch(b, n("reverse"), listOf("fwd", "rev"), "reverse"); PanelSwitch(b, n("play"), listOf("once", "loop", "hold"), "play") }
            Group("amp") { PanelKnob(b, n("decay"), "decay"); PanelKnob(b, n("level"), "level"); PanelKnob(b, n("pan"), "pan"); PanelSwitch(b, n("choke"), listOf("-", "1", "2", "3", "4"), "choke") }
            Group("tone") { PanelKnob(b, n("cutoff"), "cutoff", hot); PanelKnob(b, n("reso"), "reso", hot); PanelSwitch(b, n("mode"), listOf("lp", "bp"), "mode"); PanelKnob(b, n("crush"), "crush", Acid.colors.pink) }
            Group("punch") { PanelKnob(b, n("penv"), "pitch env"); PanelKnob(b, n("pdecay"), "decay") }
            Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "volume", "volume", hot) }
            // The whole kit at once, where there is room for them to be read.
            Group("kit") {
                PanelActions(
                    Triple(stringResource(R.string.kit_kit), hot) { onImportKit(p) },
                    Triple(stringResource(if (sliceBusy) R.string.kit_slicing else R.string.kit_slice), hot) {
                        if (sliceRel == null) { awaitingPick = true; onImportSlice() } else slicing = true
                    },
                    Triple(stringResource(R.string.kit_match), Acid.colors.textMid) { matchLevels() },
                    Triple(stringResource(R.string.kit_clear_kit), Acid.colors.textMid) { clearing = true },
                )
            }
        }
    }
}

/**
 * Cutting one file across the pads.
 *
 * Forage has had per-pad start and end from the beginning, so a slice is not a
 * new kind of thing: it is thirteen pads reading one file between two points.
 * What it needed was somewhere to put the file - see Forage::kSharedSlot - and
 * this, to work out where the cuts go.
 */
@Composable
private fun SliceDialog(name: String, onChoose: () -> Unit, onDismiss: () -> Unit,
                        onApply: (mode: Int, count: Int) -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf(13) }
    PlainDialog(
        title = stringResource(R.string.slice_title),
        onDismiss = onDismiss,
        confirmLabel = if (name.isEmpty()) "" else stringResource(R.string.slice_confirm),
        confirmEnabled = name.isNotEmpty(),
        onConfirm = if (name.isEmpty()) null else ({ onApply(mode, count) }),
        spacing = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                name.ifEmpty { stringResource(R.string.slice_no_file) },
                color = if (name.isEmpty()) Acid.colors.textDim else Acid.colors.textHi,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            TextButton(onClick = onChoose) {
                Text(stringResource(if (name.isEmpty()) R.string.slice_choose else R.string.slice_change), color = Acid.colors.accent, fontSize = 12.sp)
            }
        }
        Text(stringResource(R.string.slice_where), color = Acid.colors.textDim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stringArrayResource(R.array.slice_modes).forEachIndexed { i, label ->
                TextButton(onClick = { mode = i }) {
                    Text(label, color = if (mode == i) Acid.colors.accent else Acid.colors.textMid, fontSize = 12.sp)
                }
            }
        }
        Text(
            stringResource(if (mode == 0) R.string.slice_transients_note else R.string.slice_even_note),
            color = Acid.colors.textDim, fontSize = 11.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.slice_count), color = Acid.colors.textDim, fontSize = 11.sp)
            TextButton(onClick = { count = (count - 1).coerceAtLeast(2) }) {
                Text("−", color = Acid.colors.accent, fontSize = 15.sp)
            }
            Text("$count", color = Acid.colors.textHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { count = (count + 1).coerceAtMost(13) }) {
                Text("+", color = Acid.colors.accent, fontSize = 15.sp)
            }
        }
        Text(
            stringResource(R.string.slice_count_note, count),
            color = Acid.colors.textDim, fontSize = 11.sp,
        )
    }
}

/** Any machine without a face yet: every parameter as a knob. */
@Composable
private fun GenericPanel(b: ParamBinding) {
    Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in b.info) PanelKnob(b, p.name)
    }
}

// --- Trinity ---------------------------------------------------------------------
//
// 178 parameters will not fit on a phone at once, so the panel is sectioned
// and the sections that repeat (oscillator, envelope, LFO, matrix slot) carry
// their own selector. Names mirror engine/machine/trinity/Trinity.cpp.

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
    // Too many groups for one row, so the sections pick which groups show.
    // Inside a section it is the same Group-of-knobs row as every machine.
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections {
        SectionChips(listOf("osc", "mix", "filter", "env", "lfo", "mod", "voice"), section) { section = it }
        GroupRow {
            when (section) {
                0 -> for (o in 1..3) Group("osc $o") {
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
                }
                1 -> {
                    Group("ring") { PanelKnob(b, "ring12", "1·2", PanelAmber); PanelKnob(b, "ring23", "2·3", PanelAmber) }
                    Group("fm") { PanelKnob(b, "fm21", "2→1", PanelAmber); PanelKnob(b, "fm32", "3→2", PanelAmber) }
                    Group("noise") { PanelKnob(b, "noise", "level"); PanelKnob(b, "noisecol", "colour") }
                }
                2 -> {
                    Group("routing") { PanelSwitch(b, "route", listOf("serial", "para", "split")); PanelKnob(b, "balance", "balance") }
                    for (f in 1..2) Group("filter $f") {
                        val p = "f${f}_"
                        PanelStepKnob(b, p + "type", TRINITY_FILTERS, "type", PanelAmber)
                        PanelKnob(b, p + "freq", "freq", PanelAmber)
                        PanelKnob(b, p + "res", "reso", PanelAmber)
                        PanelStepKnob(b, p + "drivetype", TRINITY_DRIVES, "drive", PanelPink)
                        PanelKnob(b, p + "drive", "amount", PanelPink)
                        PanelKnob(b, p + "env", "envmod")
                        PanelKnob(b, p + "key", "key")
                    }
                }
                3 -> for ((title, prefix) in TRINITY_ENVS) Group(title) {
                    PanelKnob(b, prefix + "_delay", "delay")
                    PanelKnob(b, prefix + "_attack", "attack", PanelAmber)
                    PanelKnob(b, prefix + "_decay", "decay", PanelAmber)
                    PanelKnob(b, prefix + "_sustain", "sustain", PanelAmber)
                    PanelKnob(b, prefix + "_release", "release", PanelAmber)
                    PanelSwitch(b, prefix + "_repeat", listOf("once", "loop"), "repeat")
                }
                4 -> for (l in 1..3) Group("lfo $l") {
                    val p = "l${l}_"
                    PanelStepKnob(b, p + "wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, p + "rate", "rate", PanelAmber)
                    PanelStepKnob(b, p + "sync", TRINITY_LFO_SYNC, "sync", PanelAmber)
                    PanelKnob(b, p + "delay", "delay")
                    PanelKnob(b, p + "phase", "phase")
                    PanelKnob(b, p + "slew", "slew")
                    PanelSwitch(b, p + "keysync", listOf("free", "key"), "trig")
                    PanelSwitch(b, p + "oneshot", listOf("cycle", "once"), "run")
                }
                5 -> for (m in 1..12) Group("mod $m") {
                    val p = "m%02d_".format(m)
                    PanelStepKnob(b, p + "src", TRINITY_SOURCES, "from", PanelAmber)
                    PanelStepKnob(b, p + "src2", TRINITY_SOURCES, "× from")
                    PanelStepKnob(b, p + "dest", TRINITY_DESTS, "to", PanelAmber)
                    PanelKnob(b, p + "depth", "depth", PanelAmber)
                }
                else -> {
                    Group("voice") {
                        PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg", "uni"), "mode")
                        PanelKnob(b, "glide", "glide")
                        PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                        PanelKnob(b, "bend", "bend")
                        PanelKnob(b, "mpetimbre", "slide")
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
}

// --- Performance strip ------------------------------------------------------------
//
// The two controllers a player reaches for while holding a chord. One slim
// row above the keyboard: short enough not to take space from the roll, wide
// enough to hit with a thumb. They behave like the hardware they are named
// after - the wheel stays where you leave it, pressure falls back to nothing
// when you let go - which is also what makes them tell each other apart.




// --- Ratio -----------------------------------------------------------------------
//
// Six operators, two algorithms and a morph between them. Same sectioned
// Group layout as Trinity; names mirror engine/machine/ratio/Ratio.cpp and
// the algorithm list mirrors Algorithms.h - keep them in step.

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
    PanelSections {
        SectionChips(listOf("op", "algo", "filter", "env", "lfo", "mod", "voice"), section) { section = it }
        GroupRow {
            when (section) {
                0 -> for (o in 1..6) Group("op $o") {
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
                }
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
                3 -> for (e in 1..3) Group("env $e") {
                    val p = "e${e}_"
                    PanelKnob(b, p + "attack", "attack", PanelAmber)
                    PanelKnob(b, p + "decay", "decay", PanelAmber)
                    PanelKnob(b, p + "sustain", "sustain", PanelAmber)
                    PanelKnob(b, p + "release", "release", PanelAmber)
                }
                4 -> for (l in 1..3) Group("lfo $l") {
                    val p = "l${l}_"
                    PanelStepKnob(b, p + "wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, p + "rate", "rate", PanelAmber)
                    PanelStepKnob(b, p + "sync", TRINITY_LFO_SYNC, "sync", PanelAmber)
                    PanelKnob(b, p + "delay", "delay")
                    PanelKnob(b, p + "phase", "phase")
                    PanelSwitch(b, p + "keysync", listOf("free", "key"), "trig")
                }
                5 -> for (m in 1..10) Group("mod $m") {
                    val p = "m%02d_".format(m)
                    PanelStepKnob(b, p + "src", RATIO_SOURCES, "from", PanelAmber)
                    PanelStepKnob(b, p + "src2", RATIO_SOURCES, "× from")
                    PanelStepKnob(b, p + "dest", RATIO_DESTS, "to", PanelAmber)
                    PanelKnob(b, p + "depth", "depth", PanelAmber)
                }
                else -> {
                    Group("voice") {
                        PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg"), "mode")
                        PanelKnob(b, "glide", "glide")
                        PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                        PanelKnob(b, "bend", "bend")
                        PanelKnob(b, "mpetimbre", "slide")
                    }
                    Group("tuning") { PanelKnob(b, "octave", "octave"); PanelKnob(b, "transpose", "transpose") }
                    Group("out") { PanelKnob(b, "volume", "volume"); PanelKnob(b, "pan", "pan"); PanelKnob(b, "velamt", "vel") }
                }
            }
        }
    }
}

// --- Manual -----------------------------------------------------------------
//
// The drawbars are drawn as drawbars. Everything else on an organ is a tab or
// a switch, so the rest of the panel is the house style, but a registration
// is read as a shape - 88 8000 000 - and a row of knobs cannot be read that
// way. Two registrations are shown at once because morphing between them is
// the point of this machine.

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
// The colours a Hammond's drawbars are actually made in: the fundamentals
// white, the harmonics black, the two quints brown.
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
    PanelSections {
        SectionChips(listOf("bars", "perc", "vib", "rotary", "voice", "wind", "mod", "out"), section) { section = it }
        GroupRow {
            when (section) {
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
                6 -> for (m in 1..8) Group("mod $m") {
                    PanelStepKnob(b, "m${m}_src", MANUAL_SOURCES, "from", PanelAmber)
                    PanelStepKnob(b, "m${m}_dst", MANUAL_DESTS, "to", PanelAmber)
                    PanelKnob(b, "m${m}_amt", "amount", PanelAmber)
                }
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
}

// --- Cipher -----------------------------------------------------------------
//
// The bank first, because that is what a vocoder is, then the map, which is
// what this one is. Everything between measuring the modulator and imposing
// it on the carrier lives under "map", and that is where the machine stops
// being an ordinary vocoder.

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
 * A machine that listens has to be able to open the ear. A vocoder with
 * nothing coming in is a synth with the volume down, and a string waiting to
 * be spoken to is silent, so the panels own the microphone rather than
 * sending you to a menu to find it.
 */
@Composable
private fun InputListen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var running by remember { mutableStateOf(NativeEngine.inputRunning) }
    var level by remember { mutableStateOf(0f) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok -> if (ok) running = NativeEngine.startInput() }
    LaunchedEffect(Unit) {
        while (true) {
            running = NativeEngine.inputRunning
            if (running) level = NativeEngine.inputPeak()
            delay(100)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.machine_input), color = Acid.colors.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            if (running) {
                NativeEngine.stopInput()
                running = false
            } else if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                running = NativeEngine.startInput()
            } else {
                ask.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }) { Text(stringResource(if (running) R.string.machine_listening else R.string.machine_open), color = if (running) PanelTeal else PanelAmber, fontSize = 11.sp) }
        Meter(level, Modifier.width(70.dp).height(8.dp), vertical = false)
    }
}

@Composable
private fun CipherPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections {
        SectionChips(listOf("bank", "map", "carrier", "voice", "mod", "out"), section) { section = it }
        GroupRow {
            when (section) {
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
                        // modulator is unvoiced, which is what turns a sung
                        // vowel into a whisper. It had no control at all until
                        // an audit went looking: the Breath patch set it and no
                        // player could reach it or put it back.
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
                    for (m in 1..8) Group("mod $m") {
                        PanelStepKnob(b, "m${m}_src", CIPHER_SOURCES, "from", PanelAmber)
                        PanelStepKnob(b, "m${m}_dst", CIPHER_DESTS, "to", PanelAmber)
                        PanelKnob(b, "m${m}_amt", "amount", PanelAmber)
                    }
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
}


// --- Cumulus ---------------------------------------------------------------------

// The vowels the profile's formants interpolate through, and what shimmer
// can be tuned to. Names mirror engine/machine/cumulus/Cloud.cpp.
private val CUMULUS_SHIMMER = listOf("5th", "8ve", "8ve+5", "2 8ve")
private val CUMULUS_FILTERS = listOf("LP6", "LP12", "LP18", "LP24", "HP6", "HP12", "HP18", "HP24", "BP6", "BP12", "notch", "peak")
private val CUMULUS_LFO = listOf("sine", "tri", "saw↑", "saw↓", "square", "s&h", "smooth", "8 step", "16 step")

/**
 * Cumulus's panel, in two halves. The "cloud" and "morph to" sections build
 * the tables - each knob there is an inverse transform of a quarter of a
 * million points, done off the audio thread when it settles - and everything
 * else is live. They are kept in separate sections for that reason, and the
 * build ones are marked in amber.
 */
@Composable
private fun CumulusPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections {
        SectionChips(listOf("cloud", "morph to", "play", "shape", "env", "mod", "out"), section) { section = it }
        GroupRow {
            when (section) {
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
}


// --- Formulate -------------------------------------------------------------------

private val FORMULATE_WAVES = listOf("pulse", "tri", "saw", "noise", "off")
private val FORMULATE_MODES = listOf("off", "replace", "ring", "gate", "xor")

/** Expressions to start from, none of them anybody else's one-liner. */
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
    PanelSections {
        SectionChips(listOf("chip", "formula", "tables", "shape", "env", "out"), section) { section = it }
        GroupRow {
            when (section) {
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

/** The formula itself, shown as what it is: text, and whether it reads. */
@Composable
private fun FormulaButton(track: Track, error: String, onEdit: () -> Unit) {
    val c = Acid.colors
    val text = track.machine.settings["formula"].orEmpty()
    Column(Modifier.widthIn(min = 150.dp, max = 300.dp)) {
        Text(
            text.ifEmpty { stringResource(R.string.formula_none) },
            color = if (text.isEmpty()) c.textDim else c.textHi,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (error.isNotEmpty()) {
            Text(error, color = c.red, fontSize = 10.sp, maxLines = 2)
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.formula_edit), color = Acid.colors.accent, fontSize = 12.sp) }
    }
}

/**
 * Where the machine is actually programmed: one expression and three step
 * tables. Applied on OK, because a half-typed formula should not be
 * compiled on every keystroke.
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
        ScaledWindow {
            androidx.compose.material3.Surface(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).widthIn(max = 720.dp),
                shape = RoundedCornerShape(16.dp),
                color = c.card,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.formula_title), color = c.text, fontSize = 20.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = formula, onValueChange = { formula = it },
                        label = { Text(stringResource(R.string.formula_expression)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error.isNotEmpty()) Text(error, color = c.red, fontSize = 12.sp)
                    Text(stringResource(R.string.formula_examples), color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
                    Text(stringResource(R.string.formula_tables), color = c.teal, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                    androidx.compose.material3.OutlinedTextField(
                        value = arp, onValueChange = { arp = it }, label = { Text(stringResource(R.string.formula_arp)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = duty, onValueChange = { duty = it }, label = { Text(stringResource(R.string.formula_duty)) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = vol, onValueChange = { vol = it }, label = { Text(stringResource(R.string.formula_volume)) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = { onApply(formula, arp, duty, vol) }) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }
}




// --- Genesis ---------------------------------------------------------------------

/**
 * Genesis's panel. Every voice has its own group, the way the machine it is
 * named after had its own strip of knobs - and the bus at the end, because
 * the compressor and the duck are as much the sound as the kick is.
 */
@Composable
private fun GenesisPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections {
        SectionChips(listOf("kick", "snare", "toms", "metal", "bus"), section) { section = it }
        GroupRow {
            when (section) {
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
}

// --- Resonance -------------------------------------------------------------------

private val RESONANCE_SHAPES = listOf("membrane", "bar", "plate", "tube", "bowl", "metal")

/**
 * Resonance's panel: one object at a time, chosen with the pads, because
 * eight sets of fourteen knobs at once is a wall rather than an instrument.
 * The kit-wide controls sit at the end, where they belong - coupling is the
 * one that makes eight objects into one kit.
 */
@Composable
private fun ResonancePanel(b: ParamBinding, pad: Int) {
    val p = pad.coerceIn(0, 7)
    fun n(name: String) = "p%02d_%s".format(p, name)
    val hot = Acid.colors.accent
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.resonance_object, p + 1), color = hot, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.resonance_about),
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
 * Dice's panel: the loop and where it is cut, the dice themselves, and then
 * one slice at a time - chosen with the pads, the way Forage chooses a pad.
 */
/**
 * Molt: the take across the top, then what is done to it.
 *
 * The take is the instrument here, so it comes first and says what it is -
 * a name, or "no take" - with the two ways of getting one beside it. There is
 * no harmony section and no key: the notes in the clip are both, which is the
 * whole idea, and a knob for it would be a second opinion.
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
    PanelSections {
        SectionChips(listOf("take", "tune", "voice", "tone"), section) { section = it }
        GroupRow {
            when (section) {
                0 -> {
                    Group("take") {
                        Column(Modifier.widthIn(min = 150.dp, max = 280.dp)) {
                            Text(
                                sample.substringAfterLast('/').ifEmpty { stringResource(R.string.machine_no_take) },
                                color = if (sample.isEmpty()) c.textDim else c.textHi,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Row {
                                TextButton(onClick = onImport) { Text(stringResource(R.string.machine_import), color = c.textMid, fontSize = 11.sp) }
                                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.machine_samples), color = c.textMid, fontSize = 11.sp) }
                            }
                        }
                    }
                    // **Recording is one window, not a switch on a panel.**
                    //
                    // This was `rec`, `length` and `in` - a machine with its
                    // own capture buffer, its own twelve-second ceiling and no
                    // way to open the microphone from the panel it was on. It
                    // recorded six seconds of silence and said nothing.
                    // Molt's take now arrives the way every other machine's
                    // material does: a file, which can also be trimmed and
                    // levelled before it is sung, which for a real recording
                    // is most of the work.
                    Group("sing") {
                        PanelActions(
                            Triple(stringResource(R.string.machine_record), PanelAmber) { recording = true },
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
    // What the loop is to the song: the engine's guess at its bars, which the
    // bars knob can overrule, and its length - and so its tempo.
    val context = LocalContext.current
    val shape by produceState<Pair<Float, Float>?>(null, sample) {
        value = if (sample.isEmpty()) null else withContext(Dispatchers.IO) {
            NativeEngine.loopShape(java.io.File(com.rm.acidulous.engine.EngineAssets.userRoot(context), sample).absolutePath)
        }
    }
    val barsAt = b.infoOf("bars")?.map(b.value("bars"))?.toInt() ?: 0
    val loopBars = if (barsAt == 0) shape?.first ?: 0f else DICE_BARS.getOrElse(barsAt - 1) { 0f }
    val loopBpm = shape?.second?.takeIf { it > 0f && loopBars > 0f }?.let { loopBars * 4f * 60f / it }
    PanelSections {
        SectionChips(listOf("loop", "dice", "slice", "tone"), section) { section = it }
        if (section == 2) {
            Text(
                stringResource(R.string.dice_slice, p + 1),
                color = Acid.colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        GroupRow {
            when (section) {
                0 -> {
                    Group("loop") {
                        Column(Modifier.widthIn(min = 150.dp, max = 280.dp)) {
                            Text(
                                sample.substringAfterLast('/').ifEmpty { stringResource(R.string.machine_no_loop) },
                                color = if (sample.isEmpty()) c.textDim else c.textHi,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (shape != null) {
                                Text(
                                    if (loopBpm == null) stringResource(R.string.dice_loop_no_tempo)
                                    else pluralStringResource(
                                        R.plurals.dice_loop_tempo, if (loopBars <= 1f) 1 else 2,
                                        if (loopBars < 1f) "½" else loopBars.toInt().toString(), kotlin.math.round(loopBpm).toInt(),
                                    ),
                                    color = if (loopBpm == null) c.accent else c.textMid,
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                )
                            }
                            Row {
                                TextButton(onClick = onImport) { Text(stringResource(R.string.machine_import), color = c.textMid, fontSize = 11.sp) }
                                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.machine_samples), color = c.textMid, fontSize = 11.sp) }
                            }
                        }
                    }
                    // Follow plays the loop at the song's tempo, stretched so it
                    // keeps its pitch; bars is what its own tempo is worked out
                    // from, and auto is the engine's guess.
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
                    // The group's title is harvested for the lane list, so it
                    // stays a constant; which slice is selected is said above.
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

/** The bars knob past auto, as Dice's engine reads it. */
private val DICE_BARS = listOf(0.5f, 1f, 2f, 4f, 8f, 16f)

// --- Pollen ----------------------------------------------------------------------

private val POLLEN_SOURCES = listOf("sample", "live")
private val POLLEN_WINDOWS = listOf("hann", "tukey", "decay", "swell")
private val POLLEN_SCATTER = listOf("free", "8ve", "5ths", "triad", "scale")
private val POLLEN_SCALES = com.rm.acidulous.model.Scales.names
private val POLLEN_KEYS = com.rm.acidulous.model.Scales.keyNames

/**
 * Pollen's panel. The source section is where the machine is decided - a
 * file or the microphone - and everything else shapes the cloud over it.
 *
 * Two things here that no other panel has: a line saying what the buffer
 * currently holds, and a warning when the source is live, because a live
 * buffer is not part of the song and an exported song will not have it.
 */
@Composable
private fun PollenPanel(b: ParamBinding, track: Track, trackIndex: Int, editor: SongEditor, onImport: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val c = Acid.colors
    val sample = track.machine.settings["sample"].orEmpty()
    val live = (b.value("source") ?: 0f) >= 0.5f
    PanelSections {
        SectionChips(listOf("source", "cloud", "spray", "pollen", "tone", "voice"), section) { section = it }
        GroupRow {
            when (section) {
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
                                sample.substringAfterLast('/').ifEmpty { stringResource(R.string.machine_no_file) },
                                color = if (sample.isEmpty()) c.textDim else c.textHi,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (live) {
                                Text(
                                    stringResource(R.string.pollen_live),
                                    color = c.accent, fontSize = 9.sp, maxLines = 2,
                                )
                            }
                            Row {
                                TextButton(onClick = onImport) { Text(stringResource(R.string.machine_import), color = c.textMid, fontSize = 11.sp) }
                                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.machine_samples), color = c.textMid, fontSize = 11.sp) }
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
 * A control that is an action rather than a value: it sets the parameter,
 * and lets go. The engine acts on the edge, and reads the raw target rather
 * than the smoothed value so a pulse cannot be smoothed away.
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
 * The four-track's face: a card per lane, and a lane's card is where its
 * recording lives.
 *
 * **The one panel in the app that is partly about this cell rather than about
 * the machine**, and the split down the middle of each card is the machine
 * itself: the level and the mute are parameters, so they automate, map to a
 * pad and record into a lane; the take is the document, so it arranges. A
 * level that lived on the recording would have been a second kind of level,
 * invisible to all three.
 *
 * Four cards and no section chips, because four lanes are what a tape has.
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
    PanelSections {
        // Two sections and no more: what is on the tape, and what the tape is.
        // The patch bar above chooses the second of those wholesale, and these
        // are what it moved.
        SectionChips(listOf("lanes", "medium", "tempo"), section) { section = it }
        GroupRow {
            if (section >= 1) {
              if (section == 1) {
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
            } else if (section == 2) {
                Group("tempo") {
                    PanelSwitch(b, "stretch", listOf("as sung", "follow"), "takes")
                }
                Group("input") {
                    PanelKnob(b, "monitor", "monitor", PanelPink)
                    InputListen()
                }
                // **What the input goes through on its way in - and so what is
                // printed into the take.** The slots belong to the song rather
                // than to this track, but this is where the hand is when
                // somebody decides they want the amp *on* the recording rather
                // than after it.
                // Named for what happens to the file, so it needs no note -
                // the same four words the record window uses, shortened to fit
                // a card title.
                Group("printed in") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InputChainChips(editor)
                    }
                }
              }
                return@GroupRow
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
                        // The tempo it was sung at, and only when that is not
                        // the tempo it is being played at. Audio does not
                        // stretch yet, so this is the one number that says why
                        // a take drifts away from the beat.
                        if (take != null && !track.followsTempo() &&
                            kotlin.math.abs(take.bpm - sceneBpm) > 0.05f) {
                            Text(
                                stringResource(R.string.bias_take_bpm, take.bpm), color = c.accent,
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            )
                        }
                        Row {
                            TextButton(onClick = { picking = lane }) {
                                Text(stringResource(R.string.bias_audio), color = c.textMid, fontSize = 11.sp)
                            }
                            if (take != null) TextButton(onClick = {
                                editor.editClip(trackIndex, sceneId) { cl -> cl.withTake(lane, null) }
                            }) { Text(stringResource(R.string.bias_clear), color = c.textMid, fontSize = 11.sp) }
                        }
                    }
                    PanelKnob(b, "lane${lane + 1}", "level")
                    // Labelled for the automation list rather than for the
                    // card: inside a card already titled "lane 2", the lane
                    // list would otherwise read "lane 2 lane".
                    PanelSwitch(b, "mute${lane + 1}", listOf("on", "mute"), "mute")
                }
            }
        }
    }
    if (picking >= 0) TakePicker(trackIndex, sceneId, picking, editor, scope) { picking = -1 }
}

// --- Filament ---------------------------------------------------------------
//
// The exciter first, because on this machine that is the instrument choice -
// the same string plucked, struck, bowed or blown at is four instruments -
// then the string itself, then what is around it.

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
    PanelSections {
        SectionChips(listOf("exciter", "string", "prepare", "around", "mod", "out"), section) { section = it }
        GroupRow {
            when (section) {
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
                    // The input exciter plays the string with whatever is
                    // coming in, which needs something to be coming in.
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
                    for (m in 1..8) Group("mod $m") {
                        PanelStepKnob(b, "m${m}_src", FILAMENT_SOURCES, "from", PanelAmber)
                        PanelStepKnob(b, "m${m}_dst", FILAMENT_DESTS, "to", PanelAmber)
                        PanelKnob(b, "m${m}_amt", "amount", PanelAmber)
                    }
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
}

// --- Brazen -----------------------------------------------------------------
//
// The horn first, because the horn is the instrument: how big the tube is,
// what the bell does with the wave, and what is stuffed into it. Then the
// player - lips, air, how hard they are leaning on it. Then the section,
// which is the reason this machine exists and the only page here with no
// equivalent on a sampled brass library.

private val BRAZEN_MUTES = listOf("open", "straight", "cup", "harmon")

@Composable
private fun BrazenPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections {
        SectionChips(listOf("horn", "player", "section", "shape", "out"), section) { section = it }
        GroupRow {
            when (section) {
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
}

// --- Timber -----------------------------------------------------------------
//
// The pipe first, because on this machine the pipe is the instrument: what
// starts the air and what shape it is moving in are two chips that between
// them are the whole woodwind family. Then the mouth, then the holes - which
// is the page that has no equivalent anywhere else, because it is about the
// part of the instrument below the note.

private val TIMBER_FAMILY = listOf("reed", "double", "air")
private val TIMBER_BORE = listOf("cylinder", "cone")
private val TIMBER_REGISTER = listOf("natural", "register", "altissimo")

@Composable
private fun TimberPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    PanelSections {
        SectionChips(listOf("pipe", "mouth", "holes", "tongue", "shape", "out"), section) { section = it }
        GroupRow {
            when (section) {
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
}

// --- Nexus ------------------------------------------------------------------
//
// The strip stays a strip. A graph needs a screen, so this holds the things
// you reach for while playing - the macros, the morph, the output - and a
// way through to the canvas.

@Composable
private fun NexusPanel(b: ParamBinding, track: Track, onOpenPatch: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(0) }
    val patch = remember(track.machine.settings["nexus"]) {
        com.rm.acidulous.model.NexusPatch.decode(track.machine.settings["nexus"])
    }
    PanelSections {
        SectionChips(listOf("patch", "macros", "voice", "out"), section) { section = it }
        GroupRow {
            when (section) {
                0 -> {
                    Group("patch") {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                listOf(
                                    pluralStringResource(R.plurals.nexus_modules, patch.modules.size, patch.modules.size),
                                    pluralStringResource(R.plurals.nexus_cables, patch.cables.size, patch.cables.size),
                                ).joinToString(" · "),
                                color = Acid.colors.text, fontSize = 11.sp, maxLines = 1)
                            Text(patch.modules.take(6).joinToString(" ") { it.type },
                                color = Acid.colors.textDim, fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1)
                            TextButton(onClick = onOpenPatch) {
                                Text(stringResource(R.string.nexus_patch), color = PanelAmber, fontSize = 11.sp)
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
                    PanelKnob(b, "pan", "pan")
                    PanelKnob(b, "drive", "drive", PanelPink)
                }
            }
        }
    }
}

// --- Mosaic ----------------------------------------------------------------------
//
// The map section is the one place a machine puts a picture above its group
// row: a sampler without a visible key-by-velocity map is guesswork. The
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
    // A sample recorded in the app is added as a zone the same way an
    // imported one is; a SoundFont owns the whole map, so it steps aside.
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

    PanelSections {
        SectionChips(listOf("map", "sample", "grain", "filter", "env", "lfo", "mod", "voice"), section) { section = it }
        if (section == 0) {
            if (sf2.isEmpty()) {
                ZoneMapView(zones, selectedZone, { i -> selectedZone = i; editing = true },
                    Modifier.fillMaxWidth().height(96.dp).padding(bottom = 4.dp))
            } else {
                // A SoundFont preset carries its own map; the file owns it, so
                // it is shown rather than edited.
                Text(
                    stringResource(R.string.mosaic_soundfont, sf2.substringAfterLast('/'), info.ifEmpty { stringResource(R.string.mosaic_loading) }),
                    color = Acid.colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp), maxLines = 1,
                )
            }
        }
        GroupRow {
            when (section) {
                0 -> {
                    Group("instrument") {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(info.split('|').firstOrNull().orEmpty().ifEmpty { stringResource(R.string.mosaic_nothing) },
                                color = Acid.colors.text, fontSize = 11.sp, maxLines = 1)
                            Text(
                                info.split('|').let { f ->
                                    if (f.size >= 4) stringResource(R.string.mosaic_preset_info, f[1], f[2], f[3].toFloatOrNull() ?: 0f)
                                    else " "
                                },
                                color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            )
                            Row {
                                TextButton(onClick = onImportSoundFont) { Text(stringResource(R.string.mosaic_import_soundfont), color = PanelAmber, fontSize = 10.sp) }
                                if (sf2.isNotEmpty()) TextButton(onClick = onPickPreset) { Text(stringResource(R.string.mosaic_preset), color = PanelAmber, fontSize = 10.sp) }
                                TextButton(onClick = onImportZoneSamples) { Text(stringResource(R.string.mosaic_samples), color = Acid.colors.textMid, fontSize = 10.sp) }
                                TextButton(onClick = { pickingZone = true }) { Text(stringResource(R.string.mosaic_samples), color = Acid.colors.textMid, fontSize = 10.sp) }
                            }
                        }
                    }
                    if (sf2.isEmpty()) Group("zones") {
                        Column {
                            Text(pluralStringResource(R.plurals.mosaic_zones, zones.size, zones.size), color = Acid.colors.text, fontSize = 11.sp)
                            Row {
                                TextButton(
                                    onClick = {
                                        // Spread them evenly and set each root to the middle of its span.
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
                                ) { Text(stringResource(R.string.mosaic_spread), color = PanelAmber, fontSize = 10.sp) }
                                TextButton(onClick = { putZones(emptyList()) }, enabled = zones.isNotEmpty()) {
                                    Text(stringResource(R.string.mosaic_clear), color = Acid.colors.red, fontSize = 10.sp)
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
                    for (e in 1..2) Group("env $e") {
                        PanelKnob(b, "e${e}_attack", "attack")
                        PanelKnob(b, "e${e}_decay", "decay")
                        PanelKnob(b, "e${e}_sustain", "sustain")
                        PanelKnob(b, "e${e}_release", "release")
                    }
                }
                5 -> for (l in 1..2) Group("lfo $l") {
                    val p = "l${l}_"
                    PanelStepKnob(b, p + "wave", TRINITY_LFO_WAVES, "wave", PanelAmber)
                    PanelKnob(b, p + "rate", "rate", PanelAmber)
                    PanelStepKnob(b, p + "sync", TRINITY_LFO_SYNC, "sync", PanelAmber)
                    PanelKnob(b, p + "delay", "delay")
                    PanelKnob(b, p + "phase", "phase")
                    PanelSwitch(b, p + "keysync", listOf("free", "key"), "trig")
                }
                6 -> for (m in 1..8) Group("mod $m") {
                    val p = "m%02d_".format(m)
                    PanelStepKnob(b, p + "src", MOSAIC_SOURCES, "from", PanelAmber)
                    PanelStepKnob(b, p + "src2", MOSAIC_SOURCES, "× from")
                    PanelStepKnob(b, p + "dest", MOSAIC_DESTS, "to", PanelAmber)
                    PanelKnob(b, p + "depth", "depth", PanelAmber)
                }
                else -> {
                    Group("voice") {
                        PanelSwitch(b, "voicemode", listOf("poly", "mono", "leg"), "mode")
                        PanelKnob(b, "glide", "glide")
                        PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                        PanelKnob(b, "bend", "bend")
                    }
                    Group("out") { PanelKnob(b, "volume", "volume"); PanelKnob(b, "pan", "pan"); PanelKnob(b, "velamt", "vel") }
                }
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
