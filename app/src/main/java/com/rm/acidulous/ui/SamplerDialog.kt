package com.rm.acidulous.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import kotlinx.coroutines.delay
import java.io.File

/**
 * Recording into the instrument.
 *
 * Two sources, and they are not the same job. **In** is a microphone or
 * whatever is plugged into the interface, which is how a sample gets into
 * the machine in the first place. **Out** is the app's own master, which is
 * resampling: play something, capture it, and load it back as a sample to
 * play the result rather than the process. That second one is how a phone
 * gets to sound bigger than its own polyphony.
 *
 * Nothing is written by the audio thread. It pushes into a ring and a writer
 * thread puts it on disk, so a slow filesystem costs the recording a gap
 * rather than costing the output a glitch, and the dialog says which
 * happened.
 */
@Composable
fun SamplerDialog(onDismiss: () -> Unit, onRecorded: (String) -> Unit = {}) {
    val context = LocalContext.current
    val samples = remember { File(com.rm.acidulous.engine.EngineAssets.userRoot(context), "samples").apply { mkdirs() } }

    var fromInput by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(nextTakeName(samples)) }
    var monitor by remember { mutableStateOf(false) }
    var gain by remember { mutableStateOf(1f) }
    var level by remember { mutableStateOf(0f) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0f) }
    var peak by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var lastFile by remember { mutableStateOf<String?>(null) }

    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var havePermission by remember { mutableStateOf(granted) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        havePermission = ok
        if (ok) NativeEngine.startInput()
    }

    LaunchedEffect(fromInput, havePermission) {
        if (fromInput && havePermission && !NativeEngine.inputRunning) NativeEngine.startInput()
    }
    LaunchedEffect(monitor, gain) {
        NativeEngine.setMonitorLevel(if (monitor) 1f else 0f)
        NativeEngine.setInputGain(gain)
    }
    LaunchedEffect(Unit) {
        while (true) {
            level = if (fromInput) NativeEngine.inputPeak() else NativeEngine.readPeakLevel()
            recording = NativeEngine.capturing
            if (recording) {
                seconds = NativeEngine.capturedSeconds
                peak = NativeEngine.capturedPeak
            }
            delay(60)
        }
    }
    // Leave nothing running behind us: an open microphone is not something
    // to forget about, and the monitor would howl into the next screen.
    DisposableEffect(Unit) {
        onDispose {
            NativeEngine.stopCapture()
            NativeEngine.setMonitorLevel(0f)
            NativeEngine.stopInput()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!recording) onDismiss() },
        title = { Text("Record a sample", fontSize = 15.sp) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScrollWithBar(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceChip("in", fromInput) { if (!recording) fromInput = true }
                    SourceChip("out (resample)", !fromInput) { if (!recording) fromInput = false }
                }
                if (fromInput && !havePermission) {
                    Text("Recording needs permission to use the microphone.",
                        color = Color(0xFFE74C3C), fontSize = 11.sp)
                    TextButton(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("allow", color = Color(0xFFFFB454), fontSize = 12.sp)
                    }
                }

                Text("level", color = Color(0xFF7FD1B9), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Meter(level, Modifier.fillMaxWidth().height(10.dp), vertical = false)
                if (fromInput) {
                    Text("gain %.2f".format(gain), color = Color(0xFFDDDDE2), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Slider(value = gain, onValueChange = { gain = it }, valueRange = 0f..4f,
                        modifier = Modifier.fillMaxWidth().height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceChip(if (monitor) "monitor on" else "monitor off", monitor) { monitor = !monitor }
                        Text("  headphones only", color = Color(0xFF9A9AA2), fontSize = 10.sp)
                    }
                }

                Text("name", color = Color(0xFF7FD1B9), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !recording,
                )

                if (recording) {
                    Text("recording  %.1f s  peak %.2f".format(seconds, peak),
                        color = Color(0xFFE74C3C), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (NativeEngine.captureOverflowed) {
                        Text("the writer fell behind; this take has a gap in it",
                            color = Color(0xFFFFB454), fontSize = 10.sp)
                    }
                } else if (message.isNotEmpty()) {
                    Text(message, color = Color(0xFF9A9AA2), fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (recording) {
                                NativeEngine.stopCapture()
                                val file = lastFile
                                message = if (file != null) {
                                    "saved ${File(file).name}, %.1f s".format(seconds)
                                } else "saved"
                                file?.let { onRecorded("samples/" + File(it).name) }
                                name = nextTakeName(samples)
                            } else {
                                val safe = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { "take" }
                                val target = File(samples, if (safe.endsWith(".wav")) safe else "$safe.wav")
                                val error = NativeEngine.startCapture(target.absolutePath, if (fromInput) 0 else 1)
                                if (error.isEmpty()) {
                                    lastFile = target.absolutePath
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
                    if (!recording) TextButton(onClick = onDismiss) { Text("close") }
                }
            }
        },
        confirmButton = {},
    )
}

/** Samples this app recorded or imported, for machines to draw on. */
@Composable
fun SampleBrowserDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dir = remember { File(com.rm.acidulous.engine.EngineAssets.userRoot(context), "samples") }
    val files = remember {
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".wav", true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Samples", fontSize = 15.sp) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScrollWithBar(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (files.isEmpty()) {
                    Text("Nothing here yet. Record one, or import a WAV.", fontSize = 12.sp,
                        color = Color(0xFF9A9AA2))
                }
                files.forEach { file ->
                    OutlinedButton(
                        onClick = { onPick("samples/${file.name}") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(file.name, maxLines = 1, fontSize = 12.sp)
                            Text("%.1f kB".format(file.length() / 1024f), fontSize = 9.sp,
                                color = Color(0xFF9A9AA2), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SourceChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color(0xFF191B1E) else Color(0xFFDDDDE2),
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) Color(0xFF7FD1B9) else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun nextTakeName(dir: File): String {
    val used = (dir.listFiles() ?: emptyArray()).map { it.name.lowercase() }.toSet()
    var n = 1
    while (used.contains("take $n.wav")) n++
    return "take $n"
}
