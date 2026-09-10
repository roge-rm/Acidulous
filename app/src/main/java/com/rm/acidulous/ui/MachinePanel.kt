package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.ParamInfo
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.withPatch
import kotlinx.coroutines.delay

/**
 * The machine's face in the Edit screen. Knob values live in three places
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
    selectedPad: Int = 0,
    onImportSample: (pad: Int) -> Unit = {},
    onClearSample: (pad: Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val type = track.machine.type
    val info = remember(type) { NativeEngine.machineParamInfo(type) }
    val binding = rememberParamBinding(trackIndex, type, info, editor)

    Column(modifier.background(Color(0xFF1F1F23)).padding(6.dp)) {
        val loadPatch: (String) -> Unit = { name ->
            onLoadPatch(name)?.let { params ->
                editor.edit(trackIndex) { t -> t.withPatch(params) }
                binding.applyAll(params)
            }
        }
        PatchBar(type, patchNames, onSavePatch, loadPatch, factoryPatchNames, userPatchNames, onDeletePatch)
        when (type) {
            "Subvert" -> SubvertPanel(binding)
            "Hexbeat" -> HexbeatPanel(binding)
            "Trinity" -> TrinityPanel(binding)
            "Forage" -> ForagePanel(binding, track, selectedPad, onImportSample, onClearSample)
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
) {
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var listRev by remember { mutableStateOf(0) } // bumps after a delete so the browser re-reads
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(type, color = Color.White, fontSize = 13.sp)
        TextButton(onClick = { menu = true }) { Text("patch ▾", color = Color(0xFFFFB454), fontSize = 11.sp) }
        TextButton(onClick = { saving = true }) { Text("save as…", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
        TextButton(onClick = { browsing = true }) { Text("browse…", color = Color(0xFFBBBBBB), fontSize = 11.sp) }
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

@Composable
internal fun PanelKnob(b: ParamBinding, name: String, label: String = name, accent: Color = Color(0xFF7FD1B9)) {
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
    Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Group("osc") { PanelSwitch(b, "wave", listOf("saw", "pulse")); PanelKnob(b, "pw"); PanelKnob(b, "sub"); PanelKnob(b, "tune") }
        Group("filter") { PanelKnob(b, "cutoff", accent = Color(0xFFFFB454)); PanelKnob(b, "resonance", "reso", Color(0xFFFFB454)); PanelKnob(b, "envmod", accent = Color(0xFFFFB454)); PanelKnob(b, "decay", accent = Color(0xFFFFB454)); PanelSwitch(b, "mode", listOf("lp", "bp")) }
        Group("play") { PanelKnob(b, "accent"); PanelKnob(b, "slide") }
        Group("out") { PanelKnob(b, "drive", accent = Color(0xFFE07A9A)); PanelKnob(b, "volume") }
    }
}

/** Hexbeat: a group per voice family, in kit order. */
@Composable
private fun HexbeatPanel(b: ParamBinding) {
    val hot = Color(0xFFFFB454)
    Row(Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun ForagePanel(b: ParamBinding, track: Track, pad: Int, onImport: (Int) -> Unit, onClear: (Int) -> Unit) {
    val p = pad.coerceIn(0, 12)
    fun n(name: String) = "p%02d_%s".format(p, name)
    val rel = track.machine.settings[n("sample")]
    var info by remember(p, rel) { mutableStateOf("") }
    LaunchedEffect(p, rel) {
        while (true) { info = NativeEngine.sampleInfo(b.trackIndex, p); delay(400) }
    }
    val hot = Color(0xFFFFB454)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("pad ${p + 1}", color = hot, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                if (info.isEmpty()) (rel?.let { "$it (not loaded)" } ?: "no sample") else info.substringBefore('|') + "  " + (info.split('|').getOrNull(1)?.toIntOrNull()?.let { "%.2fs".format(it / 48000f) } ?: "") + (if (info.endsWith("|1")) " st" else " mono"),
                color = Color(0xFFDDDDDD), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1,
            )
            TextButton(onClick = { onImport(p) }) { Text("load…", color = hot, fontSize = 11.sp) }
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
private val TRINITY_ENVS = listOf("amp" to "a", "filter" to "f", "env 3" to "e3", "env 4" to "e4", "env 5" to "e5", "env 6" to "e6")

@Composable
private fun TrinityPanel(b: ParamBinding) {
    var section by remember { mutableStateOf(0) }
    var osc by remember { mutableStateOf(0) }
    var env by remember { mutableStateOf(0) }
    var lfo by remember { mutableStateOf(0) }
    var slot by remember { mutableStateOf(0) }
    val sections = listOf("osc", "mix", "filter", "env", "lfo", "matrix", "voice")
    Column {
        Chips(sections, section) { section = it }
        when (section) {
            0 -> {
                Chips(listOf("1", "2", "3"), osc) { osc = it }
                val o = "o${osc + 1}_"
                KnobRow {
                    PanelStepKnob(b, o + "wave", TRINITY_WAVES, "wave")
                    PanelKnob(b, o + "pos", "pos", accent = ACCENT2)
                    PanelKnob(b, o + "warp", "warp", accent = ACCENT2)
                    PanelKnob(b, o + "coarse", "coarse")
                    PanelKnob(b, o + "fine", "fine")
                    PanelKnob(b, o + "level", "level")
                    PanelStepKnob(b, o + "density", (1..8).map { "$it" }, "density", ACCENT2)
                    PanelKnob(b, o + "detune", "detune", accent = ACCENT2)
                    PanelKnob(b, o + "sync", "sync", accent = ACCENT2)
                    PanelKnob(b, o + "hard", "hard")
                    PanelKnob(b, o + "pw", "pw")
                    PanelKnob(b, o + "drift", "drift", accent = ACCENT2)
                }
            }
            1 -> KnobRow {
                PanelKnob(b, "ring12", "ring1·2")
                PanelKnob(b, "ring23", "ring2·3")
                PanelKnob(b, "fm21", "fm 2→1", accent = ACCENT2)
                PanelKnob(b, "fm32", "fm 3→2", accent = ACCENT2)
                PanelKnob(b, "noise", "noise")
                PanelKnob(b, "noisecol", "colour")
            }
            2 -> {
                KnobRow {
                    PanelSwitch(b, "route", listOf("serial", "para", "split"), "route")
                    PanelKnob(b, "balance", "balance")
                }
                for (f in 1..2) {
                    Text("filter $f", color = Color(0xFF7FD1B9), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    KnobRow {
                        PanelStepKnob(b, "f${f}_type", TRINITY_FILTERS, "type")
                        PanelKnob(b, "f${f}_freq", "freq")
                        PanelKnob(b, "f${f}_res", "res")
                        PanelStepKnob(b, "f${f}_drivetype", TRINITY_DRIVES, "drive", ACCENT2)
                        PanelKnob(b, "f${f}_drive", "amount", accent = ACCENT2)
                        PanelKnob(b, "f${f}_env", "env")
                        PanelKnob(b, "f${f}_key", "key")
                    }
                }
            }
            3 -> {
                Chips(TRINITY_ENVS.map { it.first }, env) { env = it }
                val e = TRINITY_ENVS[env].second + "_"
                KnobRow {
                    PanelKnob(b, e + "delay", "delay")
                    PanelKnob(b, e + "attack", "attack")
                    PanelKnob(b, e + "decay", "decay")
                    PanelKnob(b, e + "sustain", "sustain")
                    PanelKnob(b, e + "release", "release")
                    PanelSwitch(b, e + "repeat", listOf("once", "loop"), "repeat")
                }
            }
            4 -> {
                Chips(listOf("1", "2", "3"), lfo) { lfo = it }
                val l = "l${lfo + 1}_"
                KnobRow {
                    PanelStepKnob(b, l + "wave", TRINITY_LFO_WAVES, "wave")
                    PanelKnob(b, l + "rate", "rate")
                    PanelStepKnob(b, l + "sync", TRINITY_LFO_SYNC, "sync", ACCENT2)
                    PanelKnob(b, l + "delay", "delay")
                    PanelKnob(b, l + "phase", "phase")
                    PanelKnob(b, l + "slew", "slew", accent = ACCENT2)
                    PanelSwitch(b, l + "keysync", listOf("free", "key"), "keysync")
                    PanelSwitch(b, l + "oneshot", listOf("cycle", "once"), "oneshot")
                }
            }
            5 -> {
                Chips((1..12).map { "%02d".format(it) }, slot) { slot = it }
                val m = "m%02d_".format(slot + 1)
                KnobRow {
                    PanelStepKnob(b, m + "src", TRINITY_SOURCES, "source")
                    PanelStepKnob(b, m + "src2", TRINITY_SOURCES, "× source", ACCENT2)
                    PanelStepKnob(b, m + "dest", TRINITY_DESTS, "to")
                    PanelKnob(b, m + "depth", "depth")
                }
            }
            else -> KnobRow {
                PanelSwitch(b, "voicemode", listOf("poly", "mono", "legato", "uni"), "mode")
                PanelStepKnob(b, "unison", (1..8).map { "$it" }, "unison")
                PanelKnob(b, "unidetune", "detune")
                PanelKnob(b, "unispread", "spread")
                PanelKnob(b, "glide", "glide")
                PanelSwitch(b, "glidemode", listOf("always", "legato"), "glide on")
                PanelKnob(b, "bend", "bend")
                PanelKnob(b, "octave", "octave")
                PanelKnob(b, "transpose", "transpose")
                PanelKnob(b, "volume", "volume")
                PanelKnob(b, "pan", "pan")
                PanelKnob(b, "velamt", "vel")
            }
        }
    }
}

private val ACCENT2 = Color(0xFFFFB454)

@Composable
private fun KnobRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) { content() }
}

/** A row of small selectable chips: which oscillator, which envelope, which slot. */
@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()).padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Color(0xFF3F7D5E) else Color(0xFF2E2E33))
                    .clickable { onSelect(i) }.padding(horizontal = 8.dp, vertical = 3.dp),
            ) { Text(l, color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 10.sp) }
        }
    }
}

/** A stepped parameter as a knob that names its step - for the long lists. */
@Composable
private fun PanelStepKnob(b: ParamBinding, name: String, labels: List<String>, label: String = name, accent: Color = Color(0xFF7FD1B9)) {
    val info = b.infoOf(name) ?: return
    Knob(
        label = label, value = b.value(name), accent = accent,
        display = labels.getOrElse(info.map(b.value(name)).toInt().coerceIn(0, labels.size - 1)) { "" },
        onStart = { b.start(name) }, onChange = { v -> b.change(name, v) }, onEnd = { b.end() },
    )
}
