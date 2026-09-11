package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.Signature
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.ThemeMode

/**
 * Everything that belongs to the person and the device rather than to the
 * song: how it looks, how hard it is allowed to work, and what a new song
 * starts as.
 *
 * One dialog of titled sections in the machine panels' vocabulary - a
 * heading, a row of choices, and a line underneath saying what the choice
 * means in plain words. A new setting is a new [Section], not a new dialog.
 */
@Composable
fun SettingsDialog(trackNames: List<String>, onDismiss: () -> Unit) {
    val c = Acid.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScrollWithBar(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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

                // The numbers are the point here: a buffer is a promise about
                // how late the engine may be, and only this phone knows
                // whether it can keep it.
                val burst = NativeEngine.framesPerBurst.coerceAtLeast(1)
                val frames = NativeEngine.bufferFrames
                val ms = frames * 1000f / NativeEngine.sampleRate.coerceAtLeast(1)
                Section(
                    "audio buffer",
                    "%d frames, about %.0f ms · burst %d · %d dropouts so far. Tighter is more responsive; safer survives a phone that is busy elsewhere."
                        .format(frames, ms, burst, NativeEngine.xRunCount),
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

                Section(
                    "recording",
                    if (UiPrefs.recordBits == 24) "Recorded samples and exported songs are 24-bit, 48 kHz."
                    else "16-bit, 48 kHz: half the file, and quiet detail a little coarser.",
                ) {
                    Choice("24-bit", UiPrefs.recordBits == 24) { UiPrefs.chooseRecordBits(24) }
                    Choice("16-bit", UiPrefs.recordBits == 16) { UiPrefs.chooseRecordBits(16) }
                }

                Section(
                    "screen",
                    if (UiPrefs.keepAwake) "The screen stays on while the transport is running."
                    else "The screen sleeps on its own, playing or not.",
                ) {
                    Choice("stay awake", UiPrefs.keepAwake) { UiPrefs.chooseKeepAwake(true) }
                    Choice("let it sleep", !UiPrefs.keepAwake) { UiPrefs.chooseKeepAwake(false) }
                }

                NewSongSection()

                MidiSection(trackNames)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun NewSongSection() {
    val sig = UiPrefs.newSignature
    Section(
        "new songs",
        "%.0f bpm, %d/%d%s.".format(
            UiPrefs.newTempo, sig.beats, sig.unit,
            if (UiPrefs.newScaleOn) {
                ", in ${Scales.keyNames[UiPrefs.newScaleKey]} ${Scales.names[UiPrefs.newScaleIndex]}"
            } else {
                ", no scale"
            },
        ),
    ) {
        Choice("−", false) { UiPrefs.chooseNewTempo(UiPrefs.newTempo - 1f) }
        Choice("%.0f".format(UiPrefs.newTempo), true) { UiPrefs.chooseNewTempo(120f) }
        Choice("+", false) { UiPrefs.chooseNewTempo(UiPrefs.newTempo + 1f) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SIGNATURES.take(5)) {
                Choice("${s.beats}/${s.unit}", UiPrefs.newSignature == s) { UiPrefs.chooseNewSignature(s) }
            }
        }
    }
    // The scale a new track starts in: a Scale eventor is fitted to it, so
    // the keyboard and the roll agree with the song from the first note.
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice("no scale", !UiPrefs.newScaleOn) { UiPrefs.chooseNewScale(false) }
        Choice(
            if (UiPrefs.newScaleOn) {
                "${Scales.keyNames[UiPrefs.newScaleKey]} ${Scales.names[UiPrefs.newScaleIndex]}"
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

@Composable
private fun MidiSection(trackNames: List<String>) {
    Section(
        "midi in",
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
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Scales.keyNames.forEachIndexed { i, name -> Choice(name, k == i) { k = i } }
                }
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScrollWithBar(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Scales.names.forEachIndexed { i, name ->
                        Choice(name, scale == i, Modifier.fillMaxWidth()) { onPick(k, i) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, note: String, content: @Composable () -> Unit) {
    val c = Acid.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        Text(note, color = c.textDim, fontSize = 11.sp)
    }
}

/** One of a set: filled when it is the one in force. */
@Composable
private fun Choice(label: String, on: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent else c.control)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) c.onAccent else c.textMid, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
