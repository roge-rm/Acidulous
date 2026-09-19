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
fun SettingsDialog(trackNames: List<String>, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    // The same shell as the machine picker, and for the same reason: the
    // body is as tall as the tallest tab, so the window does not resize and
    // the Done button does not move when you change tab.
    TabbedDialog(
        title = "Settings",
        selected = tab,
        pages = listOf(
            { DisplayTab() },
            { AudioTab() },
            { RecordTab() },
            { NewSongSection() },
            { MidiRoutingSection(trackNames) },
        ),
        onDismiss = onDismiss,
        spacing = 16.dp,
        chips = { SectionChips(TABS, tab) { tab = it } },
    )
}

private val TABS = listOf("display", "audio", "record", "songs", "midi")

@Composable
private fun DisplayTab() {
    Section(
        "appearance",
        when (UiPrefs.theme) {
            ThemeMode.Auto -> "Follows the phone's own light and dark setting."
            ThemeMode.Light -> "Always light, whatever the phone is set to."
            ThemeMode.Dark -> "Always dark, whatever the phone is set to."
        },
    ) {
        Choice("auto", UiPrefs.theme == ThemeMode.Auto) { UiPrefs.chooseTheme(ThemeMode.Auto) }
        Choice("light", UiPrefs.theme == ThemeMode.Light) { UiPrefs.chooseTheme(ThemeMode.Light) }
        Choice("dark", UiPrefs.theme == ThemeMode.Dark) { UiPrefs.chooseTheme(ThemeMode.Dark) }
    }
    Section(
        "screen",
        if (UiPrefs.keepAwake) "The screen stays on while the transport is running."
        else "The screen sleeps on its own, playing or not.",
    ) {
        Choice("stay awake", UiPrefs.keepAwake) { UiPrefs.chooseKeepAwake(true) }
        Choice("let it sleep", !UiPrefs.keepAwake) { UiPrefs.chooseKeepAwake(false) }
    }
}

@Composable
private fun AudioTab() {
    // The numbers are the point here: a buffer is a promise about how late
    // the engine may be, and only this phone knows whether it can keep it.
    val burst = NativeEngine.framesPerBurst.coerceAtLeast(1)
    val frames = NativeEngine.bufferFrames
    val ms = frames * 1000f / NativeEngine.sampleRate.coerceAtLeast(1)
    val drops = NativeEngine.xRunCount
    Section(
        "buffer",
        "%d frames, about %.0f ms · burst %d · %d dropout%s so far. Tighter is more responsive; safer survives a phone that is busy elsewhere."
            .format(frames, ms, burst, drops, if (drops == 1L) "" else "s"),
    ) {
        for (b in UiPrefs.Buffer.entries) {
            Choice(b.label, UiPrefs.buffer == b) { UiPrefs.chooseBuffer(b) }
        }
    }

    Section(
        "voices",
        if (UiPrefs.voiceLimit == 0) "Every machine plays as many notes as it was built for."
        else "At most ${UiPrefs.voiceLimit} notes held per track; the oldest is released to make room. " +
            "A machine with fewer voices of its own than that is unaffected.",
    ) {
        for (n in listOf(4, 8, 16, 32, 48, 64)) {
            Choice("$n", UiPrefs.voiceLimit == n) { UiPrefs.chooseVoiceLimit(n) }
        }
        Choice("all", UiPrefs.voiceLimit == 0) { UiPrefs.chooseVoiceLimit(0) }
    }

    Section(
        "effect quality",
        if (UiPrefs.fullQuality) "Full: the master reverb runs eight combs a side, and distortion oversamples."
        else "Lean: half the reverb, no oversampling. Cheaper, and a little plainer.",
    ) {
        Choice("full", UiPrefs.fullQuality) { UiPrefs.chooseQuality(true) }
        Choice("lean", !UiPrefs.fullQuality) { UiPrefs.chooseQuality(false) }
    }
}

@Composable
private fun RecordTab() {
    Section(
        "format",
        if (UiPrefs.recordBits == 24) "Recorded samples and exported songs are 24-bit, 48 kHz."
        else "16-bit, 48 kHz: half the file, and quiet detail a little coarser.",
    ) {
        Choice("24-bit", UiPrefs.recordBits == 24) { UiPrefs.chooseRecordBits(24) }
        Choice("16-bit", UiPrefs.recordBits == 16) { UiPrefs.chooseRecordBits(16) }
    }
}

@Composable
private fun NewSongSection() {
    val sig = UiPrefs.newSignature
    SliderSection(
        "tempo", "%.0f bpm".format(UiPrefs.newTempo), "",
        UiPrefs.newTempo, 40f..240f,
    ) { UiPrefs.chooseNewTempo(it) }
    // A slider rather than five chips: it fits all eight signatures where
    // the row fitted five, in the same height.
    val sigIndex = SIGNATURES.indexOf(UiPrefs.newSignature).coerceAtLeast(0)
    SliderSection(
        "signature", "${sig.beats}/${sig.unit}", "",
        sigIndex.toFloat(), 0f..(SIGNATURES.size - 1).toFloat(), SIGNATURES.size - 2,
    ) { v -> UiPrefs.chooseNewSignature(SIGNATURES[v.toInt().coerceIn(0, SIGNATURES.size - 1)]) }
    // The scale a new track starts in: a Scale eventor is fitted to it, so
    // the keyboard and the roll agree with the song from the first note.
    var picking by remember { mutableStateOf(false) }
    Section(
        "scale",
        if (UiPrefs.newScaleOn) {
            "Each new track is fitted with a Scale eventor in " +
                "${Scales.rootName(UiPrefs.newScaleKey, UiPrefs.newScaleIndex)} ${Scales.names[UiPrefs.newScaleIndex]}, so the keys and the roll agree from the first note."
        } else {
            "New tracks start chromatic, with no Scale eventor fitted."
        },
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
        "routing",
        when (MidiHub.routing) {
            MidiHub.Routing.SelectedTrack -> "Notes play whichever track is open - what you want while writing."
            MidiHub.Routing.FixedTrack ->
                "Notes always play ${trackNames.getOrNull(MidiHub.fixedRack) ?: "track ${MidiHub.fixedRack + 1}"}, whatever is on screen."
            MidiHub.Routing.ChannelToRack -> "MIDI channel 1 plays track 1, channel 2 track 2, and so on."
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
        Section("pinned to", "Devices play this track even while another is open.") {
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
