package com.rm.acidulous.ui

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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.res.stringResource
import com.rm.acidulous.R
import androidx.annotation.StringRes

/** What a slot panel edits: the track's insert effects or its modifiers. */
enum class SlotKind(
    /** A key, for what the panel remembers about each slot; [title] is what it is called. */
    val label: String, @StringRes val title: Int, val slots: Int,
    val types: () -> List<String>, val paramInfo: (String) -> List<com.rm.acidulous.engine.ParamInfo>,
    val unit: (Int) -> String, val at: (Track, Int) -> UnitSlot,
    val withType: (Track, Int, String) -> Track, val withParam: (Track, Int, String, Float) -> Track, val withBypass: (Track, Int, Boolean) -> Track,
    /**
     * How a unit of this kind is keyed in the patch store, or null when it
     * has no presets. Modifiers would take them for almost nothing - an arp
     * pattern is exactly the sort of thing to keep - but that is a different
     * milestone, and this is the seam it will use.
     */
    val patchKey: ((String) -> String)? = null,
    val loadPatch: ((Track, Int, Map<String, Float>) -> Track)? = null,
) {
    Effects("FX", R.string.slot_fx, EFFECT_SLOTS, { NativeEngine.effectTypes }, { NativeEngine.effectParamInfo(it) }, ::effectUnit, { t, s -> t.effectAt(s) },
        { t, s, ty -> t.withEffect(s, ty) }, { t, s, n, v -> t.withEffectParam(s, n, v) }, { t, s, b -> t.withEffectBypass(s, b) },
        patchKey = PatchStore::effectKey, loadPatch = { t, s, p -> t.withEffectPatch(s, p) }),
    Modifiers("MOD", R.string.slot_mod, MODIFIER_SLOTS, { NativeEngine.inputModTypes }, { NativeEngine.inputModParamInfo(it) }, ::modifierUnit, { t, s -> t.modifierAt(s) },
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
 * One slot on its own, for the chip that opens it. The row is the same one
 * the pane used to show two of - choose the type, switch it on, and its
 * parameters underneath - so the pane can retire without taking anything
 * with it.
 */
@Composable
fun SlotDialog(
    kind: SlotKind,
    track: Track,
    trackIndex: Int,
    slot: Int,
    editor: SongEditor,
    /** When the slot's type is decided by the control that opened it, there
     *  is nothing to pick and the dropdown only invites a mistake. */
    fixedType: String? = null,
    onDismiss: () -> Unit,
) {
    val types = remember(kind) { kind.types() }
    // **Cancel and OK, like every other window, and Cancel means it.**
    //
    // This said Done because the controls in it write live - a knob is heard
    // as it turns, which is the only way to voice anything by ear - so there
    // was nothing held back for an OK to apply and nothing for a Cancel to
    // throw away. That is a true label and an inconsistent one: two windows
    // opened from the same row of chips disagreed about how to leave them.
    //
    // So Cancel is given something to do rather than the label being changed
    // to suit. The panel already knows every control's value as the window
    // opened - it is what a long press puts one knob back to - so Cancel puts
    // all of them back, in one undo step, and the bypass with them.
    val revert = remember { mutableStateOf<(() -> Unit)?>(null) }
    PlainDialog(
        title = fixedType?.lowercase() ?: stringResource(kind.title, slot + 1),
        onDismiss = { revert.value?.invoke(); onDismiss() },
        dismissLabel = stringResource(R.string.cancel),
        confirmLabel = stringResource(R.string.ok),
        onConfirm = onDismiss,
        // The default, which is 560, rather than the 420 this used to ask for.
        // That number was chosen when every control sat in one scrolling row
        // and the body only ever needed the height of a single knob; wrapped
        // into rows the arp's seventeen need nearer all of it, and the shell
        // caps the card to the window anyway, so asking for more cannot push
        // the Done button off a turned phone.
        // Turned, the unit's own row - what it is, and whether it is on -
        // goes up into the header beside the title (Dan, 2026-09-23), and the
        // cards get the height it took.
        wideHeader = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SlotHeader(kind, track, trackIndex, slot, types, editor, fixedType, wrap = true)
            }
        },
    ) {
        SlotRow(kind, track, trackIndex, slot, types, editor, fixedType, wrap = true, onRevert = { revert.value = it })
    }
}

@Composable
fun EffectsPanel(track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) =
    SlotsPanel(SlotKind.Effects, track, trackIndex, editor, modifier)

@Composable
private fun SlotRow(
    kind: SlotKind, track: Track, trackIndex: Int, slot: Int, types: List<String>, editor: SongEditor,
    fixedType: String? = null,
    /**
     * A window's shape rather than a panel's: the controls wrap into as many
     * rows as they need instead of running off the side.
     *
     * The one scrolling row is the machine-panel house style and it is right
     * there - a panel shares its height with the piano roll, so width is the
     * only axis it can have. A dialog is the opposite: it has 420 dp of height
     * to itself and the width of the screen, and an arp with thirteen controls
     * in one row made you scroll sideways to find out what it was doing while
     * two thirds of the window sat empty.
     */
    wrap: Boolean = false,
    /** Hands the window a way to put everything back; see [SlotDialog]. */
    onRevert: ((() -> Unit) -> Unit)? = null,
) {
    val fx = kind.at(track, slot)
    // The slot as the window found it. Bypass is not a parameter, so it is not
    // in the binding's baseline and has to be remembered here.
    val openedBypass = remember(kind, slot, trackIndex) { fx.bypass }
    // Per slot, and kept across a rotation, the same as a machine panel's.
    // Two effects and an modifier can fill a phone between them, and most of
    // the time what you want from a slot you are not editing is the one line
    // that says what it is and whether it is on.
    var minimized by rememberSaveable(kind.label, slot) { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Acid.colors.card).padding(4.dp)) {
        if (!(wrap && LocalDialogHeaderRow.current)) Row(
            if (wrap) Modifier.fillMaxWidth() else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            // Centred in a window, packed left in a panel: a panel's row is a
            // line in a column of slots and has to line up with the ones above
            // and below it; a window's is the only thing on its line.
            horizontalArrangement = if (wrap) {
                Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.spacedBy(4.dp)
            },
        ) {
            SlotHeader(kind, track, trackIndex, slot, types, editor, fixedType, wrap, minimized) { minimized = !minimized }
        }
        if (!fx.isEmpty && !minimized) {
            SlotFace(kind, fx.type, trackIndex, slot, editor, wrap) { b ->
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
    /** Called with the binding once it exists, so a window can undo it wholesale. */
    onBinding: ((ParamBinding) -> Unit)? = null,
) {
    val info = remember(kind, type) { kind.paramInfo(type) }
    val unit = kind.unit(slot)
    val b = rememberParamBinding(trackIndex, type, info, editor, unit) { t, n, v -> kind.withParam(t, slot, n, v) }
    androidx.compose.runtime.SideEffect { onBinding?.invoke(b) }
    // The panel follows the engine; on first show the engine holds whatever the
    // document pushed, so nothing to seed here.
    Column {
        // The same three words a machine panel has, over the same store. An
        // effect preset needed no mechanism of its own, only somewhere to put
        // the picker and a key that cannot collide with a machine's.
        val key = kind.patchKey?.invoke(type)
        val load = kind.loadPatch
        if (key != null && load != null) {
            val context = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                PatchPicker(
                    title = type,
                    patchNames = { PatchStore.list(context, key) },
                    onSave = { name -> PatchStore.save(context, Patch(key, name, kind.at(editor.song.tracks[trackIndex], slot).params)) },
                    onLoad = { name ->
                        PatchStore.load(context, key, name)?.let { patch ->
                            editor.edit(trackIndex) { t -> load(t, slot, patch.params) }
                            b.applyAll(patch.params)
                        }
                    },
                    factoryPatches = { PatchStore.factory(key) },
                    userNames = { PatchStore.userList(context, key) },
                    onDelete = { name -> PatchStore.delete(context, key, name) },
                )
            }
        }
        val control: @Composable (ParamInfo) -> Unit = { p ->
            run {
                // A sidechain names a track, so its steps are the song's own
                // track names rather than numbers nobody can match to a row.
                val labels = if (p.name == SIDECHAIN_PARAM) {
                    listOf(stringResource(R.string.slot_sidechain_own)) + (0 until SIDECHAIN_STEPS - 1).map { i ->
                        editor.song.tracks.getOrNull(i)?.name ?: stringResource(R.string.slot_sidechain_empty, i + 1)
                    }
                } else {
                    switchLabels(type, p.name, p.steps)
                }
                val accent = if (p.name in EXTRA[type].orEmpty()) Acid.colors.accent else Acid.colors.teal
                // A knob is 58 dp wide and some names are not. Shortened here
                // rather than in the engine, because the engine's name is what
                // a patch file, a lane and a mapping all address.
                val shortLabel = SHORT_LABELS[p.name] ?: p.name
                when {
                    // a few choices: buttons; many (note values): a stepped knob that names its step
                    p.curve == 2 && labels != null && labels.size <= 4 -> PanelSwitch(b, p.name, labels, label = shortLabel)
                    p.curve == 2 && labels != null -> Knob(
                        label = panelWord(shortLabel), value = b.value(p.name), accent = accent,
                        // The step's *position* in the range, not its value.
                        //
                        // `p.map` gives the parameter in its own units, and
                        // using that as a list index only works for a range
                        // that starts at zero. The Harmonizer's `interval`
                        // runs -7..7, so a default of +2 read `labels[2]` and
                        // the knob said "-5". The normalised value is already
                        // the position, which is what a list wants.
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
        // The arp's sixteen step toggles are a row of their own below, so they
        // are not among the controls a card lays out.
        val shown = info.filterNot {
            type == "Arp" && it.name.length == 3 && it.name[0] == 's' && it.name[1].isDigit()
        }
        if (wrap) {
            // A card per group, stacked down the window, each wrapping its own
            // controls. `LocalPanelStacked` is what tells `Group` to wrap
            // rather than to lay one row and let it run off the side.
            val wide = LocalDialogWide.current
            WindowCards {
                for ((title, group) in groupsFor(type, shown)) {
                    Group(title, perLine = 4, centred = true, background = Acid.colors.cardAlt) {
                        for (p in group) control(p)
                    }
                }
                // Turned, the steps are a card among the others, two lines of
                // eight, rather than a strip under them the window has no
                // height left for.
                if (type == "Arp" && wide) {
                    Group("steps", background = Acid.colors.cardAlt) { ArpStepGrid(b) }
                }
            }
            if (type == "Arp" && !wide) ArpSteps(b)
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

/**
 * What belongs with what, for the windows that get a card per group.
 *
 * A wrapped row of every control in the order the engine happens to declare
 * them is not a layout - it is a list that has run out of width, and it reads
 * as one: Dan, on the first version of this, *"knobs and buttons following no
 * apparent layout"*. These say which controls are about the same thing, so the
 * window can put a titled card round each and you can find `gate` by knowing
 * it is about time rather than by reading every label.
 *
 * Anything the engine declares that is not named here still appears, in a
 * trailing card - so adding a parameter to a modifier shows it rather than
 * hiding it, and this table is a layout rather than a filter.
 */
/** Names too long for a knob's width, said shorter. */
private val SHORT_LABELS = mapOf(
    "ratchetchance" to "rchance",
    "velspread" to "vspread",
    "strumdir" to "dir",
    "octmode" to "octmod",
    "humanise" to "human",
    "inversion" to "invert",
)

private val PANEL_GROUPS: Map<String, List<Pair<String, List<String>>>> = mapOf(
    "Arp" to listOf(
        // When a note happens, and for how long.
        "time" to listOf("rate", "gate", "swing"),
        // Which note, out of what you are holding.
        "pattern" to listOf("mode", "octaves", "octmode", "length"),
        // How hard, and how human.
        "feel" to listOf("velmode", "accent", "humanise"),
        // What it does twice, and what it does sometimes.
        "chance" to listOf("ratchet", "ratchetchance", "chance"),
        // How it sits against the song, and what happens when you let go.
        "run" to listOf("sync", "shift", "cycles", "latch"),
    ),
    "Chord" to listOf(
        // Which chord.
        "chord" to listOf("mode", "type", "key", "scale"),
        // How it is stacked.
        "voicing" to listOf("voicing", "inversion", "spread", "bass"),
        // How it is played, rather than which notes it is.
        "strum" to listOf("strum", "strumdir", "velspread"),
    ),
    "Scale" to listOf(
        "scale" to listOf("mode", "key", "scale"),
        "how" to listOf("snap", "octave"),
    ),
)

/**
 * The declared groups, then a card for whatever was not spoken for.
 *
 * Names the engine does not have are dropped rather than drawn empty, so a
 * parameter that is renamed leaves a smaller card instead of a broken one.
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
        Text(stringResource(R.string.slot_steps), color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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
    // **This key used to be written twice.** The later entry won, so of the
    // reverb's six extras only `freeze` and `gate` were drawn in the accent
    // colour and the four M53 added were not - which is the one thing that
    // colour exists to prevent. The duplicate is gone and the six are here.
    "Amp" to setOf("size", "cone"), // the cabinet you can resize
    "Gate" to setOf("key", "duck"), // the detector's own filter, and how far down shut is
    "Eq" to setOf("tilt"),
    "Distortion" to setOf("mode", "bias"),
    "Compressor" to setOf("pump", "pumprate"),
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

private val NOTE_RATES = listOf("1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8")

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
    // Scale degrees, not semitones - the whole point of the Harmonizer, so the
    // knob should not read as a number of frets.
    (name == "interval" || name == "interval2") && type == "Harmonizer" ->
        (-7..7).map { if (it > 0) "+$it" else "$it" }
    name == "shape" && type == "Tremolo" -> listOf("sine", "tri", "square")
    name == "voices" && type == "Chorus" -> listOf("2", "3", "4")
    name == "mode" && type == "Filter" -> listOf("LP", "BP", "HP")
    name == "stack" && type == "Amp" -> listOf("us", "uk", "modern")
    name == "stages" -> listOf("2", "4", "6", "8")
    name == "pumprate" -> NOTE_RATES.take(4)
    name == "lforate" || name == "rate" -> NOTE_RATES
    else -> null
}

/**
 * A song-level slot's face - a send, a master or group insert, an input
 * effect - laid out exactly as a track's effect window lays out its own:
 * the same cards, the same switches, the same short labels.
 *
 * Not through [ParamBinding], which edits a track. These slots belong to the
 * song, so the values are the document's and each change is a song gesture;
 * the window hands in how to read and write them.
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
        val shortLabel = SHORT_LABELS[p.name] ?: p.name
        val v = value(p.name)
        when {
            p.curve == 2 && labels != null && labels.size <= 4 -> SwitchGrid(
                shortLabel, labels, (v * (labels.size - 1)).roundToInt().coerceIn(0, labels.size - 1),
            ) { i -> set(p.name, if (labels.size > 1) i.toFloat() / (labels.size - 1) else 0f) }
            // Named steps - note values, modes - turn as a knob and, held,
            // open as a list, so an exact one is a tap rather than a hunt.
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
 * A slot's own line: its name, the unit in it, and whether it is on - in a
 * panel, over its face; in a window, over its cards upright and in the
 * header turned.
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
                Text(stringResource(R.string.slot_menu, if (fx.isEmpty) stringResource(R.string.slot_none) else fx.type), color = Acid.colors.accent, fontSize = 12.sp)
            }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ScaledMenu(menuScroll) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.slot_none), fontSize = 12.sp) }, onClick = {
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
                        // The document push mounts nothing new; the flag goes straight to the running effect too.
                        editor.edit(trackIndex) { t -> kind.withBypass(t, slot, bypass) }
                        NativeEngine.setParam(trackIndex, kind.unit(slot), "bypass", if (bypass) 1f else 0f, record = true)
                    },
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Acid.colors.green else Acid.colors.control),
                ) { Text(stringResource(if (on) R.string.slot_on else R.string.slot_bypass), color = if (on) Color.White else Acid.colors.textMid, fontSize = 10.sp) }
                // Folding the face away is a *panel* control: two effects and a
                // modifier can fill a phone, so a slot you are not editing is
                // worth reducing to the line that says what it is. A window is
                // the opposite - it exists to show the face - so the mark is
                // not offered there, and the row stops carrying a wide gap to
                // hold a control that would only make the window pointless.
                if (!wrap) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onMinimize() },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.button(stringResource(if (minimized) R.string.a11y_unfold_slot else R.string.a11y_fold_slot)),
                    ) { Text(if (minimized) "\u25B4" else "\u25BE", color = Acid.colors.textMid, fontSize = 13.sp) }
                }
            }
        }
