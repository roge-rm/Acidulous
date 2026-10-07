package com.rm.acidulous.ui

import com.rm.acidulous.util.format

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
import com.rm.acidulous.res.*

/**
 * Settings that belong to the user and the device, not the song: how it
 * looks, how hard it works, and what a new song starts as.
 *
 * Split into tabs using the same chips as the machine panels. It's its own
 * Dialog instead of an AlertDialog because Material caps dialogs at 560dp
 * and pads both edges. This one takes the screen width minus a margin, up
 * to a tablet sized limit.
 */
@Composable
fun SettingsDialog(trackNames: List<String> = emptyList(), onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    // The same shell as the machine picker: the body is as tall as the
    // tallest tab so the window and the Done button don't move between tabs.
    TabbedDialog(
        title = stringResource(Res.string.settings_title),
        selected = tab,
        pages = listOf(
            { DisplayTab() },
            { AudioTab(trackNames) },
            { RecordTab() },
            { NewSongSection() },
        ),
        onDismiss = onDismiss,
        spacing = 6.dp,
        pageNames = stringArrayResource(Res.array.settings_tabs).toList(),
        onSelectPage = { tab = it },
    )
}

// There's no midi tab. Where an arriving note lands is set in the MIDI
// window's `in` tab instead.

@Composable
private fun DisplayTab() {
    var showKeys by remember { mutableStateOf(false) }
    // Cards of switches, like every settings window.
    WindowCards {
        WindowCard(stringResource(Res.string.settings_screen)) {
            SwitchGrid(
                stringResource(Res.string.settings_theme), stringArrayResource(Res.array.settings_theme_choices).toList(),
                when (UiPrefs.theme) { ThemeMode.Auto -> 0; ThemeMode.Light -> 1; ThemeMode.Dark -> 2; ThemeMode.HighContrast -> 3 },
                columns = 2,
            ) { UiPrefs.chooseTheme(listOf(ThemeMode.Auto, ThemeMode.Light, ThemeMode.Dark, ThemeMode.HighContrast)[it]) }
            // Each language named in itself, so it can be found from any other,
            // in a menu: the list will outgrow a row of switches.
            if (com.rm.acidulous.AppHost.current.canChooseLanguage) {
                SwitchMenu(
                    stringResource(Res.string.settings_language), stringArrayResource(Res.array.settings_language_choices).toList(),
                    UiPrefs.language.ordinal,
                ) { UiPrefs.chooseLanguage(UiPrefs.Language.entries[it]) }
            }
            // Changes the size of this window too, as you tap it.
            SwitchGrid(stringResource(Res.string.settings_size), stringArrayResource(Res.array.settings_size_choices).toList(), UiScaleSteps.indexOf(UiPrefs.uiScale), columns = 2) {
                UiPrefs.chooseUiScale(UiScaleSteps[it])
            }
            // On a computer: how many pixels a dp is. The phone knows its own.
            if (com.rm.acidulous.AppHost.current.onDesktop) {
                SwitchGrid(
                    stringResource(Res.string.settings_screen_scale), stringArrayResource(Res.array.settings_screen_scale_choices).toList(),
                    ScreenScaleSteps.indexOf(UiPrefs.screenScale).coerceAtLeast(0), columns = 2,
                ) { UiPrefs.chooseScreenScale(ScreenScaleSteps[it]) }
            }
            // Only where the app can keep the screen on; not on desktop.
            if (com.rm.acidulous.AppHost.current.canKeepScreenOn) {
                SwitchGrid(stringResource(Res.string.settings_while_playing), stringArrayResource(Res.array.settings_while_playing_choices).toList(), if (UiPrefs.keepAwake) 0 else 1) {
                    UiPrefs.chooseKeepAwake(it == 0)
                }
            }
            // Show the diagnostics numbers everywhere they appear. Debug
            // builds only: a release has none.
            if (com.rm.acidulous.AppHost.current.debugBuild) SwitchGrid(stringResource(Res.string.settings_diagnostics), stringArrayResource(Res.array.settings_diagnostics_choices).toList(), if (UiPrefs.showDiagnostics) 0 else 1) {
                UiPrefs.chooseDiagnostics(it == 0)
            }
            // Its own window, since twenty actions would make every page this tall.
            SwitchGrid(stringResource(Res.string.settings_keyboard), listOf(stringResource(Res.string.settings_keys_button)), -1) { showKeys = true }
        }
    }
    if (showKeys) KeysDialog { showKeys = false }
    // Only shown when the screen can't give the size asked for. See
    // ui/UiScale.kt for the cap.
    val applied = LocalUiScale.current
    if (applied < UiPrefs.uiScale - 0.001f) {
        Text(stringResource(Res.string.settings_size_capped, applied), color = Acid.colors.textDim, fontSize = 11.sp)
    }
}

/** Mirrors the engine's own two, which aren't exported to Kotlin. */
private const val RACKS = 16
private const val BLOCK_FRAMES = 64

@Composable
private fun AudioTab(trackNames: List<String>) {
    // The actual numbers matter here, since only the device can say if it
    // keeps up with a given buffer.
    val burst = NativeEngine.framesPerBurst.coerceAtLeast(1)
    val frames = NativeEngine.bufferFrames
    val ms = frames * 1000f / NativeEngine.sampleRate.coerceAtLeast(1)
    val drops = NativeEngine.xRunCount
    // The three settings in one card; everything below is a reading.
    WindowCards {
        // The output device, where there's a choice: desktop, and a browser that
        // lists its outputs. On a phone the system routes it.
        val outputs = androidx.compose.runtime.remember { com.rm.acidulous.AppHost.current.audioOutputs() }
        if (outputs.isNotEmpty()) {
            WindowCard(stringResource(Res.string.settings_output)) {
                val choices = listOf(0 to stringResource(Res.string.settings_output_default)) + outputs
                DeviceList(
                    stringResource(Res.string.settings_output_device), choices.map { it.second },
                    choices.indexOfFirst { it.first == UiPrefs.outputDevice }.coerceAtLeast(0),
                ) { UiPrefs.chooseOutputDevice(choices[it].first) }
            }
        }
        WindowCard(stringResource(Res.string.settings_engine)) {
            SwitchGrid(stringResource(Res.string.settings_buffer), UiPrefs.Buffer.entries.map { stringResource(it.label) }, UiPrefs.buffer.ordinal, columns = 1) {
                UiPrefs.chooseBuffer(UiPrefs.Buffer.entries[it])
            }
            val limits = listOf(4, 8, 16, 32, 48, 64, 0)
            val li = limits.indexOf(UiPrefs.voiceLimit).coerceAtLeast(0)
            val all = stringResource(Res.string.settings_voices_all)
            CountKnob(
                stringResource(Res.string.settings_voices), li, 0 until limits.size, if (UiPrefs.voiceLimit == 0) all else "${UiPrefs.voiceLimit}",
                choices = limits.map { if (it == 0) all else "$it" },
            ) { UiPrefs.chooseVoiceLimit(limits[it]) }
            // Only where there's more than one core to give: not in a browser.
            val most = NativeEngine.coresMax
            if (most > 1) {
                val auto = stringResource(Res.string.settings_cores_auto)
                val ci = UiPrefs.cores.coerceIn(0, most)
                CountKnob(
                    stringResource(Res.string.settings_cores), ci, 0..most,
                    if (ci == 0) "$auto ${NativeEngine.coresInUse}" else "$ci",
                    choices = listOf(auto) + (1..most).map { "$it" },
                ) { UiPrefs.chooseCores(it) }
            }
            // Auto picks between the other two, so while it's on they show which
            // one it chose.
            SwitchGrid(
                stringResource(Res.string.settings_quality), stringArrayResource(Res.array.settings_quality_choices).toList(),
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
    // Where the time actually went: the worst case and which part was slow.
    // Reading clears both peaks, and this is a second reader after the
    // diagnostics line. That's deliberate: opening this page zeroes them, so
    // it shows the worst since you opened it.
    var worst by remember { mutableStateOf(0) }
    var phases by remember { mutableStateOf(IntArray(NativeEngine.Phase.entries.size)) }
    var racks by remember { mutableStateOf(IntArray(RACKS)) }
    // Whether each track was playing frozen audio when it set that peak.
    var rackFrozen by remember { mutableStateOf(BooleanArray(RACKS)) }
    var interrupted by remember { mutableStateOf(0f) }
    var coresNow by remember { mutableStateOf(1) }
    // The peak since this was opened.
    remember { NativeEngine.readWorkerWaitPeakUs() }
    var waitPeak by remember { mutableStateOf(0) }
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
                // The flag and the peak are still read (the snowflake needs the
                // peak) but the percentile is what's shown, and it's just assigned.
                val frozen = NativeEngine.worstRackWasFrozen(r)
                if (NativeEngine.worstRackUs(r) > 0) wasFrozen[r] = frozen
                byRack[r] = NativeEngine.rackPercentileUs(r)
            }
            racks = byRack
            rackFrozen = wasFrozen
            interrupted = NativeEngine.interruptedPercent
            coresNow = NativeEngine.coresInUse
            waitPeak = maxOf(waitPeak, NativeEngine.readWorkerWaitPeakUs())
            delay(120)
        }
    }
    // Against one 64-frame block's budget, not the whole callback's. The worst
    // block is one render, so the callback's budget overstated the headroom
    // by a factor of three.
    val blockBudgetMs = 1000f * BLOCK_FRAMES / NativeEngine.sampleRate.coerceAtLeast(1)
    // The readings, in a card under the settings. Every figure comes from a
    // block that ran without being interrupted, since a thread taken off its
    // core mid-block adds that whole gap to what it was timing. The percentage
    // is how many blocks were thrown away for that, which tells DSP load from
    // scheduler trouble. A browser has no peaks (AppHost.timesAudioPrecisely),
    // so it shows the buffer and a line saying why.
    val precise = com.rm.acidulous.AppHost.current.timesAudioPrecisely
    val lines = if (!precise) mutableListOf(
        "buffer  %d frames · %.0f ms · burst %d".format(frames, ms, burst),
        "peak timings  not measured in a browser, whose audio thread has no clock fine enough to time a block",
    ) else mutableListOf(
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
    // Which tracks cost the most, only those with a machine, sorted by cost,
    // so you know what to freeze.
    //
    // Uses the worst block in a hundred from the engine's histogram, not the
    // single worst, which was set by one unlucky block and varied too much
    // between runs to compare builds. `worst block` above stays a true peak,
    // since a deadline is missed by one block.
    val named = (0 until RACKS)
        .filter { it < trackNames.size && racks[it] > 0 }
        .sortedByDescending { racks[it] }
    if (precise && named.isNotEmpty()) {
        // A snowflake means the cost was paid while the track was playing
        // frozen audio, which should be near zero, so the freeze isn't working.
        lines += "worst track  " + named.take(6).joinToString("  ") {
            val mark = if (rackFrozen[it]) " ❄" else ""
            "${trackNames[it]}$mark %.2f".format(racks[it] / 1000f)
        }
    }

    // No control for the scheduler hint: the device either takes hints or
    // it doesn't.
    lines += "scheduler hint  " + when (NativeEngine.hintState) {
        0 -> "not available on this device"
        1 -> "waiting for the audio thread"
        2 -> "the audio thread didn't register"
        3 -> "this device refused it"
        else -> "on"
    }
    // On a phone with fast and slow cores, the sound is made on the fast
    // ones; a phone with one kind of core, or a computer, doesn't say.
    NativeEngine.fastCores.takeIf { it > 0 }?.let { lines += "audio thread  on the $it fast cores" }
    if (NativeEngine.coresMax > 1) {
        lines += "tracks  on %d core%s".format(coresNow, if (coresNow == 1) "" else "s") +
            if (coresNow > 1) " · longest wait for another core %.2f ms".format(waitPeak / 1000f) else ""
    }
    if (UiPrefs.showDiagnostics) WindowCards {
        WindowCard("readings · since opened") {
            // Text in a card of controls: a fixed width column when the cards
            // are side by side, so the lines aren't squeezed.
            //
            // Fixed size too, so the window doesn't resize as the numbers
            // change: full width and [ReadingLines] tall when wide. Upright the
            // window is the screen's width anyway.
            if (LocalDialogWide.current) {
                Text(
                    lines.joinToString("\n"), color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
                    minLines = ReadingLines, maxLines = ReadingLines,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.cardLineFull(),
                )
            } else androidx.compose.foundation.layout.Column(
                Modifier.cardLine(),
            ) {
                for (l in lines) {
                    Text(l, color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
                }
            }
            if (precise) SwitchGrid("peaks", listOf("reset"), -1) {
                worst = 0
                phases = IntArray(NativeEngine.Phase.entries.size)
                racks = IntArray(RACKS)
                rackFrozen = BooleanArray(RACKS)
                NativeEngine.resetRackCosts()
            }
        }
        // What a game controller sends: its buttons' codes and its axes, live,
        // so its buttons can be given jobs that fit it.
        WindowCard("controller") {
            val pad = buildList {
                if (Pad.device.isEmpty()) {
                    add("press a button or move a stick")
                } else {
                    add(Pad.device)
                    add("button  " + Pad.lastButton.ifEmpty { "-" })
                    // Two to a line, so a whole controller's buttons fit.
                    if (Pad.seen.isNotEmpty()) add("seen")
                    for (pair in Pad.seen.chunked(2)) add("  " + pair.joinToString("   "))
                    for (a in Pad.axes) add("%-9s %+.2f  rests within %.2f".format(a.name, a.value, a.flat))
                }
            }
            androidx.compose.foundation.layout.Column(Modifier.cardLine()) {
                for (l in pad) {
                    Text(
                        l, color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordTab() {
    WindowCards {
        WindowCard(stringResource(Res.string.settings_recording)) {
            // Always 48 kHz; only the bit depth is a choice, for recordings and exports.
            SwitchGrid(stringResource(Res.string.settings_depth), stringArrayResource(Res.array.settings_depth_choices).toList(), if (UiPrefs.recordBits == 24) 0 else 1) {
                UiPrefs.chooseRecordBits(if (it == 0) 24 else 16)
            }
            SwitchGrid(stringResource(Res.string.settings_count_in), listOf(stringResource(Res.string.none), "1", "2", "3", "4"), UiPrefs.countInBars, columns = 5) { UiPrefs.chooseCountInBars(it) }
            // Quantising what's played onto the clip's grid, fully or partly.
            SwitchGrid(stringResource(Res.string.settings_quantise), stringArrayResource(Res.array.off_on).toList(), if (UiPrefs.recordQuantise) 1 else 0) {
                UiPrefs.chooseRecordQuantise(on = it == 1)
            }
            if (UiPrefs.recordQuantise) {
                CountKnob(stringResource(Res.string.settings_amount), UiPrefs.recordStrength, 0..100, "${UiPrefs.recordStrength}%") {
                    UiPrefs.chooseRecordQuantise(strength = it)
                }
            }
            // What a take does to the notes there, when it starts, and when it stops.
            SwitchGrid(stringResource(Res.string.settings_take), stringArrayResource(Res.array.settings_take_choices).toList(), if (UiPrefs.recordReplace) 1 else 0) {
                UiPrefs.chooseRecordTake(replace = it == 1)
            }
            SwitchGrid(stringResource(Res.string.settings_start), stringArrayResource(Res.array.settings_start_choices).toList(), if (UiPrefs.recordOnNote) 1 else 0) {
                UiPrefs.chooseRecordTake(onNote = it == 1)
            }
            SwitchGrid(stringResource(Res.string.settings_passes), stringArrayResource(Res.array.settings_passes_choices).toList(), if (UiPrefs.recordOnce) 1 else 0) {
                UiPrefs.chooseRecordTake(once = it == 1)
            }
        }
    }
}

@Composable
private fun NewSongSection() {
    val sig = UiPrefs.newSignature
    var pickingMachine by remember { mutableStateOf(false) }
    WindowCards {
        WindowCard(stringResource(Res.string.settings_new_song)) {
            CountKnob(stringResource(Res.string.settings_tempo), UiPrefs.newTempo.roundToInt(), 40..240, "%.0f".format(UiPrefs.newTempo), PanelAmber) {
                UiPrefs.chooseNewTempo(it.toFloat())
            }
            val sigIndex = SIGNATURES.indexOf(sig).coerceAtLeast(0)
            CountKnob(
                stringResource(Res.string.settings_signature), sigIndex, 0 until SIGNATURES.size, "${sig.beats}/${sig.unit}",
                choices = SIGNATURES.map { "${it.beats}/${it.unit}" },
            ) { UiPrefs.chooseNewSignature(SIGNATURES[it]) }
            // The machine on a new song's first track, picked with the same
            // picker as the arranger's "+ track".
            SwitchGrid(stringResource(Res.string.settings_machine), listOf(UiPrefs.newMachine), -1) { pickingMachine = true }
            // The scale a new track starts in, in the same card so a lone
            // switch doesn't get a row of its own. A Scale modifier is set to
            // it, so the keyboard and roll match the song from the first note.
            // Root and scale are knobs that open as lists on a hold.
            SwitchGrid(stringResource(Res.string.settings_track_scale), stringArrayResource(Res.array.off_on).toList(), if (UiPrefs.newScaleOn) 1 else 0) { UiPrefs.chooseNewScale(it == 1) }
            if (UiPrefs.newScaleOn) {
                val key = UiPrefs.newScaleKey
                val scale = UiPrefs.newScaleIndex
                CountKnob(stringResource(Res.string.settings_root), key, 0..11, Scales.rootName(key, scale), PanelAmber, choices = (0 until 12).map { Scales.rootName(it, scale) }) {
                    UiPrefs.chooseNewScale(true, it, scale)
                }
                CountKnob(stringResource(Res.string.settings_scale), scale, 0 until Scales.names.size, Scales.names[scale], width = 132.dp, choices = Scales.names) {
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
    WindowCard(stringResource(Res.string.settings_arriving)) {
        // Follow: whichever track is open. Pinned: one track, even while
        // another is open. By channel: channel 1 to track 1, and so on.
        SwitchGrid(stringResource(Res.string.settings_arriving_to), stringArrayResource(Res.array.settings_arriving_choices).toList(), routes.indexOf(MidiHub.routing), columns = 1) {
            UiPrefs.chooseMidiRouting(routes[it])
        }
        if (MidiHub.routing == MidiHub.Routing.FixedTrack && trackNames.isNotEmpty()) {
            val at = MidiHub.fixedRack.coerceIn(0, trackNames.size - 1)
            CountKnob(stringResource(Res.string.settings_arriving_track), at, 0 until trackNames.size, trackNames[at], PanelAmber, width = 96.dp, choices = trackNames) {
                UiPrefs.chooseMidiRouting(MidiHub.Routing.FixedTrack, it)
            }
        }
        more()
    }
}

// Section and Choice are shared by every window and live in ui/Dialogs.kt.

/** The readings' height in a wide window: the most lines they wrap to at a card's width. */
private const val ReadingLines = 7
