package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.EFFECT_SLOTS
import com.rm.acidulous.model.SIDECHAIN_PARAM
import com.rm.acidulous.model.SIDECHAIN_STEPS
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.PatchStore
import com.rm.acidulous.model.withEffectPatch
import com.rm.acidulous.model.withModifierParam
import com.rm.acidulous.model.withModifierBypass
import com.rm.acidulous.model.withModifier
import com.rm.acidulous.model.modifierUnit
import com.rm.acidulous.model.UnitSlot
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
 * A track's two slots of one kind: pick a unit, bypass it, turn its knobs.
 * Knobs go through the same [ParamBinding] as a machine's, addressed to the
 * slot's unit name, so they record, automate and undo the same way.
 */
@Composable
fun SlotsPanel(kind: SlotKind, track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) {
    val types = remember(kind) { kind.types() }
    Column(modifier.background(Acid.colors.panel).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (slot in 0 until kind.slots) SlotRow(kind, track, trackIndex, slot, types, editor)
    }
}

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
    // On a square phone a unit with too much for one page gets split into
    // pages, see [PAGES].
    val pageTitles = PAGES[fixedType ?: kind.at(track, slot).type]?.let { pageNames(it) }
    if (pageTitles != null && compactWindow()) {
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
 * square phone. The arp's steps go with its pattern.
 *
 * Three pages of two cards, since a square phone's window holds two cards
 * and a strip. Paired by topic: timing and feel, which notes, and chance
 * and release.
 */
private val PAGES: Map<String, List<Set<String>>> = mapOf(
    "Arp" to listOf(setOf("time", "feel"), setOf("pattern"), setOf("chance", "run")),
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
) {
    val fx = kind.at(track, slot)
    // The bypass as the window found it. Bypass isn't a parameter, so it's not
    // in the binding's baseline and has to be remembered here.
    val openedBypass = remember(kind, slot, trackIndex) { fx.bypass }
    // Per slot and kept across rotation, like a machine panel's. Two effects
    // and a modifier can fill a phone, so a slot you aren't editing can be
    // folded down to its one line.
    var minimized by rememberSaveable(kind.label, slot) { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Acid.colors.card).padding(4.dp)) {
        if (!(wrap && LocalDialogHeaderRow.current)) Row(
            if (wrap) Modifier.fillMaxWidth() else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            // Centred in a window. Packed left in a panel so it lines up with the
            // slots above and below.
            horizontalArrangement = if (wrap) {
                Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.spacedBy(4.dp)
            },
        ) {
            SlotHeader(kind, track, trackIndex, slot, types, editor, fixedType, wrap, minimized) { minimized = !minimized }
        }
        if (!fx.isEmpty && !minimized) {
            SlotFace(kind, fx.type, trackIndex, slot, editor, wrap, page) { b ->
                onRevert?.invoke {
                    b.resetAll()
                    if (fx.bypass != openedBypass) {
                        editor.edit(trackIndex) { t -> kind.withBypass(t, slot, openedBypass) }
                        NativeEngine.setParam(trackIndex, kind.unit(slot), "bypass", if (openedBypass) 1f else 0f, record = true)
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
    /** Called with the binding once it exists, so a window can undo everything. */
    onBinding: ((ParamBinding) -> Unit)? = null,
) {
    val info = remember(kind, type) { kind.paramInfo(type) }
    val unit = kind.unit(slot)
    val b = rememberParamBinding(trackIndex, type, info, editor, unit) { t, n, v -> kind.withParam(t, slot, n, v) }
    androidx.compose.runtime.SideEffect { onBinding?.invoke(b) }
    // The panel follows the engine, which already holds what the document
    // pushed, so there's nothing to seed here.
    Column {
        // The same patch picker a machine panel has, over the same store, with
        // a key that can't collide with a machine's.
        val key = kind.patchKey?.invoke(type)
        val load = kind.loadPatch
        if (key != null && load != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PatchPicker(
                    title = type,
                    patchNames = { PatchStore.list(key) },
                    onSave = { name -> PatchStore.save(Patch(key, name, kind.at(editor.song.tracks[trackIndex], slot).params)) },
                    onLoad = { name ->
                        PatchStore.load(key, name)?.let { patch ->
                            editor.edit(trackIndex) { t -> load(t, slot, patch.params) }
                            b.applyAll(patch.params)
                        }
                    },
                    factoryPatches = { PatchStore.factory(key) },
                    userNames = { PatchStore.userList(key) },
                    onDelete = { name -> PatchStore.delete(key, name) },
                )
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
        val shown = info.filterNot {
            type == "Arp" && it.name.length == 3 && it.name[0] == 's' && it.name[1].isDigit()
        }
        if (wrap) {
            // A card per group, stacked down the window, each wrapping its own
            // controls. `LocalPanelStacked` tells `Group` to wrap.
            val wide = LocalDialogWide.current
            WindowCards {
                val pages = PAGES[type]
                for ((title, group) in groupsFor(type, shown)) {
                    if (page >= 0 && pages != null && title !in pages[page]) continue
                    Group(title, perLine = 4, centred = true, background = Acid.colors.cardAlt) {
                        for (p in group) control(p)
                    }
                }
                // Turned, the steps are a card like the others, two lines of eight,
                // since there's no height left for a strip under them.
                if (type == "Arp" && wide) {
                    Group("steps", background = Acid.colors.cardAlt) { ArpStepGrid(b) }
                }
            }
            if (type == "Arp" && !wide && (page < 0 || "pattern" in PAGES[type].orEmpty().getOrNull(page).orEmpty())) ArpSteps(b)
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) { for (p in shown) control(p) }
            if (type == "Arp") ArpSteps(b)
        }
    }
}

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
        "voicing" to listOf("voicing", "inversion", "spread", "bass"),
        // How it's played, not which notes.
        "strum" to listOf("strum", "strumdir", "velspread"),
    ),
    "Scale" to listOf(
        "scale" to listOf("mode", "key", "scale"),
        "how" to listOf("snap", "octave", "transpose"),
    ),
)

/**
 * The declared groups, then a card for anything not in them.
 *
 * Names the engine doesn't have are dropped, so a renamed parameter leaves a
 * smaller card instead of a broken one.
 */
private fun groupsFor(type: String, info: List<ParamInfo>): List<Pair<String, List<ParamInfo>>> {
    val byName = info.associateBy { it.name }
    val declared = PANEL_GROUPS[type] ?: return listOf("" to info)
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
    "Filter" to setOf("lforate", "lfodepth", "envdepth"),
    "Bitcrusher" to setOf("jitter", "tone"),
    "Phaser" to setOf("spread"),
    "Flanger" to setOf("negative", "spread"),
    "Scale" to setOf("mode", "snap"),
    "Chord" to setOf("mode", "voicing", "spread", "strum", "strumdir", "velspread"),
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
    name == "octmode" -> listOf("up", "down", "alt")
    name == "velmode" -> listOf("played", "fixed", "accent", "ramp↑", "ramp↓")
    name == "sync" -> listOf("restart", "free")
    name == "rate" && type == "Arp" -> ARP_RATES
    name == "transpose" || name == "shift" -> (-12..12).map { if (it > 0) "+$it" else "$it" }
    name == "octave" && type == "Scale" -> (-2..2).map { if (it > 0) "+$it" else "$it" }
    name == "octaves" || name == "ratchet" -> listOf("1", "2", "3", "4")
    name == "cycles" -> (1..8).map { "$it" }
    name == "length" -> (1..16).map { "$it" }
    steps == 2 -> listOf("off", "on")
    name == "time" && type == "Delay" -> listOf("1/32", "1/16", "1/8", "1/8.", "1/4", "1/4.", "1/2", "1")
    name == "mode" && type == "Distortion" -> listOf("soft", "hard", "fold", "tube")
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
) {
    val control: @Composable (ParamInfo) -> Unit = { p ->
        val labels = switchLabels(type, p.name, p.steps)
        val accent = if (p.name in EXTRA[type].orEmpty()) Acid.colors.accent else Acid.colors.teal
        val shortLabel = shortLabelOf(type, p.name)
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
    WindowCards {
        for ((title, group) in groupsFor(type, info)) {
            Group(title, perLine = 4, centred = true, background = Acid.colors.cardAlt) {
                for (p in group) control(p)
            }
        }
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
                val on = !fx.bypass
                TextButton(
                    onClick = {
                        val bypass = !fx.bypass
                        // The document push mounts nothing new, so the flag also goes straight to the running effect.
                        editor.edit(trackIndex) { t -> kind.withBypass(t, slot, bypass) }
                        NativeEngine.setParam(trackIndex, kind.unit(slot), "bypass", if (bypass) 1f else 0f, record = true)
                    },
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Acid.colors.green else Acid.colors.control),
                ) { Text(stringResource(if (on) Res.string.slot_on else Res.string.slot_bypass), color = if (on) Color.White else Acid.colors.textMid, fontSize = 10.sp) }
                // Folding is only offered in a panel, where space is short. A window
                // exists to show the face, so it doesn't get the fold mark.
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

/**
 * A slot knob's label: shortened where the engine's name is too long, and
 * told apart where one name means different things (see PanelText.kt): a
 * musical key from the gate's detector filter, the amp's speaker edge.
 */
private fun shortLabelOf(type: String, name: String): String = SHORT_LABELS[name] ?: when {
    name == "key" && type.lowercase() in MUSICAL_KEYS -> "key~music"
    name == "key" && type.lowercase() == "gate" -> "key~detector"
    name == "edge" && type.lowercase() == "amp" -> "edge~cone"
    else -> name
}

/** The slots whose `key` is a musical key, not a filter's tracking. */
private val MUSICAL_KEYS = setOf("chord", "scale", "harmonizer")
