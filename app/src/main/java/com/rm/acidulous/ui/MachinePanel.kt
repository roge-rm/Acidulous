package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Zone
import com.rm.acidulous.model.Zones
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.withPatch
import com.rm.acidulous.model.withSetting
import kotlinx.coroutines.delay

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
    onLoadPatch: (String) -> Map<String, Float>?,
    factoryPatchNames: () -> List<String> = { emptyList() },
    userPatchNames: () -> List<String> = { emptyList() },
    onDeletePatch: (String) -> Unit = {},
    onImportSoundFont: () -> Unit = {},
    onPickPreset: () -> Unit = {},
    onImportZoneSamples: () -> Unit = {},
    selectedPad: Int = 0,
    onImportSample: (pad: Int) -> Unit = {},
    onClearSample: (pad: Int) -> Unit = {},
    /** A sample already in the app's own folder, chosen rather than imported. */
    onAssignSample: (pad: Int, relative: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val type = track.machine.type
    // Folded away, the panel is just its title row: the roll takes the rest.
    var minimized by rememberSaveable { mutableStateOf(false) }
    val info = remember(type) { NativeEngine.machineParamInfo(type) }
    val binding = rememberParamBinding(trackIndex, type, info, editor)

    Column(modifier.background(Color(0xFF1F1F23)).padding(6.dp)) {
        val loadPatch: (String) -> Unit = { name ->
            onLoadPatch(name)?.let { params ->
                editor.edit(trackIndex) { t -> t.withPatch(params) }
                binding.applyAll(params)
            }
        }
        PatchBar(
            type, patchNames, onSavePatch, loadPatch, factoryPatchNames, userPatchNames, onDeletePatch,
            minimized = minimized, onToggleMinimized = { minimized = !minimized },
        )
        if (!minimized) when (type) {
            "Subvert" -> SubvertPanel(binding)
            "Hexbeat" -> HexbeatPanel(binding)
            "Trinity" -> TrinityPanel(binding)
            "Ratio" -> RatioPanel(binding)
            "Manual" -> ManualPanel(binding)
            "Cipher" -> CipherPanel(binding)
            "Mosaic" -> MosaicPanel(binding, track, trackIndex, editor, onImportSoundFont, onPickPreset, onImportZoneSamples)
            "Forage" -> ForagePanel(binding, track, selectedPad, onImportSample, onClearSample, onAssignSample)
            else -> GenericPanel(binding)
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
    /** Which unit on the rack the names address: "machine", "effect1", "effect2". */
    val unit: String = "machine",
    /** How a value lands in the document. */
    private val apply: (Track, String, Float) -> Track = { t, n, v -> t.withParam(n, v) },
) {
    fun value(name: String): Float = values.value[name] ?: info.firstOrNull { it.name == name }?.defaultNormalized ?: 0f
    fun display(name: String): String = info.firstOrNull { it.name == name }?.format(value(name)) ?: ""
    fun infoOf(name: String): ParamInfo? = info.firstOrNull { it.name == name }

    fun start(name: String) { dragging.value = name; editor.beginGesture(trackIndex) }
    fun change(name: String, v: Float) {
        values.value = values.value + (name to v)
        NativeEngine.setParam(trackIndex, unit, name, v, record = true)
        editor.updateGesture { t -> apply(t, name, v) }
    }
    fun end() { dragging.value = null; editor.endGesture() }

    /** A tap on a stepped control: one undo step, no gesture. */
    fun set(name: String, v: Float) {
        values.value = values.value + (name to v)
        NativeEngine.setParam(trackIndex, unit, name, v, record = true)
        editor.edit(trackIndex) { t -> apply(t, name, v) }
    }

    fun applyAll(params: Map<String, Float>) {
        val full = info.associate { it.name to (params[it.name] ?: it.defaultNormalized) }
        values.value = full
        for ((n, v) in full) NativeEngine.setParam(trackIndex, unit, n, v, record = false)
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
    val binding = remember(trackIndex, type, unit) { ParamBinding(trackIndex, info, editor, values, dragging, unit, apply) }
    LaunchedEffect(trackIndex, type, unit) {
        while (true) {
            val d = dragging.value
            values.value = info.associate { p ->
                p.name to (if (p.name == d) values.value[p.name] ?: p.defaultNormalized
                else NativeEngine.paramNormalized(trackIndex, unit, p.name).takeIf { it >= 0f } ?: values.value[p.name] ?: p.defaultNormalized)
            }
            delay(100)
        }
    }
    return binding
}

@Composable
private fun PatchBar(
    type: String, patchNames: () -> List<String>, onSave: (String) -> Unit, onLoad: (String) -> Unit,
    factoryNames: () -> List<String>, userNames: () -> List<String>, onDelete: (String) -> Unit,
    minimized: Boolean, onToggleMinimized: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var listRev by remember { mutableStateOf(0) } // bumps after a delete so the browser re-reads
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(type, color = Color.White, fontSize = 13.sp)
        TextButton(onClick = { menu = true }) { Text("patch ▾", color = Color(0xFFFFB454), fontSize = 11.sp) }
        TextButton(onClick = { saving = true }) { Text("save as…", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
        TextButton(onClick = { browsing = true }) { Text("browse…", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
        // Pushed to the far edge: the title row stays, everything under it goes.
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleMinimized, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (minimized) "▴" else "▾", color = Color(0xFFBBBBBB), fontSize = 13.sp)
        }
        val menuScroll = rememberScrollState()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.scrollbar(menuScroll), scrollState = menuScroll) {
            for (n in patchNames()) DropdownMenuItem(text = { Text(n, fontSize = 12.sp) }, onClick = { menu = false; onLoad(n) })
        }
    }
    if (saving) TextInputDialog("Patch name", "", onDismiss = { saving = false }) { name -> onSave(name); saving = false }
    if (browsing) {
        val factory = remember(type) { factoryNames() }
        val user = remember(type, listRev) { userNames() }
        PatchBrowserDialog(
            machine = type, factory = factory, user = user,
            onLoad = { n -> onLoad(n); browsing = false },
            onDelete = { n -> onDelete(n); listRev++ },
            onDismiss = { browsing = false },
        )
    }
}

// The panel palette. Teal is the ordinary control; amber marks the knob that
// gives a group its character; pink marks drive and output. See the style
// note at the bottom of this file.
internal val PanelTeal = Color(0xFF7FD1B9)
internal val PanelAmber = Color(0xFFFFB454)
internal val PanelPink = Color(0xFFE07A9A)

@Composable
internal fun PanelKnob(b: ParamBinding, name: String, label: String = name, accent: Color = PanelTeal) {
    Knob(
        label = label, value = b.value(name), display = b.display(name), accent = accent,
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
    )
}

@Composable
internal fun PanelSwitch(b: ParamBinding, name: String, labels: List<String>, label: String = name) {
    val info = b.infoOf(name) ?: return
    val idx = info.map(b.value(name)).toInt().coerceIn(0, labels.size - 1)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF9A9AA2), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEachIndexed { i, l ->
                val on = i == idx
                TextButton(
                    onClick = { b.set(name, if (labels.size > 1) i.toFloat() / (labels.size - 1) else 0f) },
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Color(0xFF3F7D5E) else Color(0xFF2E2E33)),
                ) { Text(l, color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 10.sp) }
            }
        }
    }
}

/**
 * The panel body every machine uses: one horizontally scrolling row of
 * [Group]s. Machines with more groups than fit comfortably put a
 * [SectionChips] row above it and show one section at a time.
 */
@Composable
internal fun GroupRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Which group of groups is showing. Only machines too big for one row need it. */
@Composable
internal fun SectionChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
                    .background(if (on) Color(0xFF3F7D5E) else Color(0xFF2E2E33))
                    .clickable { onSelect(i) }.padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l, color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip, softWrap = false,
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
        label = label, value = b.value(name), accent = accent,
        display = labels.getOrElse(info.map(b.value(name)).toInt().coerceIn(0, labels.size - 1)) { "" },
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
    )
}

@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262B)).padding(6.dp)) {
        Text(title, color = Color(0xFF7FD1B9), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) { content() }
    }
}

/** Subvert: the classic layer left to right, the open layer after it. */
@Composable
private fun SubvertPanel(b: ParamBinding) {
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
        Group("play") { PanelKnob(b, "accent") }
    }
}

/** Forage: the selected pad's sample and its controls; tap a pad to select it. */
@Composable
private fun ForagePanel(b: ParamBinding, track: Track, pad: Int, onImport: (Int) -> Unit,
                        onClear: (Int) -> Unit, onAssign: (Int, String) -> Unit) {
    val p = pad.coerceIn(0, 12)
    fun n(name: String) = "p%02d_%s".format(p, name)
    val rel = track.machine.settings[n("sample")]
    var info by remember(p, rel) { mutableStateOf("") }
    LaunchedEffect(p, rel) {
        while (true) { info = NativeEngine.sampleInfo(b.trackIndex, p); delay(400) }
    }
    val hot = Color(0xFFFFB454)
    var picking by remember { mutableStateOf(false) }
    if (picking) SampleBrowserDialog(
        onPick = { rel -> picking = false; onAssign(p, rel) },
        onDismiss = { picking = false },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("pad ${p + 1}", color = hot, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                if (info.isEmpty()) (rel?.let { "$it (not loaded)" } ?: "no sample") else info.substringBefore('|') + "  " + (info.split('|').getOrNull(1)?.toIntOrNull()?.let { "%.2fs".format(it / 48000f) } ?: "") + (if (info.endsWith("|1")) " st" else " mono"),
                color = Color(0xFFDDDDDD), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1,
            )
            TextButton(onClick = { onImport(p) }) { Text("load…", color = hot, fontSize = 11.sp) }
            TextButton(onClick = { picking = true }) { Text("recorded…", color = hot, fontSize = 11.sp) }
            if (rel != null) TextButton(onClick = { onClear(p) }) { Text("clear", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Group("sample") { PanelKnob(b, n("start"), "start"); PanelKnob(b, n("end"), "end"); PanelKnob(b, n("pitch"), "pitch", hot); PanelSwitch(b, n("reverse"), listOf("fwd", "rev"), "reverse") }
            Group("amp") { PanelKnob(b, n("decay"), "decay"); PanelKnob(b, n("level"), "level"); PanelKnob(b, n("pan"), "pan"); PanelSwitch(b, n("choke"), listOf("-", "1", "2", "3", "4"), "choke") }
            Group("tone") { PanelKnob(b, n("cutoff"), "cutoff", hot); PanelKnob(b, n("reso"), "reso", hot); PanelSwitch(b, n("mode"), listOf("lp", "bp"), "mode"); PanelKnob(b, n("crush"), "crush", Color(0xFFE07A9A)) }
            Group("punch") { PanelKnob(b, n("penv"), "pitch env"); PanelKnob(b, n("pdecay"), "decay") }
            Group("play") { PanelKnob(b, "accent") }
        }
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
    Column {
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

@Composable
fun PerformanceStrip(rack: Int, modifier: Modifier = Modifier) {
    var mod by rememberSaveable(rack) { mutableStateOf(0f) }
    var pressure by remember(rack) { mutableStateOf(0f) }
    // Re-entering the screen puts the wheel back where it was left.
    LaunchedEffect(rack) { NativeEngine.controlChange(rack, 1, (mod * 127f).toInt()) }
    Row(modifier.height(26.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TouchBar(
            label = "mod", value = mod, accent = PanelAmber, modifier = Modifier.weight(1f),
            onChange = { v -> mod = v; NativeEngine.controlChange(rack, 1, (v * 127f).toInt()) },
            onRelease = null, // a wheel stays put
        )
        TouchBar(
            label = "prs", value = pressure, accent = PanelPink, modifier = Modifier.weight(1f),
            onChange = { v -> pressure = v; NativeEngine.channelPressure(rack, (v * 127f).toInt()) },
            onRelease = { pressure = 0f; NativeEngine.channelPressure(rack, 0) },
        )
    }
}

/** A horizontal touch bar: drag anywhere along it, the fill is the value. */
@Composable
private fun TouchBar(
    label: String, value: Float, accent: Color, modifier: Modifier,
    onChange: (Float) -> Unit, onRelease: (() -> Unit)?,
) {
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF26262B))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Always take the down before deciding anything: a
                        // gesture block that returns without suspending spins
                        // the main thread.
                        val down = awaitPointerEvent()
                        val touch = down.changes.firstOrNull() ?: continue
                        if (touch.pressed) {
                            change((touch.position.x / size.width).coerceIn(0f, 1f))
                            touch.consume()
                        } else {
                            release?.invoke()
                        }
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(accent.copy(alpha = 0.55f)))
        Text(
            "$label  ${"%.2f".format(value)}",
            color = Color(0xFFDDDDE2), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
        )
    }
}


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
    Column {
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
    Color(0xFF8A6A4A), Color(0xFF8A6A4A), Color(0xFFE8E4DA), Color(0xFFE8E4DA),
    Color(0xFF8A6A4A), Color(0xFFE8E4DA), Color(0xFF6E6E76), Color(0xFF6E6E76), Color(0xFFE8E4DA),
)

@Composable
private fun Drawbars(b: ParamBinding, prefix: String, names: List<String>, colours: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        names.forEachIndexed { i, label ->
            val name = prefix + label.replace("5⅓", "513").replace("2⅔", "223")
                .replace("1⅗", "135").replace("1⅓", "113")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Color(0xFF9A9AA2), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                VerticalFader(
                    value = b.value(name),
                    modifier = Modifier.width(18.dp).height(64.dp),
                    accent = colours[i % colours.size],
                    onStart = { b.start(name) },
                    onChange = { v -> b.change(name, v) },
                    onEnd = { b.end() },
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
    Column {
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
                        Drawbars(b, "pa_", listOf("1", "2"), listOf(Color(0xFFE8E4DA)))
                        Drawbars(b, "pb_", listOf("1", "2"), listOf(Color(0xFF8A6A4A)))
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
 * A vocoder with nothing coming in is a synth with the volume down, so the
 * panel owns the microphone rather than making you find it in a menu.
 */
@Composable
private fun CipherInput() {
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
        Text("input", color = Color(0xFF7FD1B9), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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
        }) { Text(if (running) "listening" else "open", color = if (running) PanelTeal else PanelAmber, fontSize = 11.sp) }
        Meter(level, Modifier.width(70.dp).height(8.dp), vertical = false)
    }
}

@Composable
private fun CipherPanel(b: ParamBinding) {
    var section by rememberSaveable { mutableStateOf(0) }
    Column {
        SectionChips(listOf("bank", "map", "carrier", "voice", "mod", "out"), section) { section = it }
        GroupRow {
            when (section) {
                0 -> {
                    Group("listen") { CipherInput() }
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
    if (pickingZone) SampleBrowserDialog(
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

    Column {
        SectionChips(listOf("map", "sample", "grain", "filter", "env", "lfo", "mod", "voice"), section) { section = it }
        if (section == 0) {
            if (sf2.isEmpty()) {
                ZoneMapView(zones, selectedZone, { i -> selectedZone = i; editing = true },
                    Modifier.fillMaxWidth().height(96.dp).padding(bottom = 4.dp))
            } else {
                // A SoundFont preset carries its own map; the file owns it, so
                // it is shown rather than edited.
                Text(
                    "SoundFont: ${sf2.substringAfterLast('/')}   ${info.ifEmpty { "loading…" }}",
                    color = Color(0xFF9A9AA2), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp), maxLines = 1,
                )
            }
        }
        GroupRow {
            when (section) {
                0 -> {
                    Group("instrument") {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(info.split('|').firstOrNull().orEmpty().ifEmpty { "nothing loaded" },
                                color = Color.White, fontSize = 11.sp, maxLines = 1)
                            Text(
                                info.split('|').let { f ->
                                    if (f.size >= 4) "${f[1]} zones · ${f[2]} samples · %.1fs".format(f[3].toFloatOrNull() ?: 0f)
                                    else " "
                                },
                                color = Color(0xFF9A9AA2), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            )
                            Row {
                                TextButton(onClick = onImportSoundFont) { Text("soundfont…", color = PanelAmber, fontSize = 10.sp) }
                                if (sf2.isNotEmpty()) TextButton(onClick = onPickPreset) { Text("preset…", color = PanelAmber, fontSize = 10.sp) }
                                TextButton(onClick = onImportZoneSamples) { Text("samples…", color = Color(0xFFBBBBBB), fontSize = 10.sp) }
                                TextButton(onClick = { pickingZone = true }) { Text("recorded…", color = Color(0xFFBBBBBB), fontSize = 10.sp) }
                            }
                        }
                    }
                    if (sf2.isEmpty()) Group("zones") {
                        Column {
                            Text("${zones.size} zone${if (zones.size == 1) "" else "s"}", color = Color.White, fontSize = 11.sp)
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
                                ) { Text("spread", color = PanelAmber, fontSize = 10.sp) }
                                TextButton(onClick = { putZones(emptyList()) }, enabled = zones.isNotEmpty()) {
                                    Text("clear", color = Color(0xFFE74C3C), fontSize = 10.sp)
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
