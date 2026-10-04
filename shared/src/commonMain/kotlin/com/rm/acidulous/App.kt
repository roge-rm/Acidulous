package com.rm.acidulous

import com.rm.acidulous.io.*

import com.rm.acidulous.util.IO

import com.rm.acidulous.util.System

import com.rm.acidulous.util.Math

import com.rm.acidulous.util.format

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.LaunchState
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.engine.Recorder
import com.rm.acidulous.engine.uniqueIn
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.PatchStore
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.SongStore
import com.rm.acidulous.model.cleared
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.duplicateScene
import com.rm.acidulous.model.durationSeconds
import com.rm.acidulous.model.emptyClipFor
import com.rm.acidulous.model.marksFrom
import com.rm.acidulous.model.passSeconds
import com.rm.acidulous.model.splitTake
import com.rm.acidulous.model.updateClip
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.withTake
import com.rm.acidulous.model.writeTextSafely
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.BiasArm
import com.rm.acidulous.ui.EditScreen
import com.rm.acidulous.ui.MainScreen
import com.rm.acidulous.ui.TakePeaks
import com.rm.acidulous.ui.theme.AcidulousTheme
import com.rm.acidulous.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything above [App]: the interface scale, the theme, the Scaffold and
 * the splash. This is all the platform draws. [onLightTheme] is told whether
 * the theme is light, for whatever the platform draws around the app (on
 * Android, the colour of the status and navigation bar icons).
 */
@Composable
fun AppRoot(onLightTheme: (Boolean) -> Unit = {}) {
    // One density above everything.
    //
    // The interface scale multiplies the density rather than each size, so
    // every `dp` and `sp` in the tree goes through this one `Density` and the
    // whole app scales together. Composition locals reach into a `Dialog`'s
    // subcomposition too, so windows scale with it.
    //
    // Outside `AcidulousTheme` because the splash and the Scaffold's insets
    // are inside it and should scale too. Always computed from `base` rather
    // than from `LocalDensity`, or the multiplier would compound on every
    // recomposition.
    //
    // At a bigger scale the app just sees less screen (at 1.3 a Pixel 5
    // reports 302 x 655 dp instead of 393 x 851), which every screen already
    // handles. ui/UiScale.kt limits the scale to what the screen allows.
    val base = androidx.compose.ui.platform.LocalDensity.current
    // The window's size, whatever the platform calls its window.
    val windowPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val scale = com.rm.acidulous.ui.appliedScale(
        com.rm.acidulous.ui.UiPrefs.uiScale,
        minOf(windowPx.width, windowPx.height) / base.density,
        maxOf(windowPx.width, windowPx.height) / base.density,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(base.density * scale, base.fontScale),
        com.rm.acidulous.ui.LocalUiScale provides scale,
        // The unscaled density goes along too, because Compose gives every
        // window drawn over this one a fresh density and it has to reapply
        // the scale itself. See ui/UiScale.kt.
        com.rm.acidulous.ui.LocalBaseDensity provides base,
    ) {
    AcidulousTheme(com.rm.acidulous.ui.UiPrefs.theme) {
        // For the platform's own bars.
        val light = !com.rm.acidulous.ui.theme.Acid.colors.dark
        androidx.compose.runtime.LaunchedEffect(light) { onLightTheme(light) }
        Scaffold(
            // Whether anything in the app has focus, so an arrow can find
            // somewhere to start from when nothing does (see KeyHub.preview).
            modifier = Modifier.fillMaxSize()
                .then(androidx.compose.ui.Modifier.onFocusChanged { com.rm.acidulous.ui.KeyHub.anyFocused = it.hasFocus }),
            containerColor = com.rm.acidulous.ui.theme.Acid.colors.bg,
            // The bars are hidden, so their insets aren't space the app has
            // to give up. A camera cutout is, but only the sides and bottom
            // are taken here, because each screen's header lays itself out
            // around the hole in the top strip (see ui/Cutout.kt).
            contentWindowInsets = com.rm.acidulous.ui.AppContentInsets,
        ) { innerPadding ->
            App(Modifier.padding(innerPadding))
        }
        // Over the Scaffold, not inside it. The splash is a screen rather
        // than a window (see ui/SplashScreen.kt for why the system's own
        // shows nothing), and a Scaffold's content slot only draws one
        // child. In a Box of its own it's on top.
        //
        // The app is composed underneath the whole time, so the engine
        // starts during the splash rather than after it.
        var splashing by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(true)
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(com.rm.acidulous.ui.SplashMillis)
            splashing = false
        }
        if (splashing) com.rm.acidulous.ui.SplashScreen()
    }
    }
}

private const val TAG = "Acidulous.UI"

/** Remembers that the demo has been opened once: see the start of [App]. */
private const val FIRST_RUN = "first_run"
private const val DEMO_OPENED = "demo_opened"

/**
 * What the file picker offers when asked for audio.
 *
 * Only the four formats the app can read, with no match-anything wildcard
 * (which made the picker show every file on the device). Each is named
 * specifically as well as by family, because providers disagree: `audio/wav`,
 * `audio/x-wav` and `audio/vnd.wave` are all the same file to different parts
 * of Android.
 *
 * It's a hint, not a check. A provider that reports nothing useful hides the
 * file, and one that reports the wrong type offers something we can't read.
 * The real check is the decoder, which looks at the bytes (see `sniff`).
 */
private val AUDIO_TYPES = arrayOf(
    "audio/*",
    "audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/wave",
    "audio/aiff", "audio/x-aiff",
    "audio/flac", "audio/x-flac",
    "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3",
)

private sealed class Screen {
    object Main : Screen()
    data class Edit(val track: Int, val sceneId: String) : Screen()
    // Nexus's graph needs a whole screen. A node canvas can't fit in the
    // strip under the piano roll.
    data class Patch(val track: Int, val sceneId: String) : Screen()

    companion object {
        /**
         * The activity keeps itself across a rotation (see the manifest), so
         * this only runs if Android really recreated it (process death, or
         * "don't keep activities"). Either way the screen comes back.
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
fun App(modifier: Modifier = Modifier) {
    // Read through this rather than the context, so text follows a change of language.
    val resources = AppStrings

    // Referential, not structural: Song equality is by value (rev is left out
    // of equals on purpose), so a value-equal load or edit would otherwise be
    // silently dropped.
    var song by remember { mutableStateOf(DemoSong.build(), referentialEqualityPolicy()) }
    var lastPushMs by remember { mutableStateOf(0L) }
    val editor = remember {
        SongEditor(song) { edited, pushNow ->
            song = edited
    // Taps and gesture ends push at once. Mid-gesture updates throttle to ~15 Hz.
            val now = System.nanoTime() / 1_000_000L
            if (pushNow || now - lastPushMs >= 66) {
                EngineSync.sync(edited)
                lastPushMs = now
            }
        }
    }
    val recorder = remember { Recorder() }
    // Record quantise and replace, as set in Settings, record.
    androidx.compose.runtime.SideEffect {
        recorder.quantise = com.rm.acidulous.ui.UiPrefs.recordQuantise
        recorder.strength = com.rm.acidulous.ui.UiPrefs.recordStrength / 100f
        recorder.replace = com.rm.acidulous.ui.UiPrefs.recordReplace
    }
    var screen by rememberSaveable(saver = Screen.Saver) { mutableStateOf<Screen>(Screen.Main) }
    // Hardware notes go to the track of the last opened clip, which is the
    // one being worked on whether or not its editor is still open.
    var midiTrack by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(screen) {
        (screen as? Screen.Edit)?.let { midiTrack = it.track }
        com.rm.acidulous.midi.MidiHub.target = { midiTrack }
        com.rm.acidulous.ui.KeyHub.target = { midiTrack }
    }
    // An Exquis shows the scale of the track it plays, the same one the roll
    // would show: its own Scale modifier, or else the song's key. The routing
    // decides which track, the same rule its notes follow.
    val exquisTrack = song.tracks.getOrNull(com.rm.acidulous.midi.MidiHub.trackForChannel(com.rm.acidulous.midi.MidiHub.exquisChannel))
    val exquisScale = exquisTrack?.let {
        com.rm.acidulous.model.Scales.rootFor(song, it) to com.rm.acidulous.model.Scales.activeFor(song, it)
    }
    LaunchedEffect(exquisScale) {
        com.rm.acidulous.midi.MidiHub.showScale(exquisScale?.first, exquisScale?.second)
    }
    // Typed notes go where hardware notes do. On a drum machine they're its
    // pads in order rather than a scale.
    androidx.compose.runtime.SideEffect {
        // A controller's notes follow the track's scale chip, or else the song's key.
        com.rm.acidulous.ui.KeyHub.scaleOf = { rack ->
            song.tracks.getOrNull(rack)?.let { t ->
                com.rm.acidulous.model.Scales.activeFor(song, t)?.let { classes ->
                    (com.rm.acidulous.model.Scales.rootFor(song, t) ?: classes.minOrNull() ?: 0) to classes
                }
            }
        }
        com.rm.acidulous.ui.KeyHub.drumVoices = { rack ->
            song.tracks.getOrNull(rack)?.machine?.let { m ->
                if (com.rm.acidulous.model.MachineUi.kindOf(m.type) == com.rm.acidulous.model.MachineKind.Drums) {
                    com.rm.acidulous.model.MachineUi.voicesOf(m.type, m.settings).map { it.note }
                } else null
            }
        }
    }
    // With nothing focused, an arrow focuses the screen's first control (see
    // KeyHub.fallback). Moving to the next with nothing focused does that.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.SideEffect {
        com.rm.acidulous.ui.KeyHub.focusFirst = {
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
        }
    }
    // A controller's sticks, each frame while one is off centre (see Pad).
    androidx.compose.runtime.LaunchedEffect(com.rm.acidulous.ui.Pad.active) {
        if (!com.rm.acidulous.ui.Pad.active) {
            com.rm.acidulous.ui.Pad.tick(0f) // lets go of a turn
            return@LaunchedEffect
        }
        var last = 0L
        while (com.rm.acidulous.ui.Pad.active) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L) com.rm.acidulous.ui.Pad.tick(((t - last) / 1e9).toFloat().coerceAtMost(0.1f))
                last = t
            }
        }
        com.rm.acidulous.ui.Pad.tick(0f)
    }
    // The keys every screen handles the same way.
    com.rm.acidulous.ui.KeyScope(
        com.rm.acidulous.ui.KeyAction.PlayMode to { com.rm.acidulous.ui.KeyHub.togglePlayMode() },
        com.rm.acidulous.ui.KeyAction.Panic to { com.rm.acidulous.ui.panicEverything() },
        com.rm.acidulous.ui.KeyAction.KeysHelp to { com.rm.acidulous.ui.KeyHub.showingKeys = true },
        // No Back here: on the song screen back leaves the app, and Esc is
        // pressed too casually for that. The editor decides what back does.
    )
    com.rm.acidulous.ui.KeyHub.actionMenu?.let { actions ->
        com.rm.acidulous.ui.KeyActionMenu(actions, onDismiss = { com.rm.acidulous.ui.KeyHub.actionMenu = null })
    }
    if (com.rm.acidulous.ui.KeyHub.showingKeys) {
        com.rm.acidulous.ui.KeysOverlay(onDismiss = { com.rm.acidulous.ui.KeyHub.showingKeys = false })
    }

    // Importing a sample: the system picker, a copy into user/samples/, and
    // the pad's setting pointing at it. The engine loads it on the next sync.
    // (track, settings key): Forage keys a sample per pad, Pollen has one.
    val scope = rememberCoroutineScope()

    // Mosaic's instrument: a SoundFont preset, or WAVs turned into zones.
    var mapTarget by remember { mutableStateOf<Int?>(null) }
    var presetChoice by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var mapBusy by remember { mutableStateOf(false) }

    fun copyIn(uri: Doc, folder: String, fallback: String): com.rm.acidulous.io.File {
        val display = AppHost.current.docName(uri, fallback)
        val safe = display.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { fallback }
        val dir = File(EngineAssets.userRoot(), folder).apply { mkdirs() }
        val dest = File(dir, safe)
        AppHost.current.copyFromDoc(uri, dest)
        return dest
    }

    // Something the user did that didn't work. The engine reports decode
    // failures from a worker, so this switches to the main thread before it
    // touches Compose state.
    var problem by remember { mutableStateOf<String?>(null) }
    /** The file the microphone is writing to while a Bias lane is armed. */
    var biasTakeFile by remember { mutableStateOf<com.rm.acidulous.io.File?>(null) }
    DisposableEffect(Unit) {
        EngineSync.onProblem = { message, args ->
            com.rm.acidulous.util.postToMain { problem = AppStrings.getString(message, *args) }
        }
        onDispose { EngineSync.onProblem = null }
    }
    problem?.let { message ->
        com.rm.acidulous.ui.PlainDialog(
            title = stringResource(Res.string.app_load_failed_title),
            onDismiss = { problem = null },
            dismissLabel = stringResource(Res.string.close),
        ) {
            Text(message, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
            Text(
                stringResource(Res.string.app_load_formats),
                fontSize = 12.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textDim,
            )
        }
    }


    /**
     * What an import is doing, while it does it.
     *
     * Decoding a long mp3 takes seconds off the main thread, so this shows
     * that something is happening. [done] and [total] are for a kit, which is
     * thirteen of these in a row.
     */
    var converting by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    converting?.let { (what, done, total) ->
        com.rm.acidulous.ui.PlainDialog(
            title = if (total > 1) stringResource(Res.string.app_converting_of, done, total) else stringResource(Res.string.app_converting),
            onDismiss = {},           // it finishes or fails; there is nothing to cancel
            dismissLabel = "",
            spacing = 10.dp,
        ) {
            Text(what, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
            // A real progress bar for a kit, since files done out of files
            // asked for is real progress. Indeterminate for one file, since
            // it's one blocking decode with no position to show.
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
     * A window rather than a screen so trimming a sound doesn't take the
     * editor away (see SampleDialog). Holds the track as well as the pad,
     * because it belongs to the track it was opened from even if the
     * selection moves.
     */
    var sampleEdit by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // A track deleted under an open window would leave it pointing at nothing,
    // and the keys would play a track that isn't there any more.
    LaunchedEffect(song.tracks.size) {
        if (sampleEdit?.first?.let { it !in song.tracks.indices } == true) sampleEdit = null
        if (song.tracks.isNotEmpty() && midiTrack !in song.tracks.indices) midiTrack = song.tracks.size - 1
    }
    // The editor draws nothing for a track or scene that's gone, and its back
    // button goes with it, so leave it for the grid instead.
    LaunchedEffect(song.tracks.size, song.scenes) {
        val s = screen as? Screen.Edit ?: return@LaunchedEffect
        if (s.track !in song.tracks.indices || song.scenes.none { it.id == s.sceneId }) screen = Screen.Main
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

    /** Tell the user when only the start of a long file was imported. */
    fun noteTruncated(names: List<String>, seconds: Int = NativeEngine.PAD_SECONDS) {
        if (names.isEmpty()) return
        val long = if (seconds >= 120) AppStrings.getQuantityString(Res.plurals.app_minutes, seconds / 60, seconds / 60)
            else AppStrings.getQuantityString(Res.plurals.app_seconds, seconds, seconds)
        problem = AppStrings.getString(Res.string.app_truncated, long, names.joinToString(AppStrings.getString(Res.string.list_separator)))
    }

    /**
     * A file the user chose, copied in and made readable.
     *
     * Everything imported lands in `samples/` as a WAV whatever it was, so
     * nothing after this needs to know about the other formats. Decoding
     * takes a while, so it happens off the main thread and [then] is called
     * back on it with the path to store, or not at all after saying why.
     */
    fun bringIn(uri: Doc, fallback: String, maxSeconds: Int = NativeEngine.PAD_SECONDS,
                then: (String) -> Unit) {
        scope.launch {
            converting = Triple(AppHost.current.docName(uri, fallback), 1, 1)
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching { copyIn(uri, "samples", fallback) }.mapCatching { dest ->
                        val converted = NativeEngine.importAudio(dest.absolutePath, maxSeconds)
                        if (converted.isFailure) { dest.delete(); throw converted.exceptionOrNull()!! }
                        converted.getOrThrow()
                    }
                }
            } finally {
                // Whatever happened, close the window. A modal that outlives
                // its work is worse than none.
                converting = null
            }
            result
                .onSuccess { imported ->
                    then("samples/" + File(imported.path).name)
                    if (imported.truncated) noteTruncated(listOf(File(imported.path).name), maxSeconds)
                }
                .onFailure { problem = AppStrings.getString(Res.string.app_file_failed, it.message) }
        }
    }

    var importTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val samplePicker = rememberOpenDocument { uri ->
        val (track, key) = importTarget ?: return@rememberOpenDocument
        importTarget = null
        if (uri == null) return@rememberOpenDocument
        // A slice source is one file for the whole machine, so it may be a
        // whole track. See SLICE_SECONDS.
        val seconds = if (key == "slice_sample") NativeEngine.SLICE_SECONDS else NativeEngine.PAD_SECONDS
        bringIn(uri, "sample.wav", seconds) { rel -> editor.edit(track) { t -> t.withSetting(key, rel) } }
    }

    // A whole kit in one go: fills pads from the selected one onwards, in the
    // order the file names sort, which is how kit folders are usually
    // numbered. Works like Mosaic's multi-select zones.
    var kitTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val kitPicker = rememberOpenDocuments { uris ->
        val (track, firstPad) = kitTarget ?: return@rememberOpenDocuments
        kitTarget = null
        if (uris.isEmpty()) return@rememberOpenDocuments
        scope.launch {
            val named = uris.map { uri ->
                AppHost.current.docName(uri, "sample.wav") to uri
            }.sortedBy { it.first.lowercase() }
            val assigned = mutableListOf<Pair<String, String>>()
            val refused = mutableListOf<String>()
            val shortened = mutableListOf<String>()
            val wanted = named.size.coerceAtMost(13 - firstPad)
            try {
                named.forEachIndexed { i, (display, uri) ->
                    val pad = firstPad + i
                    if (pad > 12) return@forEachIndexed
                    // Set before each file, so a kit counts up instead of
                    // sitting on "1 of 13".
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
            // One edit for the whole kit, so thirteen samples are one undo
            // and one autosave.
            if (assigned.isNotEmpty()) {
                editor.edit(track) { t ->
                    var next = t
                    for ((key, rel) in assigned) next = next.withSetting(key, rel)
                    next
                }
            }
            // Loading a kit shouldn't lose twelve files because one can't be
            // read. The rest load and this says which one didn't.
            if (refused.isNotEmpty()) {
                problem = AppStrings.getString(Res.string.app_files_refused, refused.joinToString(AppStrings.getString(Res.string.list_separator)))
            } else {
                noteTruncated(shortened)
            }
        }
    }


    var status by remember { mutableStateOf("starting…") }

    val soundFontPicker = rememberOpenDocument { uri ->
        val track = mapTarget ?: return@rememberOpenDocument
        mapTarget = null
        if (uri == null) return@rememberOpenDocument
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

    val zoneSamplePicker = rememberOpenDocuments { uris ->
        val track = mapTarget ?: return@rememberOpenDocuments
        mapTarget = null
        if (uris.isEmpty()) return@rememberOpenDocuments
        // Undispatched: on Android this runs straight through right here. In
        // a browser it waits on the imports.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { runCatching {
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
        }.onFailure { Log.w(TAG, "zone import failed", it) } }
    }

    // Where the playhead is, which is also what "this scene" means.
    var position by remember { mutableStateOf(Position(0, 0, 0)) }
    // Beats left of a count-in, or 0 when not counting in.
    var countInBeats by remember { mutableStateOf(0) }
    // Whole seconds since play, from the engine's count of what it played.
    var elapsedSeconds by remember { mutableStateOf(0) }

    // Exporting: the dialog chooses what and in which format, the system
    // picker gives somewhere to put it, and the engine renders into the cache
    // first. The engine renders to a path and SAF only gives a stream, so
    // the copy at the end is the only way across.
    var exportState by remember { mutableStateOf<com.rm.acidulous.ui.ExportState?>(null) }
    var exportAsk by remember { mutableStateOf(false) }
    var exportWanted by remember { mutableStateOf(com.rm.acidulous.ui.ExportOptions()) }

    fun safeName(text: String): String =
        text.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "export" }

    /**
     * One pass of a scene, in seconds, ignoring repeats. A scene export is
     * its first pass, which is also its last only if it plays once, and only
     * the last pass has the tempo ramp.
     */
    fun sceneSeconds(scene: com.rm.acidulous.model.Scene): Float = song.passSeconds(scene, last = scene.repeat <= 1)

    /** How long the export will be, for the progress bar. */
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
    // should be delivered, and an error if it didn't get that far.
    suspend fun produceExport(options: com.rm.acidulous.ui.ExportOptions): Pair<List<File>, String> =
        withContext(Dispatchers.IO) {
            val base = safeName(song.name)
            val cache = EngineAssets.cacheRoot()
            val scene = if (options.what == com.rm.acidulous.ui.ExportWhat.Scene) position.scene else 0
            val limit = if (options.what == com.rm.acidulous.ui.ExportWhat.Scene) {
                song.scenes.getOrNull(position.scene)?.let { sceneSeconds(it) } ?: 0f
            } else {
                0f
            }
            // A normalised export measures first: the same render into no
            // file, then a gain to reach the target, but never past a true
            // peak of -1 dBTP, which lossy encoders need as headroom.
            var gainDb = 0f
            if (options.normalise && options.format.audio) {
                // A render starts from the song (see pushForRender). Runs on
                // the main thread, the only one that may send parameters.
                withContext(Dispatchers.Main) { EngineSync.pushForRender(song) }
                val m = NativeEngine.measureLoudness(options.tailSeconds, scene, limit)
                    ?: return@withContext emptyList<File>() to AppStrings.getString(Res.string.app_export_unmeasured)
                if (m[0] > -70f) gainDb = minOf(com.rm.acidulous.ui.NORMALISE_LUFS - m[0], -1f - m[1])
                Log.i(TAG, "normalise: measured %.1f LUFS, %.1f dBTP; gain %.1f dB".format(m[0], m[1], gainDb))
            }
            NativeEngine.setRenderGain(gainDb)
            // Again after the measuring pass, whose lanes moved things.
            if (options.format.audio) withContext(Dispatchers.Main) { EngineSync.pushForRender(song) }
            try { when (options.format) {
                com.rm.acidulous.ui.ExportFormat.Midi -> {
                    val file = File(cache, "$base.mid")
                    runCatching { com.rm.acidulous.model.MidiFile.write(song, file); listOf(file) to "" }
                        .getOrElse { emptyList<File>() to (it.message ?: AppStrings.getString(Res.string.app_export_midi_failed)) }
                }
                com.rm.acidulous.ui.ExportFormat.Bundle -> {
                    val file = File(cache, "$base.zip")
                    runCatching {
                        com.rm.acidulous.model.SongBundle.write(song, EngineAssets.userRoot(), file)
                        listOf(file) to ""
                    }.getOrElse { emptyList<File>() to (it.message ?: AppStrings.getString(Res.string.app_export_bundle_failed)) }
                }
                com.rm.acidulous.ui.ExportFormat.Aac -> {
                    // The platform encoder reads a file, so render to a
                    // 16-bit WAV first and transcode that.
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
                        val error = AppHost.current.encodeAac(pcm, out, options.rate * 1000)
                        pcm.delete()
                        if (error.isEmpty()) listOf(out) to "" else emptyList<File>() to error
                    }
                }
                else -> {
                    val engineFormat = options.format.engineFormat
                    // MP3 has no bit depth, so `bits` carries its bitrate
                    // instead. See Mp3Writer.
                    val depth = if (options.format.lossy) options.rate else options.bits
                    if (options.what == com.rm.acidulous.ui.ExportWhat.Stems) {
                        // A stem is what reaches the master, so a track routed
                        // into a group is in the group's stem and not its own,
                        // or the stems would add up to more than the mix.
                        val groups = song.master.groups
                        val racks = song.tracks.indices.filter {
                            val t = song.tracks[it]
                            t.machine.type.isNotEmpty() && t.mixer.output !in 1..groups.size
                        }
                        if (racks.isEmpty()) {
                            emptyList<File>() to AppStrings.getString(Res.string.app_export_no_tracks)
                        } else {
                            // The mix comes too, as file 00. It costs one more
                            // sink in a pass that's happening anyway, and stems
                            // without their mix are hard to check. Each group
                            // is also its own stem, with its tracks in it. -2
                            // is the first group to the engine.
                            val files = listOf(File(cache, "00 Mix${options.format.extension}")) +
                                racks.map {
                                    File(cache, "%02d %s%s".format(it + 1, safeName(song.tracks[it].name), options.format.extension))
                                } +
                                groups.indices.map {
                                    File(cache, "G%d %s%s".format(it + 1, safeName(groups[it].name), options.format.extension))
                                }
                            val ids = intArrayOf(-1) + racks.toIntArray() + IntArray(groups.size) { -2 - it }
                            val error = NativeEngine.renderStems(
                                files.map { it.absolutePath }.toTypedArray(), ids,
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
            } } finally {
                NativeEngine.setRenderGain(0f)
            }
        }

    fun finish(
        options: com.rm.acidulous.ui.ExportOptions, files: List<File>, error: String, where: String,
        /** Where the files went, for the share button. */
        written: List<Doc> = emptyList(),
    ) {
        exportState = if (error.isEmpty()) {
            com.rm.acidulous.ui.ExportState.Done(
                NativeEngine.renderedSeconds, NativeEngine.renderedPeak, where, files.size,
                options.format.label,
                if (options.format.audio && !options.format.lossy) options.bits else 0,
                if (options.format.lossy) options.rate else 0,
                uris = written,
                mime = options.format.mime,
            )
        } else {
            com.rm.acidulous.ui.ExportState.Failed(error)
        }
        Log.i(TAG, "export ${if (error.isEmpty()) "ok" else "failed: $error"}: ${files.size} file(s), " +
            "%.2f s, peak %.3f".format(NativeEngine.renderedSeconds, NativeEngine.renderedPeak))
        for (f in files) f.delete()
    }

    /** One file: the picker already made the document, so just fill it. */
    val filePicker = rememberCreateDocument { uri ->
        if (uri == null) return@rememberCreateDocument
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
                    AppHost.current.copyToDoc(files.first(), uri)
                    ""
                }.getOrElse { it.message ?: AppStrings.getString(Res.string.app_export_copy_failed) }
            }
            ticker.cancel()
            finish(options, files, copyError, AppHost.current.placeName(uri), listOf(uri))
        }
    }

    /** Stems: several files, so the picker gives a folder instead. */
    val folderPicker = rememberOpenFolder { tree ->
        if (tree == null) return@rememberOpenFolder
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
            val created = mutableListOf<Doc>()
            val copyError = if (error.isNotEmpty()) error else withContext(Dispatchers.IO) {
                runCatching {
                    for (file in files) {
                        val target = AppHost.current.createIn(tree, options.format.mime, file.name)
                            ?: error(AppStrings.getString(Res.string.app_export_create_failed, file.name))
                        created += target
                        AppHost.current.copyToDoc(file, target)
                    }
                    ""
                }.getOrElse { it.message ?: AppStrings.getString(Res.string.app_export_copy_failed) }
            }
            ticker.cancel()
            finish(options, files, copyError, AppHost.current.placeName(tree), created)
        }
    }

    DisposableEffect(Unit) {
        EngineSync.sampleRoot = EngineAssets.userRoot()
        EngineSync.freezeRoot = EngineAssets.freezeRoot()
        NativeEngine.setCacheRoot(EngineAssets.reelCache().absolutePath)
        // Trinity's wavetables take a moment to build, so do it off the main
        // thread now rather than stalling the first mount.
        com.rm.acidulous.util.runInBackground("Acidulous.Prewarm") { NativeEngine.prewarm() }
        EngineSync.forgetEngine()
        if (NativeEngine.start()) {
            // The engine keeps no preferences, so buffer size, voice limit,
            // quality and record format are pushed once the stream is up and
            // again whenever one changes.
            com.rm.acidulous.ui.UiPrefs.applyToEngine()
            // Link is turned on here, separately from applyToEngine, because
            // it takes the multicast lock. On if it was on when the app last
            // closed.
            if (com.rm.acidulous.ui.UiPrefs.linkWanted) {
                com.rm.acidulous.engine.LinkHub.chooseEnabled(true)
            }
            // Restore whatever was open. The demo opens once, on the first
            // run after installing, and is saved with the songs so it can be
            // opened again. After that, a session that won't load becomes a
            // new song rather than the demo.
            val restored = runCatching { SongStore.loadSession() }.getOrNull()
            val firstRun = AppHost.current.prefs(FIRST_RUN)
            val loaded = when {
                restored != null -> restored
                !firstRun.getBoolean(DEMO_OPENED, false) -> DemoSong.build().also {
                    if (!SongStore.exists(it.name)) SongStore.save(it)
                }
                else -> com.rm.acidulous.ui.UiPrefs.newSong(AppStrings.getString(Res.string.main_untitled))
            }
            firstRun.edit().putBoolean(DEMO_OPENED, true).apply()
            editor.replace(loaded)
            Log.i(TAG, if (restored != null) "resumed '${loaded.name}'" else "no session: opened '${loaded.name}'")
            status = "${NativeEngine.sampleRate / 1000}k · burst ${NativeEngine.framesPerBurst}"
        } else {
            status = "engine failed to start"
        }
        onDispose { NativeEngine.stop() }
    }

    var peak by remember { mutableStateOf(0f) }
    /** The worst callback seen since the transport last started. See the poll below. */
    var worstUs by remember { mutableStateOf(0) }
    var worstCpuUs by remember { mutableStateOf(0) }
    var wasPlaying by remember { mutableStateOf(false) }
    var lateAt by remember { mutableStateOf(0L) }
    var stalledAt by remember { mutableStateOf(0L) }
    var xrunsAt by remember { mutableStateOf(0L) }
    var lateSeen by remember { mutableStateOf(0L) }
    var strainUntil by remember { mutableStateOf(0L) }
    /** True while the engine is missing its deadline, right now. */
    var straining by remember { mutableStateOf(false) }
    // How long the automatic quality watcher has wanted each setting. 0 means
    // it doesn't want that one right now.
    var leanSince by remember { mutableStateOf(0L) }
    var fullSince by remember { mutableStateOf(0L) }
    /** Which tracks are a large enough share of a block to be worth freezing. */
    var rackHot by remember { mutableStateOf(BooleanArray(16)) }
    var lateCallbacks by remember { mutableStateOf(0L) }
    var stalled by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var bpm by remember { mutableStateOf(120f) }
    var armed by remember { mutableStateOf(false) }
    var loopScene by remember { mutableStateOf(false) }
    var stopAtEnd by remember { mutableStateOf(false) }
    var queuedScene by remember { mutableStateOf(-1) }
    // One per rack, read back each poll while clip mode is on. The engine
    // decides what's playing, like it does for queuedScene.
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
            result.song.tracks.forEachIndexed { i, t -> if (t !== song.tracks.getOrNull(i)) editor.recorded(i, t) }
        }
        if (result.push) EngineSync.sync(editor.song)
    }
    /**
     * Give the engine a different song, from a standing start.
     *
     * Stop before the swap, not after: the scheduler is reading the old
     * song's scenes, and left running the playhead would carry on into the
     * new song at whatever is at those indices. Panic after, to clear the
     * tails the stop leaves ringing, and `forgetSounding` because a hub that
     * thinks a note is held will never send its note-off.
     *
     * The screen's copies of the transport state are cleared too. They'd
     * catch up from the engine within a frame, but the play button would
     * flicker.
     */
    fun swapSong(next: com.rm.acidulous.model.Song) {
        NativeEngine.transportStop()
        NativeEngine.queuedScene = -1
        NativeEngine.stopAtEnd = false
        editor.replace(next)
        // Whatever the new song doesn't name goes back to its default, or a
        // rack that kept its machine would keep the last song's settings.
        EngineSync.pushUnnamedDefaults(next)
        // And back to the start, which stop doesn't do (it leaves the
        // playhead where it stopped). Without this a new song would open at
        // the old one's bar.
        NativeEngine.transportRewind()
        NativeEngine.panic()
        com.rm.acidulous.midi.MidiHub.forgetSounding()
        playing = false
        armed = false
        loopScene = false
        stopAtEnd = false
        queuedScene = -1
    }

    // --- Import: a MIDI file, a song bundle, or a sound ------------------------------

    /** What an import has to say: a title and a line. Not [problem], which is about audio. */
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    notice?.let { (title, message) ->
        com.rm.acidulous.ui.PlainDialog(title = title, onDismiss = { notice = null }, dismissLabel = stringResource(Res.string.close)) {
            Text(message, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
        }
    }
    // The last run ended in a crash or a freeze: say so once and offer to
    // share the report. See CrashReports.
    var crashed by remember { mutableStateOf(AppHost.current.unreadCrashReport()) }
    crashed?.let { report ->
        com.rm.acidulous.ui.PlainDialog(
            title = stringResource(Res.string.app_crashed_title),
            onDismiss = { AppHost.current.markCrashReportRead(); crashed = null },
            dismissLabel = stringResource(Res.string.close),
            confirmLabel = stringResource(Res.string.app_crashed_share),
            onConfirm = {
                AppHost.current.markCrashReportRead()
                crashed = null
                AppHost.current.shareCrashReport(report)
            },
        ) {
            Text(
                deviceStringResource(Res.string.app_crashed_note, Res.string.app_crashed_note_desktop),
                fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi,
            )
        }
    }
    /** A MIDI file that's been read and is waiting for its import window: its name and its parts. */
    var midiImport by remember { mutableStateOf<Pair<String, com.rm.acidulous.model.MidiFile.Parsed>?>(null) }

    /** A song name nothing saved already has: "Squelch", then "Squelch (2)". */
    fun freeSongName(wanted: String): String {
        val taken = SongStore.list().toSet()
        if (wanted !in taken) return wanted
        var n = 2
        while ("$wanted ($n)" in taken) n++
        return "$wanted ($n)"
    }

    /**
     * One entry point for everything from outside, sorted by file name: the
     * system picker offers every file, and MIDI files, bundles and WAVs go to
     * different places. Files shared to the app or opened with it arrive
     * here too.
     */
    fun importFile(uri: Doc) {
        val name = AppHost.current.docName(uri, "file")
        val ext = name.substringAfterLast('.', "").lowercase()
        val stem = name.substringBeforeLast('.').ifBlank { AppStrings.getString(Res.string.app_imported) }
        when (ext) {
            "mid", "midi", "smf", "kar" -> scope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    runCatching { com.rm.acidulous.model.MidiFile.read(AppHost.current.readDoc(uri)) }
                }
                parsed.onSuccess { p ->
                    if (p.parts.isEmpty()) notice = AppStrings.getString(Res.string.app_import_empty_title) to AppStrings.getString(Res.string.app_import_empty, name)
                    else midiImport = stem to p
                }.onFailure { notice = AppStrings.getString(Res.string.app_open_failed_title) to AppStrings.getString(Res.string.app_open_failed, name, it.message) }
            }
            "zip" -> scope.launch {
                // A song bundle, or a voice someone shared from the voice page.
                var voice: String? = null
                val song = withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(EngineAssets.cacheRoot(), "import.zip")
                        AppHost.current.copyFromDoc(uri, tmp)
                        try {
                            if (com.rm.acidulous.model.voice.VoiceImport.kindOf(tmp) == com.rm.acidulous.model.voice.VoiceImport.Kind.Voice) {
                                voice = com.rm.acidulous.model.voice.VoiceImport.read(tmp, EngineAssets.userRoot())
                                null
                            } else {
                                com.rm.acidulous.model.SongBundle.read(tmp, EngineAssets.userRoot())
                            }
                        } finally {
                            tmp.delete()
                        }
                    }.getOrNull()
                }
                if (voice != null) {
                    com.rm.acidulous.engine.EngineSync.voicesChanged()
                    notice = AppStrings.getString(Res.string.app_voice_imported_title) to AppStrings.getString(Res.string.app_voice_imported, voice)
                } else if (song == null) {
                    notice = AppStrings.getString(Res.string.app_open_failed_title) to AppStrings.getString(Res.string.app_not_a_bundle, name)
                } else {
                    val named = song.copy(name = freeSongName(song.name))
                    swapSong(named)
                    SongStore.save(named)
                }
            }
            // The longest any machine takes (the ten minutes a slicer can
            // hold), since it's not known yet what this sound is for.
            "wav", "wave", "aif", "aiff", "aifc", "flac", "mp3" ->
                bringIn(uri, "sample.wav", NativeEngine.SLICE_SECONDS) { rel ->
                    notice = AppStrings.getString(Res.string.app_sound_added_title) to AppStrings.getString(Res.string.app_sound_added, rel.substringAfterLast('/'))
                }
            // A tuning: checked by reading it, then kept as the file it is.
            "scl" -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = AppHost.current.readDoc(uri).decodeToString()
                        val tuning = com.rm.acidulous.model.Tunings.parseScl(text, stem)
                        val dir = com.rm.acidulous.model.TuningStore.directory(EngineAssets.userRoot())
                        File(dir, stem.replace(Regex("[^A-Za-z0-9 _.-]"), "_") + ".scl").writeTextSafely(text)
                        tuning
                    }
                }
                result.onSuccess {
                    notice = AppStrings.getString(Res.string.app_tuning_added_title) to
                        AppStrings.getQuantityString(Res.plurals.app_tuning_added, it.cents.size, it.name, it.cents.size)
                }.onFailure { notice = AppStrings.getString(Res.string.app_open_failed_title) to AppStrings.getString(Res.string.app_open_failed, name, it.message) }
            }
            else -> notice = AppStrings.getString(Res.string.app_open_failed_title) to AppStrings.getString(Res.string.app_import_what)
        }
    }
    val importPicker = rememberOpenDocument { uri ->
        if (uri != null) importFile(uri)
    }
    // Opened with the app or shared to it. Declared after the session is
    // restored, so it runs after it and isn't replaced by it.
    LaunchedEffect(Incoming.doc) {
        val uri = Incoming.doc ?: return@LaunchedEffect
        Incoming.doc = null
        importFile(uri)
    }

    /** The open song as a bundle, through the share sheet. */
    fun shareSong() {
        scope.launch {
            val bundle = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(EngineAssets.cacheRoot(), "shared").apply { deleteRecursively(); mkdirs() }
                    val file = File(dir, safeName(song.name) + ".zip")
                    com.rm.acidulous.model.SongBundle.write(song, EngineAssets.userRoot(), file)
                    file
                }
            }
            bundle.mapCatching { AppHost.current.shareFile(it, "application/zip", song.name) }
                .onFailure { notice = AppStrings.getString(Res.string.app_share_failed_title) to (it.message ?: AppStrings.getString(Res.string.app_share_failed)) }
        }
    }
    midiImport?.let { (name, parsed) ->
        com.rm.acidulous.ui.MidiImportDialog(
            fileName = name,
            parsed = parsed,
            onDismiss = { midiImport = null },
            onImport = { song ->
                midiImport = null
                val named = song.copy(name = freeSongName(song.name))
                swapSong(named)
                SongStore.save(named)
            },
        )
    }


    // --- Freeze ---------------------------------------------------------
    // The render stops the audio stream while it runs, so it happens on a
    // worker with the transport stopped, one clip at a time, and the model is
    // only changed back on the main thread.
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
                    freezeStatus = AppStrings.getString(Res.string.app_freezing, i + 1, targets.size)
                    // A freeze is a render, so it starts from the song too.
                    EngineSync.pushForRender(editor.song)
                    val frozen = withContext(Dispatchers.IO) {
                        com.rm.acidulous.model.Freeze.render(editor.song, t)
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
            com.rm.acidulous.model.Freeze.discard(editor.song, t)
            editor.editClip(t.track, t.sceneId) { it.copy(frozen = null) }
        }
    }

    /**
     * The record button, which also starts the microphone when a Bias lane is
     * armed.
     *
     * One button, because the control you press while the song is already
     * playing should be the one already under your thumb.
     *
     * The capture runs from when it's armed rather than from when the
     * transport starts, so nothing is lost while getting ready. The seconds
     * before play belong to no cell, the engine marks nothing for them, and
     * the split leaves them out.
     */
    fun startBiasCapture() {
        // The capture needs the input open, and it isn't open by default
        // since the microphone shouldn't be held when nobody asked. Opened
        // here and left open. Stopping the capture ends the recording, not
        // closing the stream.
        NativeEngine.startInput(com.rm.acidulous.ui.UiPrefs.inputDevice)
        val root = com.rm.acidulous.io.File(EngineAssets.userRoot(), "samples").apply { mkdirs() }
        val target = com.rm.acidulous.io.File(root, uniqueIn(root, "take.wav"))
        val error = NativeEngine.startCapture(target.absolutePath, 0)
        if (error.isNotEmpty()) {
            problem = AppStrings.getString(Res.string.app_record_failed, error)
            BiasArm.clear()
            return
        }
        biasTakeFile = target
    }

    /**
     * Stop the microphone and cut what was recorded into cells.
     *
     * No audio is copied: one file, N cells, each a window into it. The
     * waveforms all come from one decode (see `TakePeaks.slice`), because
     * reading a take across five scenes five times would be five peaks of
     * 100 MB to draw two hundred columns.
     */
    fun finishBiasCapture() {
        val file = biasTakeFile ?: return
        val track = BiasArm.track
        val lane = BiasArm.lane
        biasTakeFile = null
        NativeEngine.stopCapture()
        val raw = LongArray(NativeEngine.MAX_MARKS * NativeEngine.MARK_LONGS)
        val count = NativeEngine.captureMarks(raw)
        val frames = NativeEngine.capturedFrames
        if (count < 0) {
            // The buffer dropped frames, so every index after the drop is
            // wrong. The recording is kept (it's in the library and can be
            // placed by hand) but mustn't be cut up.
            problem = AppStrings.getString(Res.string.app_record_gap)
            return
        }
        if (count == 0 || track < 0 || lane < 0) {
            problem = AppStrings.getString(Res.string.app_record_unplaced)
            return
        }
        val rel = "samples/" + file.name
        // Logged because a split that goes wrong is silent, and the marks are
        // the only place to see what happened afterwards.
        Log.i(TAG, "bias split: $count mark(s) over $frames frames: " +
            marksFrom(raw, count).take(8)
                .joinToString(" ") { "${it.frame}@${it.sceneId}+${it.tick}/${it.cycleTicks}" })
        val takes = splitTake(marksFrom(raw, count), frames, rel, sceneIdOf)
        if (takes.isEmpty()) {
            problem = AppStrings.getString(Res.string.app_record_too_short)
            return
        }
        scope.launch {
            val whole = withContext(Dispatchers.Default) { TakePeaks.load(EngineSync.sampleRoot, rel) }
            var next = editor.song
            for ((sceneId, take) in takes) {
                val drawn = if (whole == null) take
                            else take.copy(peaks = TakePeaks.slice(whole, take.offset, take.frames))
                next = next.updateClip(track, sceneId, { next.emptyClipFor(sceneId) }) { c ->
                    c.withTake(lane, drawn)
                }
            }
            // One song edit for the whole take, so undoing a recording is one
            // press rather than one per scene it crossed.
            editor.edit(track, push = true) { next.tracks[track] }
            EngineSync.sync(editor.song)
        }
    }

    /**
     * Asks for the microphone permission when it's needed: the first time a
     * lane is armed and record is pressed, rather than sending the user off
     * to the recorder window.
     */
    val askToRecord = com.rm.acidulous.ui.rememberPermissions { ok ->
        if (ok) startBiasCapture()
        else problem = AppStrings.getString(Res.string.app_record_permission)
    }
    fun mayRecord(): Boolean = askToRecord.has(com.rm.acidulous.ui.Permissions.RECORD_AUDIO)

    val onArm: (Boolean) -> Unit = { on ->
        NativeEngine.recordArmed = on
        if (on) {
            if (BiasArm.any) {
                if (mayRecord()) startBiasCapture()
                else askToRecord.ask(com.rm.acidulous.ui.Permissions.RECORD_AUDIO)
            }
        } else {
            applyRecorded(recorder.flush(song, sceneIdOf))
            editor.endTake()
            finishBiasCapture()
        }
    }
    // Empty launcher cells record into themselves. It arms recording like the
    // ○ button does, and points MIDI in at the track it's looping.
    val looper = remember(editor) {
        com.rm.acidulous.ui.Looper(
            editor,
            arm = { on -> armed = on; onArm(on) },
            armed = { NativeEngine.recordArmed },
            focus = { track -> midiTrack = track },
        )
    }
    val onLoopScene: (Boolean) -> Unit = { on ->
        loopScene = on
        NativeEngine.setLoopScene(on)
    }

    // A Launchpad Pro [MK3], played by the app. The surface decides what it
    // shows and what a press means (midi/launchpad/Surface.kt). Here it gets
    // a snapshot of the app thirty times a second and its presses are carried
    // out. Its notes go to its selected track whatever the MIDI routing says,
    // since it's part of the app rather than a keyboard on a channel, and each
    // note-off goes where its note-on went.
    val lpNoteRack = remember { IntArray(128) { -1 } }
    // The device faders: a machine's first eight continuous knobs, looked up
    // once per type rather than thirty times a second.
    val lpKnobs = remember { HashMap<String, List<com.rm.acidulous.engine.ParamInfo>>() }
    fun lpDeviceKnobs(type: String) = lpKnobs.getOrPut(type) {
        NativeEngine.machineParamInfo(type).filter { it.curve != 2 }.take(8)
    }
    val lpAct by rememberUpdatedState<(com.rm.acidulous.midi.launchpad.LpAction) -> Unit> { a ->
        val none = NativeEngine.NO_CHANNEL
        when (a) {
            is com.rm.acidulous.midi.launchpad.LpAction.NoteOn -> {
                lpNoteRack[a.note] = midiTrack
                NativeEngine.midiEvent(midiTrack, 0x90, a.note, a.velocity, none)
            }
            is com.rm.acidulous.midi.launchpad.LpAction.NoteOff -> {
                val rack = lpNoteRack[a.note].takeIf { it >= 0 } ?: midiTrack
                lpNoteRack[a.note] = -1
                NativeEngine.midiEvent(rack, 0x80, a.note, 0, none)
            }
            is com.rm.acidulous.midi.launchpad.LpAction.Pressure -> {
                val rack = lpNoteRack[a.note]
                if (rack >= 0) NativeEngine.midiEvent(rack, 0xa0, a.note, a.value, none)
            }
            // The app's own actions, so the surface and the screen agree on
            // what play, record and undo do.
            com.rm.acidulous.midi.launchpad.LpAction.Play ->
                if (!com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.PlayStop)) {
                    if (playing) NativeEngine.transportStop() else EngineSync.play(position.scene, com.rm.acidulous.ui.UiPrefs.clipMode)
                }
            com.rm.acidulous.midi.launchpad.LpAction.Record ->
                if (!com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.Record)) onArm(!armed)
            com.rm.acidulous.midi.launchpad.LpAction.Panic -> com.rm.acidulous.ui.panicEverything()
            com.rm.acidulous.midi.launchpad.LpAction.Undo -> com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.Undo)
            com.rm.acidulous.midi.launchpad.LpAction.Redo -> com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.Redo)
            is com.rm.acidulous.midi.launchpad.LpAction.SelectTrack -> if (a.index in song.tracks.indices) midiTrack = a.index
            // Like tapping the scene in the grid.
            is com.rm.acidulous.midi.launchpad.LpAction.PlayScene -> song.scenes.getOrNull(a.index)?.let { scene ->
                when {
                    com.rm.acidulous.ui.UiPrefs.clipMode -> {
                        NativeEngine.launchScene(scene.engineId)
                        if (!playing) EngineSync.play(0, true)
                    }
                    playing && position.scene == a.index -> NativeEngine.stopAtEnd = !NativeEngine.stopAtEnd
                    playing -> NativeEngine.queuedScene = if (NativeEngine.queuedScene == a.index) -1 else a.index
                    else -> { onLoopScene(song.loopSong); EngineSync.play(a.index, false) }
                }
            }
            is com.rm.acidulous.midi.launchpad.LpAction.LaunchClip -> song.scenes.getOrNull(a.scene)?.let { scene ->
                NativeEngine.launchClip(a.track, scene.engineId)
                if (!playing) EngineSync.play(0, true)
            }
            // Like the clip window's clear and cut, and the scene menu's duplicate.
            is com.rm.acidulous.midi.launchpad.LpAction.ClearClip -> song.scenes.getOrNull(a.scene)?.let { scene ->
                editor.editClip(a.track, scene.id) { it.cleared() }
            }
            is com.rm.acidulous.midi.launchpad.LpAction.CopyClipDown -> {
                val from = song.scenes.getOrNull(a.scene)
                val to = song.scenes.getOrNull(a.scene + 1)
                val clip = from?.let { song.tracks.getOrNull(a.track)?.clips?.get(it.id) }
                if (to != null && clip != null) editor.edit(a.track) { t -> t.copy(clips = t.clips + (to.id to clip)) }
            }
            is com.rm.acidulous.midi.launchpad.LpAction.DuplicateScene -> editor.editSong { it.duplicateScene(a.scene) }
            is com.rm.acidulous.midi.launchpad.LpAction.ToggleMute ->
                editor.edit(a.track) { t -> t.copy(mixer = t.mixer.copy(mute = !t.mixer.mute)) }
            is com.rm.acidulous.midi.launchpad.LpAction.ToggleSolo ->
                editor.edit(a.track) { t -> t.copy(mixer = t.mixer.copy(solo = !t.mixer.solo)) }
            com.rm.acidulous.midi.launchpad.LpAction.StopClips ->
                if (com.rm.acidulous.ui.UiPrefs.clipMode && playing) NativeEngine.stopAllClips() else NativeEngine.transportStop()
            // A step: removes the note starting there at that pitch, or adds
            // one a step long. One undo each, like a tap in the roll.
            is com.rm.acidulous.midi.launchpad.LpAction.ToggleStep -> song.scenes.getOrNull(a.scene)?.let { scene ->
                editor.editClip(a.track, scene.id) { clip ->
                    val i = clip.notes.indexOfFirst { it.pitch == a.pitch && it.tick >= a.tick && it.tick < a.tick + clip.grid }
                    if (i >= 0) clip.copy(notes = clip.notes.filterIndexed { j, _ -> j != i })
                    else clip.copy(notes = (clip.notes + com.rm.acidulous.model.Note(a.tick, a.length, a.pitch, 100)).sortedBy { it.tick })
                }
            }
            // Like the mixer strips, the panel knobs and the perform page send them.
            is com.rm.acidulous.midi.launchpad.LpAction.SetMix -> {
                val (name, update) = when (a.fader) {
                    com.rm.acidulous.midi.launchpad.LpFader.Level -> "gain" to { m: com.rm.acidulous.model.Mixer -> m.copy(volume = com.rm.acidulous.model.EngineParams.volumeFrom01(a.value)) }
                    com.rm.acidulous.midi.launchpad.LpFader.Pan -> "pan" to { m: com.rm.acidulous.model.Mixer -> m.copy(pan = com.rm.acidulous.model.EngineParams.panFrom01(a.value)) }
                    com.rm.acidulous.midi.launchpad.LpFader.SendA -> "sendreverb" to { m: com.rm.acidulous.model.Mixer -> m.copy(sendReverb = a.value) }
                    else -> "senddelay" to { m: com.rm.acidulous.model.Mixer -> m.copy(sendDelay = a.value) }
                }
                NativeEngine.setParam(a.track, "channel", name, a.value)
                editor.edit(a.track) { t -> t.copy(mixer = update(t.mixer)) }
            }
            is com.rm.acidulous.midi.launchpad.LpAction.SetDevice -> song.tracks.getOrNull(midiTrack)?.let { t ->
                lpDeviceKnobs(t.machine.type).getOrNull(a.index)?.let { p ->
                    NativeEngine.setParam(midiTrack, "machine", p.name, a.value, record = true)
                    editor.edit(midiTrack) { it.withParam(p.name, a.value) }
                }
            }
            is com.rm.acidulous.midi.launchpad.LpAction.PerformParam ->
                NativeEngine.setParam(midiTrack, "perform", a.name, a.value, record = true)
            // Uses the Quantise window's last settings, since they mean the same thing.
            is com.rm.acidulous.midi.launchpad.LpAction.QuantiseClip -> song.scenes.getOrNull(a.scene)?.let { scene ->
                editor.editClip(a.track, scene.id) { clip ->
                    val len = song.clipLengthTicks(scene.id, clip)
                    clip.copy(notes = com.rm.acidulous.ui.QuantiseMemory.applyTo(song, clip, len).sortedBy { it.tick })
                }
            }
        }
    }
    val playedScale = song.tracks.getOrNull(midiTrack)?.let { t ->
        val root = com.rm.acidulous.model.Scales.rootFor(song, t)
        val classes = com.rm.acidulous.model.Scales.activeFor(song, t)
        if (root == null || classes == null) null to null
        else root to classes.map { Math.floorMod(it - root, 12) }.sorted()
    } ?: (null to null)
    val lpSample by rememberUpdatedState {
        com.rm.acidulous.midi.launchpad.LpView(
            tracks = song.tracks.mapIndexed { i, t ->
                com.rm.acidulous.midi.launchpad.LpTrack(
                    colour = com.rm.acidulous.midi.launchpad.Rgb.fromArgb(com.rm.acidulous.ui.trackColour(i, t.colour).toArgb()),
                    // Lowest first for the sequencer, like the drum grid, and in
                    // the on-screen pad order for the note page.
                    drums = if (com.rm.acidulous.model.MachineUi.kindOf(t.machine.type) == com.rm.acidulous.model.MachineKind.Drums) {
                        com.rm.acidulous.model.MachineUi.voicesOf(t.machine.type, t.machine.settings).map { it.note }.sorted()
                    } else null,
                    pads = if (com.rm.acidulous.model.MachineUi.kindOf(t.machine.type) == com.rm.acidulous.model.MachineKind.Drums) {
                        val voices = com.rm.acidulous.model.MachineUi.voicesOf(t.machine.type, t.machine.settings)
                        com.rm.acidulous.model.MachineUi.padOrder(t.machine.type, voices).map { it.note }
                    } else null,
                    clips = song.scenes.indices.filter { song.scenes[it].id in t.clips }.toSet(),
                    mute = t.mixer.mute,
                    solo = t.mixer.solo,
                    playingScene = launchStates.getOrNull(i)?.takeIf { it.playing }?.scene ?: -1,
                    queuedScene = launchStates.getOrNull(i)?.takeIf { it.queued }?.pending ?: -1,
                    level = com.rm.acidulous.model.EngineParams.volume01(t.mixer.volume),
                    pan = com.rm.acidulous.model.EngineParams.pan01(t.mixer.pan),
                    sendA = t.mixer.sendReverb,
                    sendB = t.mixer.sendDelay,
                )
            },
            played = midiTrack,
            // The played track's scale, as its roll shows it: its own, or the song's.
            root = playedScale.first,
            intervals = playedScale.second,
            scaleLocked = song.tracks.getOrNull(midiTrack)?.let { com.rm.acidulous.model.Scales.activeFor(it) != null } == true,
            playing = playing,
            armed = armed,
            beat = (position.tickInIteration % com.rm.acidulous.model.PPQN).toFloat() / com.rm.acidulous.model.PPQN,
            scenes = song.scenes.size,
            clipMode = com.rm.acidulous.ui.UiPrefs.clipMode,
            scene = position.scene,
            queuedScene = NativeEngine.queuedScene,
            seq = run {
                // The played track's clip: in clip mode the scene it's
                // playing, otherwise the song's.
                val t = song.tracks.getOrNull(midiTrack) ?: return@run null
                val clipMode = com.rm.acidulous.ui.UiPrefs.clipMode
                val ls = launchStates.getOrNull(midiTrack)
                val sceneIdx = if (clipMode && ls?.playing == true) ls.scene else position.scene
                val scene = song.scenes.getOrNull(sceneIdx) ?: return@run null
                val clip = t.clips[scene.id] ?: song.emptyClipFor(scene.id)
                val len = song.clipLengthTicks(scene.id, clip).coerceAtLeast(1)
                val head = when {
                    !playing -> -1
                    clipMode -> if (ls?.playing == true && ls.scene == sceneIdx) (ls.tickInCycle % len).toInt() else -1
                    position.scene == sceneIdx -> (position.tickInIteration % len).toInt()
                    else -> -1
                }
                com.rm.acidulous.midi.launchpad.LpSeq(sceneIdx, clip.grid.coerceAtLeast(1), len, clip.notes.map { it.tick to it.pitch }, head)
            },
            device = song.tracks.getOrNull(midiTrack)?.let { t ->
                lpDeviceKnobs(t.machine.type).map { p -> t.machine.params[p.name] ?: p.defaultNormalized }
            } ?: emptyList(),
        )
    }
    val launchpad = remember { com.rm.acidulous.ui.launchpad.LaunchpadController { lpAct(it) } }
    LaunchedEffect(launchpad) {
        launchpad.attach()
        try {
            while (true) {
                if (com.rm.acidulous.midi.MidiHub.launchpadHere && com.rm.acidulous.midi.MidiHub.launchpadOn) {
                    launchpad.view = lpSample()
                    launchpad.frame()
                    delay(33)
                } else {
                    delay(300)
                }
            }
        } finally {
            launchpad.detach()
        }
    }
    /**
     * Song to clip mode and back: just flip the flag. Hoisted, so the chip on
     * screen and a mapped pad do the same thing.
     *
     * The scheduler does the handover (`SceneScheduler::process` watches for
     * the flag to change). Going in, `adoptPlayingScene` gives every rack the
     * scene it's already playing at the phase it's already at, so nothing
     * restarts or stops, and clips just loop instead of the arranger moving
     * on. Coming out, the launcher runs to the next bar line and
     * `handBackToScenes` puts everyone on the scene most racks are already
     * playing, in phase.
     *
     * So don't do anything else here. Calling `transportStop` would silence
     * what the scheduler is about to adopt, and queuing `launchClip` requests
     * would restart every clip on top of the adoption.
     */
    val onClipMode: (Boolean) -> Unit = { on ->
        com.rm.acidulous.ui.UiPrefs.chooseClipMode(on)
        NativeEngine.setLaunchQuantise(com.rm.acidulous.ui.UiPrefs.launchQuantise * song.signature.ticksPerBar)
    }

    // An Exquis's play, record, loop, clips, undo and redo buttons, when the
    // app has them: the same actions as the Launchpad's and the screen's, and
    // lit to match. Play is green while playing and amber when stopped, like
    // the Exquis itself, record is red while armed, and loop and clips are
    // lit while on.
    val exquisPress by rememberUpdatedState<(Int) -> Unit> { id ->
        val pl = com.rm.acidulous.midi.PadLights
        when (id) {
            pl.BUTTON_PLAY -> lpAct(com.rm.acidulous.midi.launchpad.LpAction.Play)
            pl.BUTTON_RECORD -> lpAct(com.rm.acidulous.midi.launchpad.LpAction.Record)
            pl.BUTTON_UNDO -> lpAct(com.rm.acidulous.midi.launchpad.LpAction.Undo)
            pl.BUTTON_REDO -> lpAct(com.rm.acidulous.midi.launchpad.LpAction.Redo)
            pl.BUTTON_LOOP -> onLoopScene(!loopScene)
            pl.BUTTON_CLIPS -> onClipMode(!com.rm.acidulous.ui.UiPrefs.clipMode)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.rm.acidulous.midi.MidiHub.exquisButtonPressed = { exquisPress(it) }
        onDispose { com.rm.acidulous.midi.MidiHub.exquisButtonPressed = null }
    }
    val clipModeNow = com.rm.acidulous.ui.UiPrefs.clipMode
    LaunchedEffect(playing, armed, loopScene, clipModeNow) {
        val pl = com.rm.acidulous.midi.PadLights
        val off = Triple(16, 16, 16)
        com.rm.acidulous.midi.MidiHub.showExquisButtons(mapOf(
            pl.BUTTON_PLAY to if (playing) Triple(0, 127, 0) else Triple(80, 36, 0),
            pl.BUTTON_RECORD to if (armed) Triple(127, 0, 0) else Triple(24, 0, 0),
            pl.BUTTON_LOOP to if (loopScene) Triple(110, 80, 0) else off,
            pl.BUTTON_CLIPS to if (clipModeNow) Triple(0, 90, 120) else off,
            pl.BUTTON_UNDO to Triple(40, 40, 40),
            pl.BUTTON_REDO to Triple(40, 40, 40),
        ))
    }

    // Controller mappings. The hub offers every CC and note-on here before it
    // reaches the engine, and this decides whether it's being learned, drives
    // something, or carries on as normal MIDI. It sits below the transport's
    // own handlers so a mapped pad presses exactly the button the screen
    // would.
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
            // Fill is the only action that's held rather than triggered, so
            // it's the only one that needs the release as well as the press.
            if (m.action == com.rm.acidulous.model.Action.Fill.name) {
                com.rm.acidulous.ui.UiPrefs.holdFill(pressed)
            } else if (pressed) {
                when (m.action) {
                    com.rm.acidulous.model.Action.Play.name -> EngineSync.play(launcher = com.rm.acidulous.ui.UiPrefs.clipMode)
                    com.rm.acidulous.model.Action.Stop.name -> NativeEngine.transportStop()
                    com.rm.acidulous.model.Action.PlayStop.name ->
                        if (playing) NativeEngine.transportStop() else EngineSync.play(launcher = com.rm.acidulous.ui.UiPrefs.clipMode)
                    com.rm.acidulous.model.Action.Panic.name -> com.rm.acidulous.ui.panicEverything()
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

    // The singer's dictionary, the first time a song has a singer. Its words
    // were said from their spelling until now, so the song goes again.
    val hasSinger = song.tracks.any { it.machine.type == "Diction" }
    LaunchedEffect(hasSinger) {
        if (!hasSinger || com.rm.acidulous.model.lyrics.Lexicon.dictionary != null) return@LaunchedEffect
        val loaded = runCatching {
            val bytes = Res.readBytes("files/dictionary.bin")
            withContext(Dispatchers.Default) { com.rm.acidulous.model.lyrics.Dictionary(bytes) }
        }.onFailure { Log.w("App", "dictionary: ${it.message}") }.getOrNull() ?: return@LaunchedEffect
        com.rm.acidulous.model.lyrics.Lexicon.dictionary = loaded
        EngineSync.sync(editor.song)
    }

    // Keep the screen on while the transport runs (if the setting says so),
    // so a take isn't lost to the screen locking.
    KeepScreenOn(playing && com.rm.acidulous.ui.UiPrefs.keepAwake)

    LaunchedEffect(Unit) {
        while (true) {
            peak = NativeEngine.readPeakLevel()
            playing = NativeEngine.isPlaying
            // The service follows the transport rather than the app's
            // lifetime, so there's no notification while the app sits idle.
            // `wasPlaying` still holds the last poll's value here (it's
            // updated further down), and the call is only made on a change,
            // since `startForegroundService` every 80 ms would be a binder
            // call every 80 ms.
            if (playing != wasPlaying) {
                // On Android: the playback service, and stopping for a call,
                // another app's music, or unplugged headphones.
                AppHost.current.transportChanged(playing) { NativeEngine.transportStop() }
            }
            position = Position.unpack(NativeEngine.positionPacked)
            countInBeats = countInBeatsOf(NativeEngine.countInRemaining)
            elapsedSeconds = (NativeEngine.elapsedMs / 1000).toInt()
            bpm = NativeEngine.tempo
            // The engine disarms by itself at the end of a pass recorded once:
            // the take ends there as it would on the button.
            val armedNow = NativeEngine.recordArmed
            if (armed && !armedNow) onArm(false)
            armed = armedNow
            notesOn = NativeEngine.notesOn(0)
            notesOff = NativeEngine.notesOff(0)
            load = NativeEngine.loadAvg
            // Peak-hold values, and reading clears them, so only this place
            // may read them. Held across polls rather than shown raw, since
            // at 80 ms a reading would flash past too fast to read.
            // Pressing play zeroes them, so two runs in one session (say the
            // same song at two quality settings) can be compared.
            if (playing && !wasPlaying) {
                worstUs = 0
                worstCpuUs = 0
                lateAt = NativeEngine.lateCallbacks
                stalledAt = NativeEngine.stalledCallbacks
                xrunsAt = NativeEngine.xRunCount
                NativeEngine.worstCallbackUs // reading clears the peak-hold
                NativeEngine.worstCallbackCpuUs
            }
            wasPlaying = playing
            worstUs = maxOf(worstUs, NativeEngine.worstCallbackUs)
            worstCpuUs = maxOf(worstCpuUs, NativeEngine.worstCallbackCpuUs)
            // Ours are cumulative and Oboe's belongs to the stream, so all
            // three are shown as the change since play was last pressed.
            lateCallbacks = NativeEngine.lateCallbacks - lateAt
            stalled = NativeEngine.stalledCallbacks - stalledAt
            xruns = NativeEngine.xRunCount - xrunsAt
            fade = NativeEngine.masterFade
            stopAtEnd = NativeEngine.stopAtEnd
            queuedScene = NativeEngine.queuedScene
            com.rm.acidulous.midi.MidiHub.readSync()
            com.rm.acidulous.engine.LinkHub.poll()
            // A launcher track that has just looped to the start of its
            // clip: what was recorded into it goes to the engine now, so a
            // loop hears its last pass on the next one. The arranger's
            // playhead is stale in clip mode and can't tell.
            var cycleWrapped = false
            if (com.rm.acidulous.ui.UiPrefs.clipMode) {
                NativeEngine.launchStates(launchPacked)
                val next = launchPacked.map { LaunchState.unpack(it) }
                // Around the clip, not the cycle: a cycle is the clip times
                // the scene's repeats, and a loop has to hear its last pass on
                // the next one, not two passes later.
                cycleWrapped = next.indices.any { i ->
                    val scene = song.scenes.getOrNull(next[i].scene)
                    val clip = scene?.let { song.tracks.getOrNull(i)?.clips?.get(it.id) }
                    val len = if (scene != null && clip != null) song.clipLengthTicks(scene.id, clip).toLong() else 0L
                    next[i].playing && next[i].scene == launchStates[i].scene && len > 0 &&
                        next[i].tickInCycle % len < launchStates[i].tickInCycle % len
                }
                launchStates = next
                looper.poll(song, launchStates, playing)
            }
            // Only when a level moved: a new array every poll would count as a
            // change and redraw every meter for nothing.
            val levels = FloatArray(16) { i -> if (i < song.tracks.size) NativeEngine.readRackPeak(i) else 0f }
            if (!levels.contentEquals(rackPeaks)) rackPeaks = levels
            // Whether it's struggling now, not whether it ever did. It's the
            // change since the last poll, held briefly so a single late
            // callback is visible and a burst reads as one steady state
            // rather than blinking.
            val lateNow = NativeEngine.lateCallbacks
            if (lateNow > lateSeen) strainUntil = System.currentTimeMillis() + 2500
            lateSeen = lateNow
            straining = playing && System.currentTimeMillis() < strainUntil

            // Choosing quality from two signals, not one.
            //
            // A worst block over budget isn't enough on its own: the block
            // may have been interrupted rather than slow, and lean quality
            // can't get the core back from the scheduler. So lean is only
            // chosen when uninterrupted blocks are over budget (the song
            // costs more than the device has), and full comes back when
            // they aren't.
            //
            // The hysteresis is wide on purpose. Switching the amp's
            // oversampling is audible, and a watcher changing its mind at the
            // edge of the budget would do it every few seconds. Over is 100%
            // of a block and back is 70%, and each has to hold for a while
            // before anything changes.
            if (com.rm.acidulous.ui.UiPrefs.autoQuality && playing) {
                // The decaying figure, not the peak-hold. `worstBlockUs` is
                // cleared by whoever reads it and the Settings window reads
                // it, so polling it here would leave that window empty.
                // `recentCallbackUs` decays instead, so it can have two
                // readers, and the callback is the span with the deadline
                // anyway.
                val budgetUs = NativeEngine.callbackBudgetUs.toFloat()
                // The average where peaks aren't measured: a browser.
                val recent = if (AppHost.current.timesAudioPrecisely) NativeEngine.recentCallbackUs.toFloat()
                             else NativeEngine.loadAvg / 100f * budgetUs
                val interrupted = NativeEngine.interruptedPercent
                if (budgetUs > 0f) {
                    val share = recent / budgetUs
                    val ours = interrupted < 25f // mostly our own cost, not the OS taking the core
                    val now = System.currentTimeMillis()
                    if (ours && share > 1.0f) {
                        if (leanSince == 0L) leanSince = now
                        fullSince = 0L
                        if (now - leanSince > 3000) com.rm.acidulous.ui.UiPrefs.applyAutoQuality(false)
                    } else if (share < 0.7f) {
                        if (fullSince == 0L) fullSince = now
                        leanSince = 0L
                        if (now - fullSince > 15000) com.rm.acidulous.ui.UiPrefs.applyAutoQuality(true)
                    }
                }
            } else if (!com.rm.acidulous.ui.UiPrefs.autoQuality) {
                // Switched off, or never on: what the user chose is what runs.
                com.rm.acidulous.ui.UiPrefs.applyAutoQuality(com.rm.acidulous.ui.UiPrefs.fullQuality)
                leanSince = 0L
                fullSince = 0L
            }
            // Worked out here, not in composition: before the engine opens a
            // stream the sample rate is 0, and dividing by it made the budget
            // huge so no track was ever marked.
            val rate = NativeEngine.sampleRate
            val blockBudgetUs = if (rate > 0) 1_000_000f * 64f / rate else 0f
            // Hysteresis, or it blinks: the cost decays continuously, so a
            // track near the line would cross it several times a second. It
            // takes a third of a block to light and has to fall under a
            // quarter to go out.
            // A track's cost is its worst blocks, which a browser can't time
            // (AppHost.timesAudioPrecisely), so there every track would light.
            // Assigned only when one changes, like the levels above: a new
            // array every poll rebuilt the whole song screen twelve times a
            // second.
            val hot = BooleanArray(16) { i ->
                if (blockBudgetUs <= 0f || i >= song.tracks.size || !AppHost.current.timesAudioPrecisely) {
                    false
                } else {
                    val share = NativeEngine.rackCostUs(i) / blockBudgetUs
                    if (rackHot[i]) share > 0.22f else share > 0.33f
                }
            }
            if (!hot.contentEquals(rackHot)) rackHot = hot
            if (armed || playing) {
                // Each track's clip and how far in, for a take that replaces.
                val heads = launchStates
                val inClips = com.rm.acidulous.ui.UiPrefs.clipMode
                val playhead: (Int) -> Pair<String, Long>? = { rack ->
                    if (inClips) {
                        heads.getOrNull(rack)?.takeIf { it.playing }?.let { st -> song.scenes.getOrNull(st.scene)?.id?.let { it to st.tickInCycle } }
                    } else {
                        song.scenes.getOrNull(position.scene)?.id?.let { it to position.tickInIteration }
                    }
                }
                applyRecorded(recorder.poll(song, position, playing, sceneIdOf, cycleWrapped, playhead))
            }
            // A take is one pass of armed and playing. Stopping either ends it.
            if (!(armed && playing)) editor.endTake()
            delay(80)
        }
    }

    // Autosave: 1.2 s after the last edit, and again as soon as the app goes
    // to the background, because Android may kill the process from there.
    LaunchedEffect(song) {
        delay(1200)
        runCatching { SongStore.saveSession(song) }.onFailure { Log.w(TAG, "session autosave failed", it) }
    }
    val currentSong by rememberUpdatedState(song)
    OnBackground {
        runCatching { SongStore.saveSession(currentSong) }
            .onFailure { Log.w(TAG, "session save on stop failed", it) }
    }

    // Load and xruns come first, after the stream's own description, since
    // this line is cut off on a phone and it's the only place they're shown.
    // `worst` is the number a dropout is about: the longest a single callback
    // took, against the time it had. `load` next to it is a smoothed average,
    // useful for "is it working hard" but not for "why did it click", since a
    // block over budget decays out of it in 27 ms and this line is redrawn
    // every 80.
    // Read when the readout draws, not here: these change every poll, and
    // read here they rebuilt the whole app, and the editor with it, every
    // 80 ms.
    val diagnostics = {
        val budgetUs = NativeEngine.callbackBudgetUs.coerceAtLeast(1)
        // In a browser the peaks aren't measured (see
        // AppHost.timesAudioPrecisely), so the line shows the load and what's
        // playing.
        val timings = if (AppHost.current.timesAudioPrecisely) {
            " · worst %.1f/%.1fms cpu %.1f · late %d stall %d · xruns %d".format(
                worstUs / 1000f, budgetUs / 1000f, worstCpuUs / 1000f, lateCallbacks, stalled, xruns,
            )
        } else {
            ""
        }
        ("%s · load %.0f%%%s · peak %.3f · fade %.2f · on %d off %d%s").format(
            status, load, timings, peak, fade, notesOn, notesOff,
            // Only while Link is on: how many machines are sharing this tempo.
            if (com.rm.acidulous.engine.LinkHub.enabled) {
                " · link %d".format(com.rm.acidulous.engine.LinkHub.peers)
            } else {
                ""
            },
        ) }

    if (exportAsk) {
        com.rm.acidulous.ui.ExportOptionsDialog(
            sceneName = song.scenes.getOrNull(position.scene)?.name.orEmpty(),
            onDismiss = { exportAsk = false },
        ) { options ->
            exportAsk = false
            exportWanted = options
            val base = safeName(song.name)
            if (options.manyFiles) {
                folderPicker()
            } else {
                filePicker(base + options.format.extension, options.format.mime)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.rm.acidulous.ui.LocalSongMappings provides song.mappings,
    ) {
    /**
     * The system back button goes back a screen, and only leaves the app from
     * the top.
     *
     * Windows aren't handled here: a Compose Dialog is its own window and
     * handles back itself, which is also why the conversion window (whose
     * dismiss does nothing) can't be closed during the work it reports.
     */
    SystemBack(enabled = screen !is Screen.Main) {
        screen = when (val s = screen) {
            // The graph belongs to a machine, so back goes to the machine
            // rather than all the way out, like its own arrow does.
            is Screen.Patch -> Screen.Edit(s.track, s.sceneId)
            else -> Screen.Main
        }
    }

    // The song screen stays built under the others, so going back to it is
    // quick. See KeepBuilt.
    androidx.compose.foundation.layout.Box(modifier) {
    // Hidden, it's given what it last showed, so playing and editing don't
    // rebuild it behind the editor twelve times a second. The callbacks read
    // the song and transport when they run rather than holding them, for the
    // same reason.
    val hidden = screen !is Screen.Main
    val playingNow by rememberUpdatedState(playing)
    com.rm.acidulous.ui.KeepBuilt(!hidden) {
        MainScreen(
            song = heldWhile(hidden, song), editor = editor, position = heldWhile(hidden, position),
            playing = heldWhile(hidden, playing), armed = heldWhile(hidden, armed),
            performTrack = heldWhile(hidden, midiTrack),
            looper = looper,
            countInBeats = heldWhile(hidden, countInBeats),
            elapsedSeconds = heldWhile(hidden, elapsedSeconds),
            clipMode = heldWhile(hidden, com.rm.acidulous.ui.UiPrefs.clipMode),
            launchStates = heldWhile(hidden, launchStates),
            onClipMode = onClipMode,
            loopScene = heldWhile(hidden, loopScene), stopAtEnd = heldWhile(hidden, stopAtEnd),
            queuedScene = heldWhile(hidden, queuedScene),
            bpm = heldWhile(hidden, bpm), diagnostics = if (hidden) { { "" } } else diagnostics,
            rackPeaks = if (hidden) { { FloatArray(16) } } else { { rackPeaks } },
            masterPeak = if (hidden) { { 0f } } else { { peak } }, clickOn = heldWhile(hidden, clickOn),
            straining = heldWhile(hidden, straining), rackHot = heldWhile(hidden, rackHot),
            onClick = { on -> clickOn = on; EngineSync.setMetronome(on, com.rm.acidulous.ui.UiPrefs.clickVolume, com.rm.acidulous.ui.UiPrefs.clickVoice, com.rm.acidulous.ui.UiPrefs.clickDivision, com.rm.acidulous.ui.UiPrefs.clickWhen) },
            onArm = onArm, onLoopScene = onLoopScene,
            onOpenClip = { track, sceneId -> screen = Screen.Edit(track, sceneId) },
            onSave = { SongStore.save(currentSong); Log.i(TAG, "saved ${currentSong.name}") },
            onSaveAs = { name -> val renamed = currentSong.copy(name = name); editor.replace(renamed); SongStore.save(renamed); Log.i(TAG, "saved as $name") },
            onNew = { name ->
                val fresh = com.rm.acidulous.ui.UiPrefs.newSong(name)
                swapSong(fresh)
                SongStore.save(fresh)
            },
            // The same swap, for the same reason as a new song: otherwise the
            // transport would carry on into the loaded song at whatever scenes
            // are at those indices, with the old song's tails ringing.
            onLoad = { name ->
                runCatching { SongStore.load(name) }
                    .onSuccess { swapSong(it) }
                    .onFailure { Log.w(TAG, "load failed", it) }
            },
            onDelete = { name -> SongStore.delete(name); Log.i(TAG, "deleted $name") },
            songNames = { SongStore.list() },
            onExport = { if (!playingNow) exportAsk = true },
            onImport = { importPicker(arrayOf("*/*")) },
            onShareSong = { shareSong() },
            onShareExport = { done -> AppHost.current.share(done.uris.filterIsInstance<Doc>(), done.mime, done.fileName) },
            exportState = heldWhile(hidden, exportState),
            onExportCancel = { NativeEngine.cancelRender() },
            onExportDismiss = { exportState = null },
            onFreeze = onFreeze,
            onThaw = onThaw,
            freezeStatus = heldWhile(hidden, freezeStatus),
        )
    }
    // Going back, the editor is hidden at once and taken down a frame later,
    // so the song screen shows without waiting for it.
    var leaving by remember { mutableStateOf<Screen?>(null) }
    LaunchedEffect(screen) {
        if (screen is Screen.Main) { withFrameNanos { }; leaving = null } else leaving = screen
    }
    val away = if (screen !is Screen.Main) screen else leaving
    if (away != null) com.rm.acidulous.ui.KeepBuilt(screen !is Screen.Main) {
    when (val s = away) {
        Screen.Main, null -> Unit
        is Screen.Edit -> EditScreen(
            song = song, editor = editor, trackIndex = s.track, sceneId = s.sceneId,
            position = { position }, playing = playing, armed = armed, onArm = onArm,
            rackPeaks = { rackPeaks }, masterPeak = { peak }, clickOn = clickOn, onClick = { on -> clickOn = on; EngineSync.setMetronome(on, com.rm.acidulous.ui.UiPrefs.clickVolume, com.rm.acidulous.ui.UiPrefs.clickVoice, com.rm.acidulous.ui.UiPrefs.clickDivision, com.rm.acidulous.ui.UiPrefs.clickWhen) },
            onBack = { screen = Screen.Main },
            onTrack = { t -> screen = Screen.Edit(t, s.sceneId) },
            onOpenPatch = { screen = Screen.Patch(s.track, s.sceneId) },
            onOpenSample = { pad -> sampleEdit = s.track to pad },
            patchNames = { PatchStore.list(song.tracks[s.track].machine.type) },
            // Save the settings, not only the knobs: a Nexus patch without its
            // graph, a Mosaic without its zones or a Formulate without its
            // formula would come back wrong.
            onSavePatch = { name, low, high ->
                val m = song.tracks[s.track].machine
                PatchStore.save(Patch(m.type, name, m.params, m.settings, low, high))
            },
            onLoadPatch = { name -> PatchStore.load(song.tracks[s.track].machine.type, name) },
            factoryPatchNames = { PatchStore.factory(song.tracks[s.track].machine.type) },
            userPatchNames = { PatchStore.userList(song.tracks[s.track].machine.type) },
            onDeletePatch = { name -> PatchStore.delete(song.tracks[s.track].machine.type, name) },
            onImportSample = { track, pad ->
                importTarget = track to "p%02d_sample".format(pad)
                samplePicker(AUDIO_TYPES)
            },
            onImportOneSample = { track ->
                importTarget = track to "sample"
                samplePicker(AUDIO_TYPES)
            },
            onImportKit = { track, pad ->
                kitTarget = track to pad
                kitPicker(AUDIO_TYPES)
            },
            onImportSlice = { track ->
                // One file for all thirteen pads. The slice points are worked
                // out afterwards in the panel.
                importTarget = track to "slice_sample"
                samplePicker(AUDIO_TYPES)
            },
            onImportSoundFont = { track -> mapTarget = track; soundFontPicker(arrayOf("*/*")) },
            onPickPreset = { track ->
                val rel = song.tracks[track].machine.settings["sf2"]
                if (rel != null) {
                    mapBusy = true
                    scope.launch {
                        val path = File(EngineAssets.userRoot(), rel).absolutePath
                        val presets = withContext(Dispatchers.IO) { NativeEngine.soundFontPresets(path) }
                        mapBusy = false
                        if (presets.isNotEmpty()) presetChoice = track to presets
                    }
                }
            },
            onImportZoneSamples = { track -> mapTarget = track; zoneSamplePicker(AUDIO_TYPES) },
        )
        is Screen.Patch -> com.rm.acidulous.ui.PatchScreen(
            track = song.tracks[s.track],
            trackIndex = s.track,
            editor = editor,
            onBack = { screen = Screen.Edit(s.track, s.sceneId) },
        )
    }
    }
    }
    }

    // Choosing which SoundFont preset to play. Shown over either screen,
    // because the import starts from the Edit screen but the listing
    // finishes on a worker.
    presetChoice?.let { (track, presets) ->
        fun label(line: String): String {
            val f = line.split('|')
            return if (f.size >= 3) "%03d:%03d  %s".format(f[0].toIntOrNull() ?: 0, f[1].toIntOrNull() ?: 0, f[2]) else line
        }
        com.rm.acidulous.ui.PickerDialog(
            title = stringResource(Res.string.app_soundfont_preset),
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
 * A control was waiting to be learned and this is what arrived, so bind them.
 *
 * The target is the string mapping mode left there: `"rack:unit:name"` for
 * a parameter or `"action:Panic"` for a button. Learned mappings are saved
 * to the device, not the song, since a controller usually gets set up once.
 * A song can still carry its own and they take priority, though nothing in
 * the UI writes those yet.
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
 * Something mapped arrived, so do what it says.
 *
 * A parameter takes the value. An action only fires on the press, so holding
 * a footswitch doesn't fire it twice and releasing it doesn't fire it at
 * all. A note on a two-step parameter toggles it, like a pad on a switch
 * should. On anything else it sets the value from its velocity.
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
    // The master has no rack. Everything else uses the mapping's own, then
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

/**
 * [value] while [hidden] is false, and what it was when it last was while it's
 * true. For a screen kept behind another (see KeepBuilt): an argument that
 * doesn't change doesn't rebuild it.
 */
@Composable
private fun <T> heldWhile(hidden: Boolean, value: T): T {
    val held = remember { arrayOf<Any?>(value) }
    if (!hidden) held[0] = value
    @Suppress("UNCHECKED_CAST")
    return held[0] as T
}
