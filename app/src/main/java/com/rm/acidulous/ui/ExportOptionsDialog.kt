package com.rm.acidulous.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R
import androidx.annotation.StringRes

/**
 * What leaves the app, and as what.
 *
 * Two questions that are almost independent - what to render, and what to
 * write it as - plus the couple of numbers that only matter for audio. The
 * dialog hides what does not apply rather than greying it out: a bit depth
 * beside a MIDI file is not a disabled control, it is a meaningless one.
 */
enum class ExportWhat(@StringRes val label: Int) {
    Song(R.string.export_what_song),
    Scene(R.string.export_what_scene),
    Stems(R.string.export_what_stems),
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

@StringRes
private fun describeFormat(f: ExportFormat): Int? = when (f) {
    ExportFormat.Wav -> null
    ExportFormat.Aiff -> R.string.export_about_aiff
    ExportFormat.Flac -> R.string.export_about_flac
    // LAME's own licence asks that its use be acknowledged, and this is where
    // somebody choosing the format will see it.
    ExportFormat.Mp3 -> R.string.export_about_mp3
    ExportFormat.Aac -> R.string.export_about_aac
    ExportFormat.Midi -> R.string.export_about_midi
    ExportFormat.Bundle -> R.string.export_about_bundle
}

@Composable
private fun describeWhat(w: ExportWhat, sceneName: String): String = when (w) {
    ExportWhat.Song -> stringResource(R.string.export_about_song)
    ExportWhat.Scene -> stringResource(R.string.export_about_scene_named, sceneName)
    ExportWhat.Stems -> stringResource(R.string.export_about_stems)
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
        title = stringResource(R.string.export_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.export_confirm),
        onConfirm = { onExport(options) },
        spacing = 6.dp,
    ) {
        // Cards of switches, the arp window's shape, like every window with
        // settings in it (Dan, 2026-09-23).
        WindowCards {
            WindowCard(stringResource(R.string.export_file)) {
                SwitchGrid(
                    stringResource(R.string.export_format),
                    ExportFormat.entries.map { if (it == ExportFormat.Bundle) stringResource(R.string.export_format_bundle) else it.label }, ExportFormat.entries.indexOf(format), columns = 4) { i ->
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
                    SwitchGrid(stringResource(R.string.export_what), ExportWhat.entries.map { stringResource(it.label) }, ExportWhat.entries.indexOf(what), columns = 1) {
                        what = ExportWhat.entries[it]
                    }
                }
            }
            if (audio) {
                WindowCard(stringResource(R.string.export_sound)) {
                    // A bit depth is a thing a PCM format has. MP3 and AAC
                    // throw audio away instead - what they have is a budget,
                    // so that is what they are asked for.
                    if (format.lossy) {
                        val rates = listOf(128, 192, 256, 320)
                        SwitchGrid(stringResource(R.string.export_kbps), rates.map { "$it" }, rates.indexOf(rate), columns = 2) { rate = rates[it] }
                    } else {
                        val depths = if (format == ExportFormat.Flac) listOf(16, 24) else listOf(16, 24, 32)
                        SwitchGrid(stringResource(R.string.export_bits), depths.map { if (it == 32) stringResource(R.string.export_bits_float) else "$it" }, depths.indexOf(bits), columns = 1) {
                            bits = depths[it]
                        }
                    }
                    SwitchGrid(
                        stringResource(R.string.export_tail), stringArrayResource(R.array.export_tail_choices).toList(),
                        if (tail <= 0f) 0 else if (tail <= 2f) 1 else 2, columns = 1,
                    ) { tail = listOf(0f, 2f, 5f)[it] }
                    SwitchGrid(
                        stringResource(R.string.export_loudness),
                        listOf(stringResource(R.string.export_loudness_as_mixed), stringResource(R.string.export_loudness_lufs, NORMALISE_LUFS.toInt())), if (normalise) 1 else 0, columns = 1) {
                        normalise = it == 1
                    }
                }
            }
        }
        // The one line of what the switches cannot say. The format's own line
        // stays whatever else goes: LAME's licence asks that its use be
        // acknowledged, and this is where somebody choosing MP3 sees it.
        val notes = listOfNotNull(
            describeFormat(format)?.let { stringResource(it) },
            if (audio) describeWhat(what, sceneName) else null,
            if (audio && normalise) stringResource(R.string.export_about_normalise) else null,
            if (audio && tail <= 0f) stringResource(R.string.export_about_no_tail) else null,
        )
        Text(notes.joinToString(stringResource(R.string.export_about_separator)), color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
