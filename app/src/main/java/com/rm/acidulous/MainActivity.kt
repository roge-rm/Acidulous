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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.LaunchState
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
        com.rm.acidulous.ui.UiPrefs.init(this)
        com.rm.acidulous.midi.MidiHub.start(this)
        EngineAssets.install(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        goFullScreen()
        setContent {
            AcidulousTheme(com.rm.acidulous.ui.UiPrefs.theme) {
                // The bars are hidden, but a swipe brings them back, so their
                // icons still have to be readable against whichever theme is
                // running.
                val light = !com.rm.acidulous.ui.theme.Acid.colors.dark
                androidx.compose.runtime.LaunchedEffect(light) {
                    WindowCompat.getInsetsController(window, window.decorView).run {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = com.rm.acidulous.ui.theme.Acid.colors.bg,
                    // The bars are hidden, so their insets are not space this
                    // app has to give up. A camera cutout is - but only the
                    // sides and the bottom are taken here, because the top
                    // strip is where each screen's header lays itself out
                    // around the hole rather than below it (see ui/Cutout.kt).
                    contentWindowInsets = com.rm.acidulous.ui.AppContentInsets,
                ) { innerPadding ->
                    App(Modifier.padding(innerPadding))
                }
            }
        }
    }

    /**
     * The screen is the instrument. A phone gives back two strips of height
     * by hiding the status and navigation bars, which is a bar of piano roll
     * or a row of pads, and nothing in this app needs a clock on top of it.
     * The bars stay one swipe away and hide themselves again afterwards.
     */
    private fun goFullScreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog, a permission prompt or the recents screen brings them
        // back; take the height again as soon as we have focus.
        if (hasFocus) goFullScreen()
    }
}

private const val TAG = "Acidulous.UI"

/**
 * What the file picker offers when it is asked for audio.
 *
 * A match-anything wildcard was in this list - and inside this comment, until
 * it closed it - and is why the picker showed every file on the device, which
 * is not a chooser, it is a haystack. What is left is the four formats the app
 * can actually read, named specifically as well as by family because
 * providers disagree - `audio/wav` and `audio/x-wav` and `audio/vnd.wave` are
 * all the same file to three different pieces of Android.
 *
 * It is a *hint* and not a gate: a provider that reports nothing useful for a
 * file will hide it, and one that reports the wrong type will offer something
 * we cannot read. The gate is the decoder, which looks at the bytes - see
 * `sniff`. This only stops the picker wasting the player's time.
 */
private val AUDIO_TYPES = arrayOf(
    "audio/*",
    "audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/wave",
    "audio/aiff", "audio/x-aiff",
    "audio/flac", "audio/x-flac",
    "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3",
)

/** What the provider calls a file, or [fallback] when it will not say. */
private fun displayNameOf(context: android.content.Context, uri: android.net.Uri, fallback: String): String {
    var display = fallback
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) display = c.getString(i)
    }
    return display
}

private sealed class Screen {
    object Main : Screen()
    data class Edit(val track: Int, val sceneId: String) : Screen()
    // Nexus's graph needs a screen; a node canvas cannot live in the strip
    // under the piano roll.
    data class Patch(val track: Int, val sceneId: String) : Screen()

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
                    is Patch -> listOf("patch", v.track, v.sceneId)
                    else -> listOf("main")
                }
            },
            restore = { saved ->
                mutableStateOf(
                    when (saved.firstOrNull()) {
                        "edit" -> Edit(saved[1] as Int, saved[2] as String)
                        "patch" -> Patch(saved[1] as Int, saved[2] as String)
                        else -> Main
                    }
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
    // Hardware notes go where the last opened clip was, which is the track
    // the player is working on whether or not its editor is still in front.
    var midiTrack by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(screen) {
        (screen as? Screen.Edit)?.let { midiTrack = it.track }
        com.rm.acidulous.midi.MidiHub.target = { midiTrack }
    }

    // Importing a sample: the system picker, a copy into user/samples/, and the
    // pad's setting pointing at it. The engine loads it on the next sync.
    // (track, settings key): Forage keys a sample per pad, Pollen has one.
    val scope = rememberCoroutineScope()

    // Mosaic's instrument: a SoundFont preset, or WAVs turned into zones.
    var mapTarget by remember { mutableStateOf<Int?>(null) }
    var presetChoice by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var mapBusy by remember { mutableStateOf(false) }

    fun copyIn(uri: android.net.Uri, folder: String, fallback: String): java.io.File {
        val display = displayNameOf(context, uri, fallback)
        val safe = display.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { fallback }
        val dir = File(EngineAssets.userRoot(context), folder).apply { mkdirs() }
        val dest = File(dir, safe)
        context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    // Something the player did that did not work. The engine reports decode
    // failures from a worker, so this hops to the main thread before it
    // touches Compose state.
    var problem by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        EngineSync.onProblem = { message ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { problem = message }
        }
        onDispose { EngineSync.onProblem = null }
    }
    problem?.let { message ->
        com.rm.acidulous.ui.PlainDialog(
            title = "That would not load",
            onDismiss = { problem = null },
            dismissLabel = "Close",
        ) {
            Text(message, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
            Text(
                "Acidulous reads WAV, AIFF, FLAC and MP3 - mono or stereo, at any rate - " +
                    "and converts what it imports to WAV. M4A, Ogg and WMA it cannot read.",
                fontSize = 12.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textDim,
            )
        }
    }


    /**
     * What an import is doing, while it does it.
     *
     * Decoding a long mp3 takes seconds, all of them off the main thread and
     * none of them visible - Dan, on a long file: "it seems like nothing is
     * happening". [done] and [total] are for a kit, which is thirteen of
     * these one after another.
     */
    var converting by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    converting?.let { (what, done, total) ->
        com.rm.acidulous.ui.PlainDialog(
            title = if (total > 1) "Converting $done of $total" else "Converting",
            onDismiss = {},           // it finishes or it fails; there is nothing to cancel
            dismissLabel = "",
            spacing = 10.dp,
        ) {
            Text(what, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
            // Determinate for a kit, because files done out of files asked
            // for is real progress. Indeterminate for one file, because it
            // is one blocking decode and a bar that invented a position
            // would be a bar that lies - all this one has to say is that
            // something is still happening.
            if (total > 1) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { done.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    /**
     * One pad's sample, open over whatever is underneath.
     *
     * A window rather than a screen so that trimming a sound does not take
     * the editor away while you do it - see SampleDialog. Holds the track as
     * well as the pad, because the track it was opened from is the one it
     * belongs to even if the selection moves.
     */
    var sampleEdit by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // A track deleted under an open window would leave it addressing nothing.
    LaunchedEffect(song.tracks.size) {
        if (sampleEdit?.first?.let { it !in song.tracks.indices } == true) sampleEdit = null
    }
    sampleEdit?.takeIf { it.first in song.tracks.indices }?.let { (track, pad) ->
        com.rm.acidulous.ui.SampleDialog(
            track = song.tracks[track],
            trackIndex = track,
            pad = pad,
            editor = editor,
            onBack = { sampleEdit = null },
        )
    }

    /** Say so when only the front of a long file arrived. */
    fun noteTruncated(names: List<String>, seconds: Int = NativeEngine.PAD_SECONDS) {
        if (names.isEmpty()) return
        val long = if (seconds >= 120) "%d minutes".format(seconds / 60) else "$seconds seconds"
        problem = "Only the first $long of " + names.joinToString(", ") +
            " was imported - that is as much as a sample can hold."
    }

    /**
     * A file the player chose, copied in and made readable.
     *
     * Everything imported lands in `samples/` as a WAV whatever it arrived
     * as, so nothing past this point has to know that four formats exist.
     * Decoding a thirty-second FLAC is not instant, so it happens off the
     * main thread and [then] is called back on it with the path to store -
     * or not called at all, after saying why.
     */
    fun bringIn(uri: android.net.Uri, fallback: String, maxSeconds: Int = NativeEngine.PAD_SECONDS,
                then: (String) -> Unit) {
        scope.launch {
            converting = Triple(displayNameOf(context, uri, fallback), 1, 1)
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching { copyIn(uri, "samples", fallback) }.mapCatching { dest ->
                        val converted = NativeEngine.importAudio(dest.absolutePath, maxSeconds)
                        if (converted.isFailure) { dest.delete(); throw converted.exceptionOrNull()!! }
                        converted.getOrThrow()
                    }
                }
            } finally {
                // Whatever happened, the window goes: a modal that outlives
                // its work is worse than no window at all.
                converting = null
            }
            result
                .onSuccess { imported ->
                    then("samples/" + File(imported.path).name)
                    if (imported.truncated) noteTruncated(listOf(File(imported.path).name), maxSeconds)
                }
                .onFailure { problem = "That file would not load - ${it.message}." }
        }
    }

    var importTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val (track, key) = importTarget ?: return@rememberLauncherForActivityResult
        importTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        // A slice source is one file for the whole machine rather than one of
        // thirteen, so it is allowed to be a whole track. See SLICE_SECONDS.
        val seconds = if (key == "slice_sample") NativeEngine.SLICE_SECONDS else NativeEngine.PAD_SECONDS
        bringIn(uri, "sample.wav", seconds) { rel -> editor.edit(track) { t -> t.withSetting(key, rel) } }
    }

    // A whole kit in one trip.
    //
    // Building a Forage kit used to be thirteen round trips through the system
    // picker, because this launcher took one document and the pads are filled
    // one at a time. Mosaic's zones had been multi-select from the start; this
    // is the same contract, filling pads from the one that is selected
    // onwards, in the order the file names sort - which is the order a kit
    // folder is almost always numbered in.
    var kitTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val kitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val (track, firstPad) = kitTarget ?: return@rememberLauncherForActivityResult
        kitTarget = null
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val named = uris.map { uri ->
                var display = "sample.wav"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) display = c.getString(i)
                }
                display to uri
            }.sortedBy { it.first.lowercase() }
            val assigned = mutableListOf<Pair<String, String>>()
            val refused = mutableListOf<String>()
            val shortened = mutableListOf<String>()
            val wanted = named.size.coerceAtMost(13 - firstPad)
            try {
                named.forEachIndexed { i, (display, uri) ->
                    val pad = firstPad + i
                    if (pad > 12) return@forEachIndexed
                    // Set before each file rather than once, so a kit of
                    // thirteen counts up instead of sitting on "1 of 13".
                    converting = Triple(display, i + 1, wanted)
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val dest = copyIn(uri, "samples", display)
                            val converted = NativeEngine.importAudio(dest.absolutePath)
                            if (converted.isFailure) { dest.delete(); throw converted.exceptionOrNull()!! }
                            val imported = converted.getOrThrow()
                            if (imported.truncated) shortened += display
                            assigned += "p%02d_sample".format(pad) to "samples/" + File(imported.path).name
                        }.onFailure { refused += display }
                    }
                }
            } finally {
                converting = null
            }
            // One edit for the whole kit, so thirteen samples are one undo and
            // one autosave rather than thirteen of each.
            if (assigned.isNotEmpty()) {
                editor.edit(track) { t ->
                    var next = t
                    for ((key, rel) in assigned) next = next.withSetting(key, rel)
                    next
                }
            }
            // A kit is loaded in one go, so one file being unreadable must not
            // lose the other twelve - the rest land and this says which did not.
            if (refused.isNotEmpty()) {
                problem = "These would not load: " + refused.joinToString(", ") + "."
            } else {
                noteTruncated(shortened)
            }
        }
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
            val added = uris.mapNotNull { uri ->
                val dest = copyIn(uri, "samples", "sample.wav")
                val converted = NativeEngine.importAudio(dest.absolutePath)
                if (converted.isFailure) { dest.delete(); null }
                else com.rm.acidulous.model.Zone(path = "samples/" + File(converted.getOrThrow().path).name)
            }
            editor.edit(track) { t ->
                t.withSetting("sf2", null).withSetting("sf2preset", null)
                    .withSetting("zones", com.rm.acidulous.model.Zones.encode(existing + added))
            }
        }.onFailure { Log.w(TAG, "zone import failed", it) }
    }

    // Where the playhead is, which is also what "this scene" means.
    var position by remember { mutableStateOf(Position(0, 0, 0)) }
    // Beats left of a count-in, or 0 when the song is simply running.
    var countInBeats by remember { mutableStateOf(0) }

    // Exporting: the dialog chooses what and as what, the system picker gives
    // somewhere to put it, and the engine renders into the cache first. It
    // renders to a path and SAF only hands out a stream, so the copy at the
    // end is not a detour - it is the only way across.
    var exportState by remember { mutableStateOf<com.rm.acidulous.ui.ExportState?>(null) }
    var exportAsk by remember { mutableStateOf(false) }
    var exportWanted by remember { mutableStateOf(com.rm.acidulous.ui.ExportOptions()) }

    fun safeName(text: String): String =
        text.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "export" }

    /** One pass of a scene, in seconds: its own length, repeats aside. */
    fun sceneSeconds(scene: com.rm.acidulous.model.Scene): Float {
        val bpm = scene.tempo?.bpm ?: song.tempo
        val beats = song.signatureOf(scene).ticksPerBar.toFloat() / com.rm.acidulous.model.PPQN
        return song.barsOf(scene) * beats * 60f / bpm
    }

    /** What the export will produce, before it produces it, for the progress bar. */
    fun expectedSeconds(options: com.rm.acidulous.ui.ExportOptions): Float {
        if (!options.format.audio) return 0.1f
        val body = if (options.what == com.rm.acidulous.ui.ExportWhat.Scene) {
            song.scenes.getOrNull(position.scene)?.let { sceneSeconds(it) } ?: song.durationSeconds()
        } else {
            song.durationSeconds()
        }
        return body + options.tailSeconds
    }

    // Renders or writes into the cache. Returns the files in the order they
    // should be delivered, and an error if it did not get that far.
    suspend fun produceExport(options: com.rm.acidulous.ui.ExportOptions): Pair<List<File>, String> =
        withContext(Dispatchers.IO) {
            val base = safeName(song.name)
            val cache = context.cacheDir
            val scene = if (options.what == com.rm.acidulous.ui.ExportWhat.Scene) position.scene else 0
            val limit = if (options.what == com.rm.acidulous.ui.ExportWhat.Scene) {
                song.scenes.getOrNull(position.scene)?.let { sceneSeconds(it) } ?: 0f
            } else {
                0f
            }
            when (options.format) {
                com.rm.acidulous.ui.ExportFormat.Midi -> {
                    val file = File(cache, "$base.mid")
                    runCatching { com.rm.acidulous.model.MidiFile.write(song, file); listOf(file) to "" }
                        .getOrElse { emptyList<File>() to (it.message ?: "could not write the MIDI file") }
                }
                com.rm.acidulous.ui.ExportFormat.Bundle -> {
                    val file = File(cache, "$base.zip")
                    runCatching {
                        com.rm.acidulous.model.SongBundle.write(song, EngineAssets.userRoot(context), file)
                        listOf(file) to ""
                    }.getOrElse { emptyList<File>() to (it.message ?: "could not write the bundle") }
                }
                com.rm.acidulous.ui.ExportFormat.Aac -> {
                    // The platform encoder reads a file, so the render goes
                    // to a 16-bit WAV first and is transcoded off it.
                    val pcm = File(cache, "export-pcm.wav")
                    val out = File(cache, "$base.m4a")
                    val rendered = NativeEngine.renderSong(
                        pcm.absolutePath, options.tailSeconds, format = 0, bits = 16,
                        startScene = scene, maxSeconds = limit,
                    )
                    if (rendered.isNotEmpty()) {
                        pcm.delete()
                        emptyList<File>() to rendered
                    } else {
                        val error = com.rm.acidulous.media.AacEncoder.encode(pcm, out, options.rate * 1000)
                        pcm.delete()
                        if (error.isEmpty()) listOf(out) to "" else emptyList<File>() to error
                    }
                }
                else -> {
                    val engineFormat = options.format.engineFormat
                    // MP3 has no bit depth, so the number the sinks call
                    // `bits` carries its bitrate instead - see Mp3Writer.
                    val depth = if (options.format.lossy) options.rate else options.bits
                    if (options.what == com.rm.acidulous.ui.ExportWhat.Stems) {
                        val racks = song.tracks.indices.filter { song.tracks[it].machine.type.isNotEmpty() }
                        if (racks.isEmpty()) {
                            emptyList<File>() to "no tracks to render"
                        } else {
                            // The mix comes too, as file 00. It costs one
                            // more sink in a pass that is happening anyway,
                            // and stems without the mix they came from are
                            // hard to check and easy to misalign.
                            val files = listOf(File(cache, "00 Mix${options.format.extension}")) +
                                racks.map {
                                    File(cache, "%02d %s%s".format(it + 1, safeName(song.tracks[it].name), options.format.extension))
                                }
                            val error = NativeEngine.renderStems(
                                files.map { it.absolutePath }.toTypedArray(), (intArrayOf(-1) + racks.toIntArray()),
                                options.tailSeconds, engineFormat, depth, scene, limit,
                            )
                            if (error.isEmpty()) files to "" else emptyList<File>() to error
                        }
                    } else {
                        val file = File(cache, "$base${options.format.extension}")
                        val error = NativeEngine.renderSong(
                            file.absolutePath, options.tailSeconds, engineFormat, depth, scene, limit,
                        )
                        if (error.isEmpty()) listOf(file) to "" else emptyList<File>() to error
                    }
                }
            }
        }

    fun finish(options: com.rm.acidulous.ui.ExportOptions, files: List<File>, error: String, where: String) {
        exportState = if (error.isEmpty()) {
            com.rm.acidulous.ui.ExportState.Done(
                NativeEngine.renderedSeconds, NativeEngine.renderedPeak, where, files.size,
                options.format.label,
                if (options.format.audio && !options.format.lossy) options.bits else 0,
                if (options.format.lossy) options.rate else 0,
            )
        } else {
            com.rm.acidulous.ui.ExportState.Failed(error)
        }
        Log.i(TAG, "export ${if (error.isEmpty()) "ok" else "failed: $error"}: ${files.size} file(s), " +
            "%.2f s, peak %.3f".format(NativeEngine.renderedSeconds, NativeEngine.renderedPeak))
        for (f in files) f.delete()
    }

    /** One file: the picker already made the document, so just fill it. */
    // The contract is held separately from the launcher because the MIME
    // type is per export, and a launcher will not give its contract back.
    val fileContract = remember { CreateAnyDocument() }
    val filePicker = rememberLauncherForActivityResult(fileContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val options = exportWanted
        val expected = expectedSeconds(options)
        exportState = com.rm.acidulous.ui.ExportState.Running(0f, expected)
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(100)
                    exportState = com.rm.acidulous.ui.ExportState.Running(NativeEngine.renderedSeconds, expected)
                }
            }
            val (files, error) = produceExport(options)
            val copyError = if (error.isNotEmpty()) error else withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        files.first().inputStream().use { it.copyTo(out) }
                    }
                    ""
                }.getOrElse { it.message ?: "copy failed" }
            }
            ticker.cancel()
            finish(options, files, copyError, displayName(context, uri))
        }
    }

    /** Stems: several files, so the picker has to give up a folder instead. */
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val options = exportWanted
        val expected = expectedSeconds(options)
        exportState = com.rm.acidulous.ui.ExportState.Running(0f, expected)
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(100)
                    exportState = com.rm.acidulous.ui.ExportState.Running(NativeEngine.renderedSeconds, expected)
                }
            }
            val (files, error) = produceExport(options)
            val copyError = if (error.isNotEmpty()) error else withContext(Dispatchers.IO) {
                runCatching {
                    val parentId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                    for (file in files) {
                        val target = android.provider.DocumentsContract.createDocument(
                            context.contentResolver, parent, options.format.mime, file.name,
                        ) ?: error("could not create ${file.name}")
                        context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                    }
                    ""
                }.getOrElse { it.message ?: "copy failed" }
            }
            ticker.cancel()
            finish(options, files, copyError, displayName(context, tree))
        }
    }

    DisposableEffect(Unit) {
        EngineSync.sampleRoot = EngineAssets.userRoot(context)
        EngineSync.freezeRoot = EngineAssets.freezeRoot(context)
        // Trinity's wavetables take a moment to build; do it off the main
        // thread now rather than stalling the first mount.
        Thread { NativeEngine.prewarm() }.start()
        if (NativeEngine.start()) {
            // The engine keeps no preferences: the buffer depth, voice limit,
            // quality and record format have to be pushed once the stream is
            // up, and again whenever one of them changes.
            com.rm.acidulous.ui.UiPrefs.applyToEngine()
            // Link needs a Context for the multicast lock, so it cannot go
            // in applyToEngine with the rest; it is switched on here if it
            // was on when the app was last closed.
            if (com.rm.acidulous.ui.UiPrefs.linkWanted) {
                com.rm.acidulous.engine.LinkHub.setEnabled(context, true)
            }
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
    var bpm by remember { mutableStateOf(120f) }
    var armed by remember { mutableStateOf(false) }
    var loopScene by remember { mutableStateOf(false) }
    var stopAtEnd by remember { mutableStateOf(false) }
    var queuedScene by remember { mutableStateOf(-1) }
    // One per rack, read back each poll while clip mode is on. The engine is
    // the source of truth for what is playing, exactly as it is for queuedScene.
    val launchPacked = remember { LongArray(16) }
    var launchStates by remember { mutableStateOf(List(16) { LaunchState.idle }) }
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
    // --- Freeze ---------------------------------------------------------
    // The render takes the audio stream down for as long as it runs, so it
    // happens on a worker with the transport stopped, one clip at a time,
    // and the model is only touched back on the main thread.
    var freezeStatus by remember { mutableStateOf<String?>(null) }
    val onFreeze: (List<com.rm.acidulous.model.Freeze.Target>) -> Unit = { targets ->
        if (targets.isNotEmpty() && freezeStatus == null) {
            scope.launch {
                if (NativeEngine.isPlaying) {
                    NativeEngine.transportStop()
                    delay(120)
                }
                var done = 0
                targets.forEachIndexed { i, t ->
                    freezeStatus = "freezing ${i + 1} of ${targets.size}…"
                    val frozen = withContext(Dispatchers.IO) {
                        com.rm.acidulous.model.Freeze.render(context, editor.song, t)
                    }
                    if (frozen != null) {
                        ++done
                        editor.editClip(t.track, t.sceneId) { it.copy(frozen = frozen) }
                    }
                }
                freezeStatus = null
                Log.i(TAG, "froze $done of ${targets.size} clip(s)")
            }
        }
    }
    val onThaw: (List<com.rm.acidulous.model.Freeze.Target>) -> Unit = { targets ->
        for (t in targets) {
            if (editor.song.tracks.getOrNull(t.track)?.clips?.get(t.sceneId)?.frozen == null) continue
            com.rm.acidulous.model.Freeze.discard(context, editor.song, t)
            editor.editClip(t.track, t.sceneId) { it.copy(frozen = null) }
        }
    }

    val onArm: (Boolean) -> Unit = { on ->
        NativeEngine.recordArmed = on
        if (!on) applyRecorded(recorder.flush(song, sceneIdOf))
    }
    val onLoopScene: (Boolean) -> Unit = { on ->
        loopScene = on
        NativeEngine.setLoopScene(on)
    }
    // Hoisted, so the chip on screen and a mapped pad press the same thing.
    /**
     * Song to clip and back: flip the flag and get out of the way.
     *
     * The handover is the *scheduler's* - `SceneScheduler::process` watches
     * the flag change and does the whole of it. Going in, `adoptPlayingScene`
     * hands every rack the scene it is already playing at the phase it is
     * already at, so nothing restarts and nothing stops; the only difference
     * is that a clip now loops at the end of its cycle instead of the
     * arranger moving on. Coming out, the launcher runs to the next bar line
     * and `handBackToScenes` puts everyone on the scene most racks are
     * already playing, in phase.
     *
     * So there is nothing for this to do but say which mode it is. Two
     * previous versions of it did more and both broke the handover: one
     * called `transportStop`, which silenced the thing the scheduler was
     * about to adopt, and one queued fresh `launchClip` requests, which
     * restarted every clip from its cycle boundary on top of an adoption
     * that had already placed it correctly.
     */
    val onClipMode: (Boolean) -> Unit = { on ->
        com.rm.acidulous.ui.UiPrefs.chooseClipMode(on)
        NativeEngine.setLaunchQuantise(com.rm.acidulous.ui.UiPrefs.launchQuantise * song.signature.ticksPerBar)
    }

    // Controller mappings. The hub offers every CC and note-on here before it
    // reaches the engine; this decides whether it is being learned, drives
    // something, or is nobody's business and carries on as MIDI. It sits
    // here, below the transport's own handlers, so a mapped pad presses
    // exactly the button the screen would have pressed.
    com.rm.acidulous.midi.MidiHub.onMappable = onMappable@{ cc, note, value, routedRack ->
        val waiting = com.rm.acidulous.ui.UiPrefs.mapWaiting
        if (com.rm.acidulous.ui.UiPrefs.mapMode && waiting != null) {
            learnMapping(waiting, cc, note)
            return@onMappable true
        }
        val m = com.rm.acidulous.model.Mappings.find(
            song, com.rm.acidulous.ui.UiPrefs.mappings, cc = cc, note = note,
        ) ?: return@onMappable false
        val pressed = note != null || value >= com.rm.acidulous.model.Mappings.PRESS
        if (m.isAction) {
            // Fill is the only action that is *held* rather than triggered, so
            // it is the only one that wants the release as well as the press.
            if (m.action == com.rm.acidulous.model.Action.Fill.name) {
                com.rm.acidulous.ui.UiPrefs.holdFill(pressed)
            } else if (pressed) {
                when (m.action) {
                    com.rm.acidulous.model.Action.Play.name -> NativeEngine.transportPlay()
                    com.rm.acidulous.model.Action.Stop.name -> NativeEngine.transportStop()
                    com.rm.acidulous.model.Action.PlayStop.name ->
                        if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay()
                    com.rm.acidulous.model.Action.Panic.name -> { NativeEngine.panic(); com.rm.acidulous.midi.MidiHub.forgetSounding() }
                    com.rm.acidulous.model.Action.RecordArm.name -> { armed = !armed; onArm(armed) }
                    com.rm.acidulous.model.Action.LoopScene.name -> onLoopScene(!loopScene)
                    com.rm.acidulous.model.Action.ClipMode.name -> onClipMode(!com.rm.acidulous.ui.UiPrefs.clipMode)
                }
            }
        } else {
            fireMapping(m, value, note != null, routedRack, editor, midiTrack)
        }
        true
    }

    // A take that dies because the screen locked is a take lost, so the
    // window is held awake while the transport runs - and only while it
    // runs, and only if the setting says so.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(playing, com.rm.acidulous.ui.UiPrefs.keepAwake) {
        view.keepScreenOn = playing && com.rm.acidulous.ui.UiPrefs.keepAwake
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        while (true) {
            peak = NativeEngine.readPeakLevel()
            playing = NativeEngine.isPlaying
            position = Position.unpack(NativeEngine.positionPacked)
            countInBeats = countInBeatsOf(NativeEngine.countInRemaining)
            bpm = NativeEngine.tempo
            armed = NativeEngine.recordArmed
            notesOn = NativeEngine.notesOn(0)
            notesOff = NativeEngine.notesOff(0)
            load = NativeEngine.loadAvg
            xruns = NativeEngine.xRunCount
            fade = NativeEngine.masterFade
            stopAtEnd = NativeEngine.stopAtEnd
            queuedScene = NativeEngine.queuedScene
            com.rm.acidulous.midi.MidiHub.readSync()
            com.rm.acidulous.engine.LinkHub.poll()
            if (com.rm.acidulous.ui.UiPrefs.clipMode) {
                NativeEngine.launchStates(launchPacked)
                launchStates = launchPacked.map { LaunchState.unpack(it) }
            }
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

    // Load and xruns come first, after the stream's own description. They
    // used to be fifth and sixth in a line that is one ellipsised row, so on
    // a phone they were cut off the end - which mattered once the header
    // stopped showing the number and this became the only place it lives.
    val diagnostics = "%s · load %.0f%% · xruns %d · peak %.3f · fade %.2f · on %d off %d%s"
        .format(
            status, load, xruns, peak, fade, notesOn, notesOff,
            // Only while Link is on, and only the number that matters when
            // it is: how many machines are keeping this time.
            if (com.rm.acidulous.engine.LinkHub.enabled) {
                " · link %d".format(com.rm.acidulous.engine.LinkHub.peers)
            } else {
                ""
            },
        )

    if (exportAsk) {
        com.rm.acidulous.ui.ExportOptionsDialog(
            sceneName = song.scenes.getOrNull(position.scene)?.name.orEmpty(),
            onDismiss = { exportAsk = false },
        ) { options ->
            exportAsk = false
            exportWanted = options
            val base = safeName(song.name)
            if (options.manyFiles) {
                folderPicker.launch(null)
            } else {
                fileContract.mime = options.format.mime
                filePicker.launch(base + options.format.extension)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.rm.acidulous.ui.LocalSongMappings provides song.mappings,
    ) {
    /**
     * The system back button goes back a screen, and only leaves from the top.
     *
     * Nothing handled it at all, so back quit the app from wherever you were -
     * from a machine editor, from a Nexus graph, with a take unsaved. Windows
     * are not in here: a Compose Dialog is its own window and takes back for
     * itself, which is why the conversion window (whose dismiss does nothing)
     * cannot be dismissed out from under the work it is reporting.
     */
    androidx.activity.compose.BackHandler(enabled = screen !is Screen.Main) {
        screen = when (val s = screen) {
            // The graph belongs to a machine, so back goes to the machine
            // rather than all the way out - the same place its own arrow goes.
            is Screen.Patch -> Screen.Edit(s.track, s.sceneId)
            else -> Screen.Main
        }
    }

    when (val s = screen) {
        Screen.Main -> MainScreen(
            song = song, editor = editor, position = position, playing = playing, armed = armed,
            countInBeats = countInBeats,
            clipMode = com.rm.acidulous.ui.UiPrefs.clipMode,
            launchStates = launchStates,
            onClipMode = onClipMode,
            loopScene = loopScene, stopAtEnd = stopAtEnd, queuedScene = queuedScene,
            bpm = bpm, diagnostics = diagnostics,
            rackPeaks = rackPeaks, masterPeak = peak, clickOn = clickOn,
            onClick = { on -> clickOn = on; EngineSync.setMetronome(on, com.rm.acidulous.ui.UiPrefs.clickVolume, com.rm.acidulous.ui.UiPrefs.clickVoice, com.rm.acidulous.ui.UiPrefs.clickDivision, com.rm.acidulous.ui.UiPrefs.clickWhen) },
            onArm = onArm, onLoopScene = onLoopScene,
            onOpenClip = { track, sceneId -> screen = Screen.Edit(track, sceneId) },
            onSave = { SongStore.save(context, song); Log.i(TAG, "saved ${song.name}") },
            onSaveAs = { name -> val renamed = song.copy(name = name); editor.replace(renamed); SongStore.save(context, renamed); Log.i(TAG, "saved as $name") },
            onNew = { name -> val fresh = com.rm.acidulous.ui.UiPrefs.newSong(name); editor.replace(fresh); SongStore.save(context, fresh) },
            onLoad = { name -> runCatching { SongStore.load(context, name) }.onSuccess { editor.replace(it) }.onFailure { Log.w(TAG, "load failed", it) } },
            onDelete = { name -> SongStore.delete(context, name); Log.i(TAG, "deleted $name") },
            songNames = { SongStore.list(context) },
            onExport = { if (!playing) exportAsk = true },
            exportState = exportState,
            onExportCancel = { NativeEngine.cancelRender() },
            onExportDismiss = { exportState = null },
            onFreeze = onFreeze,
            onThaw = onThaw,
            freezeStatus = freezeStatus,
            modifier = modifier,
        )
        is Screen.Edit -> EditScreen(
            song = song, editor = editor, trackIndex = s.track, sceneId = s.sceneId,
            position = position, playing = playing, armed = armed, onArm = onArm,
            rackPeaks = rackPeaks, masterPeak = peak, clickOn = clickOn, onClick = { on -> clickOn = on; EngineSync.setMetronome(on, com.rm.acidulous.ui.UiPrefs.clickVolume, com.rm.acidulous.ui.UiPrefs.clickVoice, com.rm.acidulous.ui.UiPrefs.clickDivision, com.rm.acidulous.ui.UiPrefs.clickWhen) },
            onBack = { screen = Screen.Main },
            onOpenPatch = { screen = Screen.Patch(s.track, s.sceneId) },
            onOpenSample = { pad -> sampleEdit = s.track to pad },
            patchNames = { PatchStore.list(context, song.tracks[s.track].machine.type) },
            // The settings and not only the knobs: a Nexus patch without its
            // graph, a Mosaic without its zones or a Formulate without its
            // formula is a bag of numbers wired to whatever happened to be
            // loaded. Factory patches have always carried them; user ones
            // never could, and nothing said so - it just came back wrong.
            onSavePatch = { name, low, high ->
                val m = song.tracks[s.track].machine
                PatchStore.save(context, Patch(m.type, name, m.params, m.settings, low, high))
            },
            onLoadPatch = { name -> PatchStore.load(context, song.tracks[s.track].machine.type, name) },
            factoryPatchNames = { PatchStore.factory(song.tracks[s.track].machine.type) },
            userPatchNames = { PatchStore.userList(context, song.tracks[s.track].machine.type) },
            onDeletePatch = { name -> PatchStore.delete(context, song.tracks[s.track].machine.type, name) },
            onImportSample = { track, pad ->
                importTarget = track to "p%02d_sample".format(pad)
                samplePicker.launch(AUDIO_TYPES)
            },
            onImportOneSample = { track ->
                importTarget = track to "sample"
                samplePicker.launch(AUDIO_TYPES)
            },
            onImportKit = { track, pad ->
                kitTarget = track to pad
                kitPicker.launch(AUDIO_TYPES)
            },
            onImportSlice = { track ->
                // One file for all thirteen pads; the slice points are worked
                // out afterwards, in the panel.
                importTarget = track to "slice_sample"
                samplePicker.launch(AUDIO_TYPES)
            },
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
            onImportZoneSamples = { track -> mapTarget = track; zoneSamplePicker.launch(AUDIO_TYPES) },
            modifier = modifier,
        )
        is Screen.Patch -> com.rm.acidulous.ui.PatchScreen(
            track = song.tracks[s.track],
            trackIndex = s.track,
            editor = editor,
            onBack = { screen = Screen.Edit(s.track, s.sceneId) },
            modifier = modifier,
        )
    }
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

/**
 * `CreateDocument` fixes its MIME type when it is built, and this window
 * writes six different kinds of file. Rather than six launchers, the type
 * is set per launch - the picker uses it to suggest a folder and to name
 * the file sensibly, so it is worth getting right.
 */
/**
 * Ticks left of a count-in, as the number you would say out loud.
 *
 * Rounded *up*, because the first beat of a four-beat count should read
 * "4" for the whole of that beat rather than flicking to 3 immediately.
 */
/**
 * A control was waiting; this is what arrived. Bind them.
 *
 * The target is the string mapping mode parked there - `"rack:unit:name"`
 * for a parameter or `"action:Panic"` for a button. Learned mappings go to
 * the device, not the song: a controller is the room you are in, and the
 * commonest thing is to set one up once and forget it. A song may still
 * carry its own and they win; nothing in the UI writes those yet.
 */
private fun learnMapping(target: String, cc: Int?, note: Int?) {
    val prefs = com.rm.acidulous.ui.UiPrefs
    val parts = target.split(":")
    val mapping = when {
        parts.size == 2 && parts[0] == "action" ->
            com.rm.acidulous.model.Mapping(cc = cc, note = note, action = parts[1])
        parts.size == 3 -> com.rm.acidulous.model.Mapping(
            cc = cc, note = note, unit = parts[1], name = parts[2],
            rack = parts[0].toIntOrNull(),
        )
        else -> return
    }
    prefs.chooseMappings(com.rm.acidulous.model.Mappings.set(prefs.mappings, mapping))
    prefs.chooseMapWaiting(null)
}

/**
 * Something mapped arrived. Do what it says.
 *
 * A parameter takes the value; an action takes the press edge and nothing
 * else, so holding a footswitch does not fire it twice and letting go does
 * not fire it at all. A note on a two-step parameter toggles it, because
 * that is what a pad on a switch should do; on anything else it sets the
 * value from its velocity.
 */
private fun fireMapping(
    m: com.rm.acidulous.model.Mapping,
    value: Int,
    fromNote: Boolean,
    routedRack: Int,
    editor: com.rm.acidulous.model.SongEditor,
    selectedRack: Int,
) {
    val unit = m.unit ?: return
    val name = m.name ?: return
    // The master has no rack; everything else takes the mapping's own, then
    // whatever the MIDI routing chose, then the selected track.
    val rack = if (unit == "master") {
        0
    } else {
        m.rack ?: routedRack.takeIf { it in 0 until com.rm.acidulous.model.MAX_TRACKS } ?: selectedRack
    }
    val track = editor.song.tracks.getOrNull(rack)
    if (unit != "master" && track == null) return
    val switch = com.rm.acidulous.model.mappedIsSwitch(track, unit, name)
    val current = if (unit == "master") {
        com.rm.acidulous.model.currentMaster(editor.song.master, name)
    } else {
        com.rm.acidulous.model.currentMapped(track!!, unit, name)
    }
    val v01 = when {
        fromNote && switch -> if (current >= 0.5f) 0f else 1f
        else -> value / 127f
    }
    editor.applyMapped(rack, unit, name, v01)
}


private fun countInBeatsOf(ticks: Long): Int =
    if (ticks <= 0) 0 else ((ticks + com.rm.acidulous.model.PPQN - 1) / com.rm.acidulous.model.PPQN).toInt()

private class CreateAnyDocument : ActivityResultContracts.CreateDocument("*/*") {
    var mime: String = "*/*"
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        super.createIntent(context, input).setType(mime)
}

/**
 * What the system calls the place a file went, for the "done" line.
 *
 * A tree has no display name to query - asking gives back the whole
 * document id - so the folder's own name is taken off the end of it.
 */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String = runCatching {
    if (android.provider.DocumentsContract.isTreeUri(uri)) {
        val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
        return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { "the folder" }
    }
    var display = uri.lastPathSegment ?: "the file"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) display = c.getString(i)
    }
    display
}.getOrDefault("the file")
