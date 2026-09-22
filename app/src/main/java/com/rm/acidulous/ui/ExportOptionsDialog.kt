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

/** What a normalised export aims for: where the streaming services turn songs to. */
const val NORMALISE_LUFS = -14f

data class ExportOptions(
    val what: ExportWhat = ExportWhat.Song,
    val format: ExportFormat = ExportFormat.Wav,
    val bits: Int = 24,
    /** Kilobits a second, for the formats that throw audio away. */
    val rate: Int = 256,
    val tailSeconds: Float = 2f,
    /** Measured first and turned to [NORMALISE_LUFS], never past -1 dBTP. */
    val normalise: Boolean = false,
) {
    /** Several files, so the picker has to ask for a folder. */
    val manyFiles: Boolean get() = what == ExportWhat.Stems && format.audio
}

private fun describeFormat(f: ExportFormat): String = when (f) {
    ExportFormat.Wav -> ""
    ExportFormat.Aiff -> "A WAV with a different header."
    ExportFormat.Flac -> "Lossless, about half the size."
    // LAME's own licence asks that its use be acknowledged, and this is where
    // somebody choosing the format will see it.
    ExportFormat.Mp3 -> "Lossy. Encoded by LAME."
    ExportFormat.Aac -> "Lossy."
    ExportFormat.Midi -> "The notes, not the sound."
    ExportFormat.Bundle -> "The song and every sample it uses, in one file."
}

private fun describeWhat(w: ExportWhat): String = when (w) {
    ExportWhat.Song -> "Every scene in order, with its repeats."
    ExportWhat.Scene -> "The open scene, once through."
    ExportWhat.Stems -> "One file per track, post-fader, plus the mix."
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
    var normalise by rememberSaveable { mutableStateOf(false) }

    // The data formats describe the whole song by their nature: there is no
    // such thing as one track's worth of song bundle.
    val audio = format.audio
    val options = ExportOptions(if (audio) what else ExportWhat.Song, format, bits, rate, tail, audio && normalise)

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
                    128 -> "Rough: it shows on cymbals and reverb tails."
                    320 -> "As much as MP3 has to give."
                    else -> ""
                },
            ) {
                for (kbps in listOf(128, 192, 256, 320)) {
                    Choice("$kbps", rate == kbps) { rate = kbps }
                }
            }
            if (!format.lossy) Section(
                "depth",
                when (bits) {
                    16 -> "Half the size. For listening, not for mastering."
                    32 -> "Floating point: it cannot clip."
                    else -> ""
                },
            ) {
                Choice("16", bits == 16) { bits = 16 }
                Choice("24", bits == 24) { bits = 24 }
                if (format != ExportFormat.Flac) {
                    Choice("32 float", bits == 32) { bits = 32 }
                }
            }
            Section(
                "render tail",
                if (tail <= 0f) "Cuts any reverb on the last note." else "",
            ) {
                Choice("none", tail <= 0f) { tail = 0f }
                Choice("2 s", tail > 0f && tail <= 2f) { tail = 2f }
                Choice("5 s", tail > 2f) { tail = 5f }
            }
            if (audio) Section("loudness", if (normalise) "Renders twice: once to measure, once to write." else "") {
                Choice("as mixed", !normalise) { normalise = false }
                Choice("${NORMALISE_LUFS.toInt()} LUFS", normalise) { normalise = true }
            }
        }
    }
}
