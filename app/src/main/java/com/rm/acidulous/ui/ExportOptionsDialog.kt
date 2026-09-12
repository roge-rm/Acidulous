package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * What leaves the app, and as what.
 *
 * Two questions that are almost independent - what to render, and what to
 * write it as - plus the couple of numbers that only matter for audio. The
 * dialog hides what does not apply rather than greying it out: a bit depth
 * beside a MIDI file is not a disabled control, it is a meaningless one.
 */
enum class ExportWhat(val label: String) {
    Song("song"),
    Scene("this scene"),
    Stems("stems"),
}

/**
 * [engineFormat] indexes `acidulous::AudioFormat`; -1 means this one is not
 * rendered by the engine at all. [manyFiles] means it needs a folder rather
 * than a filename, because the system's create-a-document picker makes one
 * file and stems are not one file.
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mime: String,
    val engineFormat: Int,
    val audio: Boolean,
    /** No bit depth to choose: the format throws audio away instead. */
    val lossy: Boolean = false,
) {
    Wav("wav", ".wav", "audio/wav", 0, true),
    Aiff("aiff", ".aiff", "audio/aiff", 1, true),
    Flac("flac", ".flac", "audio/flac", 2, true),
    Mp3("mp3", ".mp3", "audio/mpeg", 3, true, lossy = true),
    Aac("m4a", ".m4a", "audio/mp4", -1, true, lossy = true),
    Midi("mid", ".mid", "audio/midi", -1, false),
    Bundle("bundle", ".zip", "application/zip", -1, false),
}

data class ExportOptions(
    val what: ExportWhat = ExportWhat.Song,
    val format: ExportFormat = ExportFormat.Wav,
    val bits: Int = 24,
    /** Kilobits a second, for the formats that throw audio away. */
    val rate: Int = 256,
    val tailSeconds: Float = 2f,
) {
    /** Several files, so the picker has to ask for a folder. */
    val manyFiles: Boolean get() = what == ExportWhat.Stems && format.audio
}

private fun describeFormat(f: ExportFormat): String = when (f) {
    ExportFormat.Wav -> "Uncompressed, and what every other program reads."
    ExportFormat.Aiff -> "Uncompressed, the same audio as a WAV with a different header on it."
    ExportFormat.Flac -> "Lossless and about half the size. Identical audio to the WAV, not merely close."
    // LAME's own licence asks that its use be acknowledged, and this is where
    // somebody choosing the format will see it.
    ExportFormat.Mp3 -> "Small, lossy, and playable by everything there is. Encoded by LAME."
    ExportFormat.Aac -> "Small, and lossy. For sending someone a listen rather than for working on."
    ExportFormat.Midi -> "The notes, not the sound: every track's clips at their real positions, for another program to play."
    ExportFormat.Bundle -> "The song and every sample it uses, in one file you can move to another device."
}

private fun describeWhat(w: ExportWhat): String = when (w) {
    ExportWhat.Song -> "Every scene in order, with its repeats, as one file."
    ExportWhat.Scene -> "Just the scene that is open, once through."
    ExportWhat.Stems -> "Each track as its own file - after its fader and pan, before the master bus - and the mix beside them."
}

@Composable
fun ExportOptionsDialog(
    sceneName: String,
    onDismiss: () -> Unit,
    onExport: (ExportOptions) -> Unit,
) {
    var what by rememberSaveable { mutableStateOf(ExportWhat.Song) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.Wav) }
    var bits by rememberSaveable { mutableStateOf(24) }
    var rate by rememberSaveable { mutableStateOf(256) }
    var tail by rememberSaveable { mutableStateOf(2f) }

    // The data formats describe the whole song by their nature: there is no
    // such thing as one track's worth of song bundle.
    val audio = format.audio
    val options = ExportOptions(if (audio) what else ExportWhat.Song, format, bits, rate, tail)

    PlainDialog(
        title = "Export",
        onDismiss = onDismiss,
        confirmLabel = "Export",
        onConfirm = { onExport(options) },
    ) {
        Section("format", describeFormat(format)) {
            for (f in ExportFormat.entries) {
                Choice(f.label, format == f) {
                    format = f
                    // FLAC has nowhere to put a float, so a 32-bit choice
                    // made under another format quietly becomes 24 rather
                    // than being silently ignored at the far end.
                    if (f == ExportFormat.Flac && bits == 32) bits = 24
                }
            }
        }

        if (audio) {
            Section("what", describeWhat(what) + if (what == ExportWhat.Scene) "  ($sceneName)" else "") {
                for (w in ExportWhat.entries) {
                    Choice(w.label, what == w) { what = w }
                }
            }
            // A bit depth is a thing a PCM format has. MP3 and AAC throw
            // audio away instead - what they have is a budget, so that is
            // what they are asked for.
            if (format.lossy) Section(
                "rate",
                when (rate) {
                    128 -> "Small, and it shows on cymbals and reverb tails. For a rough listen."
                    192 -> "The old default, and fine for most things on most speakers."
                    320 -> "As much as MP3 has to give. Hard to tell from the master on anything but headphones."
                    else -> "Transparent enough for almost anybody, at two thirds the size of the top rate."
                },
            ) {
                for (kbps in listOf(128, 192, 256, 320)) {
                    Choice("$kbps", rate == kbps) { rate = kbps }
                }
            }
            if (!format.lossy) Section(
                "depth",
                when (bits) {
                    16 -> "Half the size, and what a CD is. Fine for anything you are only going to listen to."
                    32 -> "Floating point: it cannot clip, so a loud master survives intact for mastering elsewhere."
                    else -> "The usual choice for a master - more room under the loudest part than 16 gives."
                },
            ) {
                Choice("16", bits == 16) { bits = 16 }
                Choice("24", bits == 24) { bits = 24 }
                if (format != ExportFormat.Flac) {
                    Choice("32 float", bits == 32) { bits = 32 }
                }
            }
            Section(
                "tail",
                if (tail <= 0f) "Stops on the last note, cutting any reverb with it."
                else "Keeps rendering for %.0f seconds after the end, so reverb and delay finish.".format(tail),
            ) {
                Choice("none", tail <= 0f) { tail = 0f }
                Choice("2 s", tail > 0f && tail <= 2f) { tail = 2f }
                Choice("5 s", tail > 2f) { tail = 5f }
            }
        }
    }
}
