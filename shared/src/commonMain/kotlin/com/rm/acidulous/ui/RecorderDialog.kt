package com.rm.acidulous.ui

import com.rm.acidulous.io.*

import com.rm.acidulous.util.Math

import com.rm.acidulous.util.format

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.nextTakeName
import com.rm.acidulous.engine.safeFileName
import com.rm.acidulous.engine.uniqueIn
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.rm.acidulous.AppHost
import com.rm.acidulous.AudioInput
import com.rm.acidulous.res.*

/**
 * The recorder window's pages: record a take, edit it, and the library of
 * recordings. Any of them can be the one it opens on.
 *
 * It hands back a path, so one window serves Molt's take, Forage's pads,
 * Dice's loop, Pollen's buffer and Mosaic's zones without knowing about them.
 */
enum class RecorderPage { Record, Edit, Library }

/**
 * The input settings: source, gain, monitor and the tuner's reading. Held by
 * the window, not the page, because on a square phone the input card is its
 * own page and both need the same one.
 */
private class InputSetup(granted: Boolean) {
    var fromInput by mutableStateOf(true)
    var havePermission by mutableStateOf(granted)
    var monitor by mutableStateOf(false)
    var gain by mutableStateOf(1f)
    var tunerHz by mutableStateOf(0f)
}

@Composable
fun RecorderDialog(
    onDismiss: () -> Unit,
    startOn: RecorderPage = RecorderPage.Record,
    /** Files the song uses, so the library can warn before deleting. */
    inUse: Set<String> = emptySet(),
    /**
     * The song, for the two input effect slots. They belong to the song, and
     * live in this window because they're applied to the recording as it's made.
     */
    editor: SongEditor,
    /** Set when a machine opened this and is waiting for a file. */
    onPick: ((String) -> Unit)? = null,
) {
    val c = Acid.colors
    val samples = remember { File(EngineAssets.userRoot(), "samples").apply { mkdirs() } }
    // On a square phone the input card gets its own page after record, since
    // it doesn't fit on the record page.
    val inputPage = compactWindow()
    fun tabOf(page: RecorderPage) = if (inputPage && page != RecorderPage.Record) page.ordinal + 1 else page.ordinal
    var tab by remember { mutableStateOf(tabOf(startOn)) }
    val permissions = rememberPermissions { }
    val setup = remember { InputSetup(permissions.has(Permissions.RECORD_AUDIO)) }
    LaunchedEffect(setup.monitor, setup.gain) {
        NativeEngine.setMonitorLevel(if (setup.monitor) 1f else 0f)
        NativeEngine.setInputGain(setup.gain)
    }
    // The tuner only listens while the window is open and recording from the
    // input. While on, the audio thread copies every input block into its ring.
    DisposableEffect(setup.fromInput, setup.havePermission) {
        NativeEngine.setTunerOn(setup.fromInput && setup.havePermission)
        onDispose { NativeEngine.setTunerOn(false) }
    }
    LaunchedEffect(setup.fromInput, setup.havePermission) {
        if (!setup.fromInput || !setup.havePermission) { setup.tunerHz = 0f; return@LaunchedEffect }
        while (true) {
            // Off the UI thread. A reading is an autocorrelation over half a second
            // of audio and takes about a millisecond.
            setup.tunerHz = withContext(Dispatchers.Default) { NativeEngine.tunerHz() }
            delay(120)
        }
    }
    // Bumped after anything changes the folder so the list is re-read.
    var generation by remember { mutableStateOf(0) }
    /** The file the edit page works on: the last one recorded or picked. */
    var chosen by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            // Stop everything: the microphone, the monitor (which would howl into
            // the next screen) and any audition.
            NativeEngine.stopCapture()
            NativeEngine.setMonitorLevel(0f)
            NativeEngine.stopInput()
            NativeEngine.auditionFile("")
        }
    }

    TabbedDialog(
        title = stringResource(Res.string.sound_title),
        selected = tab,
        // While recording it won't close on a tap outside, so a take can't be
        // lost by accident.
        onDismiss = { if (!recording) onDismiss() },
        dismissLabel = stringResource(if (recording) Res.string.sound_recording_button else Res.string.close),
        spacing = 6.dp,
        pageNames = stringArrayResource(Res.array.sound_tabs).toList().let {
            if (inputPage) listOf(it[0], stringResource(Res.string.sound_tab_input)) + it.drop(1) else it
        },
        onSelectPage = { tab = it },
        pages = listOfNotNull(
            {
                RecordPage(
                    setup = setup,
                    withInput = !inputPage,
                    samples = samples,
                    editor = editor,
                    onRecording = { recording = it },
                    onRecorded = { file ->
                        chosen = file
                        generation++
                        tab = tabOf(RecorderPage.Edit)
                    },
                )
            },
            (@Composable { WindowCards { InputCard(setup, editor) } }).takeIf { inputPage },
            {
                EditPage(
                    file = chosen,
                    samples = samples,
                    onSaved = { file ->
                        chosen = file
                        generation++
                        onPick?.invoke("samples/" + file.name)
                    },
                )
            },
            {
                LibraryPage(
                    samples = samples,
                    generation = generation,
                    onChanged = { generation++ },
                    inUse = inUse,
                    chosen = chosen,
                    onChoose = { file ->
                        chosen = file
                        onPick?.invoke("samples/" + file.name)
                    },
                    onEdit = { file -> chosen = file; tab = tabOf(RecorderPage.Edit) },
                )
            },
        ),
    )
}

// --- page one: recording ---------------------------------------------------

@Composable
private fun RecordPage(setup: InputSetup, withInput: Boolean, samples: File, editor: SongEditor,
                       onRecording: (Boolean) -> Unit, onRecorded: (File) -> Unit) {
    val resources = AppStrings
    val c = Acid.colors

    var fromInput by setup::fromInput
    var name by remember { mutableStateOf(nextTakeName(samples)) }
    var level by remember { mutableStateOf(0f) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0f) }
    var peak by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var lastFile by remember { mutableStateOf<File?>(null) }
    var opened by remember { mutableStateOf("") }
    var havePermission by setup::havePermission
    // The input device. 0 is the platform's default.
    var device by remember { mutableStateOf(UiPrefs.inputDevice) }
    val devices = remember(havePermission, generationOfDevices()) { inputsOf() }

    val permissions = rememberPermissions { ok ->
        havePermission = ok
        if (ok) NativeEngine.startInput(device)
    }

    LaunchedEffect(fromInput, havePermission, device) {
        if (fromInput && havePermission) {
            UiPrefs.chooseInputDevice(device)
            NativeEngine.startInput(device)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            level = if (fromInput) NativeEngine.inputPeak() else NativeEngine.readPeakLevel()
            val now = NativeEngine.capturing
            if (now != recording) {
                recording = now
                onRecording(now)
            }
            if (recording) {
                seconds = NativeEngine.capturedSeconds
                peak = NativeEngine.capturedPeak
            }
            opened = if (NativeEngine.inputRunning) {
                val ch = resources.getString(if (NativeEngine.inputChannels > 1) Res.string.sound_stereo else Res.string.sound_mono)
                val rate = NativeEngine.inputRate
                resources.getString(if (rate > 0 && rate != 48000) Res.string.sound_opened_resampled else Res.string.sound_opened, ch, rate)
            } else ""
            delay(60)
        }
    }

    // The name, record button and meter come first, so you can record without
    // scrolling. The settings follow, in the order the signal travels from
    // source to file.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text(stringResource(Res.string.sound_name), fontSize = 11.sp) },
            modifier = Modifier.typing() then Modifier.weight(1f), enabled = !recording,
        )
        Button(
            onClick = {
                if (recording) {
                    NativeEngine.stopCapture()
                    // Set here, not by the loop above: onRecorded switches to the edit
                    // page, which ends this one before it sees the take stop, and the
                    // window would keep saying "Recording…" and not close.
                    recording = false
                    onRecording(false)
                    val file = lastFile
                    message = if (file != null) resources.getString(Res.string.sound_saved_take, file.name, seconds) else resources.getString(Res.string.sound_saved)
                    name = nextTakeName(samples)
                    if (file != null) onRecorded(file)
                } else {
                    val target = File(samples, uniqueIn(samples, safeFileName(name, "take") + ".wav"))
                    val error = NativeEngine.startCapture(target.absolutePath, if (fromInput) 0 else 1)
                    if (error.isEmpty()) {
                        lastFile = target
                        seconds = 0f
                        peak = 0f
                        message = ""
                    } else {
                        message = error
                    }
                }
            },
            enabled = !fromInput || havePermission,
        ) { Text(stringResource(if (recording) Res.string.sound_stop else Res.string.sound_record)) }
    }

    // Right under the button, since it's what you watch while recording.
    Meter(level, Modifier.fillMaxWidth().height(10.dp), vertical = false, track = c.sunken)

    if (recording) {
        Text(
            stringResource(Res.string.sound_recording, seconds, peak),
            color = c.red, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
        if (NativeEngine.captureOverflowed) {
            Text(stringResource(Res.string.sound_overflowed), color = c.accent, fontSize = 10.sp)
        }
        if (NativeEngine.captureDeaf) {
            Text(stringResource(Res.string.sound_deaf), color = c.red, fontSize = 10.sp)
        }
    } else if (message.isNotEmpty()) {
        Text(message, color = c.textDim, fontSize = 11.sp)
    }

    // The one setting that can't wait: the button above is disabled without
    // it, so the explanation has to be visible.
    if (fromInput && !havePermission) {
        Text(stringResource(Res.string.sound_permission), color = c.red, fontSize = 11.sp)
        TextButton(onClick = { permissions.ask(Permissions.RECORD_AUDIO) }) {
            Text(stringResource(Res.string.sound_allow), color = c.accent, fontSize = 12.sp)
        }
    }

    // The settings as cards, like every settings window, in the order the
    // signal travels from source to file.
    WindowCards {
        WindowCard(stringResource(Res.string.sound_take)) {
            // In records the input; resample records what the app is playing.
            SwitchGrid(stringResource(Res.string.sound_source), stringArrayResource(Res.array.sound_source_choices).toList(), if (fromInput) 0 else 1, columns = 1, enabled = listOf(!recording, !recording)) {
                fromInput = it == 0
            }
            if (fromInput && havePermission && devices.size > 1) {
                // Only shown when there's a choice. A computer's list and names are
                // long, see DeviceList.
                val pick: (Int) -> Unit = { if (!recording) device = devices[it].id }
                if (AppHost.current.onDesktop) {
                    DeviceList(stringResource(Res.string.sound_input), devices.map { it.label }, devices.indexOfFirst { it.id == device }, enabled = !recording, onPick = pick)
                } else SwitchGrid(stringResource(Res.string.sound_input), devices.map { it.label }, devices.indexOfFirst { it.id == device }, columns = 1) {
                    pick(it)
                }
            }
            SwitchGrid(stringResource(Res.string.sound_bits), listOf("16", "24"), if (UiPrefs.recordBits == 16) 0 else 1, columns = 1, enabled = listOf(!recording, !recording)) {
                UiPrefs.chooseRecordBits(if (it == 0) 16 else 24)
            }
            if (fromInput && opened.isNotEmpty()) Box(Modifier.cardLine()) { Readout(opened, good = true) }
        }
        if (withInput) InputCard(setup, editor)
    }
}

/** The input: gain and monitor, the effects recorded into a take, and the tuner. */
@Composable
private fun InputCard(setup: InputSetup, editor: SongEditor) {
    if (!setup.fromInput) return
    val c = Acid.colors
    WindowCard(stringResource(Res.string.sound_input_card)) {
        Knob(label = stringResource(Res.string.sound_gain), value = setup.gain / 4f, display = "%.2f".format(setup.gain), modifier = panelKnobWidth(), onChange = { setup.gain = it * 4f })
        SwitchGrid(stringResource(Res.string.sound_monitor), stringArrayResource(Res.array.off_on).toList(), if (setup.monitor) 1 else 0) { setup.monitor = it == 1 }
        if (AppHost.current.cleansInput) {
            SwitchGrid(stringResource(Res.string.sound_mic), stringArrayResource(Res.array.sound_mic_choices).toList(), if (UiPrefs.inputClean) 1 else 0) {
                UiPrefs.chooseInputClean(it == 1)
            }
        }
        // The title explains it: these effects are recorded into the take.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.sound_printed), color = c.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { InputChainChips(editor) }
        }
        // The tuner is in the input card since tuning is the first thing you do
        // after plugging in.
        if (setup.havePermission) Box(Modifier.cardLine()) { TunerStrip(setup.tunerHz) }
    }
}


// --- page two: editing -----------------------------------------------------

@Composable
private fun EditPage(file: File?, samples: File, onSaved: (File) -> Unit) {
    val c = Acid.colors
    val resources = AppStrings
    if (file == null) {
        Text(
            stringResource(Res.string.sound_edit_none),
            color = c.textDim, fontSize = 12.sp,
        )
        return
    }

    val columns = 900
    var shape by remember(file, file.lastModified()) { mutableStateOf(FloatArray(0)) }
    var meta by remember(file, file.lastModified()) { mutableStateOf("") }
    val frames = meta.split('|').getOrNull(1)?.toIntOrNull() ?: 0
    val rate = meta.split('|').getOrNull(3)?.toIntOrNull() ?: 48000

    var view by remember(file) { mutableStateOf(WaveView()) }
    var start by remember(file) { mutableStateOf(0f) }
    var end by remember(file) { mutableStateOf(1f) }

    // Every edit is heard and seen as it's made, from a copy in memory (see
    // editPreview). The file isn't touched until `apply`, which makes
    // `revert` free.
    var fadeIn by remember(file) { mutableStateOf(0f) }
    var fadeOut by remember(file) { mutableStateOf(0f) }
    var gainDb by remember(file) { mutableStateOf(0f) }
    var normalise by remember(file) { mutableStateOf(false) }
    var reverse by remember(file) { mutableStateOf(false) }
    var lowCut by remember(file) { mutableStateOf(0f) }
    var cutoff by remember(file) { mutableStateOf(0f) }
    var reso by remember(file) { mutableStateOf(0f) }
    var filterType by remember(file) { mutableStateOf(1) } // LP12
    var squash by remember(file) { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(file) { mutableStateOf("") }
    var saveAs by remember(file) { mutableStateOf(file.nameWithoutExtension) }

    /** The edit as the engine takes it (see NativeEngine.editSample). */
    fun opsNow(): FloatArray {
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val ops = FloatArray(NativeEngine.EDIT_OPS)
        ops[0] = (lo * frames)
        ops[1] = (hi * frames)
        ops[2] = fadeIn * 2000f
        ops[3] = fadeOut * 2000f
        ops[4] = gainDb
        ops[5] = if (normalise) 0.97f else 0f
        ops[6] = if (reverse) 1f else 0f
        ops[7] = if (lowCut <= 0f) 0f else hzOf(lowCut)
        ops[8] = if (cutoff <= 0f) 0f else hzOf(cutoff)
        ops[9] = reso
        ops[10] = filterType.toFloat()
        ops[11] = squash
        ops[12] = 10f
        ops[13] = 120f
        return ops
    }

    LaunchedEffect(file, file.lastModified()) {
        meta = withContext(Dispatchers.Default) { NativeEngine.fileInfo(file.absolutePath) }
    }
    // The waveform is the edit as it stands. A knob being turned asks many
    // times a second, and each ask cancels the one before, so it waits a
    // moment and only the last is built.
    LaunchedEffect(meta, view, start, end, fadeIn, fadeOut, gainDb, normalise, reverse, lowCut, cutoff, reso, filterType, squash) {
        if (frames <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(40)
        val out = FloatArray(columns * 2)
        val a = (view.from * frames).toInt().coerceIn(0, maxOf(0, frames - 1))
        val z = ((view.from + view.span) * frames).toInt().coerceIn(a + 1, maxOf(1, frames))
        val ops = opsNow()
        val got = withContext(Dispatchers.Default) { NativeEngine.editPreview(file.absolutePath, ops, out, a, z) }
        shape = if (got > 0) out else FloatArray(0)
    }

    // Where `play` has got to, read every frame while the page is open. It's
    // a fraction of the whole file, which matches the waveform's coordinates.
    var playhead by remember(file) { mutableStateOf(-1f) }
    LaunchedEffect(file) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            playhead = NativeEngine.auditionProgress
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The file name is shown under the waveform, where there's room for it.
        Spacer(Modifier.weight(1f))
        // Stop while it plays, so a long take doesn't have to be waited out.
        val playing = playhead >= 0f
        // What's played is the preview, edits and all.
        TextButton(onClick = { if (playing) NativeEngine.auditionFile("") else NativeEngine.auditionPreview() }) {
            Text(stringResource(if (playing) Res.string.sound_stop else Res.string.sound_play), color = c.accent, fontSize = 12.sp)
        }
        TextButton(onClick = { start = 0f; end = 1f }) { Text(stringResource(Res.string.sound_all), color = c.accent, fontSize = 12.sp) }
        // Normalise and reverse apply to the whole file, so they sit up here.
        TextButton(onClick = { normalise = !normalise }) {
            Text(stringResource(Res.string.sound_norm), color = if (normalise) c.accent else c.textMid, fontSize = 12.sp)
        }
        TextButton(onClick = { reverse = !reverse }) {
            Text(stringResource(Res.string.sound_rev), color = if (reverse) c.accent else c.textMid, fontSize = 12.sp)
        }
        if (view.zoomed) {
            TextButton(onClick = { view = WaveView() }) { Text(stringResource(Res.string.sound_fit), color = c.teal, fontSize = 12.sp) }
        }
    }

    Waveform(
        shape = shape,
        frames = frames,
        view = view,
        onView = { view = it },
        start = start,
        end = end,
        onStart = { start = it },
        onEnd = { end = it },
        modifier = Modifier.fillMaxWidth().height(120.dp),
        empty = stringResource(Res.string.sound_unreadable),
        playhead = playhead,
    )

    val seconds = if (rate > 0) frames.toFloat() / rate else 0f
    Readout(
        stringResource(
            Res.string.sound_file_readout,
            file.name,
            seconds,
            stringResource(if (meta.split('|').getOrNull(2) == "2") Res.string.sound_stereo else Res.string.sound_mono),
            seconds * (maxOf(start, end) - minOf(start, end)),
        ),
    )

    // Cards that wrap, like every settings window.
    WindowCards {
        val off = stringResource(Res.string.sound_off)
        WindowCard(stringResource(Res.string.sound_level)) {
            EditKnob(stringResource(Res.string.sound_fade_in), fadeIn, "%.0f ms".format(fadeIn * 2000f)) { fadeIn = it }
            EditKnob(stringResource(Res.string.sound_fade_out), fadeOut, "%.0f ms".format(fadeOut * 2000f)) { fadeOut = it }
            EditKnob(stringResource(Res.string.sound_gain), (gainDb + 24f) / 48f, "%+.1f dB".format(gainDb), PanelAmber) {
                gainDb = it * 48f - 24f
            }
            EditKnob(stringResource(Res.string.sound_squash), squash, if (squash <= 0f) off else "%.2f".format(squash), PanelPink) {
                squash = it
            }
        }
        WindowCard(stringResource(Res.string.sound_tone)) {
            EditKnob(stringResource(Res.string.sound_low_cut), lowCut, if (lowCut <= 0f) off else "%.0f Hz".format(hzOf(lowCut))) {
                lowCut = it
            }
            EditKnob(stringResource(Res.string.sound_cutoff), cutoff, if (cutoff <= 0f) off else "%.0f Hz".format(hzOf(cutoff)), PanelAmber) {
                cutoff = it
            }
            EditKnob(stringResource(Res.string.sound_reso), reso, "%.2f".format(reso)) { reso = it }
            SwitchGrid(stringResource(Res.string.sound_filter), stringArrayResource(Res.array.sound_filter_choices).toList(), if (filterType == 5) 1 else 0) { filterType = if (it == 1) 5 else 1 }
        }
    }

    if (message.isNotEmpty()) Text(message, color = c.textDim, fontSize = 11.sp)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = saveAs, onValueChange = { saveAs = it }, singleLine = true,
            label = { Text(stringResource(Res.string.sound_save_as), fontSize = 11.sp) },
            modifier = Modifier.typing() then Modifier.weight(1f), enabled = !busy,
        )
        Button(
            enabled = !busy && frames > 0,
            onClick = {
                busy = true
                message = ""
                val ops = opsNow()
                val wanted = safeFileName(saveAs, file.nameWithoutExtension) + ".wav"
                val target = if (wanted == file.name) file else File(samples, uniqueIn(samples, wanted))
                val error = NativeEngine.editSample(file.absolutePath, target.absolutePath, ops)
                busy = false
                if (error.isEmpty()) {
                    message = resources.getString(Res.string.sound_saved_file, target.name)
                    // The file has the edit in it now, so the knobs go back
                    // to nothing or the preview would apply it twice.
                    start = 0f; end = 1f; fadeIn = 0f; fadeOut = 0f; gainDb = 0f
                    normalise = false; reverse = false; lowCut = 0f; cutoff = 0f; reso = 0f
                    squash = 0f
                    onSaved(target)
                } else {
                    message = error
                }
            },
        ) { Text(if (busy) "…" else stringResource(Res.string.sound_apply)) }
        TextButton(onClick = {
            start = 0f; end = 1f; fadeIn = 0f; fadeOut = 0f; gainDb = 0f
            normalise = false; reverse = false; lowCut = 0f; cutoff = 0f; reso = 0f
            squash = 0f; message = ""
        }) { Text(stringResource(Res.string.sound_revert), color = c.textMid, fontSize = 12.sp) }
    }
}

// --- page three: the library -----------------------------------------------

@Composable
private fun LibraryPage(
    samples: File,
    generation: Int,
    onChanged: () -> Unit,
    inUse: Set<String>,
    chosen: File?,
    onChoose: (File) -> Unit,
    onEdit: (File) -> Unit,
) {
    val c = Acid.colors
    val files = remember(generation) {
        (samples.listFiles { f -> f.isFile && f.name.endsWith(".wav", true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }
    // Deleting can't be undone, so it takes two taps: the first arms the row,
    // the second deletes.
    var armed by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }

    if (files.isEmpty()) {
        Text(stringResource(Res.string.sound_library_none), fontSize = 12.sp, color = c.textDim)
    }
    files.forEach { file ->
        val rel = "samples/${file.name}"
        val used = rel in inUse
        val isArmed = armed == rel
        val stamp = com.rm.acidulous.util.dayAndTime(file.lastModified())
        DialogRow(
            mark = when {
                file == chosen -> "●"
                used -> "▶"
                else -> "♪"
            },
            name = file.name,
            under = when {
                isArmed -> stringResource(Res.string.sound_delete_armed, Res.string.sound_delete_armed_mouse)
                used -> stringResource(Res.string.sound_file_info_used, stamp, file.length() / 1024f)
                else -> stringResource(Res.string.sound_file_info, stamp, file.length() / 1024f)
            },
            on = isArmed || file == chosen,
            monoUnder = !isArmed,
            onRemove = {
                if (!isArmed) {
                    armed = rel
                } else {
                    armed = null
                    // The engine holds a decoded copy, so a track playing this keeps
                    // playing until the song is reloaded. Then it reports a missing
                    // file like any other.
                    if (file.delete()) onChanged()
                }
            },
        ) { armed = null; onChoose(file) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { NativeEngine.auditionFile(file.absolutePath) }) {
                Text(stringResource(Res.string.sound_play), color = c.accent, fontSize = 11.sp)
            }
            TextButton(onClick = { onEdit(file) }) { Text(stringResource(Res.string.sound_edit), color = c.teal, fontSize = 11.sp) }
            TextButton(onClick = { renaming = file }) { Text(stringResource(Res.string.sound_rename), color = c.textMid, fontSize = 11.sp) }
        }
    }

    renaming?.let { file ->
        TextInputDialog(
            title = stringResource(Res.string.sound_rename_title),
            initial = file.nameWithoutExtension,
            onDismiss = { renaming = null },
            onConfirm = { typed ->
                renaming = null
                val wanted = safeFileName(typed, file.nameWithoutExtension) + ".wav"
                if (wanted != file.name) {
                    // Never overwrite an existing file on rename.
                    val target = File(samples, uniqueIn(samples, wanted))
                    if (file.renameTo(target)) onChanged()
                }
            },
        )
    }
}

// --- the small shared things -----------------------------------------------

/** A knob whose value is a local `Float`, not a machine parameter. */
@Composable
private fun EditKnob(
    label: String,
    value: Float,
    display: String,
    accent: androidx.compose.ui.graphics.Color = PanelTeal,
    onChange: (Float) -> Unit,
) {
    Knob(label = label, value = value, display = display, accent = accent, modifier = panelKnobWidth(), onChange = onChange)
}

/** A switch whose state is a local `Boolean`. */
@Composable
private fun PanelToggle(label: String, on: Boolean, onClick: () -> Unit) {
    // Wide enough for a four letter word. At 46 dp "norm" wrapped onto two
    // lines.
    Column(
        Modifier.height(PanelControlH).width(66.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Choice(label, on, Modifier.fillMaxWidth()) { onClick() }
    }
}

/**
 * A knob position as a frequency, 20 Hz to 20 kHz, on a log scale so the
 * middle of the knob is the middle of what you hear.
 */
private fun hzOf(position: Float): Float = 20f * Math.pow(1000.0, position.toDouble()).toFloat()

private data class InputChoice(val id: Int, val label: String)

/**
 * The inputs the platform reports. 0, the platform's default, is always
 * offered and always first.
 */
private fun inputsOf(): List<InputChoice> {
    val found = AppHost.current.audioInputs().mapNotNull { d ->
        val word = when (d.kind) {
            AudioInput.Kind.BuiltIn -> AppStrings.getString(Res.string.sound_input_builtin)
            AudioInput.Kind.Headset -> AppStrings.getString(Res.string.sound_input_headset)
            AudioInput.Kind.Usb -> AppStrings.getString(Res.string.sound_input_usb)
            AudioInput.Kind.Bluetooth -> AppStrings.getString(Res.string.sound_input_bluetooth)
            AudioInput.Kind.Line -> AppStrings.getString(Res.string.sound_input_line)
            AudioInput.Kind.NotAnEar -> null
            // A phone's switch has room for a short word, a computer's list for the name.
            AudioInput.Kind.Other -> if (AppHost.current.onDesktop) d.name else d.name?.lowercase()?.take(12)
        } ?: return@mapNotNull null
        InputChoice(d.id, word)
    }
    return listOf(InputChoice(0, AppStrings.getString(Res.string.sound_input_default))) + found.distinctBy { it.label }
}

/** Devices come and go, so this re-lists them on every recomposition. */
private fun generationOfDevices(): Int = AppHost.current.audioInputs().sumOf { it.id }
