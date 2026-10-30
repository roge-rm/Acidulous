package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.RoomImpulse
import com.rm.acidulous.engine.safeFileName
import com.rm.acidulous.engine.uniqueIn
import com.rm.acidulous.io.*
import com.rm.acidulous.model.VenueRooms
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The engine's rate, which the impulse's frames are counted at. */
private const val ROOM_RATE = 48000f
/** Columns in the impulse's picture. */
private const val ROOM_COLUMNS = 240
/** The shortest trim, in seconds. */
private const val ROOM_SHORTEST = 0.05f

/** "room 1", or the first number not taken in [dir]. */
private fun nextRoomName(dir: File, pattern: (Int) -> String): String {
    val used = (dir.listFiles() ?: emptyArray()).map { it.nameWithoutExtension.lowercase() }.toSet()
    var n = 1
    while (pattern(n).lowercase() in used) n++
    return pattern(n)
}

/**
 * Captures a room's impulse: a tone to set the level by, a sine sweep played
 * into the room and recorded, then the impulse to trim, hear and save into
 * your impulses. [onSaved] gets the saved file's setting
 * ("impulses/name.wav"), for the Venue that opened this to play.
 */
@Composable
fun RoomCaptureDialog(onDismiss: () -> Unit, onSaved: ((String) -> Unit)? = null) {
    val c = Acid.colors
    val resources = AppStrings
    val scope = rememberCoroutineScope()
    val dir = remember { File(EngineAssets.userRoot(), VenueRooms.FOLDER).apply { mkdirs() } }

    var havePermission by remember { mutableStateOf(false) }
    val asking = rememberPermissions { ok -> havePermission = ok }
    remember { havePermission = asking.has(Permissions.RECORD_AUDIO); true }
    // The record window may have the input open already; leave it as it was found.
    val inputWasOpen = remember { NativeEngine.inputRunning }

    var levelDb by remember { mutableStateOf(-12f) }
    var tone by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(4) }
    var repeats by remember { mutableStateOf(2) }
    var state by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0f) }
    var input by remember { mutableStateOf(0f) }
    var sweepPeak by remember { mutableStateOf(0f) }
    var working by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RoomImpulse?>(null) }
    val picture = remember { FloatArray(ROOM_COLUMNS) { -90f } }
    var pictureGen by remember { mutableStateOf(0) }
    var trim by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var rooms by remember { mutableStateOf(0) }
    // What was last saved: the capture, its trim and its name. The button reads "saved" until one changes.
    var savedAs by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var name by remember { mutableStateOf(nextRoomName(dir) { resources.getString(Res.string.room_default_name, it) }) }

    LaunchedEffect(havePermission) {
        if (havePermission) NativeEngine.startInput(UiPrefs.inputDevice)
    }
    DisposableEffect(Unit) {
        onDispose {
            NativeEngine.roomSweepStop()
            NativeEngine.roomTone(false, 0f)
            NativeEngine.auditionFile("")
            if (!inputWasOpen) NativeEngine.stopInput()
            // Frees the recording and the impulse. Off the UI thread: it waits for an analysis or a save.
            CoroutineScope(Dispatchers.Default).launch { NativeEngine.roomClose() }
        }
    }
    LaunchedEffect(tone, levelDb) { NativeEngine.roomTone(tone, levelDb) }
    LaunchedEffect(Unit) {
        while (true) {
            input = NativeEngine.inputPeak()
            var now = NativeEngine.roomSweepState
            if (now == 2) {
                progress = NativeEngine.roomSweepProgress
                sweepPeak = NativeEngine.roomSweepPeak
            }
            if (now == 3 && state == 2) {
                sweepPeak = NativeEngine.roomSweepPeak
                working = true
                val (found, why) = withContext(Dispatchers.Default) { NativeEngine.roomAnalyse(picture) }
                working = false
                // Read again, as the analysis took a while.
                now = NativeEngine.roomSweepState
                result = found
                pictureGen++
                if (found != null) {
                    if (savedAs != null) name = nextRoomName(dir) { resources.getString(Res.string.room_default_name, it) }
                    trim = found.autoFrames
                    message = ""
                    failed = false
                } else {
                    message = resources.getString(Res.string.room_failed, why)
                    failed = true
                }
            }
            state = now
            delay(60)
        }
    }
    val sweeping = state == 2
    val isSaved = savedAs == Triple(pictureGen, trim, name)

    val capturePage: @Composable () -> Unit = {
        if (!havePermission) {
            Text(stringResource(Res.string.sound_permission), color = c.red, fontSize = 11.sp)
            TextButton(onClick = { asking.ask(Permissions.RECORD_AUDIO) }) {
                Text(stringResource(Res.string.sound_allow), color = c.accent, fontSize = 12.sp)
            }
        }
        WindowCards {
            WindowCard(stringResource(Res.string.room_level_card)) {
                SwitchGrid(stringResource(Res.string.room_tone), stringArrayResource(Res.array.off_on).toList(), if (tone) 1 else 0, enabled = listOf(!sweeping, !sweeping)) {
                    tone = it == 1
                }
                Knob(
                    label = stringResource(Res.string.room_level), value = (levelDb + 30f) / 30f,
                    display = "%.0f dB".format(levelDb), modifier = panelKnobWidth(), steps = 31,
                    onChange = { if (!sweeping) levelDb = (it * 30f - 30f).let { v -> kotlin.math.round(v) } },
                )
                Meter(input, Modifier.roomLine(160.dp).height(10.dp), vertical = false, track = c.sunken)
            }
            WindowCard(stringResource(Res.string.room_sweep_card)) {
                Knob(
                    label = stringResource(Res.string.room_length), value = (seconds - 2) / 8f,
                    display = "$seconds s", modifier = panelKnobWidth(), steps = 9,
                    onChange = { if (!sweeping) seconds = 2 + kotlin.math.round(it * 8f).toInt() },
                )
                Knob(
                    label = stringResource(Res.string.room_repeats), value = (repeats - 1) / 3f,
                    display = "$repeats", modifier = panelKnobWidth(), steps = 4,
                    onChange = { if (!sweeping) repeats = 1 + kotlin.math.round(it * 3f).toInt() },
                )
                ButtonSquare("", stringResource(if (sweeping) Res.string.room_stop else Res.string.room_sweep), lit = sweeping) {
                    if (sweeping) {
                        NativeEngine.roomSweepStop()
                    } else if (havePermission && !working && !saving) {
                        tone = false
                        NativeEngine.auditionFile("")
                        val error = NativeEngine.roomSweepStart(seconds.toFloat(), repeats, levelDb)
                        message = error
                        failed = error.isNotEmpty()
                        if (error.isEmpty()) {
                            state = 2
                            progress = 0f
                            sweepPeak = 0f
                        }
                    }
                }
                val line = when {
                    sweeping -> stringResource(Res.string.room_sweeping, (progress * 100f).toInt(), sweepPeak)
                    working -> stringResource(Res.string.room_working)
                    else -> ""
                }
                Box(Modifier.cardLine()) { Readout(line) }
            }
            WindowCard(stringResource(Res.string.room_result_card)) {
                val r = result
                val frames = r?.frames ?: 0
                val shortest = (ROOM_SHORTEST * ROOM_RATE).toInt().coerceAtMost(frames)
                ImpulsePicture(picture, pictureGen, if (frames > 0) trim / frames.toFloat() else 1f, Modifier.roomLine(240.dp).height(56.dp))
                Knob(
                    label = stringResource(Res.string.room_trim),
                    value = if (frames > shortest) (trim - shortest) / (frames - shortest).toFloat() else 1f,
                    display = if (r != null) "%.2f s".format(trim / ROOM_RATE) else "-", modifier = panelKnobWidth(),
                    onChange = { if (r != null) trim = shortest + ((frames - shortest) * it).toInt() },
                    onReset = r?.let { { trim = it.autoFrames } },
                )
                ButtonSquare("", stringResource(Res.string.room_listen), lit = false) {
                    if (r != null) scope.launch { withContext(Dispatchers.Default) { NativeEngine.roomListen(trim) } }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.sound_name), fontSize = 11.sp) },
                    modifier = Modifier.typing() then Modifier.roomLine(200.dp),
                )
                val readout = r?.let {
                    stringResource(
                        Res.string.room_readout, trim / ROOM_RATE,
                        stringResource(if (it.channels > 1) Res.string.sound_stereo else Res.string.sound_mono),
                        it.snrDb, it.latencyFrames * 1000f / ROOM_RATE,
                    )
                } ?: ""
                Box(Modifier.cardLine()) {
                    // Sized for the longest readout, so a turned window is wide enough for it on one line.
                    Text(
                        stringResource(Res.string.room_readout, 10f, stringResource(Res.string.sound_stereo), 99f, 999f),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        modifier = Modifier.alpha(0f).silent(),
                    )
                    Readout(readout, good = r != null)
                }
            }
        }
        // A warning, and what happened: the only lines under the cards.
        if (sweepPeak >= 0.99f && !working) {
            Text(stringResource(Res.string.room_clipped), color = c.red, fontSize = 11.sp)
        }
        if (message.isNotEmpty()) Text(message, color = if (failed) c.red else c.textDim, fontSize = 11.sp)
    }

    TabbedDialog(
        title = stringResource(Res.string.room_title),
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.close),
        confirmLabel = if (tab != 0) "" else stringResource(if (isSaved) Res.string.room_saved_button else Res.string.room_save),
        confirmEnabled = result != null && !sweeping && !working && !saving && name.isNotBlank() && !isSaved,
        onConfirm = confirm@{
            if (saving) return@confirm
            saving = true
            val frames = trim
            val at = Triple(pictureGen, trim, name)
            val file = File(dir, uniqueIn(dir, safeFileName(name, "room") + ".wav"))
            // Finishes even if the window closes meanwhile, so a saved file is always put on the Venue.
            scope.launch {
                withContext(NonCancellable) {
                    val error = withContext(Dispatchers.Default) { NativeEngine.roomSave(file.absolutePath, frames) }
                    saving = false
                    if (error.isEmpty()) {
                        message = resources.getString(Res.string.room_saved, file.nameWithoutExtension)
                        failed = false
                        savedAs = at
                        rooms++
                        onSaved?.invoke("${VenueRooms.FOLDER}/${file.name}")
                    } else {
                        message = error
                        failed = true
                    }
                }
            }
        },
        spacing = 6.dp,
        pageNames = listOf(stringResource(Res.string.room_tab_capture), stringResource(Res.string.room_tab_rooms)),
        onSelectPage = { tab = it },
        pages = listOf(capturePage, { RoomsPage(dir, rooms) { rooms++ } }),
    )
}

/** Told when a room's file is renamed (to its new setting) or deleted (to ""), so a Venue playing it can follow. Called with the old setting first. */
val LocalRoomChanged = androidx.compose.runtime.compositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

/** Your rooms, by name: tap to hear one, rename it, or delete it with two taps on the cross. */
@Composable
private fun RoomsPage(dir: File, generation: Int, onChanged: () -> Unit) {
    val c = Acid.colors
    val changed = LocalRoomChanged.current
    val files = remember(generation) {
        (dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("wav", "flac", "aif", "aiff") } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
    }
    var armed by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    if (files.isEmpty()) Text(stringResource(Res.string.room_none), fontSize = 12.sp, color = c.textDim)
    files.forEach { file ->
        val setting = "${VenueRooms.FOLDER}/${file.name}"
        val isArmed = armed == setting
        DialogRow(
            mark = "♪",
            name = file.nameWithoutExtension,
            under = if (isArmed) stringResource(Res.string.sound_delete_armed, Res.string.sound_delete_armed_mouse)
            else stringResource(Res.string.sound_file_info, com.rm.acidulous.util.dayAndTime(file.lastModified()), file.length() / 1024f),
            on = isArmed,
            monoUnder = !isArmed,
            onRemove = {
                if (!isArmed) {
                    armed = setting
                } else {
                    armed = null
                    NativeEngine.auditionFile("")
                    if (file.delete()) {
                        changed(setting, "")
                        onChanged()
                    }
                }
            },
        ) { armed = null; NativeEngine.auditionFile(file.absolutePath) }
        TextButton(onClick = { armed = null; renaming = file }) {
            Text(stringResource(Res.string.sound_rename), color = c.textMid, fontSize = 11.sp)
        }
    }
    renaming?.let { file ->
        TextInputDialog(
            title = stringResource(Res.string.sound_rename_title),
            initial = file.nameWithoutExtension,
            onDismiss = { renaming = null },
            onConfirm = { typed ->
                renaming = null
                val wanted = safeFileName(typed, file.nameWithoutExtension) + "." + file.extension
                if (wanted != file.name) {
                    val target = File(dir, uniqueIn(dir, wanted))
                    if (file.renameTo(target)) {
                        changed("${VenueRooms.FOLDER}/${file.name}", "${VenueRooms.FOLDER}/${target.name}")
                        onChanged()
                    }
                }
            },
        )
    }
}

/** A drawing's width: the card's upright, [wide] when the cards sit side by side, where filling would take a line. */
@Composable
private fun Modifier.roomLine(wide: androidx.compose.ui.unit.Dp): Modifier =
    if (LocalDialogWide.current) this.width(wide) else this.fillMaxWidth()

/** The impulse's peaks in dB, -60 to 0, with what the trim cuts off dimmed. */
@Composable
private fun ImpulsePicture(peaks: FloatArray, generation: Int, keep: Float, modifier: Modifier) {
    val c = Acid.colors
    val wave = c.teal
    val cut = c.textDim
    val back = c.sunken
    val marker = c.accent
    Canvas(modifier.silent()) {
        // Read so a new impulse redraws.
        generation.hashCode()
        drawRect(back)
        val n = peaks.size
        if (n == 0) return@Canvas
        val w = size.width / n
        for (i in 0 until n) {
            val h = ((peaks[i] + 60f) / 60f).coerceIn(0f, 1f) * size.height
            if (h <= 0f) continue
            val x = i * w
            drawRect(if (x / size.width <= keep) wave else cut, Offset(x, size.height - h), Size(w.coerceAtLeast(1f), h))
        }
        val at = keep.coerceIn(0f, 1f) * size.width
        drawRect(marker, Offset(at - 1f, 0f), Size(2f, size.height))
    }
}
