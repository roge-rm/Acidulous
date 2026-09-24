package com.rm.acidulous.ui

import kotlin.math.roundToInt
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R

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
        title = stringResource(R.string.settings_title),
        selected = tab,
        pages = listOf(
            { DisplayTab() },
            { AudioTab(trackNames) },
            { RecordTab() },
            { NewSongSection() },
        ),
        onDismiss = onDismiss,
        spacing = 6.dp,
        chips = { SectionChips(stringArrayResource(R.array.settings_tabs).toList(), tab) { tab = it } },
    )
}

// **No midi tab.** It held one section - where an arriving note lands - and
// that same section is the first thing in the MIDI window's own `in` tab,
// which is where somebody goes when a keyboard is not playing what they
// expect. Two places to change one setting is one place too many, and the
// other four chips are wider for it.

@Composable
private fun DisplayTab() {
    // Cards of switches, the arp window's shape, like every window with
    // settings in it (Dan, 2026-09-23).
    WindowCards {
        WindowCard(stringResource(R.string.settings_screen)) {
            SwitchGrid(
                stringResource(R.string.settings_theme), stringArrayResource(R.array.settings_theme_choices).toList(),
                when (UiPrefs.theme) { ThemeMode.Auto -> 0; ThemeMode.Light -> 1; ThemeMode.Dark -> 2 },
                columns = 1,
            ) { UiPrefs.chooseTheme(listOf(ThemeMode.Auto, ThemeMode.Light, ThemeMode.Dark)[it]) }
            // The one setting whose effect is the window it is being read in:
            // the cells grow under the finger that taps them.
            SwitchGrid(stringResource(R.string.settings_size), stringArrayResource(R.array.settings_size_choices).toList(), UiScaleSteps.indexOf(UiPrefs.uiScale), columns = 2) {
                UiPrefs.chooseUiScale(UiScaleSteps[it])
            }
            SwitchGrid(stringResource(R.string.settings_while_playing), stringArrayResource(R.array.settings_while_playing_choices).toList(), if (UiPrefs.keepAwake) 0 else 1) {
                UiPrefs.chooseKeepAwake(it == 0)
            }
            // The numbers kept for finding faults, everywhere they appear.
            SwitchGrid(stringResource(R.string.settings_diagnostics), stringArrayResource(R.array.settings_diagnostics_choices).toList(), if (UiPrefs.showDiagnostics) 0 else 1) {
                UiPrefs.chooseDiagnostics(it == 0)
            }
        }
    }
    // A line only when the screen cannot give what was asked for, which is
    // the one thing the switch cannot show - see ui/UiScale.kt for the cap.
    val applied = LocalUiScale.current
    if (applied < UiPrefs.uiScale - 0.001f) {
        Text(stringResource(R.string.settings_size_capped, applied), color = Acid.colors.textDim, fontSize = 11.sp)
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
    // The three things to set, in a card; everything under it is a reading.
    WindowCards {
        WindowCard(stringResource(R.string.settings_engine)) {
            SwitchGrid(stringResource(R.string.settings_buffer), UiPrefs.Buffer.entries.map { stringResource(it.label) }, UiPrefs.buffer.ordinal, columns = 1) {
                UiPrefs.chooseBuffer(UiPrefs.Buffer.entries[it])
            }
            val limits = listOf(4, 8, 16, 32, 48, 64, 0)
            val li = limits.indexOf(UiPrefs.voiceLimit).coerceAtLeast(0)
            val all = stringResource(R.string.settings_voices_all)
            CountKnob(
                stringResource(R.string.settings_voices), li, 0 until limits.size, if (UiPrefs.voiceLimit == 0) all else "${UiPrefs.voiceLimit}",
                choices = limits.map { if (it == 0) all else "$it" },
            ) { UiPrefs.chooseVoiceLimit(limits[it]) }
            // Auto decides between the other two, so while it is on they
            // show which one it chose rather than being chosen.
            SwitchGrid(
                stringResource(R.string.settings_quality), stringArrayResource(R.array.settings_quality_choices).toList(),
                if (UiPrefs.autoQuality) 2 else if (UiPrefs.fullQuality) 0 else 1,
                columns = 1,
            ) { i ->
                when (i) {
                    2 -> UiPrefs.chooseAutoQuality(!UiPrefs.autoQuality)
                    else -> { if (UiPrefs.autoQuality) UiPrefs.chooseAutoQuality(false); UiPrefs.chooseQuality(i == 0) }
                }
            }
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
    // The readings, in a card of their own under the one you set things in.
    // Every figure comes from a block that ran without being interrupted,
    // which is the only kind whose parts can be believed: a thread taken off
    // its core mid-block hands that whole absence to whatever it was timing.
    // The percentage is how many blocks were thrown away for that reason -
    // and it is the answer to "is this the DSP or the scheduler" by itself.
    val lines = mutableListOf(
        "buffer  %d frames · %.0f ms · burst %d · %d dropout%s".format(frames, ms, burst, drops, if (drops == 1L) "" else "s") +
            if (UiPrefs.autoQuality) " · auto running %s".format(if (UiPrefs.qualityNow) "full" else "lean") else "",
        "worst block  %.2f ms of %.2f · %s%s".format(
            worst / 1000f,
            blockBudgetMs,
            NativeEngine.Phase.entries
                .joinToString(" ") { "${it.name.lowercase().take(3)} %.2f".format(phases[it.ordinal] / 1000f) },
            if (interrupted >= 0.5f) " · %.0f%% interrupted".format(interrupted) else "",
        ),
    )
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
        // A snowflake means that cost was paid while the track was playing
        // frozen audio - which should be next to nothing, so it is the
        // readout saying the freeze is not doing its job.
        lines += "worst track  " + named.take(6).joinToString("  ") {
            val mark = if (rackFrozen[it]) " ❄" else ""
            "${trackNames[it]}$mark %.2f".format(racks[it] / 1000f)
        }
    }

    // No control for the scheduler hint, because there is nothing to
    // choose: the device either takes hints or it does not.
    lines += "scheduler hint  " + when (NativeEngine.hintState) {
        0 -> "not available on this device"
        1 -> "waiting for the audio thread"
        2 -> "the audio thread didn't register"
        3 -> "this device refused it"
        else -> "on"
    }
    if (UiPrefs.showDiagnostics) WindowCards {
        WindowCard("readings · since opened") {
            // Lines of prose in a card of controls: a column of a set width
            // when the cards are side by side, where filling the row would
            // squeeze each line to a letter wide.
            androidx.compose.foundation.layout.Column(
                Modifier.cardLine(),
            ) {
                for (l in lines) {
                    Text(l, color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
                }
            }
            SwitchGrid("peaks", listOf("reset"), -1) {
                worst = 0
                phases = IntArray(NativeEngine.Phase.entries.size)
                racks = IntArray(RACKS)
                rackFrozen = BooleanArray(RACKS)
                NativeEngine.resetRackCosts()
            }
        }
    }
}

@Composable
private fun RecordTab() {
    WindowCards {
        // 48 kHz either way; only the depth is a choice.
        WindowCard(stringResource(R.string.settings_recording)) {
            SwitchGrid(stringResource(R.string.settings_depth), stringArrayResource(R.array.settings_depth_choices).toList(), if (UiPrefs.recordBits == 24) 0 else 1) {
                UiPrefs.chooseRecordBits(if (it == 0) 24 else 16)
            }
        }
    }
}

@Composable
private fun NewSongSection() {
    val sig = UiPrefs.newSignature
    var pickingMachine by remember { mutableStateOf(false) }
    WindowCards {
        WindowCard(stringResource(R.string.settings_new_song)) {
            CountKnob(stringResource(R.string.settings_tempo), UiPrefs.newTempo.roundToInt(), 40..240, "%.0f".format(UiPrefs.newTempo), PanelAmber) {
                UiPrefs.chooseNewTempo(it.toFloat())
            }
            val sigIndex = SIGNATURES.indexOf(sig).coerceAtLeast(0)
            CountKnob(
                stringResource(R.string.settings_signature), sigIndex, 0 until SIGNATURES.size, "${sig.beats}/${sig.unit}",
                choices = SIGNATURES.map { "${it.beats}/${it.unit}" },
            ) { UiPrefs.chooseNewSignature(SIGNATURES[it]) }
            // What the one track of a new song holds, behind the same picker
            // the arranger's "+ track" uses: nineteen machines is not a switch.
            SwitchGrid(stringResource(R.string.settings_machine), listOf(UiPrefs.newMachine), -1) { pickingMachine = true }
        }
        // The scale a new track starts in: a Scale modifier is fitted to it,
        // so the keyboard and the roll agree with the song from the first
        // note. Root and scale are knobs that open as lists on a hold, which
        // retires the window of its own this used to open.
        WindowCard(stringResource(R.string.settings_new_scale)) {
            SwitchGrid(stringResource(R.string.settings_use), stringArrayResource(R.array.off_on).toList(), if (UiPrefs.newScaleOn) 1 else 0) { UiPrefs.chooseNewScale(it == 1) }
            if (UiPrefs.newScaleOn) {
                val key = UiPrefs.newScaleKey
                val scale = UiPrefs.newScaleIndex
                CountKnob(stringResource(R.string.settings_root), key, 0..11, Scales.rootName(key, scale), PanelAmber, choices = (0 until 12).map { Scales.rootName(it, scale) }) {
                    UiPrefs.chooseNewScale(true, it, scale)
                }
                CountKnob(stringResource(R.string.settings_scale), scale, 0 until Scales.names.size, Scales.names[scale], width = 132.dp, choices = Scales.names) {
                    UiPrefs.chooseNewScale(true, key, it)
                }
            }
        }
    }
    if (pickingMachine) {
        MachinePickerDialog(
            current = UiPrefs.newMachine,
            onDismiss = { pickingMachine = false },
        ) { type -> UiPrefs.chooseNewMachine(type); pickingMachine = false }
    }
}

/** Where an arriving note lands: the first card on the MIDI window's in tab. */
@Composable
internal fun MidiRoutingSection(trackNames: List<String>, more: @Composable () -> Unit = {}) {
    val routes = listOf(MidiHub.Routing.SelectedTrack, MidiHub.Routing.FixedTrack, MidiHub.Routing.ChannelToRack)
    WindowCard(stringResource(R.string.settings_arriving)) {
        // Follow: whichever track is open. Pinned: one track, even while
        // another is open. By channel: channel 1 to track 1, and so on.
        SwitchGrid(stringResource(R.string.settings_arriving_to), stringArrayResource(R.array.settings_arriving_choices).toList(), routes.indexOf(MidiHub.routing), columns = 1) {
            UiPrefs.chooseMidiRouting(routes[it])
        }
        if (MidiHub.routing == MidiHub.Routing.FixedTrack && trackNames.isNotEmpty()) {
            val at = MidiHub.fixedRack.coerceIn(0, trackNames.size - 1)
            CountKnob(stringResource(R.string.settings_arriving_track), at, 0 until trackNames.size, trackNames[at], PanelAmber, width = 96.dp, choices = trackNames) {
                UiPrefs.chooseMidiRouting(MidiHub.Routing.FixedTrack, it)
            }
        }
        more()
    }
}

// Section and Choice live in ui/Dialogs.kt: they are the shared vocabulary
// of every window here, not a settings idea.
