package com.rm.acidulous.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * What to export and in which format, plus the few settings that only matter
 * for audio. The dialog hides options that don't apply instead of greying them
 * out.
 */
enum class ExportWhat(val label: StringResource) {
    Song(Res.string.export_what_song),
    Scene(Res.string.export_what_scene),
    Stems(Res.string.export_what_stems),
}

/**
 * [engineFormat] indexes acidulous::AudioFormat, -1 means the engine doesn't
 * render this one. [manyFiles] means it needs a folder instead of a filename,
 * since the system's create-document picker only makes one file.
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mime: String,
    val engineFormat: Int,
    val audio: Boolean,
    /** No bit depth to choose, the format uses a bitrate instead. */
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

/** Loudness target for a normalised export, what the streaming services use. */
const val NORMALISE_LUFS = -14f

data class ExportOptions(
    val what: ExportWhat = ExportWhat.Song,
    val format: ExportFormat = ExportFormat.Wav,
    val bits: Int = 24,
    /** Kilobits per second, for the lossy formats. */
    val rate: Int = 256,
    val tailSeconds: Float = 2f,
    /** Measured first and brought to [NORMALISE_LUFS], never above -1 dBTP. */
    val normalise: Boolean = false,
) {
    /** Several files, so the picker has to ask for a folder. */
    val manyFiles: Boolean get() = what == ExportWhat.Stems && format.audio
}

private fun describeFormat(f: ExportFormat): StringResource? = when (f) {
    ExportFormat.Wav -> null
    ExportFormat.Aiff -> Res.string.export_about_aiff
    ExportFormat.Flac -> Res.string.export_about_flac
    // LAME's licence asks that its use is acknowledged, this is where someone
    // choosing MP3 will see it.
    ExportFormat.Mp3 -> Res.string.export_about_mp3
    ExportFormat.Aac -> Res.string.export_about_aac
    ExportFormat.Midi -> Res.string.export_about_midi
    ExportFormat.Bundle -> Res.string.export_about_bundle
}

@Composable
private fun describeWhat(w: ExportWhat, sceneName: String): String = when (w) {
    ExportWhat.Song -> stringResource(Res.string.export_about_song)
    ExportWhat.Scene -> stringResource(Res.string.export_about_scene_named, sceneName)
    ExportWhat.Stems -> stringResource(Res.string.export_about_stems)
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

    // The data formats always hold the whole song, there's no per-track bundle.
    val audio = format.audio
    val options = ExportOptions(if (audio) what else ExportWhat.Song, format, bits, rate, tail, audio && normalise)

    PlainDialog(
        title = stringResource(Res.string.export_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.export_confirm),
        onConfirm = { onExport(options) },
        spacing = 6.dp,
    ) {
        // Cards of switches, like every window with settings in it.
        WindowCards {
            WindowCard(stringResource(Res.string.export_file)) {
                // AAC uses the phone's own encoder, so it's only offered where there is
                // one.
                val formats = ExportFormat.entries.filter { it != ExportFormat.Aac || com.rm.acidulous.AppHost.current.canEncodeAac }
                SwitchGrid(
                    stringResource(Res.string.export_format),
                    formats.map { if (it == ExportFormat.Bundle) stringResource(Res.string.export_format_bundle) else it.label }, formats.indexOf(format), columns = 4) { i ->
                    val f = formats[i]
                    format = f
                    // FLAC can't store float, so a 32-bit choice from another
                    // format becomes 24.
                    if (f == ExportFormat.Flac && bits == 32) bits = 24
                }
                // The data formats always hold the whole song, there's no
                // per-track bundle.
                if (audio) {
                    SwitchGrid(stringResource(Res.string.export_what), ExportWhat.entries.map { stringResource(it.label) }, ExportWhat.entries.indexOf(what), columns = 1) {
                        what = ExportWhat.entries[it]
                    }
                }
            }
            if (audio) {
                WindowCard(stringResource(Res.string.export_sound)) {
                    // PCM formats have a bit depth. MP3 and AAC have a bitrate
                    // instead.
                    if (format.lossy) {
                        val rates = listOf(128, 192, 256, 320)
                        SwitchGrid(stringResource(Res.string.export_kbps), rates.map { "$it" }, rates.indexOf(rate), columns = 2) { rate = rates[it] }
                    } else {
                        val depths = if (format == ExportFormat.Flac) listOf(16, 24) else listOf(16, 24, 32)
                        SwitchGrid(stringResource(Res.string.export_bits), depths.map { if (it == 32) stringResource(Res.string.export_bits_float) else "$it" }, depths.indexOf(bits), columns = 1) {
                            bits = depths[it]
                        }
                    }
                    SwitchGrid(
                        stringResource(Res.string.export_tail), stringArrayResource(Res.array.export_tail_choices).toList(),
                        if (tail <= 0f) 0 else if (tail <= 2f) 1 else 2, columns = 1,
                    ) { tail = listOf(0f, 2f, 5f)[it] }
                    SwitchGrid(
                        stringResource(Res.string.export_loudness),
                        listOf(stringResource(Res.string.export_loudness_as_mixed), stringResource(Res.string.export_loudness_lufs, NORMALISE_LUFS.toInt())), if (normalise) 1 else 0, columns = 1) {
                        normalise = it == 1
                    }
                }
            }
        }
        // A single line for what the switches can't say. The format's own line
        // always stays, since LAME's licence asks that its use is acknowledged.
        val notes = listOfNotNull(
            describeFormat(format)?.let { stringResource(it) },
            if (audio) describeWhat(what, sceneName) else null,
            if (audio && normalise) stringResource(Res.string.export_about_normalise) else null,
            if (audio && tail <= 0f) stringResource(Res.string.export_about_no_tail) else null,
        )
        Text(notes.joinToString(stringResource(Res.string.export_about_separator)), color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
