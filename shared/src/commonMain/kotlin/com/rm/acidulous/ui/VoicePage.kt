package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.safeFileName
import com.rm.acidulous.io.File
import com.rm.acidulous.io.absolutePath
import com.rm.acidulous.io.deleteRecursively
import com.rm.acidulous.io.ZipWriter
import com.rm.acidulous.AppHost
import kotlinx.coroutines.Dispatchers
import com.rm.acidulous.util.IO
import kotlinx.coroutines.withContext
import com.rm.acidulous.io.name
import com.rm.acidulous.io.writeBytesSafely
import com.rm.acidulous.model.voice.Tone
import com.rm.acidulous.model.voice.VoiceBank
import com.rm.acidulous.model.voice.VoicePrompts
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** How long a take runs: long enough to sing a prompt without hurrying. */
private const val TAKE_SECONDS = 2.5f

private enum class Singing { No, Tone, Take }

/**
 * Recording a voice for Diction: a list of short things to sing, one at a
 * time, each on the same note. Tap sing, hear the note, sing, and it moves
 * on. A voice is saved as it goes, so it can be finished another day.
 */
@Composable
internal fun VoicePage(
    havePermission: Boolean,
    askPermission: () -> Unit,
    /** The input's pitch now, or 0, from the window's tuner. */
    tunerHz: () -> Float,
    onRecording: (Boolean) -> Unit,
) {
    val c = Acid.colors
    val resources = AppStrings
    val root = remember { EngineAssets.userRoot() }
    var banks by remember { mutableStateOf(VoiceBank.all(root)) }
    var bank by remember { mutableStateOf(banks.firstOrNull()) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // The prompt shown: where this voice left off.
    var at by remember(bank?.name) {
        mutableStateOf(bank?.nextToSing()?.let { VoicePrompts.all.indexOf(it) }?.coerceAtLeast(0) ?: 0)
    }
    var singing by remember { mutableStateOf(Singing.No) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var hz by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    // The input has to be running for a take, and for the tuner.
    LaunchedEffect(havePermission) { if (havePermission) NativeEngine.startInput(UiPrefs.inputDevice) }
    LaunchedEffect(Unit) {
        while (true) {
            hz = tunerHz()
            delay(120)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (job?.isActive == true) {
                job?.cancel()
                NativeEngine.stopCapture()
                onRecording(false)
            }
        }
    }

    fun folder() = bank?.let { VoiceBank.folderOf(root, it.name) }

    fun sing() {
        val b = bank ?: return
        val dir = folder() ?: return
        val prompt = VoicePrompts.all[at]
        job = scope.launch {
            onRecording(true)
            message = ""
            // The note first, on its own, so it isn't in the take.
            val tone = File(dir, "tone-${b.note}.wav")
            if (!tone.exists()) tone.writeBytesSafely(Tone.wav(b.note))
            singing = Singing.Tone
            NativeEngine.auditionFile(tone.absolutePath)
            delay((Tone.SECONDS * 1000f).toLong() + 150L)
            // Recorded to a part file and moved into place when finished, so a
            // take cut short never replaces a good one.
            val part = File(dir, "${prompt.id}.part.wav")
            val error = NativeEngine.startCapture(part.absolutePath, 0)
            if (error.isNotEmpty()) {
                message = error
                singing = Singing.No
                onRecording(false)
                return@launch
            }
            singing = Singing.Take
            // Timed by the clock, not by counting waits: on a busy phone
            // the waits run long and a take ran twice its length.
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                val elapsed = started.elapsedNow().inWholeMilliseconds / 1000f
                progress = (elapsed / TAKE_SECONDS).coerceAtMost(1f)
                if (elapsed >= TAKE_SECONDS) break
                delay(30)
            }
            NativeEngine.stopCapture()
            val take = File(dir, "${prompt.id}.wav")
            take.delete()
            part.renameTo(take)
            val next = b.copy(takes = b.takes + (prompt.id to take.name))
            VoiceBank.save(dir, next)
            bank = next
            banks = VoiceBank.all(root)
            singing = Singing.No
            progress = 0f
            onRecording(false)
            // On to the next one not yet sung, after this one.
            val after = VoicePrompts.all.drop(at + 1).firstOrNull { !next.sung(it) } ?: next.nextToSing()
            if (after != null) at = VoicePrompts.all.indexOf(after)
        }
    }

    /**
     * The voice as a zip for the share sheet: its index and its takes, in a
     * folder named after it. Not the reference tone, which is remade, or a
     * take cut short.
     */
    fun share() {
        val b = bank ?: return
        val dir = folder() ?: return
        scope.launch {
            val zip = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(EngineAssets.cacheRoot(), "shared").apply { deleteRecursively(); mkdirs() }
                    val file = File(out, safeFileName(b.name, "voice") + ".zip")
                    val w = ZipWriter(file)
                    w.addFile("${b.name}/${VoiceBank.INDEX}", File(dir, VoiceBank.INDEX))
                    for (take in b.takes.values) {
                        val f = File(dir, take)
                        if (f.exists()) w.addFile("${b.name}/$take", f)
                    }
                    w.close()
                    file
                }
            }
            zip.mapCatching { AppHost.current.shareFile(it, "application/zip", b.name) }
                .onFailure { message = it.message ?: resources.getString(Res.string.voice_share_failed) }
        }
    }

    val busy = singing != Singing.No

    WindowCards {
        WindowCard(stringResource(Res.string.voice_card)) {
            if (banks.isNotEmpty()) {
                SwitchGrid(
                    stringResource(Res.string.voice_which), banks.map { it.name },
                    banks.indexOfFirst { it.name == bank?.name }, columns = 1,
                    enabled = banks.map { !busy },
                ) { bank = banks[it] }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { naming = true }, enabled = !busy) {
                    Text(stringResource(Res.string.voice_new), color = c.accent, fontSize = 12.sp)
                }
                if (bank != null) {
                    TextButton(onClick = { share() }, enabled = !busy && bank?.takes?.isNotEmpty() == true) {
                        Text(stringResource(Res.string.voice_share), color = c.accent, fontSize = 12.sp)
                    }
                    TextButton(onClick = { deleting = true }, enabled = !busy) {
                        Text(stringResource(Res.string.voice_delete), color = c.red, fontSize = 12.sp)
                    }
                }
            }
            bank?.let { b ->
                // The note every prompt is sung on. A voice keeps it, since its
                // takes are all at that pitch.
                Knob(
                    label = stringResource(Res.string.voice_note),
                    value = (b.note - 36) / 36f,
                    display = noteName(b.note),
                    accent = PanelAmber,
                    modifier = panelKnobWidth(),
                    onChange = { v ->
                        if (!busy && b.takes.isEmpty()) {
                            val note = (36 + v * 36f).roundToInt().coerceIn(36, 72)
                            if (note != b.note) {
                                val next = b.copy(note = note)
                                folder()?.let { VoiceBank.save(it, next) }
                                bank = next
                            }
                        }
                    },
                )
                Box(Modifier.cardLine()) {
                    Readout(
                        (1..VoicePrompts.stages).joinToString("  ") { s ->
                            resources.getString(Res.string.voice_stage, s, b.doneIn(s), VoicePrompts.inStage(s).size)
                        },
                        good = b.doneIn(1) == VoicePrompts.inStage(1).size,
                    )
                }
            }
        }

        val b = bank
        if (b != null) {
            val prompt = VoicePrompts.all[at]
            WindowCard(stringResource(Res.string.voice_prompt)) {
                Column(Modifier.cardLineFull(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(prompt.sung, color = if (b.sung(prompt)) c.teal else c.text, fontSize = 30.sp)
                    // A held vowel is like the vowel of a word; a consonant prompt
                    // is about the consonant, not the whole word.
                    val hint = if (prompt.held) stringResource(Res.string.voice_like, prompt.like)
                        else stringResource(Res.string.voice_the_in, prompt.letters, prompt.like)
                    Text(hint, color = c.textDim, fontSize = 12.sp)
                    // How close the voice is to the note, while it sings.
                    val cents = if (hz > 0f) 1200f * log2(hz / (440f * 2f.pow((b.note - 69) / 12f))) else Float.NaN
                    Text(
                        when {
                            singing == Singing.Tone -> stringResource(Res.string.voice_listen)
                            singing == Singing.Take -> if (cents.isNaN()) stringResource(Res.string.voice_sing_now)
                                else stringResource(Res.string.voice_cents, cents.roundToInt().let { if (it > 0) "+$it" else "$it" })
                            message.isNotEmpty() -> message
                            else -> stringResource(Res.string.voice_count, at + 1, VoicePrompts.all.size)
                        },
                        color = if (singing == Singing.Take) c.red else c.textMid,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                    Meter(if (singing == Singing.Take) progress else 0f, Modifier.fillMaxWidth().height(6.dp), vertical = false, track = c.sunken)
                }
                Row(Modifier.cardLineFull(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (at > 0) at-- }, enabled = !busy && at > 0) { Text("◀", fontSize = 16.sp) }
                    Button(
                        onClick = { if (havePermission) sing() else askPermission() },
                        enabled = !busy,
                        modifier = Modifier.width(96.dp),
                    ) { Text(stringResource(if (b.sung(prompt)) Res.string.voice_again else Res.string.voice_sing)) }
                    TextButton(
                        onClick = { folder()?.let { NativeEngine.auditionFile(File(it, "${prompt.id}.wav").absolutePath) } },
                        enabled = !busy && b.sung(prompt),
                    ) { Text(stringResource(Res.string.voice_play), fontSize = 12.sp) }
                    TextButton(onClick = { if (at < VoicePrompts.all.size - 1) at++ }, enabled = !busy && at < VoicePrompts.all.size - 1) {
                        Text("▶", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    // Every take goes with it, so it's asked first.
    bank?.takeIf { deleting }?.let { b ->
        PlainDialog(
            title = stringResource(Res.string.voice_delete_title, b.name),
            onDismiss = { deleting = false },
            confirmLabel = stringResource(Res.string.voice_delete),
            onConfirm = {
                VoiceBank.folderOf(root, b.name).deleteRecursively()
                banks = VoiceBank.all(root)
                bank = banks.firstOrNull()
                deleting = false
            },
        ) {
            Text(stringResource(Res.string.voice_delete_note, b.takes.size), color = c.textMid, fontSize = 13.sp)
        }
    }
    if (naming) {
        TextInputDialog(
            title = stringResource(Res.string.voice_new),
            initial = resources.getString(Res.string.voice_default_name),
            onDismiss = { naming = false },
        ) { typed ->
            naming = false
            val name = safeFileName(typed, "voice")
            val dir = VoiceBank.folderOf(root, name)
            val made = VoiceBank.load(dir) ?: VoiceBank(name).also { VoiceBank.save(dir, it) }
            banks = VoiceBank.all(root)
            bank = made
        }
    }
}
