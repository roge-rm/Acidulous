package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.EFFECT_SLOTS
import com.rm.acidulous.model.InsertMod
import com.rm.acidulous.model.SIDECHAIN_PARAM
import com.rm.acidulous.model.SIDECHAIN_STEPS
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.PatchStore
import com.rm.acidulous.model.withEffectPatch
import com.rm.acidulous.model.slotOf
import com.rm.acidulous.model.withSlotSetting
import com.rm.acidulous.model.withModifierParam
import com.rm.acidulous.model.withModifierBypass
import com.rm.acidulous.model.withModifier
import com.rm.acidulous.model.modifierUnit
import com.rm.acidulous.model.UnitSlot
import com.rm.acidulous.model.VenueRooms
import com.rm.acidulous.model.MODIFIER_SLOTS
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.effectUnit
import com.rm.acidulous.model.withEffect
import com.rm.acidulous.model.withEffectBypass
import com.rm.acidulous.model.withEffectParam
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.roundToInt
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/** What a slot panel edits: the track's insert effects or its modifiers. */
enum class SlotKind(
    /** A key for what the panel remembers about each slot; [title] is its display name. */
    val label: String, val title: StringResource, val slots: Int,
    val types: () -> List<String>, val paramInfo: (String) -> List<com.rm.acidulous.engine.ParamInfo>,
    val unit: (Int) -> String, val at: (Track, Int) -> UnitSlot,
    val withType: (Track, Int, String) -> Track, val withParam: (Track, Int, String, Float) -> Track, val withBypass: (Track, Int, Boolean) -> Track,
    /**
     * How a unit of this kind is keyed in the patch store, or null when it has
     * no presets. Modifiers don't have presets yet, but this is where they'd go.
     */
    val patchKey: ((String) -> String)? = null,
    val loadPatch: ((Track, Int, Map<String, Float>) -> Track)? = null,
) {
    Effects("FX", Res.string.slot_fx, EFFECT_SLOTS, { NativeEngine.effectTypes }, { NativeEngine.effectParamInfo(it) }, ::effectUnit, { t, s -> t.effectAt(s) },
        { t, s, ty -> t.withEffect(s, ty) }, { t, s, n, v -> t.withEffectParam(s, n, v) }, { t, s, b -> t.withEffectBypass(s, b) },
        patchKey = PatchStore::effectKey, loadPatch = { t, s, p -> t.withEffectPatch(s, p) }),
    Modifiers("MOD", Res.string.slot_mod, MODIFIER_SLOTS, { NativeEngine.inputModTypes }, { NativeEngine.inputModParamInfo(it) }, ::modifierUnit, { t, s -> t.modifierAt(s) },
        { t, s, ty -> t.withModifier(s, ty) }, { t, s, n, v -> t.withModifierParam(s, n, v) }, { t, s, b -> t.withModifierBypass(s, b) }),
}

/**
 * A track's slots of one kind: pick a unit, bypass it, turn its knobs.
 * Knobs go through the same [ParamBinding] as a machine's, addressed to the
 * slot's unit name, so they record, automate and undo the same way.
 *
 * Four full inserts are taller than a phone can spare under the roll, so the
 * slots scroll past what the screen leaves them. An empty slot is one line.
 */
@Composable
fun SlotsPanel(
    kind: SlotKind, track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier,
    /** The most it may take, from the screen; see [SLOTS_PANEL_MAX_H]. */
    maxHeight: androidx.compose.ui.unit.Dp = SLOTS_PANEL_MAX_H,
) {
    val types = remember(kind) { kind.types() }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Acid.colors.panel)) {
        // The patch picker joins the header line when the panel is wide
        // enough for both, which saves a line per slot.
        val roomy = this.maxWidth >= SlotHeaderPickerW
        // A side column stacks each slot's cards two knobs to a line, as a
        // machine panel does there, and the column scrolls.
        val side = this.maxWidth < SlotSideW
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides side) {
            Column(
                Modifier.heightIn(max = maxHeight).verticalScrollWithBar(rememberScrollState()).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (slot in 0 until kind.slots) SlotRow(kind, track, trackIndex, slot, types, editor, pickerInHeader = roomy)
            }
        }
    }
}

/** How wide a panel has to be for a slot's patch picker to share its header line. */
private val SlotHeaderPickerW = 360.dp

/** Narrower than this, the panel is a side column; see [SlotsPanel]. */
private val SlotSideW = 300.dp

/**
 * One slot on its own, for the chip that opens it: choose the type, switch
 * it on, and its parameters underneath.
 */
@Composable
fun SlotDialog(
    kind: SlotKind,
    track: Track,
    trackIndex: Int,
    slot: Int,
    editor: SongEditor,
    /** Set when the control that opened the slot decides its type, so there's
     *  no dropdown to pick from. */
    fixedType: String? = null,
    onDismiss: () -> Unit,
) {
    val types = remember(kind) { kind.types() }
    // The controls write live, so you hear a knob as it turns. Cancel puts
    // every control and the bypass back to how they were when the window
    // opened, in one undo step, so it works like every other window.
    val revert = remember { mutableStateOf<(() -> Unit)?>(null) }
    val title = fixedType?.lowercase() ?: stringResource(kind.title, slot + 1)
    val dismiss = { revert.value?.invoke(); onDismiss() }
    // Uses the shell's default height (560), which the arp's wrapped rows of
    // controls need most of. The shell caps the card to the window anyway, so
    // this can't push the buttons off a turned phone. Turned, the unit's row
    // (what it is and whether it's on) moves into the header beside the title
    // and the cards get its height.
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SlotHeader(kind, track, trackIndex, slot, types, editor, fixedType, wrap = true)
        }
    }
    // Upright or on a square phone, a unit with too much for one page gets
    // split into pages so nothing scrolls, see [PAGES]. Turned, its cards
    // sit side by side on one.
    val pageTitles = PAGES[fixedType ?: kind.at(track, slot).type]?.let { pageNames(it) }
    if (pageTitles != null && (compactWindow() || screenShape() == ScreenShape.Tall)) {
        var page by rememberSaveable(kind.label, slot) { mutableStateOf(0) }
        TabbedDialog(
            title = title,
            selected = page,
            onDismiss = dismiss,
            dismissLabel = stringResource(Res.string.cancel),
            confirmLabel = stringResource(Res.string.ok),
            onConfirm = onDismiss,
            wideHeader = header,
            chips = { SectionChips(pageTitles, page) { page = it } },
            pages = pageTitles.indices.map { i ->
                { SlotRow(kind, track, trackIndex, slot, types, editor, fixedType, wrap = true, page = i, onRevert = { revert.value = it }) }
            },
        )
        return
    }
    PlainDialog(
        title = title,
        onDismiss = dismiss,
        dismissLabel = stringResource(Res.string.cancel),
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = onDismiss,
        wideHeader = header,
    ) {
        SlotRow(kind, track, trackIndex, slot, types, editor, fixedType, wrap = true, onRevert = { revert.value = it })
    }
}

/**
 * Which card titles each page shows, for a unit split into pages on a
 * phone held upright or a square one, so none of them scrolls. The arp's
 * steps go with its pattern.
 *
 * Pages of two cards, since a square phone's window holds two cards and a
 * strip, paired by topic. The arp: timing and feel, which notes, chance and
 * release. The chord: which chord and how it's stacked, how it's strummed,
 * and playing it from the keys or from memory.
 */
private val PAGES: Map<String, List<Set<String>>> = mapOf(
    "Arp" to listOf(setOf("time", "feel"), setOf("pattern"), setOf("chance", "run")),
    "Chord" to listOf(setOf("chord", "voicing"), setOf("strum", "rhythm"), setOf("strum keys", "memory")),
)

/** A page's tab: its cards' titles, in the phone's language. */
@Composable
private fun pageNames(pages: List<Set<String>>): List<String> {
    val resources = AppStrings
    return pages.map { cards -> cards.joinToString(" · ") { resources.panelWord(it) } }
}

@Composable
fun EffectsPanel(track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) =
    SlotsPanel(SlotKind.Effects, track, trackIndex, editor, modifier)

@Composable
private fun SlotRow(
    kind: SlotKind, track: Track, trackIndex: Int, slot: Int, types: List<String>, editor: SongEditor,
    fixedType: String? = null,
    /**
     * Lay the controls out as a window: they wrap into rows instead of running
     * off the side in one scrolling row, which is the panel style. A panel
     * shares its height with the piano roll, but a window has height to spare.
     */
    wrap: Boolean = false,
    /** Gives the window a way to put everything back; see [SlotDialog]. */
    onRevert: ((() -> Unit) -> Unit)? = null,
    /** One page of the unit's cards, or all of them when -1; see [PAGES]. */
    page: Int = -1,
    /** Put the patch picker on the header line; a panel with the room. */
    pickerInHeader: Boolean = false,
) {
    val fx = kind.at(track, slot)
    // The bypass as the window found it. Bypass isn't a parameter, so it's not
    // in the binding's baseline and has to be remembered here.
    val openedBypass = remember(kind, slot, trackIndex) { fx.bypass }
    // A Venue's impulse too, which is a setting.
    val openedImpulse = remember(kind, slot, trackIndex) { fx.settings[VenueRooms.SETTING] }
    // Per slot and kept across rotation, like a machine panel's. Two effects
    // and a modifier can fill a phone, so a slot you aren't editing can be
    // folded down to its one line.
    var minimized by rememberSaveable(kind.label, slot) { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Acid.colors.card).padding(4.dp)) {
        val face = !fx.isEmpty && !minimized
        val hasPatches = kind.patchKey?.invoke(fx.type) != null && kind.loadPatch != null
        val headerHere = !wrap && pickerInHeader && face && hasPatches
        // A side column is too narrow for the bypass on the first line, so it
        // starts the second, with the patch picker after it when there's a face.
        val side = !wrap && LocalPanelStacked.current
        if (!(wrap && LocalDialogHeaderRow.current) && !headerHere) Row(
            if (wrap || side) Modifier.fillMaxWidth() else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            // Centred in a window. Packed left in a panel so it lines up with the
            // slots above and below.
            horizontalArrangement = if (wrap) {
                Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.spacedBy(4.dp)
            },
        ) {
            SlotHeader(kind, track, trackIndex, slot, types, editor, fixedType, wrap, minimized, { minimized = !minimized }, withBypass = !side)
        }
        if (side && !fx.isEmpty && !(face && hasPatches)) SlotBypass(kind, fx, trackIndex, slot, editor)
        if (face) {
            val header: (@Composable (@Composable () -> Unit) -> Unit)? = if (headerHere) { picker ->
                Row(Modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SlotHeader(kind, track, trackIndex, slot, types, editor, fixedType, wrap, minimized, { minimized = !minimized }, picker)
                }
            } else if (side) { picker ->
                Row(Modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SlotBypass(kind, fx, trackIndex, slot, editor)
                    picker()
                }
            } else null
            SlotFace(kind, fx.type, trackIndex, slot, editor, wrap, page, header) { b ->
                onRevert?.invoke {
                    b.resetAll()
                    if (fx.bypass != openedBypass) {
                        editor.edit(trackIndex) { t -> kind.withBypass(t, slot, openedBypass) }
                        NativeEngine.setParam(trackIndex, kind.unit(slot), "bypass", if (openedBypass) 1f else 0f, record = true)
                    }
                    val now = editor.song.tracks.getOrNull(trackIndex)?.let { kind.at(it, slot) }
                    if (now != null && now.settings[VenueRooms.SETTING] != openedImpulse) {
                        editor.editSong { song -> song.withSlotSetting(trackIndex, kind.unit(slot), VenueRooms.SETTING, openedImpulse) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotFace(
    kind: SlotKind, type: String, trackIndex: Int, slot: Int, editor: SongEditor,
    wrap: Boolean = false,
    /** Which of [PAGES]' pages to show, or all when -1. */
    page: Int = -1,
    /** The slot's header line, given the patch picker to draw on it; null to draw the picker on a line of its own. */
    header: (@Composable (@Composable () -> Unit) -> Unit)? = null,
    /** Called with the binding once it exists, so a window can undo everything. */
    onBinding: ((ParamBinding) -> Unit)? = null,
) {
    // An insert's mod settings ride in the same binding, so they record, lock and revert with the knobs.
    val info = remember(kind, type) { kind.paramInfo(type) + if (kind == SlotKind.Effects) modInfo else emptyList() }
    val unit = kind.unit(slot)
    val b = rememberParamBinding(trackIndex, type, info, editor, unit) { t, n, v -> kind.withParam(t, slot, n, v) }
    androidx.compose.runtime.SideEffect { onBinding?.invoke(b) }
    // A Venue's impulse is a setting, not a knob, so it's read from the song.
    var impulseEdits by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val venue = type == VenueRooms.TYPE && kind == SlotKind.Effects
    val impulse = if (!venue) "" else impulseEdits.let {
        editor.song.slotOf(trackIndex, unit)?.let { VenueRooms.impulseOf(it) } ?: VenueRooms.DEFAULT
    }
    val pickImpulse: (String) -> Unit = { new ->
        editor.editSong { song -> song.withSlotSetting(trackIndex, unit, VenueRooms.SETTING, new) }
        impulseEdits++
    }
    // The panel follows the engine, which already holds what the document
    // pushed, so there's nothing to seed here.
    Column {
        // The same patch picker a machine panel has, over the same store, with
        // a key that can't collide with a machine's.
        val key = kind.patchKey?.invoke(type)
        val load = kind.loadPatch
        if (key != null && load != null) {
            val picker: @Composable () -> Unit = {
                PatchPicker(
                    title = type,
                    patchNames = { PatchStore.list(key) },
                    onSave = { name ->
                        val at = kind.at(editor.song.tracks[trackIndex], slot)
                        PatchStore.save(Patch(key, name, InsertMod.withoutMod(at.params), settings = at.settings))
                    },
                    onLoad = { name ->
                        PatchStore.load(key, name)?.let { patch ->
                            editor.edit(trackIndex) { t -> load(t, slot, patch.params) }
                            // A Formula's formula comes with its preset.
                            if (type == "Formula" && kind == SlotKind.Effects) {
                                editor.editSong { song -> song.withSlotSetting(trackIndex, unit, "formula", patch.settings["formula"]) }
                            }
                            // So does a Venue's impulse.
                            if (type == VenueRooms.TYPE && kind == SlotKind.Effects) {
                                editor.editSong { song -> song.withSlotSetting(trackIndex, unit, VenueRooms.SETTING, patch.settings[VenueRooms.SETTING]) }
                                impulseEdits++
                            }
                            // The slot's mod stays as it is.
                            b.applyAll(InsertMod.withoutMod(patch.params) + modInfo.associate { it.name to b.value(it.name) })
                        }
                    },
                    factoryPatches = { PatchStore.factory(key) },
                    userNames = { PatchStore.userList(key) },
                    onDelete = { name -> PatchStore.delete(key, name) },
                    key = key,
                    owner = "$key:$slot",
                )
            }
            if (header != null) header(picker) else Row(verticalAlignment = Alignment.CenterVertically) { picker() }
        }
        // A Formula effect's formula: words, so a field rather than a knob.
        if (type == "Formula" && kind == SlotKind.Effects) {
            var edits by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val text = edits.let { editor.song.slotOf(trackIndex, unit)?.settings?.get("formula").orEmpty() }
            EffectFormula(text, EngineSync.effectFormulaErrors[EngineSync.effectFormulaKey(trackIndex, unit)].orEmpty()) { new ->
                editor.editSong { song -> song.withSlotSetting(trackIndex, unit, "formula", new.ifEmpty { null }) }
                edits++
            }
        }
        val control: @Composable (ParamInfo) -> Unit = { p ->
            run {
                // A sidechain picks a track, so its steps show the song's track names.
                val labels = if (p.name == SIDECHAIN_PARAM) {
                    listOf(stringResource(Res.string.slot_sidechain_own)) + (0 until SIDECHAIN_STEPS - 1).map { i ->
                        editor.song.tracks.getOrNull(i)?.name ?: stringResource(Res.string.slot_sidechain_empty, i + 1)
                    }
                } else {
                    switchLabels(type, p.name, p.steps)
                }
                val accent = if (p.name in EXTRA[type].orEmpty()) Acid.colors.accent else Acid.colors.teal
                // A knob is 58 dp wide and some names aren't. Shortened here, not in the
                // engine, because patch files, lanes and mappings all use the engine name.
                val shortLabel = shortLabelOf(type, p.name)
                when {
                    // A few choices: buttons. Many (note values): a stepped knob that names its step.
                    p.curve == 2 && labels != null && labels.size <= 4 -> PanelSwitch(b, p.name, labels, label = shortLabel)
                    p.curve == 2 && labels != null -> Knob(
                        label = panelWord(shortLabel), value = b.value(p.name), accent = accent,
                        // The step's position in the range, not its value. `p.map` gives
                        // the value in its own units, which only works as a list index
                        // for a range starting at 0. The Harmonizer's `interval` runs
                        // -7..7, so +2 showed "-5".
                        display = panelWords(labels)[(b.value(p.name) * (labels.size - 1))
                            .roundToInt().coerceIn(0, labels.size - 1)],
                        onStart = { b.start(p.name) }, onChange = { v -> b.change(p.name, v) }, onEnd = { b.end() },
                        onReset = { b.reset(p.name) },
                        steps = labels.size,
                    )
                    else -> PanelKnob(b, p.name, label = shortLabel, accent = accent)
                }
            }
        }
        // The arp's sixteen step toggles get their own row below, so they're
        // left out of the cards.
        val model = if (type == "Reverb") reverbModel(b.value("model")) else null
        val shown = info.filterNot {
            InsertMod.isModParam(it.name) ||
                type == "Arp" && it.name.length == 3 && it.name[0] == 's' && it.name[1].isDigit() ||
                // The chord's memory slots are learned, not turned.
                type == "Chord" && CHORD_MEMORY_NOTE.matches(it.name) ||
                model != null && !reverbShows(model, it.name, trackInsert = kind == SlotKind.Effects)
        }
        if (wrap) {
            // A card per group, stacked down the window, each wrapping its own
            // controls. `LocalPanelStacked` tells `Group` to wrap.
            val wide = LocalDialogWide.current
            BusyCards(type) { perLine ->
                val pages = PAGES[type]
                if (venue) VenueImpulseCard(impulse, pickImpulse)
                for ((title, group) in groupsFor(type, shown, model)) {
                    if (page >= 0 && pages != null && title !in pages[page]) continue
                    key(title) {
                        Group(title, perLine = perLine, centred = true, background = Acid.colors.cardAlt) {
                            for (p in group) key(p.name) { control(p) }
                            if (type == "Chord" && title == "memory") LearnChord(b, trackIndex, unit, editor, slot, kind)
                        }
                    }
                }
                if (kind == SlotKind.Effects) ModCard(type, modTrackNames(editor), b.modAccess())
                // Turned, the steps are a card like the others, two lines of eight,
                // since there's no height left for a strip under them.
                if (type == "Arp" && wide) {
                    Group("steps", background = Acid.colors.cardAlt) { ArpStepGrid(b) }
                }
            }
            if (type == "Arp" && !wide && (page < 0 || "pattern" in PAGES[type].orEmpty().getOrNull(page).orEmpty())) ArpSteps(b)
        } else {
            // A panel's knobs are in titled cards like a machine's, as tall as
            // the mod card beside them. The reverb's model first, then its own
            // knobs, as its window has them.
            // Packed cards take four knobs to a line, as a window's do; a side column's two.
            val perLine = if (venue && !LocalPanelStacked.current) 4 else LocalStackedPerLine.current
            // Packed, Venue's tone card goes beside the impulse card, which saves a line.
            val groups = groupsFor(type, shown, model).let { g ->
                if (perLine == 4) g.sortedBy { it.first != "tone" } else g
            }
            val cards: @Composable () -> Unit = {
                if (venue) VenueImpulseCard(impulse, pickImpulse)
                for ((title, group) in groups) key(title) {
                    Group(title, perLine = perLine, background = Acid.colors.cardAlt) {
                        for (p in group) key(p.name) { control(p) }
                    }
                }
                if (kind == SlotKind.Effects) ModCard(type, modTrackNames(editor), b.modAccess())
            }
            // Label and value lines just taller than their words, as in the
            // reverb's window, so a slot costs the roll less height.
            val tight = androidx.compose.material3.LocalTextStyle.current.merge(
                androidx.compose.ui.text.TextStyle(lineHeight = 1.3.em),
            )
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides tight,
                LocalControlH provides PanelControlH - 24.dp,
            ) { when {
                // A side column: the cards go down it two knobs to a line, and
                // the slots' column scrolls; see [SlotsPanel].
                LocalPanelStacked.current -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { cards() }
                // Venue's cards are too many for one row upright, so they're
                // packed onto lines like its window's.
                venue -> WindowCards(pack = true) { cards() }
                else -> Row(
                    Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { cards() }
            } }
            if (type == "Arp") ArpSteps(b)
        }
    }
}

/** The tracks a keyed mod can follow, by name, as the sidechain lists them. */
@Composable
internal fun modTrackNames(editor: SongEditor): List<String> =
    (0 until SIDECHAIN_STEPS - 1).map { i ->
        editor.song.tracks.getOrNull(i)?.name ?: stringResource(Res.string.slot_sidechain_empty, i + 1)
    }

/** About two full slots: a header and a row of knobs each. The editor passes more upright when the screen has it. */
private val SLOTS_PANEL_MAX_H = 300.dp

/** Names too long for a knob's width, said shorter. */
private val SHORT_LABELS = mapOf(
    "ratchetchance" to "rchance",
    "velspread" to "vspread",
    "strumdir" to "dir",
    "octmode" to "octmod",
    "humanise" to "human",
    "inversion" to "invert",
)

/**
 * Which controls belong together, for windows that get a card per group,
 * so related controls sit in one titled card instead of in engine order.
 *
 * Anything not named here still shows up in a trailing card, so a new
 * parameter is never hidden.
 */
private val PANEL_GROUPS: Map<String, List<Pair<String, List<String>>>> = mapOf(
    "Arp" to listOf(
        // When a note happens, and for how long.
        "time" to listOf("rate", "gate", "swing"),
        // Which note, out of what you're holding.
        "pattern" to listOf("mode", "octaves", "octmode", "length"),
        // How hard, and how human.
        "feel" to listOf("velmode", "accent", "humanise"),
        // Ratchets and chance.
        "chance" to listOf("ratchet", "ratchetchance", "chance"),
        // Timing against the song, and what happens when you let go.
        "run" to listOf("sync", "shift", "cycles", "latch"),
    ),
    "Chord" to listOf(
        // Which chord.
        "chord" to listOf("mode", "type", "key", "scale"),
        // How it's stacked.
        "voicing" to listOf("voicing", "shape", "inversion", "spread", "bass"),
        // How it's played, not which notes.
        "strum" to listOf("strum", "strumdir", "velspread"),
        // Keys above the split play the chord's notes, keys below pick the chord.
        "strum keys" to listOf("play", "split", "keys", "latch", "ring"),
        // The chord strummed in time while its key is held.
        "rhythm" to listOf("rhythm", "swing", "accent", "mute", "humanise"),
        // A chord of your own, learned from the keys.
        "memory" to listOf("memory"),
    ),
    "Scale" to listOf(
        "scale" to listOf("mode", "key", "scale"),
        "how" to listOf("snap", "octave", "transpose"),
    ),
    "Delay" to listOf(
        "echo" to listOf("time", "feedback", "tone", "mix"),
        // Taps and spacing only act in multi-tap.
        "mode" to listOf("mode", "taps", "spacing"),
        "extra" to listOf("duck", "wobble"),
        "out" to listOf("gain"),
    ),
    "Transient" to listOf(
        "shape" to listOf("attack", "sustain", "time"),
        "extra" to listOf("split"),
        "out" to listOf("gain"),
    ),
    "Midside" to listOf(
        "levels" to listOf("width", "mid", "side"),
        "eq" to listOf("midtilt", "sidetilt", "mono"),
        "extra" to listOf("breathe"),
        "out" to listOf("gain"),
    ),
    // The model's own knobs go in a card of their own after the first; see [groupsFor].
    // The gain ends the tail card rather than taking a card of its own, and
    // the tail comes last so the mod card after it can share its line.
    "Reverb" to listOf(
        "tank" to listOf("model", "size", "damp", "tone", "predelay", "mix"),
        "extra" to listOf("freeze", "gate", "shimmer", "bits", "crush", "wobble"),
        "tail" to listOf("duck", "duckrel", "distance", "bloom", "gain"),
    ),
    // The impulse card comes first, drawn by the face; see [VenueImpulseCard].
    "Venue" to listOf(
        "tail" to listOf("predelay", "length", "decay", "stretch"),
        "tone" to listOf("lowcut", "highcut"),
        "out" to listOf("width", "mix", "gain"),
    ),
    "Deesser" to listOf(
        "band" to listOf("freq", "threshold", "range"),
        "how" to listOf("mode", "listen", "relative"),
        "out" to listOf("gain"),
    ),
)

/**
 * The declared groups, then a card for anything not in them.
 *
 * Names the engine doesn't have are dropped, so a renamed parameter leaves a
 * smaller card instead of a broken one.
 */
private fun groupsFor(type: String, info: List<ParamInfo>, model: String? = null): List<Pair<String, List<ParamInfo>>> {
    val byName = info.associateBy { it.name }
    val declared = PANEL_GROUPS[type]?.let { g ->
        // The reverb's model knobs, in a card named after the model.
        if (model != null) g.take(1) + (model to REVERB_MODEL_KNOBS[model].orEmpty()) + g.drop(1) else g
    } ?: return listOf("" to info)
    val out = ArrayList<Pair<String, List<ParamInfo>>>()
    val taken = HashSet<String>()
    for ((title, names) in declared) {
        val here = names.mapNotNull { byName[it] }
        if (here.isEmpty()) continue
        taken += here.map { it.name }
        out += title to here
    }
    val rest = info.filter { it.name !in taken }
    if (rest.isNotEmpty()) out += "more" to rest
    return out
}

/**
 * An effect window's cards, packed. The reverb has the most knobs of any
 * effect, so its window draws smaller dials and puts as many on a line as fit,
 * which keeps every model on one screen without scrolling.
 */
@Composable
private fun BusyCards(type: String, content: @Composable (perLine: Int) -> Unit) {
    if (type != "Reverb") {
        WindowCards(pack = true) { content(4) }
        return
    }
    // The theme's text sits in 24 sp lines, so a knob's 9 sp label and value
    // take 24 dp each. Lines just taller than the words save most of a knob's height.
    val tight = androidx.compose.material3.LocalTextStyle.current.merge(
        androidx.compose.ui.text.TextStyle(lineHeight = 1.3.em),
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalKnobDial provides BusyDial,
        LocalControlH provides BusyDial + 24.dp,
        LocalKnobGap provides 4.dp,
        androidx.compose.material3.LocalTextStyle provides tight,
    ) {
        WindowCards(pack = true) { content(8) }
    }
}

/** The dial in a busy effect window; see [BusyCards]. 48 dp knobs, wide enough for "predelay". */
private val BusyDial = 42.dp

/** The reverb's models, in the engine's order (Reverb.h, ModelKind). */
private val REVERB_MODELS = listOf(
    "room", "plate", "hall", "spring", "early", "space", "tube", "string", "drumhead", "thunder", "howl", "flutter",
)

/** Each reverb model's own knobs. The rest are on every model. */
private val REVERB_MODEL_KNOBS = mapOf(
    "spring" to listOf("drip", "knock"),
    "early" to listOf("rate"),
    "space" to listOf("width", "depth", "height", "position", "material"),
    "tube" to listOf("pitch", "follow", "open"),
    "string" to listOf("pitch", "follow", "stiff", "pick"),
    "drumhead" to listOf("tension", "snares"),
    "thunder" to listOf("metal"),
    "howl" to listOf("howl"),
    "flutter" to listOf("walls"),
)
private val REVERB_OWN_KNOBS = REVERB_MODEL_KNOBS.values.flatten().toSet()

/** The reverb's model, by name, from `model`'s normalised value. */
private fun reverbModel(v: Float): String =
    REVERB_MODELS[(v * (REVERB_MODELS.size - 1)).roundToInt().coerceIn(0, REVERB_MODELS.size - 1)]

/**
 * Whether the reverb shows a knob on this model: the shared ones, less size on
 * space, which is sized in metres, and the model's own. `follow` tunes to the
 * track's notes, so only a track's insert has it.
 */
private fun reverbShows(model: String, name: String, trackInsert: Boolean): Boolean = when {
    name == "follow" && !trackInsert -> false
    name == "size" -> model != "space"
    name in REVERB_OWN_KNOBS -> name in REVERB_MODEL_KNOBS[model].orEmpty()
    else -> true
}

/** The arp's pattern: sixteen compact step toggles in one row, the ones past `length` dimmed. */
@Composable
private fun ArpSteps(b: ParamBinding) {
    val length = b.infoOf("length")?.map(b.value("length"))?.toInt() ?: 16
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.slot_steps), color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        for (i in 1..16) {
            val name = "s%02d".format(i)
            val on = b.value(name) >= 0.5f
            val inRange = i <= length
            Box(
                Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (on && inRange) Acid.colors.green else if (on) Acid.colors.greenDim else Acid.colors.control)
                    .clickable { b.set(name, if (on) 0f else 1f) },
                contentAlignment = Alignment.Center,
            ) { Text("$i", color = if (!inRange) Acid.colors.textFaint else if (on) Color.White else Acid.colors.text, fontSize = 8.sp) }
        }
    }
}

/** The same sixteen steps as [ArpSteps], as two lines of eight for a card. */
@Composable
private fun ArpStepGrid(b: ParamBinding) {
    val length = b.infoOf("length")?.map(b.value("length"))?.toInt() ?: 16
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in row * 8 + 1..row * 8 + 8) {
                    val name = "s%02d".format(i)
                    val on = b.value(name) >= 0.5f
                    val inRange = i <= length
                    Box(
                        Modifier.width(28.dp).height(30.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (on && inRange) Acid.colors.green else if (on) Acid.colors.greenDim else Acid.colors.control)
                            .clickable { b.set(name, if (on) 0f else 1f) },
                        contentAlignment = Alignment.Center,
                    ) { Text("$i", color = if (!inRange) Acid.colors.textFaint else if (on) Color.White else Acid.colors.text, fontSize = 9.sp) }
                }
            }
        }
    }
}

/** The "extra something" controls, drawn in the accent colour so they stand out from the classic set. */
private val EXTRA = mapOf(
    "Delay" to setOf("duck", "wobble"),
    "Reverb" to setOf("freeze", "gate", "shimmer", "bits", "crush", "wobble"),
    "Chorus" to setOf("drift"),
    "Tremolo" to setOf("pan", "skew"),
    "Width" to setOf("below", "haas"),
    "Shifter" to setOf("spread", "feedback"),
    "Harmonizer" to setOf("scale", "key"),
    // Each key must appear only once here, or the later entry silently wins.
    "Amp" to setOf("size", "cone"), // the cabinet you can resize
    "Gate" to setOf("key", "duck"), // the detector's own filter, and how far down shut is
    "Eq" to setOf("tilt"),
    "Distortion" to setOf("mode", "bias"),
    "Compressor" to setOf("pump", "pumprate"),
    "Swell" to setOf("split"), // one band to three
    "Rotary" to setOf("tempo"), // the horn turning on the beat
    "Grain" to setOf("freeze", "feedback"),
    "Resonator" to setOf("metal"), // stiff strings, toward a bell
    "Smash" to setOf("up"), // the lift, which a compressor doesn't have
    "Acid" to setOf("pattern", "accent"),
    "Mouth" to setOf("move"),
    "Tape" to setOf("stop", "stoptime"),
    "Slicer" to setOf("seed", "pitch"),
    "Magneto" to setOf("dubs"),
    "Horn" to setOf("breath", "snap"),
    "Spectral" to setOf("freeze", "peaks"),
    "Formula" to setOf("rate", "smooth"),
    "Venue" to setOf("stretch"), // a bigger or smaller room of the same shape
    "Transient" to setOf("split"), // only the highs shaped
    "Midside" to setOf("breathe"), // the side opens in the gaps
    "Deesser" to setOf("relative"), // measured against the whole sound
    "Filter" to setOf("lforate", "lfodepth", "envdepth"),
    "Bitcrusher" to setOf("jitter", "tone"),
    "Phaser" to setOf("spread"),
    "Flanger" to setOf("negative", "spread"),
    "Scale" to setOf("mode", "snap"),
    "Chord" to setOf("mode", "voicing", "shape", "spread", "strum", "strumdir", "velspread", "play", "ring", "rhythm"),
    "Arp" to setOf("ratchet", "ratchetchance", "chance", "shift", "cycles", "humanise", "latch"),
)

// Mirrors engine/inputmod/Scales.h - same order.
val SCALE_NAMES = listOf(
    "Ionian (Major)", "Dorian", "Phrygian", "Lydian", "Mixolydian", "Aeolian (Minor)", "Locrian",
    "Harmonic Minor", "Melodic Minor",
    "Major Pentatonic", "Minor Pentatonic", "Major Blues", "Minor Blues", "Egyptian",
    "Hungarian Minor", "Byzantine", "Persian", "Hirajoshi", "In Sen", "Iwato", "Enigmatic", "Phrygian Dominant", "Neapolitan Minor", "Neapolitan Major",
    "Altered", "Lydian Dominant", "Lydian Augmented", "Locrian nat2", "Bebop Dominant", "Bebop Major",
    "Whole Tone", "Dim Whole-Half", "Dim Half-Whole",
)
val KEY_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val CHORD_NAMES = listOf(
    "maj", "min", "dim", "aug", "sus2", "sus4", "5", "6", "m6", "7", "maj7", "m7", "m7b5", "dim7", "mMaj7", "7sus4",
    "add9", "madd9", "9", "maj9", "m9", "11", "13", "oct", "5+oct",
)
private val ARP_MODES = listOf("up", "down", "up-down", "down-up", "up&down", "converge", "diverge", "random", "walk", "played", "chord", "pinky", "thumb")
private val ARP_RATES = listOf("1/1", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32")

/** A tempo-locked LFO's rates, shortest first, as dsp::Lfo::kBeats has them. */
internal val NOTE_RATES = listOf(
    "1/32", "1/16T", "1/16", "1/8T", "1/16.", "1/8", "1/4T", "1/8.",
    "1/4", "1/2T", "1/4.", "1/2", "1/2.", "1", "2", "4", "8",
)

private fun switchLabels(type: String, name: String, steps: Int): List<String>? = when {
    name == "mode" && type == "Scale" -> listOf("snap", "degree")
    name == "snap" -> listOf("nearest", "down", "up")
    name == "mode" && type == "Chord" -> listOf("fixed", "diatonic")
    name == "mode" && type == "Arp" -> ARP_MODES
    name == "key" -> KEY_NAMES
    name == "scale" -> SCALE_NAMES
    name == "type" && type == "Chord" -> CHORD_NAMES
    name == "voicing" -> listOf("triad", "7th", "9th")
    name == "inversion" -> listOf("root", "1st", "2nd", "3rd")
    name == "strumdir" -> listOf("up", "down")
    name == "play" && type == "Chord" -> listOf("chord", "strum keys", "split")
    name == "shape" && type == "Chord" -> listOf("close", "guitar")
    name == "memory" && type == "Chord" -> listOf("off") + (1..8).map { "$it" }
    name == "rhythm" && type == "Chord" -> listOf(
        "off", "4ths", "8ths", "down-up", "folk", "rock", "gallop", "reggae", "funk", "ballad", "bossa",
    )
    name == "keys" && type == "Chord" -> listOf("all", "white")
    name == "split" && type == "Chord" -> (24..96).map { noteName(it) }
    name == "octmode" -> listOf("up", "down", "alt")
    name == "velmode" -> listOf("played", "fixed", "accent", "ramp↑", "ramp↓")
    name == "sync" -> listOf("restart", "free")
    name == "rate" && type == "Arp" -> ARP_RATES
    name == "transpose" || name == "shift" -> (-12..12).map { if (it > 0) "+$it" else "$it" }
    name == "octave" && type == "Scale" -> (-2..2).map { if (it > 0) "+$it" else "$it" }
    name == "octaves" || name == "ratchet" -> listOf("1", "2", "3", "4")
    name == "cycles" -> (1..8).map { "$it" }
    name == "length" -> (1..16).map { "$it" }
    // Two-step switches with words of their own, before the plain off/on.
    name == "mode" && type == "Acid" -> listOf("LP", "BP")
    name == "stop" && type == "Tape" -> listOf("run", "stop")
    name == "snap" && type == "Horn" -> listOf("free", "semi")
    name == "freeze" && type == "Spectral" -> listOf("run", "hold")
    name == "mode" && type == "Deesser" -> listOf("split", "wide")
    steps == 2 -> listOf("off", "on")
    name == "mode" && type == "Delay" -> listOf("normal", "ping-pong", "multi-tap", "reverse")
    name == "taps" && type == "Delay" -> listOf("2", "3", "4")
    name == "time" && type == "Delay" -> listOf("1/32", "1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2", "1")
    name == "mode" && type == "Distortion" -> listOf("soft", "hard", "fold", "tube")
    name == "model" && type == "Reverb" -> REVERB_MODELS
    name == "material" && type == "Reverb" -> listOf("tile", "plaster", "wood", "curtains")
    name == "speed" && type == "Rotary" -> listOf("brake", "slow", "fast")
    name == "tempo" && type == "Rotary" -> listOf("free", "1/1", "1/2", "1/4", "1/8", "1/8T")
    name == "freeze" && type == "Grain" -> listOf("off", "on")
    name == "strings" && type == "Resonator" -> (4..16).map { "$it" }
    name == "pattern" && type == "Acid" -> listOf("track", "8ths", "16ths", "offbeat", "push", "busy", "halves", "synco", "rolling")
    name == "move" && type == "Mouth" -> listOf("lfo", "level", "key")
    name == "rate" && type == "Slicer" -> listOf("1/4", "1/8", "1/16", "1/32")
    name == "seed" && type == "Slicer" -> (1..16).map { "$it" }
    name == "mode" && type == "Magneto" -> listOf("SP", "LP2", "LP4", "HQ", "XLP")
    name == "dubs" && type == "Magneto" -> (1..4).map { "$it" }
    name == "kind" && type == "Horn" -> listOf("brass", "clarinet", "oboe", "flute")
    name == "octave" && type == "Horn" -> listOf("-2", "-1", "0", "+1", "+2")
    // Scale degrees, not semitones, since that's how the Harmonizer works.
    (name == "interval" || name == "interval2") && type == "Harmonizer" ->
        (-7..7).map { if (it > 0) "+$it" else "$it" }
    name == "shape" && type == "Tremolo" -> listOf("sine", "tri", "square")
    name == "voices" && type == "Chorus" -> listOf("2", "3", "4")
    name == "mode" && type == "Filter" -> listOf("LP", "BP", "HP")
    name == "stack" && type == "Amp" -> listOf("us", "uk", "modern")
    name == "stages" -> listOf("2", "4", "6", "8")
    name == "pumprate" -> listOf("1/16", "1/8", "1/4", "1/2")
    name == "lforate" || name == "rate" -> NOTE_RATES
    else -> null
}

/**
 * The face of a song-level slot (a send, a master or group insert, an input
 * effect), laid out like a track's effect window.
 *
 * Doesn't use [ParamBinding], which edits a track. These values belong to the
 * song and each change is a song gesture; the window passes in how to read
 * and write them.
 */
@Composable
internal fun SongSlotFace(
    type: String,
    info: List<ParamInfo>,
    value: (String) -> Float,
    start: () -> Unit,
    change: (String, Float) -> Unit,
    end: () -> Unit,
    /** A tap on a switch: one song edit, no gesture. */
    set: (String, Float) -> Unit,
    /** The song's tracks for the slot's mod card, or null for no card. */
    modTracks: List<String>? = null,
    /** A card before the knobs' cards, like Venue's impulse. */
    lead: (@Composable () -> Unit)? = null,
) {
    val control: @Composable (ParamInfo) -> Unit = { p ->
        val labels = switchLabels(type, p.name, p.steps)?.let { panelWords(it) }
        val accent = if (p.name in EXTRA[type].orEmpty()) Acid.colors.accent else Acid.colors.teal
        val shortLabel = panelWord(shortLabelOf(type, p.name))
        val v = value(p.name)
        when {
            p.curve == 2 && labels != null && labels.size <= 4 -> SwitchGrid(
                shortLabel, labels, (v * (labels.size - 1)).roundToInt().coerceIn(0, labels.size - 1),
            ) { i -> set(p.name, if (labels.size > 1) i.toFloat() / (labels.size - 1) else 0f) }
            // Named steps (note values, modes) turn as a knob and, held, open as a
            // list, so picking an exact one is a tap.
            p.curve == 2 && labels != null -> {
                val n = labels.size
                val idx = (v * (n - 1)).roundToInt().coerceIn(0, n - 1)
                fun at(i: Int) = if (n > 1) i.toFloat() / (n - 1) else 0f
                CountKnob(
                    shortLabel, idx, 0 until n, labels[idx], accent, choices = labels,
                    onStart = start, onEnd = end, pick = { i -> set(p.name, at(i)) },
                ) { i -> change(p.name, at(i)) }
            }
            else -> Knob(
                label = shortLabel, value = v, accent = accent, modifier = panelKnobWidth(),
                display = p.format(v),
                onStart = start, onChange = { nv -> change(p.name, nv) }, onEnd = end,
            )
        }
    }
    // A song slot never hears notes, so the reverb's `follow` isn't offered.
    val model = if (type == "Reverb") reverbModel(value("model")) else null
    val shown = if (model != null) info.filter { reverbShows(model, it.name, trackInsert = false) } else info
    BusyCards(type) { perLine ->
        lead?.invoke()
        for ((title, group) in groupsFor(type, shown, model)) {
            key(title) {
                Group(title, perLine = perLine, centred = true, background = Acid.colors.cardAlt) {
                    for (p in group) key(p.name) { control(p) }
                }
            }
        }
        if (modTracks != null) ModCard(type, modTracks, ModAccess(value, { start() }, change, end, set))
    }
}

/**
 * A slot's header line: its name, the unit in it, and whether it's on. In a
 * panel it sits over the face; in a window it's over the cards upright and
 * in the header turned.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SlotHeader(
    kind: SlotKind, track: Track, trackIndex: Int, slot: Int, types: List<String>, editor: SongEditor,
    fixedType: String?, wrap: Boolean, minimized: Boolean = false, onMinimize: () -> Unit = {},
    /** Drawn after the bypass, like the slot's patch picker when the panel has room for it on this line. */
    extra: (@Composable () -> Unit)? = null,
    /** False when the bypass is drawn on a line of its own; see [SlotRow]. */
    withBypass: Boolean = true,
) {
    val fx = kind.at(track, slot)
    var menu by remember { mutableStateOf(false) }
            if (fixedType == null) Text(stringResource(kind.title, slot + 1), color = Acid.colors.teal, fontSize = 10.sp)
            if (fixedType == null) TextButton(onClick = { menu = true }) {
                Text(stringResource(Res.string.slot_menu, if (fx.isEmpty) stringResource(Res.string.slot_none) else fx.type), color = Acid.colors.accent, fontSize = 12.sp)
            }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ScaledMenu(menuScroll) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.slot_none), fontSize = 12.sp) }, onClick = {
                        menu = false
                        editor.edit(trackIndex) { t -> kind.withType(t, slot, "") }
                    })
                    for (t in types) DropdownMenuItem(text = { Text(t, fontSize = 12.sp) }, onClick = {
                        menu = false
                        if (t != fx.type) editor.edit(trackIndex) { tr -> kind.withType(tr, slot, t) }
                    })
                }
            }
            if (!fx.isEmpty) {
                if (withBypass) SlotBypass(kind, fx, trackIndex, slot, editor)
                // Folding is only offered in a panel, where space is short. A window
                // exists to show the face, so it doesn't get the fold mark.
                extra?.invoke()
                if (!wrap) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onMinimize() },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.button(stringResource(if (minimized) Res.string.a11y_unfold_slot else Res.string.a11y_fold_slot)),
                    ) { Text(if (minimized) "\u25B4" else "\u25BE", color = Acid.colors.textMid, fontSize = 13.sp) }
                }
            }
        }

/** A slot's on/bypass button. One line at the size of either language's longer word. */
@Composable
private fun SlotBypass(kind: SlotKind, fx: UnitSlot, trackIndex: Int, slot: Int, editor: SongEditor) {
    val on = !fx.bypass
    TextButton(
        onClick = {
            val bypass = !fx.bypass
            // The document push mounts nothing new, so the flag also goes straight to the running effect.
            editor.edit(trackIndex) { t -> kind.withBypass(t, slot, bypass) }
            NativeEngine.setParam(trackIndex, kind.unit(slot), "bypass", if (bypass) 1f else 0f, record = true)
        },
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Acid.colors.green else Acid.colors.control),
    ) {
        Text(
            stringResource(if (on) Res.string.slot_on else Res.string.slot_bypass),
            color = if (on) Color.White else Acid.colors.textMid, fontSize = 10.sp, maxLines = 1, softWrap = false,
        )
    }
}

/**
 * A slot knob's label: shortened where the engine's name is too long, and
 * told apart where one name means different things (see PanelText.kt): a
 * musical key from the gate's detector filter, the amp's speaker edge.
 */
private fun shortLabelOf(type: String, name: String): String = SHORT_LABELS[name] ?: when {
    name == "key" && type.lowercase() in MUSICAL_KEYS -> "key~music"
    name == "key" && type.lowercase() == "gate" -> "key~detector"
    name == "edge" && type.lowercase() == "amp" -> "edge~cone"
    name == "ring" && type.lowercase() == "chord" -> "ring~strings"
    name == "split" && type.lowercase() == "chord" -> "split~keys"
    name == "mid" && type.lowercase() == "midside" -> "mid~centre"
    else -> name
}

/** The slots whose `key` is a musical key, not a filter's tracking. */
private val MUSICAL_KEYS = setOf("chord", "scale", "harmonizer")

/** Formulas to start a Formula effect from, all original. x is the track, 0 to 255. */
internal val FORMULA_FX_EXAMPLES = listOf(
    "x" to "eight bits",
    "x & (255 << (a >> 5))" to "fewer bits with a",
    "128 + ((x - 128) * (a + 16) >> 4)" to "wrap it with a",
    "128 + ((x - 128) * sin(t * (a + 1) >> 3) >> 7)" to "ring it with a",
    "t >> 6 & 1 ? x : 128" to "chop on the clock",
    "x ^ r >> (8 - (b >> 5))" to "grit with b",
    "(x & 240) | (t >> 2 & 15)" to "low bits from the clock",
    "x ^ t >> 4" to "scrambled in time",
)

/**
 * A Formula effect's formula: shown, with its error if it has one, and
 * edited in a dialog that only applies on OK, so a half-typed formula is
 * never compiled.
 */
@Composable
internal fun EffectFormula(text: String, error: String, onSet: (String) -> Unit) {
    val c = Acid.colors
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(min = 140.dp, max = 320.dp).padding(bottom = 6.dp)) {
        Text(
            text.ifEmpty { stringResource(Res.string.formula_none) },
            color = if (text.isEmpty()) c.textDim else c.textHi,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (error.isNotEmpty()) Text(error, color = c.red, fontSize = 10.sp, maxLines = 2)
        TextButton(onClick = { editing = true }) {
            Text(stringResource(Res.string.formula_edit), color = c.accent, fontSize = 12.sp)
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(text) }
        PlainDialog(
            title = stringResource(Res.string.formula_title),
            onDismiss = { editing = false },
            confirmLabel = stringResource(Res.string.ok),
            onConfirm = { editing = false; onSet(draft.trim()) },
            spacing = 8.dp,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text(stringResource(Res.string.formula_fx_expression)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.typing() then Modifier.fillMaxWidth(),
            )
            Text(stringResource(Res.string.formula_examples), color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            for ((example, what) in FORMULA_FX_EXAMPLES) {
                Row(
                    Modifier.fillMaxWidth().clickable { draft = example }.padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(example, color = c.textHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Text(panelWord(what), color = c.textDim, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

/** The chord modifier's memory slot notes, m1_1 to m8_6. */
private val CHORD_MEMORY_NOTE = Regex("m[1-8]_[1-6]")

/**
 * Learns a chord into the memory slot chosen (or slot 1): press learn, play
 * the chord, let go. While it listens the chord modifier is bypassed, so the
 * keys sound as played.
 */
@Composable
private fun LearnChord(b: ParamBinding, trackIndex: Int, unit: String, editor: SongEditor, slot: Int, kind: SlotKind) {
    var learning by remember { mutableStateOf(false) }
    var learned by remember { mutableStateOf<List<Int>?>(null) }
    ButtonSquare(panelWord("learn"), panelWord(if (learning) "play~now" else "learn"), learning) {
        if (learning) {
            learning = false
        } else {
            learned = null
            learning = true
        }
    }
    androidx.compose.runtime.DisposableEffect(learning) {
        if (learning) {
            NativeEngine.setParam(trackIndex, unit, "bypass", 1f, record = false)
            val held = HashSet<Int>()
            val seen = HashSet<Int>()
            NativeEngine.noteWatch = { rack, note, on ->
                // The screen's keys and a MIDI port's arrive one at a time in
                // practice, so no lock: a shape is a few notes, let go together.
                if (rack == trackIndex) run {
                    if (on) { held += note; seen += note } else held -= note
                    if (held.isEmpty() && seen.isNotEmpty()) {
                        learned = seen.sorted().let { s -> s.map { it - s.first() } }.distinct().take(6)
                        learning = false
                    }
                }
            }
        }
        onDispose {
            if (learning || NativeEngine.noteWatch != null) {
                NativeEngine.noteWatch = null
                val bypassed = kind.at(editor.song.tracks[trackIndex], slot).bypass
                NativeEngine.setParam(trackIndex, unit, "bypass", if (bypassed) 1f else 0f, record = false)
            }
        }
    }
    // Written on the UI thread once the keys are up: the slot's notes, and the slot chosen.
    androidx.compose.runtime.LaunchedEffect(learned) {
        val shape = learned ?: return@LaunchedEffect
        val chosen = (b.value("memory") * 8f).roundToInt().coerceIn(0, 8).let { if (it == 0) 1 else it }
        for (k in 1..6) {
            val iv = shape.getOrNull(k - 1) ?: -1
            b.set("m${chosen}_$k", (iv + 1) / 37f)
        }
        b.set("memory", chosen / 8f)
        learned = null
    }
}

