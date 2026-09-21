package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalContext
import com.rm.acidulous.model.EFFECT_SLOTS
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.PatchStore
import com.rm.acidulous.model.withEffectPatch
import com.rm.acidulous.model.withEventorParam
import com.rm.acidulous.model.withEventorBypass
import com.rm.acidulous.model.withEventor
import com.rm.acidulous.model.eventorUnit
import com.rm.acidulous.model.UnitSlot
import com.rm.acidulous.model.EVENTOR_SLOTS
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.effectUnit
import com.rm.acidulous.model.withEffect
import com.rm.acidulous.model.withEffectBypass
import com.rm.acidulous.model.withEffectParam
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.roundToInt

/** What a slot panel edits: the track's insert effects or its eventors. */
enum class SlotKind(
    val label: String, val slots: Int,
    val types: () -> List<String>, val paramInfo: (String) -> List<com.rm.acidulous.engine.ParamInfo>,
    val unit: (Int) -> String, val at: (Track, Int) -> UnitSlot,
    val withType: (Track, Int, String) -> Track, val withParam: (Track, Int, String, Float) -> Track, val withBypass: (Track, Int, Boolean) -> Track,
    /**
     * How a unit of this kind is keyed in the patch store, or null when it
     * has no presets. Eventors would take them for almost nothing - an arp
     * pattern is exactly the sort of thing to keep - but that is a different
     * milestone, and this is the seam it will use.
     */
    val patchKey: ((String) -> String)? = null,
    val loadPatch: ((Track, Int, Map<String, Float>) -> Track)? = null,
) {
    Effects("FX", EFFECT_SLOTS, { NativeEngine.effectTypes }, { NativeEngine.effectParamInfo(it) }, ::effectUnit, { t, s -> t.effectAt(s) },
        { t, s, ty -> t.withEffect(s, ty) }, { t, s, n, v -> t.withEffectParam(s, n, v) }, { t, s, b -> t.withEffectBypass(s, b) },
        patchKey = PatchStore::effectKey, loadPatch = { t, s, p -> t.withEffectPatch(s, p) }),
    Eventors("EV", EVENTOR_SLOTS, { NativeEngine.eventorTypes }, { NativeEngine.eventorParamInfo(it) }, ::eventorUnit, { t, s -> t.eventorAt(s) },
        { t, s, ty -> t.withEventor(s, ty) }, { t, s, n, v -> t.withEventorParam(s, n, v) }, { t, s, b -> t.withEventorBypass(s, b) }),
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
    PlainDialog(
        title = fixedType?.lowercase() ?: "${kind.label}${slot + 1}",
        onDismiss = onDismiss,
        dismissLabel = "Done",
        maxBodyHeight = 420.dp,
    ) {
        SlotRow(kind, track, trackIndex, slot, types, editor, fixedType)
    }
}

@Composable
fun EffectsPanel(track: Track, trackIndex: Int, editor: SongEditor, modifier: Modifier = Modifier) =
    SlotsPanel(SlotKind.Effects, track, trackIndex, editor, modifier)

@Composable
private fun SlotRow(
    kind: SlotKind, track: Track, trackIndex: Int, slot: Int, types: List<String>, editor: SongEditor,
    fixedType: String? = null,
) {
    val fx = kind.at(track, slot)
    var menu by remember { mutableStateOf(false) }
    // Per slot, and kept across a rotation, the same as a machine panel's.
    // Two effects and an eventor can fill a phone between them, and most of
    // the time what you want from a slot you are not editing is the one line
    // that says what it is and whether it is on.
    var minimized by rememberSaveable(kind.label, slot) { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Acid.colors.card).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (fixedType == null) Text("${kind.label}${slot + 1}", color = Acid.colors.teal, fontSize = 10.sp)
            if (fixedType == null) TextButton(onClick = { menu = true }) {
                Text(if (fx.isEmpty) "none ▾" else "${fx.type} ▾", color = Acid.colors.accent, fontSize = 12.sp)
            }
            val menuScroll = rememberScrollState()
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ScaledMenu(menuScroll) {
                    DropdownMenuItem(text = { Text("none", fontSize = 12.sp) }, onClick = {
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
                ) { Text(if (on) "on" else "bypass", color = if (on) Color.White else Acid.colors.textMid, fontSize = 10.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { minimized = !minimized },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(if (minimized) "\u25B4" else "\u25BE", color = Acid.colors.textMid, fontSize = 13.sp) }
            }
        }
        if (!fx.isEmpty && !minimized) SlotFace(kind, fx.type, trackIndex, slot, editor)
    }
}

@Composable
private fun SlotFace(kind: SlotKind, type: String, trackIndex: Int, slot: Int, editor: SongEditor) {
    val info = remember(kind, type) { kind.paramInfo(type) }
    val unit = kind.unit(slot)
    val b = rememberParamBinding(trackIndex, type, info, editor, unit) { t, n, v -> kind.withParam(t, slot, n, v) }
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
        Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            for (p in info) {
                if (type == "Arp" && p.name.length == 3 && p.name[0] == 's' && p.name[1].isDigit()) continue // the step row below
                val labels = switchLabels(type, p.name, p.steps)
                val accent = if (p.name in EXTRA[type].orEmpty()) Acid.colors.accent else Acid.colors.teal
                when {
                    // a few choices: buttons; many (note values): a stepped knob that names its step
                    p.curve == 2 && labels != null && labels.size <= 4 -> PanelSwitch(b, p.name, labels)
                    p.curve == 2 && labels != null -> Knob(
                        label = p.name, value = b.value(p.name), accent = accent,
                        // The step's *position* in the range, not its value.
                        //
                        // `p.map` gives the parameter in its own units, and
                        // using that as a list index only works for a range
                        // that starts at zero. The Harmonizer's `interval`
                        // runs -7..7, so a default of +2 read `labels[2]` and
                        // the knob said "-5". The normalised value is already
                        // the position, which is what a list wants.
                        display = labels[(b.value(p.name) * (labels.size - 1))
                            .roundToInt().coerceIn(0, labels.size - 1)],
                        onStart = { b.start(p.name) }, onChange = { v -> b.change(p.name, v) }, onEnd = { b.end() },
                    )
                    else -> PanelKnob(b, p.name, accent = accent)
                }
            }
        }
        if (type == "Arp") ArpSteps(b)
    }
}

/** The arp's pattern: sixteen compact step toggles in one row, the ones past `length` dimmed. */
@Composable
private fun ArpSteps(b: ParamBinding) {
    val length = b.infoOf("length")?.map(b.value("length"))?.toInt() ?: 16
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("steps", color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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

// Mirrors engine/eventor/Scales.h - same order.
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
