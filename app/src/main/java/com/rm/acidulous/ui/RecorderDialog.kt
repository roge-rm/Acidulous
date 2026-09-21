package com.rm.acidulous.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording, editing and keeping the material.
 *
 * **One window, because it was three half-windows.** Recording lived behind a
 * File-menu item that handed its result to nobody; the browser was a picker
 * bolted to the bottom of the same file; and the only waveform in the app
 * could draw a Forage pad and nothing else. A player who wants to sing
 * something into Molt had to record it in one place, find it in another and
 * mount it in a third, and could not trim it anywhere.
 *
 * The three pages are the three things that happen to a recording in order -
 * make it, shape it, keep it - and any of them can be the one you open on.
 *
 * It hands back a *path*. What a machine does with a path it already knows,
 * which is what lets one window serve Molt's take, Forage's pads, Dice's loop,
 * Pollen's buffer and Mosaic's zones without knowing anything about them.
 */
enum class RecorderPage { Record, Edit, Library }

@Composable
fun RecorderDialog(
    onDismiss: () -> Unit,
    startOn: RecorderPage = RecorderPage.Record,
    /** What the song is playing, so the library can say so before deleting. */
    inUse: Set<String> = emptySet(),
    /**
     * The song, for the two effect slots on the input.
     *
     * They belong to the song rather than to a track, and they belong on this
     * window rather than in the mixer: what they do is done to the recording
     * as it is made, so the place to decide about them is the place where the
     * recording is being made.
     */
    editor: SongEditor,
    /** Supplied when a machine opened this and is waiting for a file. */
    onPick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val c = Acid.colors
    val samples = remember { File(EngineAssets.userRoot(context), "samples").apply { mkdirs() } }
    var tab by remember { mutableStateOf(startOn.ordinal) }
    // Re-read the folder after anything changes it rather than trusting the
    // list the window opened with.
    var generation by remember { mutableStateOf(0) }
    /** The file the edit page is working on: the last one recorded or picked. */
    var chosen by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            // Leave nothing running behind us: an open microphone is not
            // something to forget about, the monitor would howl into the next
            // screen, and an audition outlives the window that started it.
            NativeEngine.stopCapture()
            NativeEngine.setMonitorLevel(0f)
            NativeEngine.stopInput()
            NativeEngine.auditionFile("")
        }
    }

    TabbedDialog(
        title = "Sound",
        selected = tab,
        // While it is recording the window will not go away by itself:
        // tapping outside mid-take and losing it is not a thing to allow.
        onDismiss = { if (!recording) onDismiss() },
        dismissLabel = if (recording) "Recording…" else "Close",
        maxBodyHeight = 460.dp,
        spacing = 6.dp,
        chips = { SectionChips(listOf("record", "edit", "library"), tab) { tab = it } },
        pages = listOf(
            {
                RecordPage(
                    samples = samples,
                    editor = editor,
                    onRecording = { recording = it },
                    onRecorded = { file ->
                        chosen = file
                        generation++
                        tab = RecorderPage.Edit.ordinal
                    },
                )
            },
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
                    onEdit = { file -> chosen = file; tab = RecorderPage.Edit.ordinal },
                )
            },
        ),
    )
}

// --- page one: making one --------------------------------------------------

@Composable
private fun RecordPage(samples: File, editor: SongEditor, onRecording: (Boolean) -> Unit,
                       onRecorded: (File) -> Unit) {
    val context = LocalContext.current
    val c = Acid.colors

    var fromInput by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(nextTakeName(samples)) }
    var monitor by remember { mutableStateOf(false) }
    var gain by remember { mutableStateOf(1f) }
    var level by remember { mutableStateOf(0f) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0f) }
    var peak by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var lastFile by remember { mutableStateOf<File?>(null) }
    var opened by remember { mutableStateOf("") }

    val granted =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var havePermission by remember { mutableStateOf(granted) }
    // Which ear. Nought is whatever the platform would have chosen, which is
    // what everything did before there was a screen to choose on.
    var device by remember { mutableStateOf(UiPrefs.inputDevice) }
    val devices = remember(havePermission, generationOfDevices(context)) { inputsOf(context) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        havePermission = ok
        if (ok) NativeEngine.startInput(device)
    }

    LaunchedEffect(fromInput, havePermission, device) {
        if (fromInput && havePermission) {
            UiPrefs.chooseInputDevice(device)
            NativeEngine.startInput(device)
        }
    }
    LaunchedEffect(monitor, gain) {
        NativeEngine.setMonitorLevel(if (monitor) 1f else 0f)
        NativeEngine.setInputGain(gain)
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
                val ch = if (NativeEngine.inputChannels > 1) "stereo" else "mono"
                val rate = NativeEngine.inputRate
                if (rate > 0 && rate != 48000) "$ch · $rate Hz · resampled" else "$ch · $rate Hz"
            } else ""
            delay(60)
        }
    }

    // **What you came to do, first.** The name, the button and the meter were
    // under the source, the device, the gain, the monitor and the bit depth -
    // six rows of setting up before the one thing the page is for, and on a
    // phone that is a scroll before you can press record. Dan: "the user
    // doesn't need to scroll before they can hit the record button".
    //
    // So the page is in two halves: what you do, then how it is set up. The
    // settings have not moved relative to each other - they read in the order
    // a signal travels, from where it comes from to what it is written as.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("name", fontSize = 11.sp) },
            modifier = Modifier.weight(1f), enabled = !recording,
        )
        Button(
            onClick = {
                if (recording) {
                    NativeEngine.stopCapture()
                    val file = lastFile
                    message = if (file != null) "saved ${file.name}, %.1f s".format(seconds) else "saved"
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
        ) { Text(if (recording) "stop" else "record") }
    }

    // Under the button, because while it is running this is the thing being
    // watched and it must not be somewhere else on the page.
    Meter(level, Modifier.fillMaxWidth().height(10.dp), vertical = false, track = c.sunken)

    if (recording) {
        Text(
            "recording  %.1f s  peak %.2f".format(seconds, peak),
            color = c.red, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
        if (NativeEngine.captureOverflowed) {
            Text("the writer fell behind; this take has a gap in it", color = c.accent, fontSize = 10.sp)
        }
        if (NativeEngine.captureDeaf) {
            Text("nothing is arriving - this take is silence", color = c.red, fontSize = 10.sp)
        }
    } else if (message.isNotEmpty()) {
        Text(message, color = c.textDim, fontSize = 11.sp)
    }

    // The one setting that cannot wait: the button above is disabled without
    // it, and a disabled button with its explanation below the fold is a
    // button that looks broken.
    if (fromInput && !havePermission) {
        Text("Recording needs permission to use the microphone.", color = c.red, fontSize = 11.sp)
        TextButton(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) {
            Text("allow", color = c.accent, fontSize = 12.sp)
        }
    }

    Section("source", note = if (fromInput) "" else "Play something and capture the result.") {
        Choice("in", fromInput) { if (!recording) fromInput = true }
        Choice("out (resample)", !fromInput) { if (!recording) fromInput = false }
    }

    if (fromInput && havePermission) {
        if (devices.size > 1) {
            // Only where there is a choice to make. On a phone with nothing
            // plugged in this is one row saying "built-in", which is a line
            // that has not earned itself.
            Section("input", note = opened) {
                for (d in devices) {
                    Choice(d.label, d.id == device) { if (!recording) device = d.id }
                }
            }
        } else if (opened.isNotEmpty()) {
            Readout(opened, good = true)
        }
    }

    if (fromInput) {
        // **The title is the explanation.** Three lines of prose under these
        // chips said what they do to a recording, which is the shape the house
        // rule exists to prevent: if a note is needed, the title is wrong.
        // Naming them for what happens to the file says it in four words, and
        // it says it where somebody reading the page finds it rather than
        // where somebody who already stopped would.
        //
        // They are first on the page for the same reason: everything else here
        // decides how the take is captured, and these decide what is in it.
        Section("printed into the recording") { InputChainChips(editor) }
        SliderSection(
            title = "gain",
            value = "%.2f".format(gain),
            note = "",
            position = gain / 4f,
            range = 0f..1f,
        ) { gain = it * 4f }
        Section("monitor", note = "Headphones only: a speaker will feed back.") {
            Choice("off", !monitor) { monitor = false }
            Choice("on", monitor) { monitor = true }
        }
    }

    Section("depth", note = if (UiPrefs.recordBits == 16) "Half the size." else "") {
        Choice("16 bit", UiPrefs.recordBits == 16) { if (!recording) UiPrefs.chooseRecordBits(16) }
        Choice("24 bit", UiPrefs.recordBits == 24) { if (!recording) UiPrefs.chooseRecordBits(24) }
    }
}


// --- page two: shaping it --------------------------------------------------

@Composable
private fun EditPage(file: File?, samples: File, onSaved: (File) -> Unit) {
    val c = Acid.colors
    if (file == null) {
        Text(
            "Record something, or pick a sample in the library, and it lands here.",
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

    // The shaping, all of it local until `apply` - the file on disk is not
    // touched by turning a knob, which is what makes `revert` free.
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

    LaunchedEffect(file, file.lastModified(), view) {
        val out = FloatArray(columns * 2)
        val info = withContext(Dispatchers.Default) { NativeEngine.fileInfo(file.absolutePath) }
        val total = info.split('|').getOrNull(1)?.toIntOrNull() ?: 0
        val a = (view.from * total).toInt().coerceIn(0, maxOf(0, total - 1))
        val z = ((view.from + view.span) * total).toInt().coerceIn(a + 1, maxOf(1, total))
        val got = withContext(Dispatchers.Default) {
            NativeEngine.fileShape(file.absolutePath, out, if (total > 0) a else 0, if (total > 0) z else 0)
        }
        shape = if (got > 0) out else FloatArray(0)
        meta = info
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.name, color = c.textHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.weight(1f), maxLines = 1,
        )
        TextButton(onClick = { NativeEngine.auditionFile(file.absolutePath) }) {
            Text("play", color = c.accent, fontSize = 12.sp)
        }
        TextButton(onClick = { start = 0f; end = 1f }) { Text("all", color = c.accent, fontSize = 12.sp) }
        if (view.zoomed) {
            TextButton(onClick = { view = WaveView() }) { Text("fit", color = c.teal, fontSize = 12.sp) }
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
        modifier = Modifier.fillMaxWidth().height(180.dp),
        empty = "That file could not be read.",
    )

    val seconds = if (rate > 0) frames.toFloat() / rate else 0f
    Readout(
        "%.2f s · %s · keeping %.2f s".format(
            seconds,
            if (meta.split('|').getOrNull(2) == "2") "stereo" else "mono",
            seconds * (maxOf(start, end) - minOf(start, end)),
        ),
    )

    // Panel cards inside a window, which the pad editor already does: these
    // are the machine-panel vocabulary and they should look like it.
    Row(
        Modifier.fillMaxWidth().horizontalScrollWithBar(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Group("trim") {
            EditKnob("fade in", fadeIn, "%.0f ms".format(fadeIn * 2000f)) { fadeIn = it }
            EditKnob("fade out", fadeOut, "%.0f ms".format(fadeOut * 2000f)) { fadeOut = it }
        }
        Group("level") {
            EditKnob("gain", (gainDb + 24f) / 48f, "%+.1f dB".format(gainDb), PanelAmber) {
                gainDb = it * 48f - 24f
            }
            PanelToggle("norm", normalise) { normalise = !normalise }
            PanelToggle("rev", reverse) { reverse = !reverse }
        }
        Group("tone") {
            EditKnob("low cut", lowCut, if (lowCut <= 0f) "off" else "%.0f Hz".format(hzOf(lowCut))) {
                lowCut = it
            }
            EditKnob("cutoff", cutoff, if (cutoff <= 0f) "off" else "%.0f Hz".format(hzOf(cutoff)), PanelAmber) {
                cutoff = it
            }
            EditKnob("reso", reso, "%.2f".format(reso)) { reso = it }
            PanelToggle(if (filterType == 1) "low" else "high", filterType == 5) {
                filterType = if (filterType == 1) 5 else 1
            }
        }
        Group("squash") {
            EditKnob("amount", squash, if (squash <= 0f) "off" else "%.2f".format(squash), PanelPink) {
                squash = it
            }
        }
    }

    Text("save as", color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = saveAs, onValueChange = { saveAs = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), enabled = !busy,
    )
    if (message.isNotEmpty()) Text(message, color = c.textDim, fontSize = 11.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            enabled = !busy && frames > 0,
            onClick = {
                busy = true
                message = ""
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
                val wanted = safeFileName(saveAs, file.nameWithoutExtension) + ".wav"
                val target = if (wanted == file.name) file else File(samples, uniqueIn(samples, wanted))
                val error = NativeEngine.editSample(file.absolutePath, target.absolutePath, ops)
                busy = false
                if (error.isEmpty()) {
                    message = "saved ${target.name}"
                    onSaved(target)
                } else {
                    message = error
                }
            },
        ) { Text(if (busy) "…" else "apply") }
        TextButton(onClick = {
            start = 0f; end = 1f; fadeIn = 0f; fadeOut = 0f; gainDb = 0f
            normalise = false; reverse = false; lowCut = 0f; cutoff = 0f; reso = 0f
            squash = 0f; message = ""
        }) { Text("revert", color = c.textMid, fontSize = 12.sp) }
    }
}

// --- page three: keeping it ------------------------------------------------

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
    // Deleting a file cannot be undone, so it takes two taps: the first arms
    // the row and the second does it. A confirm dialog on top of a dialog is
    // worse, and a single tap beside "use this one" is an accident waiting.
    var armed by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }

    if (files.isEmpty()) {
        Text("Nothing here yet. Record one, or import a WAV.", fontSize = 12.sp, color = c.textDim)
    }
    files.forEach { file ->
        val rel = "samples/${file.name}"
        val used = rel in inUse
        val isArmed = armed == rel
        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        DialogRow(
            mark = when {
                file == chosen -> "●"
                used -> "▶"
                else -> "♪"
            },
            name = file.name,
            under = when {
                isArmed -> "delete this? tap the cross again"
                used -> "$stamp · %.0f kB · a track is playing this".format(file.length() / 1024f)
                else -> "$stamp · %.0f kB".format(file.length() / 1024f)
            },
            on = isArmed || file == chosen,
            monoUnder = !isArmed,
            onRemove = {
                if (!isArmed) {
                    armed = rel
                } else {
                    armed = null
                    // The engine holds a decoded copy, so a track playing this
                    // keeps playing until the song is reloaded - and then
                    // finds nothing and says so, which is the same path as any
                    // other file that will not read.
                    if (file.delete()) onChanged()
                }
            },
        ) { armed = null; onChoose(file) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { NativeEngine.auditionFile(file.absolutePath) }) {
                Text("play", color = c.accent, fontSize = 11.sp)
            }
            TextButton(onClick = { onEdit(file) }) { Text("edit", color = c.teal, fontSize = 11.sp) }
            TextButton(onClick = { renaming = file }) { Text("rename", color = c.textMid, fontSize = 11.sp) }
        }
    }

    renaming?.let { file ->
        TextInputDialog(
            title = "Rename",
            initial = file.nameWithoutExtension,
            onDismiss = { renaming = null },
            onConfirm = { typed ->
                renaming = null
                val wanted = safeFileName(typed, file.nameWithoutExtension) + ".wav"
                if (wanted != file.name) {
                    // A rename that collided used to be an overwrite, which is
                    // one file eating another silently.
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
    Column(
        Modifier.height(PanelControlH).width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Knob(label = label, value = value, display = display, accent = accent, onChange = onChange)
    }
}

/** A switch whose state is a local `Boolean`, ditto. */
@Composable
private fun PanelToggle(label: String, on: Boolean, onClick: () -> Unit) {
    // Wide enough for the word. `Choice` is twelve-point text with twelve dp
    // of padding each side, so a four letter label wants about sixty-six -
    // at forty-six "norm" came out as two lines reading "no rm".
    Column(
        Modifier.height(PanelControlH).width(66.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Choice(label, on, Modifier.fillMaxWidth()) { onClick() }
    }
}

/**
 * A knob position as a frequency, twenty hertz to twenty kilohertz.
 *
 * Logarithmic, because a filter is: half a turn should be the middle of what
 * a person hears rather than the middle of the number.
 */
private fun hzOf(position: Float): Float = 20f * Math.pow(1000.0, position.toDouble()).toFloat()

private data class InputChoice(val id: Int, val label: String)

/**
 * What the platform says is plugged in.
 *
 * Nought is always offered and always first: it is whatever the platform
 * would have chosen, which is what every version of this app before now used
 * and is still the right answer when the list is unfamiliar.
 */
private fun inputsOf(context: Context): List<InputChoice> {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return listOf(InputChoice(0, "default"))
    val found = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { d ->
        val word = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
            AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_FM_TUNER -> null
            else -> d.productName?.toString()?.lowercase()?.take(12)
        } ?: return@mapNotNull null
        InputChoice(d.id, word)
    }
    return listOf(InputChoice(0, "default")) + found.distinctBy { it.label }
}

/** Devices come and go; re-listing on every recomposition is the cheap answer. */
private fun generationOfDevices(context: Context): Int {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
    return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).sumOf { it.id }
}
