package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Scales
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.ThemeMode

/**
 * Everything that belongs to the person and the device rather than to the
 * song: how it looks, how hard it is allowed to work, and what a new song
 * starts as.
 *
 * Five tabs rather than one long scroll, because settings only ever
 * accumulate and a list of everything is a list nobody reads. They use the
 * same chips a machine panel uses for its sections, so the app has one idea
 * of what a tab looks like.
 *
 * It is also its own Dialog rather than an AlertDialog: Material caps a
 * dialog at 560dp and pads it off both edges, which on a phone left every
 * explanatory line wrapping three times over for no reason. This one takes
 * the screen's width, minus a margin, up to a tablet-sized limit.
 */
@Composable
fun SettingsDialog(trackNames: List<String> = emptyList(), onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    // The same shell as the machine picker, and for the same reason: the
    // body is as tall as the tallest tab, so the window does not resize and
    // the Done button does not move when you change tab.
    TabbedDialog(
        title = "Settings",
        selected = tab,
        pages = listOf(
            { DisplayTab() },
            { AudioTab(trackNames) },
            { RecordTab() },
            { NewSongSection() },
        ),
        onDismiss = onDismiss,
        spacing = 16.dp,
        chips = { SectionChips(TABS, tab) { tab = it } },
    )
}

// **No midi tab.** It held one section - where an arriving note lands - and
// that same section is the first thing in the MIDI window's own `in` tab,
// which is where somebody goes when a keyboard is not playing what they
// expect. Two places to change one setting is one place too many, and the
// other four chips are wider for it.
private val TABS = listOf("display", "audio", "record", "songs")

@Composable
private fun DisplayTab() {
    Section("theme") {
        Choice("auto", UiPrefs.theme == ThemeMode.Auto) { UiPrefs.chooseTheme(ThemeMode.Auto) }
        Choice("light", UiPrefs.theme == ThemeMode.Light) { UiPrefs.chooseTheme(ThemeMode.Light) }
        Choice("dark", UiPrefs.theme == ThemeMode.Dark) { UiPrefs.chooseTheme(ThemeMode.Dark) }
    }
    // The one setting whose effect is the window it is being read in: the
    // chips grow under the finger that taps them.
    //
    // A note only when the screen cannot give what was asked for, which is the
    // one thing the chips cannot show - see ui/UiScale.kt for the cap. On every
    // phone this has been built for it says nothing at all.
    val applied = LocalUiScale.current
    Section(
        "interface size",
        if (applied >= UiPrefs.uiScale - 0.001f) ""
        else "This screen can give %.2fx of it.".format(applied),
    ) {
        UiScaleSteps.forEachIndexed { i, step ->
            Choice(UiScaleLabels[i], UiPrefs.uiScale == step) { UiPrefs.chooseUiScale(step) }
        }
    }
    Section("screen while playing") {
        Choice("stay awake", UiPrefs.keepAwake) { UiPrefs.chooseKeepAwake(true) }
        Choice("let it sleep", !UiPrefs.keepAwake) { UiPrefs.chooseKeepAwake(false) }
    }
}

/** Mirrors the engine's own two, which are not exported to Kotlin. */
private const val RACKS = 16
private const val BLOCK_FRAMES = 64

@Composable
private fun AudioTab(trackNames: List<String>) {
    // The numbers are the point here: a buffer is a promise about how late
    // the engine may be, and only this phone knows whether it can keep it.
    val burst = NativeEngine.framesPerBurst.coerceAtLeast(1)
    val frames = NativeEngine.bufferFrames
    val ms = frames * 1000f / NativeEngine.sampleRate.coerceAtLeast(1)
    val drops = NativeEngine.xRunCount
    Section(
        "audio buffer",
        "%d frames · %.0f ms · burst %d · %d dropout%s"
            .format(frames, ms, burst, drops, if (drops == 1L) "" else "s"),
    ) {
        for (b in UiPrefs.Buffer.entries) {
            Choice(b.label, UiPrefs.buffer == b) { UiPrefs.chooseBuffer(b) }
        }
    }

    // **Where the time actually went.** The buffer above says how long the
    // engine has; this says how long it took, worst case, and which part of it
    // was slow. Both peaks are cleared by reading, and this is a second reader
    // after the diagnostics line - which is fine and deliberate: opening this
    // page zeroes them, so what it shows is "since you opened it", which is
    // the window somebody looking at it means.
    var worst by remember { mutableStateOf(0) }
    var phases by remember { mutableStateOf(IntArray(NativeEngine.Phase.entries.size)) }
    var racks by remember { mutableStateOf(IntArray(RACKS)) }
    // Whether each one was playing frozen audio when it set that peak.
    var rackFrozen by remember { mutableStateOf(BooleanArray(RACKS)) }
    var interrupted by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            worst = maxOf(worst, NativeEngine.worstBlockUs)
            val next = phases.copyOf()
            for (p in NativeEngine.Phase.entries) {
                next[p.ordinal] = maxOf(next[p.ordinal], NativeEngine.worstPhaseUs(p))
            }
            phases = next
            val byRack = racks.copyOf()
            val wasFrozen = rackFrozen.copyOf()
            for (r in 0 until RACKS) {
                // The flag and the peak are still read - the peak is what the
                // snowflake hangs off - but what is *shown* is the percentile,
                // which is not a running maximum and so is simply assigned.
                val frozen = NativeEngine.worstRackWasFrozen(r)
                if (NativeEngine.worstRackUs(r) > 0) wasFrozen[r] = frozen
                byRack[r] = NativeEngine.rackPercentileUs(r)
            }
            racks = byRack
            rackFrozen = wasFrozen
            interrupted = NativeEngine.interruptedPercent
            delay(120)
        }
    }
    // **Against the block's budget, not the callback's.** This read "2.46 ms
    // of 4.00" and meant 2.46 of 1.33: the worst *block* is one 64-frame
    // render and 4 ms is what a whole 192-frame callback gets, so the figure
    // that mattered was being flattered by a factor of three.
    val blockBudgetMs = 1000f * BLOCK_FRAMES / NativeEngine.sampleRate.coerceAtLeast(1)
    Section(
        "worst block",
        // Every figure here comes from a block that ran without being
        // interrupted, which is the only kind whose parts can be believed: a
        // thread taken off its core mid-block hands that whole absence to
        // whatever it was timing. The percentage is how many blocks were
        // thrown away for that reason - and it is the answer to "is this the
        // DSP or the scheduler" all by itself.
        "%.2f ms of %.2f · %s%s".format(
            worst / 1000f,
            blockBudgetMs,
            NativeEngine.Phase.entries
                .joinToString(" ") { "${it.name.lowercase().take(3)} %.2f".format(phases[it.ordinal] / 1000f) },
            if (interrupted >= 0.5f) " · %.0f%% interrupted".format(interrupted) else "",
        ),
    ) {
        Choice("reset", false) {
            worst = 0
            phases = IntArray(NativeEngine.Phase.entries.size)
            racks = IntArray(RACKS)
            rackFrozen = BooleanArray(RACKS)
            NativeEngine.resetRackCosts()
        }
    }

    // **Which track**, because "the racks are most of it" is half an answer.
    // Only the ones with a machine, sorted by cost, so the list says what to
    // freeze rather than making somebody work it out.
    //
    // **The worst block in a hundred, not the worst block.** A peak over a
    // whole song is set by one unlucky block and nothing afterwards can lower
    // it, which made this the least repeatable number on the page: three runs
    // of one build on one phone put these up to 26% apart, so a change worth
    // twenty per cent could not be told from the same build measured twice.
    // The engine keeps a histogram instead. `worst block` above stays a true
    // peak, because that one is about a deadline and a deadline is missed by
    // one block.
    val named = (0 until RACKS)
        .filter { it < trackNames.size && racks[it] > 0 }
        .sortedByDescending { racks[it] }
    if (named.isNotEmpty()) {
        Section(
            "worst track",
            // A snowflake means that cost was paid while the track was playing
            // frozen audio - which should be next to nothing, so it is the
            // readout saying the freeze is not doing its job rather than the
            // track being expensive.
            named.take(6).joinToString("  ") {
                val mark = if (rackFrozen[it]) " ❄" else ""
                "${trackNames[it]}$mark %.2f".format(racks[it] / 1000f)
            },
        ) {}
    }

    Section(
        "machine voice limit",
        if (UiPrefs.voiceLimit == 0) "" else "Per track. The oldest note goes first.",
    ) {
        for (n in listOf(4, 8, 16, 32, 48, 64)) {
            Choice("$n", UiPrefs.voiceLimit == n) { UiPrefs.chooseVoiceLimit(n) }
        }
        Choice("all", UiPrefs.voiceLimit == 0) { UiPrefs.chooseVoiceLimit(0) }
    }

    // **What it says is now what it does.** This read "Half the reverb, no
    // oversampling" while the reverb half was dead code - the one with the
    // branch in it had not been included by anything since the sends became
    // ordinary effect slots - and the oversampling half reached the
    // distortion only, never the amp, which is the dearest thing here.
    Section(
        "quality",
        when {
            UiPrefs.autoQuality -> "Auto: running %s.".format(if (UiPrefs.qualityNow) "full" else "lean")
            UiPrefs.fullQuality -> ""
            else ->
                "Amp and distortion alias instead of oversampling, the reverb is " +
                    "half a room, struck objects keep half their partials, the synth " +
                    "thins its stacks and lets fewer notes ring out, and grain clouds halve."
        },
    ) {
        Choice("full", UiPrefs.fullQuality, enabled = !UiPrefs.autoQuality) { UiPrefs.chooseQuality(true) }
        Choice("lean", !UiPrefs.fullQuality, enabled = !UiPrefs.autoQuality) { UiPrefs.chooseQuality(false) }
        Choice("auto", UiPrefs.autoQuality) { UiPrefs.chooseAutoQuality(!UiPrefs.autoQuality) }
    }

    // No control, because there is nothing to choose: the device either takes
    // hints or it does not. The line says which, and nothing else.
    Section(
        "scheduler hint",
        when (NativeEngine.hintState) {
            0 -> "Not taken on this device."
            1 -> "Waiting for the audio thread."
            2 -> "The audio thread never named itself."
            3 -> "This device refused it."
            else -> "On. Every callback's real length is reported."
        },
    ) {}
}

@Composable
private fun RecordTab() {
    // 48 kHz either way; only the depth is a choice.
    Section("recording and export depth") {
        Choice("24-bit", UiPrefs.recordBits == 24) { UiPrefs.chooseRecordBits(24) }
        Choice("16-bit", UiPrefs.recordBits == 16) { UiPrefs.chooseRecordBits(16) }
    }
}

@Composable
private fun NewSongSection() {
    val sig = UiPrefs.newSignature
    SliderSection(
        "new song tempo", "%.0f bpm".format(UiPrefs.newTempo), "",
        UiPrefs.newTempo, 40f..240f,
    ) { UiPrefs.chooseNewTempo(it) }
    // A slider rather than five chips: it fits all eight signatures where
    // the row fitted five, in the same height.
    val sigIndex = SIGNATURES.indexOf(UiPrefs.newSignature).coerceAtLeast(0)
    SliderSection(
        "new song signature", "${sig.beats}/${sig.unit}", "",
        sigIndex.toFloat(), 0f..(SIGNATURES.size - 1).toFloat(), SIGNATURES.size - 2,
    ) { v -> UiPrefs.chooseNewSignature(SIGNATURES[v.toInt().coerceIn(0, SIGNATURES.size - 1)]) }
    // What the one track of a new song holds. Hexbeat by default - a new
    // song is usually a beat before it is anything else - and behind the same
    // picker the arranger's "+ track" uses, because nineteen machines is not
    // a row of chips and a second list of them would be a second list to keep
    // up to date.
    var pickingMachine by remember { mutableStateOf(false) }
    Section("new song machine", com.rm.acidulous.model.MachineUi.describe(UiPrefs.newMachine)) {
        Choice(UiPrefs.newMachine, true) { pickingMachine = true }
    }
    if (pickingMachine) {
        MachinePickerDialog(
            current = UiPrefs.newMachine,
            onDismiss = { pickingMachine = false },
        ) { type -> UiPrefs.chooseNewMachine(type); pickingMachine = false }
    }
    // The scale a new track starts in: a Scale modifier is fitted to it, so
    // the keyboard and the roll agree with the song from the first note.
    var picking by remember { mutableStateOf(false) }
    Section(
        // The chips carry the answer either way: "no scale", or the scale.
        "new track scale",
    ) {
        Choice("no scale", !UiPrefs.newScaleOn) { UiPrefs.chooseNewScale(false) }
        Choice(
            if (UiPrefs.newScaleOn) {
                "${Scales.rootName(UiPrefs.newScaleKey, UiPrefs.newScaleIndex)} ${Scales.names[UiPrefs.newScaleIndex]}"
            } else {
                "choose…"
            },
            UiPrefs.newScaleOn,
        ) { picking = true }
    }
    if (picking) {
        ScalePickerDialog(
            key = UiPrefs.newScaleKey,
            scale = UiPrefs.newScaleIndex,
            onDismiss = { picking = false },
        ) { key, index ->
            UiPrefs.chooseNewScale(true, key, index)
            picking = false
        }
    }
}

/** Where an arriving note lands. Shown here and on the MIDI window's in tab. */
@Composable
internal fun MidiRoutingSection(trackNames: List<String>) {
    Section(
        "where arriving notes go",
        when (MidiHub.routing) {
            MidiHub.Routing.SelectedTrack -> "Whichever track is open."
            MidiHub.Routing.FixedTrack ->
                trackNames.getOrNull(MidiHub.fixedRack) ?: "track ${MidiHub.fixedRack + 1}"
            MidiHub.Routing.ChannelToRack -> "Channel 1 to track 1, and so on."
        },
    ) {
        Choice("follow", MidiHub.routing == MidiHub.Routing.SelectedTrack) {
            UiPrefs.chooseMidiRouting(MidiHub.Routing.SelectedTrack)
        }
        Choice("pinned", MidiHub.routing == MidiHub.Routing.FixedTrack) {
            UiPrefs.chooseMidiRouting(MidiHub.Routing.FixedTrack)
        }
        Choice("by channel", MidiHub.routing == MidiHub.Routing.ChannelToRack) {
            UiPrefs.chooseMidiRouting(MidiHub.Routing.ChannelToRack)
        }
    }
    if (MidiHub.routing == MidiHub.Routing.FixedTrack && trackNames.isNotEmpty()) {
        Section("pinned to", "Played even while another track is open.") {
            trackNames.forEachIndexed { i, name ->
                Choice(name, MidiHub.fixedRack == i) {
                    UiPrefs.chooseMidiRouting(MidiHub.Routing.FixedTrack, i)
                }
            }
        }
    }
}

/** The 33 scales, in a list, with the twelve keys across the top. */
@Composable
private fun ScalePickerDialog(key: Int, scale: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    var k by remember { mutableStateOf(key) }
    PlainDialog(title = "Scale", onDismiss = onDismiss, spacing = 10.dp) {
        Section("key") {
            for (i in 0 until 12) Choice(Scales.rootName(i, scale), k == i) { k = i }
        }
        ListSection("scale") {
            Scales.names.forEachIndexed { i, name ->
                DialogRow(mark = if (scale == i) "●" else "·", name = name, on = scale == i) { onPick(k, i) }
            }
        }
    }
}

// Section and Choice live in ui/Dialogs.kt: they are the shared vocabulary
// of every window here, not a settings idea.
