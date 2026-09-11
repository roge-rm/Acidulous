package com.rm.acidulous

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.engine.Recorder
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.PatchStore
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.SongStore
import com.rm.acidulous.model.durationSeconds
import com.rm.acidulous.model.withSetting
import java.io.File
import com.rm.acidulous.ui.EditScreen
import com.rm.acidulous.ui.MainScreen
import com.rm.acidulous.ui.theme.AcidulousTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineAssets.install(this)
        enableEdgeToEdge()
        setContent {
            AcidulousTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    App(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private const val TAG = "Acidulous.UI"

private sealed class Screen {
    object Main : Screen()
    data class Edit(val track: Int, val sceneId: String) : Screen()

    companion object {
        /**
         * The activity keeps itself across a rotation (see the manifest), so
         * this only runs if Android really did recreate us - process death,
         * "don't keep activities". Either way the screen comes back.
         */
        val Saver: Saver<MutableState<Screen>, Any> = listSaver<MutableState<Screen>, Any>(
            save = { state ->
                when (val v = state.value) {
                    is Edit -> listOf("edit", v.track, v.sceneId)
                    else -> listOf("main")
                }
            },
            restore = { saved ->
                mutableStateOf(
                    if (saved.firstOrNull() == "edit") Edit(saved[1] as Int, saved[2] as String) else Main
                )
            },
        )
    }
}

@Composable
private fun App(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Referential, not structural: Song equality is by value (rev is outside
    // equals on purpose), so a value-equal load or edit would otherwise be a
    // silently dropped write.
    var song by remember { mutableStateOf(DemoSong.build(), referentialEqualityPolicy()) }
    var lastPushMs by remember { mutableStateOf(0L) }
    val editor = remember {
        SongEditor(song) { edited, pushNow ->
            song = edited
            // Taps and gesture ends push at once; mid-gesture updates throttle to ~15 Hz.
            val now = SystemClock.uptimeMillis()
            if (pushNow || now - lastPushMs >= 66) {
                EngineSync.sync(edited)
                lastPushMs = now
            }
        }
    }
    val recorder = remember { Recorder() }
    var screen by rememberSaveable(saver = Screen.Saver) { mutableStateOf<Screen>(Screen.Main) }

    // Importing a sample: the system picker, a copy into user/samples/, and the
    // pad's setting pointing at it. The engine loads it on the next sync.
    var importTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val (track, pad) = importTarget ?: return@rememberLauncherForActivityResult
        importTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            var display = "sample.wav"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) display = c.getString(i)
            }
            val safe = display.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { "sample.wav" }
            val dir = File(EngineAssets.userRoot(context), "samples").apply { mkdirs() }
            val dest = File(dir, safe)
            context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
            editor.edit(track) { t -> t.withSetting("p%02d_sample".format(pad), "samples/$safe") }
        }.onFailure { Log.w(TAG, "sample import failed", it) }
    }
    val scope = rememberCoroutineScope()

    // Mosaic's instrument: a SoundFont preset, or WAVs turned into zones.
    var mapTarget by remember { mutableStateOf<Int?>(null) }
    var presetChoice by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var mapBusy by remember { mutableStateOf(false) }

    fun copyIn(uri: android.net.Uri, folder: String, fallback: String): java.io.File {
        var display = fallback
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) display = c.getString(i)
        }
        val safe = display.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { fallback }
        val dir = File(EngineAssets.userRoot(context), folder).apply { mkdirs() }
        val dest = File(dir, safe)
        context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    var status by remember { mutableStateOf("starting…") }

    val soundFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val track = mapTarget ?: return@rememberLauncherForActivityResult
        mapTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dest = copyIn(uri, "soundfonts", "instrument.sf2")
            val rel = "soundfonts/${dest.name}"
            editor.edit(track) { t -> t.withSetting("zones", null).withSetting("sf2", rel).withSetting("sf2preset", "0") }
            // Listing presets reads the file, so it waits for a worker.
            mapBusy = true
            scope.launch {
                val presets = withContext(Dispatchers.IO) { NativeEngine.soundFontPresets(dest.absolutePath) }
                mapBusy = false
                if (presets.isEmpty()) {
                    val why = withContext(Dispatchers.IO) { NativeEngine.soundFontError(dest.absolutePath) }
                    Log.w(TAG, "soundfont ${dest.name}: ${why.ifEmpty { "no presets" }}")
                } else if (presets.size > 1) {
                    presetChoice = track to presets
                }
            }
        }.onFailure { Log.w(TAG, "soundfont import failed", it) }
    }

    val zoneSamplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val track = mapTarget ?: return@rememberLauncherForActivityResult
        mapTarget = null
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        runCatching {
            val existing = com.rm.acidulous.model.Zones.decode(song.tracks[track].machine.settings["zones"])
            val added = uris.map { uri ->
                val dest = copyIn(uri, "samples", "sample.wav")
                com.rm.acidulous.model.Zone(path = "samples/${dest.name}")
            }
            editor.edit(track) { t ->
                t.withSetting("sf2", null).withSetting("sf2preset", null)
                    .withSetting("zones", com.rm.acidulous.model.Zones.encode(existing + added))
            }
        }.onFailure { Log.w(TAG, "zone import failed", it) }
    }

    // Exporting: the system save picker gives a target; the engine renders the
    // song offline into the cache, and the file is copied into the target.
    var exportState by remember { mutableStateOf<com.rm.acidulous.ui.ExportState?>(null) }
    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val expected = song.durationSeconds() + 2f
        exportState = com.rm.acidulous.ui.ExportState.Running(0f, expected)
        val temp = File(context.cacheDir, "export.wav")
        scope.launch {
            val progress = launch {
                while (true) { delay(100); exportState = com.rm.acidulous.ui.ExportState.Running(NativeEngine.renderedSeconds, expected) }
            }
            val error = withContext(Dispatchers.IO) {
                val e = NativeEngine.renderSong(temp.absolutePath, tailSeconds = 2f)
                if (e.isNotEmpty()) e else runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ""
                }.getOrElse { it.message ?: "copy failed" }
            }
            progress.cancel()
            val name = runCatching {
                var display = uri.lastPathSegment ?: "export.wav"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) display = c.getString(i)
                }
                display
            }.getOrDefault("export.wav")
            exportState = if (error.isEmpty()) com.rm.acidulous.ui.ExportState.Done(NativeEngine.renderedSeconds, NativeEngine.renderedPeak, name)
            else com.rm.acidulous.ui.ExportState.Failed(error)
            Log.i(TAG, "export ${if (error.isEmpty()) "ok" else "failed: $error"}: %.2f s, peak %.3f".format(NativeEngine.renderedSeconds, NativeEngine.renderedPeak))
            temp.delete()
        }
    }

    DisposableEffect(Unit) {
        EngineSync.sampleRoot = EngineAssets.userRoot(context)
        // Trinity's wavetables take a moment to build; do it off the main
        // thread now rather than stalling the first mount.
        Thread { NativeEngine.prewarm() }.start()
        if (NativeEngine.start()) {
            // Come back to whatever was open. Only a first run falls through
            // to the demo - reloading it every launch used to overwrite
            // Demo.json and throw away the session.
            val restored = runCatching { SongStore.loadSession(context) }.getOrNull()
            val loaded = restored ?: DemoSong.build().also {
                if (!SongStore.exists(context, it.name)) SongStore.save(context, it)
            }
            editor.replace(loaded)
            Log.i(TAG, if (restored != null) "resumed '${loaded.name}'" else "first run: built the demo")
            status = "${NativeEngine.sampleRate / 1000}k · burst ${NativeEngine.framesPerBurst}"
        } else {
            status = "engine failed to start"
        }
        onDispose { NativeEngine.stop() }
    }

    var peak by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Position(0, 0, 0)) }
    var bpm by remember { mutableStateOf(120f) }
    var armed by remember { mutableStateOf(false) }
    var loopScene by remember { mutableStateOf(false) }
    var stopAtEnd by remember { mutableStateOf(false) }
    var queuedScene by remember { mutableStateOf(-1) }
    var notesOn by remember { mutableStateOf(0) }
    var notesOff by remember { mutableStateOf(0) }
    var load by remember { mutableStateOf(0f) }
    var xruns by remember { mutableStateOf(0L) }
    var fade by remember { mutableStateOf(1f) }
    var rackPeaks by remember { mutableStateOf(FloatArray(16)) }
    var clickOn by remember { mutableStateOf(false) }

    val sceneIdOf: (Long) -> String? = { id -> song.scenes.firstOrNull { it.engineId == id }?.id }
    fun applyRecorded(result: Recorder.Result) {
        if (result.song !== song) {
            result.song.tracks.forEachIndexed { i, t -> if (t !== song.tracks.getOrNull(i)) editor.edit(i, push = false) { t } }
        }
        if (result.push) EngineSync.sync(editor.song)
    }
    val onArm: (Boolean) -> Unit = { on ->
        NativeEngine.recordArmed = on
        if (!on) applyRecorded(recorder.flush(song, sceneIdOf))
    }
    val onLoopScene: (Boolean) -> Unit = { on ->
        loopScene = on
        NativeEngine.setLoopScene(on)
    }

    LaunchedEffect(Unit) {
        while (true) {
            peak = NativeEngine.readPeakLevel()
            playing = NativeEngine.isPlaying
            position = Position.unpack(NativeEngine.positionPacked)
            bpm = NativeEngine.tempo
            armed = NativeEngine.recordArmed
            notesOn = NativeEngine.notesOn(0)
            notesOff = NativeEngine.notesOff(0)
            load = NativeEngine.loadAvg
            xruns = NativeEngine.xRunCount
            fade = NativeEngine.masterFade
            stopAtEnd = NativeEngine.stopAtEnd
            queuedScene = NativeEngine.queuedScene
            rackPeaks = FloatArray(16) { i -> if (i < song.tracks.size) NativeEngine.readRackPeak(i) else 0f }
            if (armed || playing) applyRecorded(recorder.poll(song, position, playing, sceneIdOf))
            delay(80)
        }
    }

    // Autosave: 1.2 s after the last edit, and again the moment the app goes
    // to the background, because Android may kill the process from there.
    LaunchedEffect(song) {
        delay(1200)
        runCatching { SongStore.saveSession(context, song) }.onFailure { Log.w(TAG, "session autosave failed", it) }
    }
    val currentSong by rememberUpdatedState(song)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { SongStore.saveSession(context, currentSong) }
                    .onFailure { Log.w(TAG, "session save on stop failed", it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val diagnostics = "%s · peak %.3f · fade %.2f · load %.0f%% · xruns %d · on %d off %d".format(status, peak, fade, load, xruns, notesOn, notesOff)

    when (val s = screen) {
        Screen.Main -> MainScreen(
            song = song, editor = editor, position = position, playing = playing, armed = armed,
            loopScene = loopScene, stopAtEnd = stopAtEnd, queuedScene = queuedScene,
            bpm = bpm, diagnostics = diagnostics,
            rackPeaks = rackPeaks, masterPeak = peak, clickOn = clickOn,
            onClick = { on -> clickOn = on; EngineSync.setMetronome(on) },
            onArm = onArm, onLoopScene = onLoopScene,
            onOpenClip = { track, sceneId -> screen = Screen.Edit(track, sceneId) },
            onSave = { SongStore.save(context, song); Log.i(TAG, "saved ${song.name}") },
            onSaveAs = { name -> val renamed = song.copy(name = name); editor.replace(renamed); SongStore.save(context, renamed); Log.i(TAG, "saved as $name") },
            onNew = { name -> val fresh = SongStore.blank(name); editor.replace(fresh); SongStore.save(context, fresh) },
            onLoad = { name -> runCatching { SongStore.load(context, name) }.onSuccess { editor.replace(it) }.onFailure { Log.w(TAG, "load failed", it) } },
            onDelete = { name -> SongStore.delete(context, name); Log.i(TAG, "deleted $name") },
            songNames = { SongStore.list(context) },
            onExport = { if (!playing) wavPicker.launch(song.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".wav") },
            exportState = exportState,
            onExportCancel = { NativeEngine.cancelRender() },
            onExportDismiss = { exportState = null },
            modifier = modifier,
        )
        is Screen.Edit -> EditScreen(
            song = song, editor = editor, trackIndex = s.track, sceneId = s.sceneId,
            position = position, playing = playing, armed = armed, onArm = onArm,
            onBack = { screen = Screen.Main },
            patchNames = { PatchStore.list(context, song.tracks[s.track].machine.type) },
            onSavePatch = { name -> PatchStore.save(context, Patch(song.tracks[s.track].machine.type, name, song.tracks[s.track].machine.params)) },
            onLoadPatch = { name -> PatchStore.load(context, song.tracks[s.track].machine.type, name)?.params },
            factoryPatchNames = { PatchStore.factoryNames(song.tracks[s.track].machine.type) },
            userPatchNames = { PatchStore.userList(context, song.tracks[s.track].machine.type) },
            onDeletePatch = { name -> PatchStore.delete(context, song.tracks[s.track].machine.type, name) },
            onImportSample = { track, pad -> importTarget = track to pad; samplePicker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) },
            onImportSoundFont = { track -> mapTarget = track; soundFontPicker.launch(arrayOf("*/*")) },
            onPickPreset = { track ->
                val rel = song.tracks[track].machine.settings["sf2"]
                if (rel != null) {
                    mapBusy = true
                    scope.launch {
                        val path = File(EngineAssets.userRoot(context), rel).absolutePath
                        val presets = withContext(Dispatchers.IO) { NativeEngine.soundFontPresets(path) }
                        mapBusy = false
                        if (presets.isNotEmpty()) presetChoice = track to presets
                    }
                }
            },
            onImportZoneSamples = { track -> mapTarget = track; zoneSamplePicker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) },
            modifier = modifier,
        )
    }

    // Choosing which preset of a SoundFont to play. Shown over either screen,
    // because the import that raises it starts from the Edit screen but the
    // listing finishes on a worker.
    presetChoice?.let { (track, presets) ->
        fun label(line: String): String {
            val f = line.split('|')
            return if (f.size >= 3) "%03d:%03d  %s".format(f[0].toIntOrNull() ?: 0, f[1].toIntOrNull() ?: 0, f[2]) else line
        }
        com.rm.acidulous.ui.PickerDialog(
            title = "SoundFont preset",
            options = presets.map { label(it) },
            onDismiss = { presetChoice = null },
        ) { chosen ->
            val index = presets.indexOfFirst { label(it) == chosen }
            if (index >= 0) editor.edit(track) { t -> t.withSetting("sf2preset", index.toString()) }
            presetChoice = null
        }
    }
}

