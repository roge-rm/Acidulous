package com.rm.acidulous.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
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
    ExportWhat.Stems -> "One file per track (after the fader), plus the mix."
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
        spacing = 6.dp,
    ) {
        // Cards of switches, the arp window's shape, like every window with
        // settings in it (Dan, 2026-09-23).
        WindowCards {
            WindowCard("file") {
                SwitchGrid("format", ExportFormat.entries.map { it.label }, ExportFormat.entries.indexOf(format), columns = 4) { i ->
                    val f = ExportFormat.entries[i]
                    format = f
                    // FLAC has nowhere to put a float, so a 32-bit choice
                    // made under another format quietly becomes 24 rather
                    // than being silently ignored at the far end.
                    if (f == ExportFormat.Flac && bits == 32) bits = 24
                }
                // The data formats describe the whole song by their nature:
                // there is no such thing as one track's worth of bundle.
                if (audio) {
                    SwitchGrid("what", ExportWhat.entries.map { it.label }, ExportWhat.entries.indexOf(what), columns = 1) {
                        what = ExportWhat.entries[it]
                    }
                }
            }
            if (audio) {
                WindowCard("sound") {
                    // A bit depth is a thing a PCM format has. MP3 and AAC
                    // throw audio away instead - what they have is a budget,
                    // so that is what they are asked for.
                    if (format.lossy) {
                        val rates = listOf(128, 192, 256, 320)
                        SwitchGrid("kbps", rates.map { "$it" }, rates.indexOf(rate), columns = 2) { rate = rates[it] }
                    } else {
                        val depths = if (format == ExportFormat.Flac) listOf(16, 24) else listOf(16, 24, 32)
                        SwitchGrid("bits", depths.map { if (it == 32) "32 float" else "$it" }, depths.indexOf(bits), columns = 1) {
                            bits = depths[it]
                        }
                    }
                    SwitchGrid(
                        "tail", listOf("none", "2 s", "5 s"),
                        if (tail <= 0f) 0 else if (tail <= 2f) 1 else 2, columns = 1,
                    ) { tail = listOf(0f, 2f, 5f)[it] }
                    SwitchGrid("loudness", listOf("as mixed", "${NORMALISE_LUFS.toInt()} LUFS"), if (normalise) 1 else 0, columns = 1) {
                        normalise = it == 1
                    }
                }
            }
        }
        // The one line of what the switches cannot say. The format's own line
        // stays whatever else goes: LAME's licence asks that its use be
        // acknowledged, and this is where somebody choosing MP3 sees it.
        val notes = listOfNotNull(
            describeFormat(format).ifEmpty { null },
            if (audio) describeWhat(what) + if (what == ExportWhat.Scene) " ($sceneName)" else "" else null,
            if (audio && normalise) "Renders twice: once to measure, once to write." else null,
            if (audio && tail <= 0f) "Cuts any reverb on the last note." else null,
        )
        Text(notes.joinToString(" "), color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
