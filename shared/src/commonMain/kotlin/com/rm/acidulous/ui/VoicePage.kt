package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.safeFileName
import com.rm.acidulous.io.File
import com.rm.acidulous.io.absolutePath
import com.rm.acidulous.io.deleteRecursively
import com.rm.acidulous.io.ZipWriter
import com.rm.acidulous.model.voice.VoiceImport
import com.rm.acidulous.AppHost
import kotlinx.coroutines.Dispatchers
import com.rm.acidulous.util.IO
import kotlinx.coroutines.withContext
import com.rm.acidulous.io.name
import com.rm.acidulous.io.writeBytesSafely
import com.rm.acidulous.model.INPUT_SLOTS
import com.rm.acidulous.model.inputUnit
import com.rm.acidulous.model.voice.Prompt
import com.rm.acidulous.model.voice.TakeCut
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

/** The count between the note and the take, in beats, and how long a beat is. */
private const val COUNT_BEATS = 3
private const val COUNT_BEAT_MS = 400L

private enum class Singing { No, Tone, Count, Take }

/**
 * Where each part of a prompt goes in the take, as a label and where it ends
 * along the bar. The end is left empty, so a voice can finish.
 */
private fun guide(prompt: Prompt): List<Pair<String, Float>> = when {
    prompt.glides -> VoicePrompts.DIPHTHONG_PARTS[prompt.sounds[0]]
        ?.let { (from, to) -> listOf(from to 0.7f, to to 0.9f) } ?: listOf(prompt.sung to 0.85f)
    prompt.held -> listOf(prompt.sung to 0.85f)
    else -> VoicePrompts.sungOf(prompt.sounds[0]).let { v -> listOf(v to 0.36f, prompt.letters to 0.52f, v to 0.9f) }
}

/**
 * Where the singer was shown to put a prompt's consonant, in seconds into a
 * take's file [seconds] long, or -1 for a vowel. Counted from the end, since
 * a take always ends [TAKE_SECONDS] after it began, however long the count
 * before it ran, or whether there was one.
 */
private fun consonantAt(prompt: Prompt, seconds: Float): Float {
    if (prompt.held || seconds <= 0f) return -1f
    val parts = guide(prompt)
    return (seconds - TAKE_SECONDS * (1f - (parts[0].second + parts[1].second) / 2f)).coerceAtLeast(0f)
}

/** Cuts a take up, off the page's thread. Null if the engine's answer can't be read. */
private suspend fun cutOf(prompt: Prompt, file: File, note: Int): TakeCut? {
    val kind = when {
        prompt.glides -> 1
        prompt.held -> 0
        else -> 2
    }
    val noteHz = 440f * 2f.pow((note - 69) / 12f)
    return withContext(Dispatchers.IO) {
        // "name|frames|channels|rate|peak", read from the end since a name may hold anything.
        val info = NativeEngine.fileInfo(file.absolutePath).split('|')
        val frames = info.getOrNull(info.size - 4)?.toFloatOrNull() ?: 0f
        val rate = info.getOrNull(info.size - 2)?.toFloatOrNull() ?: 0f
        val seconds = if (rate > 0f) frames / rate else 0f
        TakeCut.parse(NativeEngine.cutTake(file.absolutePath, kind, noteHz, consonantAt(prompt, seconds)))
    }
}

/** Why a take should be sung again, as the singer reads it. */
@Composable
private fun problemText(problem: String): String = when (problem) {
    "too quiet" -> stringResource(Res.string.voice_problem_quiet)
    "too loud" -> stringResource(Res.string.voice_problem_loud)
    "too short" -> stringResource(Res.string.voice_problem_short)
    "no clear note" -> stringResource(Res.string.voice_problem_note)
    "no consonant found" -> stringResource(Res.string.voice_problem_consonant)
    "consonant unclear" -> stringResource(Res.string.voice_problem_unclear)
    "glide too soon" -> stringResource(Res.string.voice_problem_glide)
    "unreadable" -> stringResource(Res.string.voice_problem_unreadable)
    else -> problem
}

/** The take's progress over where each part of the prompt goes. */
@Composable
private fun GuideBar(parts: List<Pair<String, Float>>, progress: Float, recording: Boolean, modifier: Modifier = Modifier) {
    val c = Acid.colors
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            drawRect(c.sunken)
            var from = 0f
            parts.forEachIndexed { i, (_, to) ->
                drawRect(if (i % 2 == 0) c.cardHi else c.cardAlt, Offset(from * size.width, 0f), Size((to - from) * size.width, size.height))
                from = to
            }
            if (progress > 0f) drawRect(if (recording) c.red else c.accent, size = Size(progress * size.width, size.height), alpha = 0.75f)
            for ((_, to) in parts) drawLine(c.text, Offset(to * size.width, 0f), Offset(to * size.width, size.height), 1.5f)
        }
        Row(Modifier.fillMaxWidth()) {
            var from = 0f
            for ((label, to) in parts) {
                Text(label, Modifier.weight(to - from), color = c.textMid, fontSize = 12.sp, textAlign = TextAlign.Center)
                from = to
            }
            if (from < 1f) Spacer(Modifier.weight(1f - from))
        }
    }
}

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
    /** Whether the song has each input effect bypassed, to put back after a take. */
    inputBypass: (slot: Int) -> Boolean,
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
    var zipping by remember { mutableStateOf(false) }
    // How many takes are in the zip so far, for the share button.
    var zipped by remember { mutableStateOf(0) }
    var hz by remember { mutableStateOf(0f) }
    var count by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var cutting by remember { mutableStateOf(false) }

    // The input has to be running for a take, and for the tuner.
    LaunchedEffect(havePermission) { if (havePermission) NativeEngine.startInput(UiPrefs.inputDevice) }
    LaunchedEffect(Unit) {
        while (true) {
            hz = tunerHz()
            delay(120)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            level = NativeEngine.inputPeak()
            delay(50)
        }
    }

    // Takes not cut yet, or cut by an older cutter, cut now, one at a time.
    LaunchedEffect(bank?.name) {
        val name = bank?.name ?: return@LaunchedEffect
        val dir = VoiceBank.folderOf(root, name)
        for (p in VoicePrompts.all) {
            val take = bank?.takes?.get(p.id) ?: continue
            val was = bank?.cuts?.get(p.id)
            if (was != null && (was.by == TakeCut.CUTTER || was.hand)) continue
            val cut = cutOf(p, File(dir, take), bank?.note ?: continue) ?: continue
            // Sung again meanwhile, or another voice chosen: this cut is stale.
            val now = bank ?: break
            if (now.name != name) break
            if (now.takes[p.id] != take || now.cuts[p.id] != was || singing != Singing.No) continue
            val next = now.copy(cuts = now.cuts + (p.id to cut))
            VoiceBank.save(dir, next)
            bank = next
        }
        com.rm.acidulous.engine.EngineSync.voicesChanged()
    }

    // A take is the voice as it is: the mic raw, and without the effects the
    // record page prints into a take. Both go back as they were after it.
    fun takeInputOver() {
        if (UiPrefs.inputClean) NativeEngine.setInputClean(false)
        for (s in 0 until INPUT_SLOTS) NativeEngine.setParam(0, inputUnit(s), "bypass", 1f, record = false)
    }
    fun giveInputBack() {
        if (UiPrefs.inputClean) NativeEngine.setInputClean(true)
        for (s in 0 until INPUT_SLOTS) NativeEngine.setParam(0, inputUnit(s), "bypass", if (inputBypass(s)) 1f else 0f, record = false)
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
            takeInputOver()
            try {
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
                // Recording through the count, so a voice that comes in early is
                // kept. The cutter finds where it starts.
                singing = Singing.Count
                for (beat in COUNT_BEATS downTo 1) {
                    count = beat
                    delay(COUNT_BEAT_MS)
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
                progress = 0f
                onRecording(false)
                // Cut straight away, so a take that has to be sung again says so
                // while the singer is still here.
                val cut = cutOf(prompt, take, b.note)
                val now = bank ?: b
                val next = now.copy(
                    takes = now.takes + (prompt.id to take.name),
                    cuts = if (cut != null) now.cuts + (prompt.id to cut) else now.cuts - prompt.id,
                )
                VoiceBank.save(dir, next)
                bank = next
                banks = VoiceBank.all(root)
                com.rm.acidulous.engine.EngineSync.voicesChanged()
                singing = Singing.No
                // On to the next one not done, after this one. A take that has
                // to be sung again stays, saying why.
                if (next.done(prompt)) {
                    val after = VoicePrompts.all.drop(at + 1).firstOrNull { !next.done(it) } ?: next.nextToSing()
                    if (after != null) at = VoicePrompts.all.indexOf(after)
                }
            } finally {
                giveInputBack()
            }
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
        if (zipping) return
        zipping = true
        zipped = 0
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
                        zipped++
                    }
                    w.close()
                    file
                }
            }
            zipping = false
            zip.mapCatching { AppHost.current.shareFile(it, "application/zip", b.name) }
                .onFailure { message = it.message ?: resources.getString(Res.string.voice_share_failed) }
        }
    }

    // A voice someone shared: into the voices folder, and chosen.
    var importing by remember { mutableStateOf(false) }
    val importPicker = com.rm.acidulous.rememberOpenDocument { doc ->
        if (doc == null) return@rememberOpenDocument
        importing = true
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(EngineAssets.cacheRoot(), "voice-import.zip")
                    AppHost.current.copyFromDoc(doc, tmp)
                    try { VoiceImport.read(tmp, root) } finally { tmp.delete() }
                }.getOrNull()
            }
            importing = false
            if (name == null) {
                message = resources.getString(Res.string.voice_import_failed)
            } else {
                banks = VoiceBank.all(root)
                bank = banks.firstOrNull { it.name == name }
                com.rm.acidulous.engine.EngineSync.voicesChanged()
            }
        }
    }

    val busy = singing != Singing.No || zipping || importing

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
                TextButton(onClick = { importPicker(arrayOf("application/zip")) }, enabled = !busy) {
                    Text(stringResource(Res.string.voice_import), color = c.accent, fontSize = 12.sp)
                }
                if (bank != null) {
                    TextButton(onClick = { share() }, enabled = !busy && bank?.takes?.isNotEmpty() == true) {
                        Text(if (zipping) stringResource(Res.string.voice_zipping, zipped, bank?.takes?.size ?: 0) else stringResource(Res.string.voice_share), color = c.accent, fontSize = 12.sp)
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
                        (1..VoicePrompts.stages).joinToString("  ·  ") { s ->
                            if (s == 1) resources.getString(Res.string.voice_stage, b.doneIn(s), VoicePrompts.inStage(s).size)
                            else resources.getString(Res.string.voice_stage_extra, VoicePrompts.carrierOf(s), b.doneIn(s), VoicePrompts.inStage(s).size)
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
                    val problem = b.problemOf(prompt)
                    Text(
                        prompt.sung,
                        color = when {
                            singing == Singing.Take -> c.red
                            problem.isNotEmpty() -> c.pink
                            b.sung(prompt) -> c.teal
                            else -> c.text
                        },
                        fontSize = 30.sp,
                    )
                    // A held vowel is like the vowel of a word; a consonant prompt
                    // is about the consonant, not the whole word.
                    val hint = if (prompt.held) stringResource(Res.string.voice_like, prompt.like)
                        else stringResource(Res.string.voice_the_in, prompt.letters, prompt.like)
                    Text(hint, color = c.textDim, fontSize = 12.sp)
                    // How close the voice is to the note, while it sings, in
                    // whichever octave it sings: a low voice sings it an octave down.
                    val cents = if (hz > 0f) 1200f * log2(hz / (440f * 2f.pow((b.note - 69) / 12f))) else Float.NaN
                    val centsOff = cents - 1200f * kotlin.math.round(cents / 1200f)
                    Text(
                        when {
                            singing == Singing.Tone -> stringResource(Res.string.voice_listen)
                            singing == Singing.Count -> "$count"
                            singing == Singing.Take -> if (cents.isNaN()) stringResource(Res.string.voice_sing_now)
                                else stringResource(Res.string.voice_cents, centsOff.roundToInt().let { if (it > 0) "+$it" else "$it" })
                            message.isNotEmpty() -> message
                            problem.isNotEmpty() -> stringResource(Res.string.voice_sing_again, problemText(problem))
                            else -> stringResource(Res.string.voice_count, at + 1, VoicePrompts.all.size)
                        },
                        color = if (singing == Singing.Take) c.red else if (singing == Singing.No && problem.isNotEmpty()) c.pink else c.textMid,
                        fontSize = if (singing == Singing.Count || singing == Singing.Take) 20.sp else 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    GuideBar(guide(prompt), if (singing == Singing.Take) progress else 0f, singing == Singing.Take, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.voice_mic), color = c.textDim, fontSize = 12.sp)
                        Meter(level, Modifier.weight(1f).height(6.dp), vertical = false, track = c.sunken)
                    }
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
                    TextButton(onClick = { cutting = true }, enabled = !busy && b.takes[prompt.id] != null) {
                        Text(stringResource(Res.string.voice_cut), fontSize = 12.sp)
                    }
                    TextButton(onClick = { if (at < VoicePrompts.all.size - 1) at++ }, enabled = !busy && at < VoicePrompts.all.size - 1) {
                        Text("▶", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    // A take's cut, moved by hand.
    val b = bank
    val dir = folder()
    val takeName = b?.takes?.get(VoicePrompts.all[at].id)
    if (cutting && b != null && dir != null && takeName != null) {
        val prompt = VoicePrompts.all[at]
        val take = File(dir, takeName)
        VoiceCutDialog(
            prompt = prompt,
            file = take,
            cut = b.cuts[prompt.id],
            consonantNear = { seconds -> consonantAt(prompt, seconds) },
            recut = { cutOf(prompt, take, b.note) },
            onKeep = { cut ->
                val now = bank ?: b
                val next = now.copy(cuts = now.cuts + (prompt.id to cut))
                VoiceBank.save(dir, next)
                bank = next
                com.rm.acidulous.engine.EngineSync.voicesChanged()
                cutting = false
            },
            onDismiss = { cutting = false },
        )
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
