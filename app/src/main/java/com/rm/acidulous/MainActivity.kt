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
import com.rm.acidulous.engine.uniqueIn
import com.rm.acidulous.model.emptyClipFor
import com.rm.acidulous.model.marksFrom
import com.rm.acidulous.model.splitTake
import com.rm.acidulous.model.updateClip
import com.rm.acidulous.model.withTake
import com.rm.acidulous.ui.BiasArm
import com.rm.acidulous.ui.TakePeaks
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.model.clipLengthTicks
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
import com.rm.acidulous.model.passSeconds
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.writeTextSafely
import java.io.File
import com.rm.acidulous.ui.EditScreen
import com.rm.acidulous.ui.MainScreen
import com.rm.acidulous.ui.theme.AcidulousTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.rm.acidulous.model.duplicateScene
import com.rm.acidulous.model.cleared
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.emptyClipFor
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

/**
 * A file another app opened with this one, or shared to it, waiting for the
 * app to be ready to import it. Filled by the activity from its intent; taken
 * by the composition once the session is back, so what arrives is not then
 * replaced by the song that was open last time.
 */
internal object Incoming {
    var uri by mutableStateOf<android.net.Uri?>(null)

    fun from(intent: android.content.Intent?) {
        intent ?: return
        uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND ->
                @Suppress("DEPRECATION") (intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri)
            else -> null
        } ?: return
    }
}

/**
 * The share sheet, with [uris] readable by whatever app is chosen - for the
 * length of that app's visit, not for ever.
 */
internal fun share(context: android.content.Context, uris: List<android.net.Uri>, mime: String, title: String) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
    } else {
        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = mime
    // The grant travels on the clip data; without it the chosen app is handed
    // a link it may not open.
    send.clipData = android.content.ClipData.newRawUri(title, uris[0]).apply {
        uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
    }
    send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, title))
}

/** A crash report through the share sheet, as a text file. */
internal fun shareCrashReport(context: android.content.Context, report: File) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val copy = File(dir, report.name).also { report.copyTo(it, overwrite = true) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", copy)
        share(context, listOf(uri), "text/plain", context.getString(R.string.app_crash_report_subject))
    }.onFailure { Log.w("Acidulous.Crash", "could not share the report", it) }
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Incoming.from(intent)
    }

    override fun onDestroy() {
        // Leaving for good: the Exquis's pads go dark rather than going on
        // showing a scale for an app that is not there.
        if (isFinishing) {
            com.rm.acidulous.midi.MidiHub.clearPads()
            // And a Launchpad goes back to being itself.
            com.rm.acidulous.midi.MidiHub.releaseLaunchpad()
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything else can throw.
        CrashReports.install(this)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) CrashReports.collect(this)
        // Only on a fresh start: a recreated activity has already taken it.
        if (savedInstanceState == null) Incoming.from(intent)
        com.rm.acidulous.ui.UiPrefs.init(this)
        com.rm.acidulous.model.Names.scene = { getString(R.string.name_scene, it) }
        com.rm.acidulous.model.Names.copyOf = { getString(R.string.name_copy, it) }
        com.rm.acidulous.midi.MidiHub.start(this)
        EngineAssets.install(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        goFullScreen()
        setContent {
            // **One density, above everything.**
            //
            // The interface scale is a multiplier on the density rather than
            // on each of the sizes: every `dp` and every `sp` in the tree
            // resolves through this one `Density`, so the whole app grows
            // together and no size has to learn about the setting. Composition
            // locals reach into a `Dialog`'s subcomposition too, so the windows
            // come with it.
            //
            // Outside `AcidulousTheme` because the splash and the Scaffold's
            // own insets are inside it and both are sizes somebody asked to be
            // bigger. Read from `base` every time rather than from
            // `LocalDensity` after the fact, or the multiplier would compound
            // on itself on every recomposition.
            //
            // What the app then believes is that it has *less* screen - at 1.3
            // a Pixel 5 reports 302 x 655 dp instead of 393 x 851 - which is
            // the shape M43 already made every screen survive at 393 dp of
            // height. ui/UiScale.kt caps the wish against what there is.
            val base = androidx.compose.ui.platform.LocalDensity.current
            // Not `window`: that is the Activity's own, wanted a few lines
            // down for the bar appearance.
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
                // The unscaled one travels too, because every window drawn
                // over this one is handed a fresh density by Compose and has
                // to put the scale back itself - see ui/UiScale.kt.
                com.rm.acidulous.ui.LocalBaseDensity provides base,
            ) {
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
                // **Over the Scaffold, not inside it.** The splash is a
                // screen rather than a window - see ui/SplashScreen.kt for
                // why the system's own is made to show nothing - and a
                // Scaffold's content slot takes one child, so a second one
                // handed to it is measured and then not drawn. In a Box of
                // its own it is plainly on top.
                //
                // The app is composed underneath it the whole time, so the
                // three quarters of a second is spent on the engine starting
                // rather than instead of it.
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

    /**
     * **Every key comes through here first**, whether or not anything on
     * screen has focus - which a Compose modifier would need before it saw a
     * key at all. Play mode's notes and the chords with a modifier are taken
     * before the screen; whatever the focused control does not use comes
     * back for the plain-letter shortcuts. See ui/Keys.kt.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.rm.acidulous.ui.KeyHub.preview(event)) return true
        if (super.dispatchKeyEvent(event)) return true
        return com.rm.acidulous.ui.KeyHub.fallback(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.rm.acidulous.ui.KeyHub.usingKeys = false
        return super.dispatchTouchEvent(ev)
    }

    /** What Meta+/ lists on a USB keyboard: the shortcuts this screen answers to. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<android.view.KeyboardShortcutGroup>,
        menu: android.view.Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val items = com.rm.acidulous.ui.KeyHub.live().mapNotNull { action ->
            val chord = com.rm.acidulous.ui.UiPrefs.keyBindings[action]?.firstOrNull() ?: return@mapNotNull null
            var mods = 0
            if (chord.ctrl) mods = mods or android.view.KeyEvent.META_CTRL_ON
            if (chord.alt) mods = mods or android.view.KeyEvent.META_ALT_ON
            if (chord.shift) mods = mods or android.view.KeyEvent.META_SHIFT_ON
            if (chord.meta) mods = mods or android.view.KeyEvent.META_META_ON
            android.view.KeyboardShortcutInfo(getString(action.label), chord.key, mods)
        }
        if (items.isNotEmpty()) data += android.view.KeyboardShortcutGroup(getString(R.string.keys_title), items)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A note held on a key whose key up now goes to another window would
        // never end.
        if (!hasFocus) com.rm.acidulous.ui.KeyHub.releaseAll()
        // A dialog, a permission prompt or the recents screen brings them
        // back; take the height again as soon as we have focus.
        if (hasFocus) goFullScreen()
    }
}

private const val TAG = "Acidulous.UI"

/** Where the app remembers that the demo has been opened once: see the start of [App]. */
private const val FIRST_RUN = "first_run"
private const val DEMO_OPENED = "demo_opened"

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
    // Read through this rather than the context, so words follow a change of language.
    val resources = androidx.compose.ui.platform.LocalResources.current

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
    // Record quantise, as the tempo window's record card sets it.
    androidx.compose.runtime.SideEffect {
        recorder.quantise = com.rm.acidulous.ui.UiPrefs.recordQuantise
        recorder.strength = com.rm.acidulous.ui.UiPrefs.recordStrength / 100f
    }
    var screen by rememberSaveable(saver = Screen.Saver) { mutableStateOf<Screen>(Screen.Main) }
    // Hardware notes go where the last opened clip was, which is the track
    // the player is working on whether or not its editor is still in front.
    var midiTrack by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(screen) {
        (screen as? Screen.Edit)?.let { midiTrack = it.track }
        com.rm.acidulous.midi.MidiHub.target = { midiTrack }
        com.rm.acidulous.ui.KeyHub.target = { midiTrack }
    }
    // An Exquis shows the scale of the track it plays: the one the roll would
    // show for that track - its own Scale modifier, or failing that the
    // song's key. Which track is the routing's call, the same rule its notes
    // follow, so a pinned track or a channel's own shows that one's.
    val exquisTrack = song.tracks.getOrNull(com.rm.acidulous.midi.MidiHub.trackForChannel(com.rm.acidulous.midi.MidiHub.exquisChannel))
    val exquisScale = exquisTrack?.let {
        com.rm.acidulous.model.Scales.rootFor(song, it) to com.rm.acidulous.model.Scales.activeFor(song, it)
    }
    LaunchedEffect(exquisScale) {
        com.rm.acidulous.midi.MidiHub.showScale(exquisScale?.first, exquisScale?.second)
    }
    // Typed notes go where hardware notes do; on a drum machine they are its
    // pads in order rather than a scale.
    androidx.compose.runtime.SideEffect {
        com.rm.acidulous.ui.KeyHub.drumVoices = { rack ->
            song.tracks.getOrNull(rack)?.machine?.let { m ->
                if (com.rm.acidulous.model.MachineUi.kindOf(m.type) == com.rm.acidulous.model.MachineKind.Drums) {
                    com.rm.acidulous.model.MachineUi.voicesOf(m.type, m.settings).map { it.note }
                } else null
            }
        }
    }
    // The keys every screen answers to the same way.
    com.rm.acidulous.ui.KeyScope(
        com.rm.acidulous.ui.KeyAction.PlayMode to { com.rm.acidulous.ui.KeyHub.togglePlayMode() },
        com.rm.acidulous.ui.KeyAction.Panic to { com.rm.acidulous.ui.panicEverything() },
        com.rm.acidulous.ui.KeyAction.KeysHelp to { com.rm.acidulous.ui.KeyHub.showingKeys = true },
        // No Back here: at the song screen back leaves the app, and Esc is
        // pressed too casually for that. The editor says what back means.
    )
    com.rm.acidulous.ui.KeyHub.actionMenu?.let { actions ->
        com.rm.acidulous.ui.KeyActionMenu(actions, onDismiss = { com.rm.acidulous.ui.KeyHub.actionMenu = null })
    }
    if (com.rm.acidulous.ui.KeyHub.showingKeys) {
        com.rm.acidulous.ui.KeysOverlay(onDismiss = { com.rm.acidulous.ui.KeyHub.showingKeys = false })
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
    /** The file the microphone is writing to while a Bias lane is armed. */
    var biasTakeFile by remember { mutableStateOf<java.io.File?>(null) }
    DisposableEffect(Unit) {
        EngineSync.onProblem = { message, args ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { problem = resources.getString(message, *args) }
        }
        onDispose { EngineSync.onProblem = null }
    }
    problem?.let { message ->
        com.rm.acidulous.ui.PlainDialog(
            title = stringResource(R.string.app_load_failed_title),
            onDismiss = { problem = null },
            dismissLabel = stringResource(R.string.close),
        ) {
            Text(message, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
            Text(
                stringResource(R.string.app_load_formats),
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
            title = if (total > 1) stringResource(R.string.app_converting_of, done, total) else stringResource(R.string.app_converting),
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
        val long = if (seconds >= 120) resources.getQuantityString(R.plurals.app_minutes, seconds / 60, seconds / 60)
            else resources.getQuantityString(R.plurals.app_seconds, seconds, seconds)
        problem = resources.getString(R.string.app_truncated, long, names.joinToString(resources.getString(R.string.list_separator)))
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
                .onFailure { problem = resources.getString(R.string.app_file_failed, it.message) }
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
                problem = resources.getString(R.string.app_files_refused, refused.joinToString(resources.getString(R.string.list_separator)))
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
    // A scene export is its first pass, which is its last only when it
    // plays once - and only the last pass carries a ramp.
    fun sceneSeconds(scene: com.rm.acidulous.model.Scene): Float = song.passSeconds(scene, last = scene.repeat <= 1)

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
            // A normalised export measures first: the same render into no file,
            // then a gain that brings it to the target - but never past a true
            // peak of -1 dBTP, which is what a lossy encoder needs above it.
            var gainDb = 0f
            if (options.normalise && options.format.audio) {
                // See there: a render starts from the document. On the main
                // thread, the only one that may send parameters.
                withContext(Dispatchers.Main) { EngineSync.pushForRender(song) }
                val m = NativeEngine.measureLoudness(options.tailSeconds, scene, limit)
                    ?: return@withContext emptyList<File>() to resources.getString(R.string.app_export_unmeasured)
                if (m[0] > -70f) gainDb = minOf(com.rm.acidulous.ui.NORMALISE_LUFS - m[0], -1f - m[1])
                Log.i(TAG, "normalise: measured %.1f LUFS, %.1f dBTP; gain %.1f dB".format(m[0], m[1], gainDb))
            }
            NativeEngine.setRenderGain(gainDb)
            // After the measuring pass too, whose lanes moved things again.
            if (options.format.audio) withContext(Dispatchers.Main) { EngineSync.pushForRender(song) }
            try { when (options.format) {
                com.rm.acidulous.ui.ExportFormat.Midi -> {
                    val file = File(cache, "$base.mid")
                    runCatching { com.rm.acidulous.model.MidiFile.write(song, file); listOf(file) to "" }
                        .getOrElse { emptyList<File>() to (it.message ?: resources.getString(R.string.app_export_midi_failed)) }
                }
                com.rm.acidulous.ui.ExportFormat.Bundle -> {
                    val file = File(cache, "$base.zip")
                    runCatching {
                        com.rm.acidulous.model.SongBundle.write(song, EngineAssets.userRoot(context), file)
                        listOf(file) to ""
                    }.getOrElse { emptyList<File>() to (it.message ?: resources.getString(R.string.app_export_bundle_failed)) }
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
                        // A stem is what reaches the master, so a track routed
                        // into a group is in the group's stem and not also in
                        // its own - or the stems would sum to more than the mix.
                        val groups = song.master.groups
                        val racks = song.tracks.indices.filter {
                            val t = song.tracks[it]
                            t.machine.type.isNotEmpty() && t.mixer.output !in 1..groups.size
                        }
                        if (racks.isEmpty()) {
                            emptyList<File>() to resources.getString(R.string.app_export_no_tracks)
                        } else {
                            // The mix comes too, as file 00. It costs one
                            // more sink in a pass that is happening anyway,
                            // and stems without the mix they came from are
                            // hard to check and easy to misalign.
                            // And each group is a stem of its own, with its
                            // tracks in it. -2 is the first group to the engine.
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
        written: List<android.net.Uri> = emptyList(),
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
                }.getOrElse { it.message ?: resources.getString(R.string.app_export_copy_failed) }
            }
            ticker.cancel()
            finish(options, files, copyError, displayName(context, uri), listOf(uri))
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
            val created = mutableListOf<android.net.Uri>()
            val copyError = if (error.isNotEmpty()) error else withContext(Dispatchers.IO) {
                runCatching {
                    val parentId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                    for (file in files) {
                        val target = android.provider.DocumentsContract.createDocument(
                            context.contentResolver, parent, options.format.mime, file.name,
                        ) ?: error(resources.getString(R.string.app_export_create_failed, file.name))
                        created += target
                        context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                    }
                    ""
                }.getOrElse { it.message ?: resources.getString(R.string.app_export_copy_failed) }
            }
            ticker.cancel()
            finish(options, files, copyError, displayName(context, tree), created)
        }
    }

    DisposableEffect(Unit) {
        EngineSync.sampleRoot = EngineAssets.userRoot(context)
        EngineSync.freezeRoot = EngineAssets.freezeRoot(context)
        NativeEngine.setCacheRoot(EngineAssets.reelCache(context).absolutePath)
        // Trinity's wavetables take a moment to build; do it off the main
        // thread now rather than stalling the first mount.
        Thread { NativeEngine.prewarm() }.start()
        EngineSync.forgetEngine()
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
            // Come back to whatever was open. The demo comes up once, on the
            // first run after installing, and is saved with the songs so it can
            // be opened again from there; a session that will not load after
            // that is a new song rather than the demo every time.
            val restored = runCatching { SongStore.loadSession(context) }.getOrNull()
            val firstRun = context.getSharedPreferences(FIRST_RUN, android.content.Context.MODE_PRIVATE)
            val loaded = when {
                restored != null -> restored
                !firstRun.getBoolean(DEMO_OPENED, false) -> DemoSong.build().also {
                    if (!SongStore.exists(context, it.name)) SongStore.save(context, it)
                }
                else -> com.rm.acidulous.ui.UiPrefs.newSong(resources.getString(R.string.main_untitled))
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
    // How long the automatic quality watcher has wanted each answer. Zero
    // means "it does not want that one at the moment".
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
            result.song.tracks.forEachIndexed { i, t -> if (t !== song.tracks.getOrNull(i)) editor.recorded(i, t) }
        }
        if (result.push) EngineSync.sync(editor.song)
    }
    /**
     * Put a different song in front of the engine, from a standing start.
     *
     * **Stop before the swap, not after.** The scheduler is reading the old
     * song's scenes and the swap is what pulls them out from under it; left
     * running, the playhead carries straight on into a song it has never seen
     * and plays whatever happens to be at those indices. Panic *after*,
     * because that is what clears the tails the stop leaves ringing, and
     * `forgetSounding` because a hub that still believes a note is down will
     * never send its note-off.
     *
     * The screen's own copies of the transport state are cleared too. They
     * are polled from the engine and would catch up on their own within a
     * frame, but a play button that shows a stop glyph for one frame at the
     * exact moment a song changes is a flicker somebody will report.
     */
    fun swapSong(next: com.rm.acidulous.model.Song) {
        NativeEngine.transportStop()
        NativeEngine.queuedScene = -1
        NativeEngine.stopAtEnd = false
        editor.replace(next)
        // What the new song does not name goes back to default, or a rack
        // that kept its machine keeps the last song's settings too.
        EngineSync.pushUnnamedDefaults(next)
        // And back to the top, which a stop deliberately does not do: a stop
        // leaves the playhead where it stopped so you can read where that
        // was, which is right until the song underneath it changes. Without
        // this a brand new song opened reading bar 3 of the old one.
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

    /** What an import has to say: a title and a line. Not [problem], whose words are about audio. */
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    notice?.let { (title, message) ->
        com.rm.acidulous.ui.PlainDialog(title = title, onDismiss = { notice = null }, dismissLabel = stringResource(R.string.close)) {
            Text(message, fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi)
        }
    }
    // The last run ended in a crash or a freeze: say so once, and offer the
    // report to whoever can fix it. See CrashReports.
    var crashed by remember { mutableStateOf(CrashReports.unread(context)) }
    crashed?.let { report ->
        com.rm.acidulous.ui.PlainDialog(
            title = stringResource(R.string.app_crashed_title),
            onDismiss = { CrashReports.markRead(context); crashed = null },
            dismissLabel = stringResource(R.string.close),
            confirmLabel = stringResource(R.string.app_crashed_share),
            onConfirm = {
                CrashReports.markRead(context)
                crashed = null
                shareCrashReport(context, report)
            },
        ) {
            Text(
                stringResource(R.string.app_crashed_note),
                fontSize = 13.sp, color = com.rm.acidulous.ui.theme.Acid.colors.textHi,
            )
        }
    }
    /** A MIDI file read and waiting for its import window: its name and its parts. */
    var midiImport by remember { mutableStateOf<Pair<String, com.rm.acidulous.model.MidiFile.Parsed>?>(null) }

    /** A song name nothing saved already has: "Squelch", then "Squelch (2)". */
    fun freeSongName(wanted: String): String {
        val taken = SongStore.list(context).toSet()
        if (wanted !in taken) return wanted
        var n = 2
        while ("$wanted ($n)" in taken) n++
        return "$wanted ($n)"
    }

    /**
     * One door for everything that comes from outside, told apart by its
     * name: the system picker offers every file, and a MIDI file, a bundle
     * and a WAV go to three different places. Also where a file shared to
     * the app, or opened with it, arrives.
     */
    fun importFile(uri: android.net.Uri) {
        val name = displayNameOf(context, uri, "file")
        val ext = name.substringAfterLast('.', "").lowercase()
        val stem = name.substringBeforeLast('.').ifBlank { resources.getString(R.string.app_imported) }
        when (ext) {
            "mid", "midi", "smf", "kar" -> scope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    runCatching { com.rm.acidulous.model.MidiFile.read(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                }
                parsed.onSuccess { p ->
                    if (p.parts.isEmpty()) notice = resources.getString(R.string.app_import_empty_title) to resources.getString(R.string.app_import_empty, name)
                    else midiImport = stem to p
                }.onFailure { notice = resources.getString(R.string.app_open_failed_title) to resources.getString(R.string.app_open_failed, name, it.message) }
            }
            "zip" -> scope.launch {
                val song = withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(context.cacheDir, "import.zip")
                        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        com.rm.acidulous.model.SongBundle.read(tmp, EngineAssets.userRoot(context)).also { tmp.delete() }
                    }.getOrNull()
                }
                if (song == null) {
                    notice = resources.getString(R.string.app_open_failed_title) to resources.getString(R.string.app_not_a_bundle, name)
                } else {
                    val named = song.copy(name = freeSongName(song.name))
                    swapSong(named)
                    SongStore.save(context, named)
                }
            }
            // As long as any machine takes - the ten minutes a slicer can
            // hold - since nobody has said yet what this sound is for.
            "wav", "wave", "aif", "aiff", "aifc", "flac", "mp3" ->
                bringIn(uri, "sample.wav", NativeEngine.SLICE_SECONDS) { rel ->
                    notice = resources.getString(R.string.app_sound_added_title) to resources.getString(R.string.app_sound_added, rel.substringAfterLast('/'))
                }
            // A tuning: checked by reading it, then kept as the file it is.
            "scl" -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                        val tuning = com.rm.acidulous.model.Tunings.parseScl(text, stem)
                        val dir = com.rm.acidulous.model.TuningStore.directory(EngineAssets.userRoot(context))
                        File(dir, stem.replace(Regex("[^A-Za-z0-9 _.-]"), "_") + ".scl").writeTextSafely(text)
                        tuning
                    }
                }
                result.onSuccess {
                    notice = resources.getString(R.string.app_tuning_added_title) to
                        resources.getQuantityString(R.plurals.app_tuning_added, it.cents.size, it.name, it.cents.size)
                }.onFailure { notice = resources.getString(R.string.app_open_failed_title) to resources.getString(R.string.app_open_failed, name, it.message) }
            }
            else -> notice = resources.getString(R.string.app_open_failed_title) to resources.getString(R.string.app_import_what)
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }
    // Opened with the app or shared to it. Declared after the session is
    // restored, so it runs after it and is not replaced by it.
    LaunchedEffect(Incoming.uri) {
        val uri = Incoming.uri ?: return@LaunchedEffect
        Incoming.uri = null
        importFile(uri)
    }

    /** The open song as a bundle, through the share sheet. */
    fun shareSong() {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
                    val file = File(dir, safeName(song.name) + ".zip")
                    com.rm.acidulous.model.SongBundle.write(song, EngineAssets.userRoot(context), file)
                    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                }
            }
            uri.onSuccess { share(context, listOf(it), "application/zip", song.name) }
                .onFailure { notice = resources.getString(R.string.app_share_failed_title) to (it.message ?: resources.getString(R.string.app_share_failed)) }
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
                SongStore.save(context, named)
            },
        )
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
                    freezeStatus = resources.getString(R.string.app_freezing, i + 1, targets.size)
                    // A freeze is a render: it starts from the document too.
                    EngineSync.pushForRender(editor.song)
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

    /**
     * The record button, and - when a Bias lane is armed - the microphone too.
     *
     * **One button.** Arming a lane and then hunting for a second control to
     * start it would be two ways of saying the same thing, and the one you
     * press while the song is already playing has to be the one already under
     * your thumb.
     *
     * The capture runs from the moment it is armed rather than from the moment
     * the transport starts, so nothing is lost while somebody is getting ready
     * - the seconds before play belong to no cell, the engine stamps no mark
     * for them, and the split simply leaves them out.
     */
    fun startBiasCapture() {
        // The input has to be open before the capture will take it, and it is
        // not open by default - the microphone is not something to hold when
        // nobody asked. Opened here and left open; stopping the capture is
        // what ends the recording, not closing the stream.
        NativeEngine.startInput(com.rm.acidulous.ui.UiPrefs.inputDevice)
        val root = java.io.File(EngineAssets.userRoot(context), "samples").apply { mkdirs() }
        val target = java.io.File(root, uniqueIn(root, "take.wav"))
        val error = NativeEngine.startCapture(target.absolutePath, 0)
        if (error.isNotEmpty()) {
            problem = resources.getString(R.string.app_record_failed, error)
            BiasArm.clear()
            return
        }
        biasTakeFile = target
    }

    /**
     * Stop the microphone and cut what was recorded into cells.
     *
     * No audio is copied: one file, N cells, each a window into it. The shapes
     * are taken out of one decode - see `TakePeaks.slice` - because a take
     * across five scenes read five times is five peaks of a hundred megabytes
     * to draw two hundred columns.
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
            // The ring dropped frames, so every index after the drop names the
            // wrong moment. The recording is kept - it is in the library and
            // can be placed by hand - but it must not be cut up.
            problem = resources.getString(R.string.app_record_gap)
            return
        }
        if (count == 0 || track < 0 || lane < 0) {
            problem = resources.getString(R.string.app_record_unplaced)
            return
        }
        val rel = "samples/" + file.name
        // Worth a line in the log: a split that goes wrong is silent, and the
        // marks are the only place the answer can be read afterwards.
        Log.i(TAG, "bias split: $count mark(s) over $frames frames: " +
            marksFrom(raw, count).take(8)
                .joinToString(" ") { "${it.frame}@${it.sceneId}+${it.tick}/${it.cycleTicks}" })
        val takes = splitTake(marksFrom(raw, count), frames, rel, sceneIdOf)
        if (takes.isEmpty()) {
            problem = resources.getString(R.string.app_record_too_short)
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
            // One document edit for the whole take, so undoing a recording is
            // one press rather than one per scene it crossed.
            editor.edit(track, push = true) { next.tracks[track] }
            EngineSync.sync(editor.song)
        }
    }

    /**
     * Asked for at the moment it is needed, which is the first time a lane is
     * armed and record is pressed.
     *
     * The recorder window has its own button for this; arriving there to be
     * told to go somewhere else is the version of this that does not respect
     * anybody's time.
     */
    val askToRecord = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) startBiasCapture()
        else problem = resources.getString(R.string.app_record_permission)
    }
    fun mayRecord(): Boolean = context.checkSelfPermission(
        android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val onArm: (Boolean) -> Unit = { on ->
        NativeEngine.recordArmed = on
        if (on) {
            if (BiasArm.any) {
                if (mayRecord()) startBiasCapture()
                else askToRecord.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        } else {
            applyRecorded(recorder.flush(song, sceneIdOf))
            editor.endTake()
            finishBiasCapture()
        }
    }
    // Empty launcher cells record into themselves. It arms recording the way
    // the ○ button does, and points MIDI in at the track it is looping.
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

    // **A Launchpad Pro [MK3], played by the app.** The surface decides what
    // it shows and what a press means (midi/launchpad/Surface.kt); here it is
    // shown the app thirty times a second and its presses are carried out.
    // Its notes go to the track it has selected whatever the MIDI routing
    // says - it is part of the app, not a keyboard on a channel - and each
    // note-off goes where its note-on went.
    val lpNoteRack = remember { IntArray(128) { -1 } }
    // The device faders: a machine's first eight continuous knobs, looked
    // up once a type rather than thirty times a second.
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
            // The keyboard's own actions, so the surface agrees with the
            // screen about what play, record and undo mean where it is.
            com.rm.acidulous.midi.launchpad.LpAction.Play ->
                if (!com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.PlayStop)) {
                    if (playing) NativeEngine.transportStop() else EngineSync.play(position.scene, com.rm.acidulous.ui.UiPrefs.clipMode)
                }
            com.rm.acidulous.midi.launchpad.LpAction.Record ->
                if (!com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.Record)) onArm(!armed)
            com.rm.acidulous.midi.launchpad.LpAction.Panic -> com.rm.acidulous.ui.panicEverything()
            com.rm.acidulous.midi.launchpad.LpAction.Undo -> com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.Undo)
            com.rm.acidulous.midi.launchpad.LpAction.Redo -> com.rm.acidulous.ui.KeyHub.run(com.rm.acidulous.ui.KeyAction.Redo)
            is com.rm.acidulous.midi.launchpad.LpAction.SelectTrack -> midiTrack = a.index
            // As a tap on the scene in the grid does.
            is com.rm.acidulous.midi.launchpad.LpAction.PlayScene -> song.scenes.getOrNull(a.index)?.let { scene ->
                when {
                    com.rm.acidulous.ui.UiPrefs.clipMode -> {
                        song.tracks.forEachIndexed { t, tr -> if (tr.clips[scene.id] != null) NativeEngine.launchClip(t, scene.engineId) }
                        if (!playing) EngineSync.play(0, true)
                    }
                    playing && position.scene == a.index -> NativeEngine.stopAtEnd = !NativeEngine.stopAtEnd
                    playing -> NativeEngine.queuedScene = if (NativeEngine.queuedScene == a.index) -1 else a.index
                    else -> { onLoopScene(true); EngineSync.play(a.index, false) }
                }
            }
            is com.rm.acidulous.midi.launchpad.LpAction.LaunchClip -> song.scenes.getOrNull(a.scene)?.let { scene ->
                NativeEngine.launchClip(a.track, scene.engineId)
                if (!playing) EngineSync.play(0, true)
            }
            // As the clip window's clear, cut, and the scene menu's duplicate.
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
            // A step: the note starting in it at that pitch goes, or one a
            // step long arrives. One undo each, as a tap in the roll.
            is com.rm.acidulous.midi.launchpad.LpAction.ToggleStep -> song.scenes.getOrNull(a.scene)?.let { scene ->
                editor.editClip(a.track, scene.id) { clip ->
                    val i = clip.notes.indexOfFirst { it.pitch == a.pitch && it.tick >= a.tick && it.tick < a.tick + clip.grid }
                    if (i >= 0) clip.copy(notes = clip.notes.filterIndexed { j, _ -> j != i })
                    else clip.copy(notes = (clip.notes + com.rm.acidulous.model.Note(a.tick, a.length, a.pitch, 100)).sortedBy { it.tick })
                }
            }
            // As the mixer's strips, the panels' knobs and the perform page send them.
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
            // As the Quantise window was last set: the two mean the same thing.
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
                    // Lowest first for the sequencer, as the drum grid reads;
                    // and as the pads are laid out on screen, for the note page.
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
                // The played track's clip where it is: in clip mode the scene
                // it is playing, otherwise the song's.
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

    // An Exquis's play, record, loop, clips, undo and redo, when the app has
    // them: the same actions the Launchpad's and the screen's buttons are,
    // and lit as the app is - play green while playing and amber stopped, as
    // the Exquis itself does, record red while armed, loop and clips lit
    // while on.
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
            // The service follows the transport rather than the app's
            // lifetime, so an app sitting on the grid is not holding a
            // notification open for nothing. `wasPlaying` still holds the last
            // poll's answer here - it is updated further down - and the call
            // is made only on the change, because `startForegroundService`
            // every eighty milliseconds is a binder call every eighty
            // milliseconds.
            if (playing != wasPlaying) {
                PlaybackService.follow(context, playing)
                // Stop for a call, another app's music, or headphones pulled
                // out - see media/AudioFocus.
                com.rm.acidulous.media.AudioFocus.follow(context, playing) { NativeEngine.transportStop() }
            }
            position = Position.unpack(NativeEngine.positionPacked)
            countInBeats = countInBeatsOf(NativeEngine.countInRemaining)
            bpm = NativeEngine.tempo
            armed = NativeEngine.recordArmed
            notesOn = NativeEngine.notesOn(0)
            notesOff = NativeEngine.notesOff(0)
            load = NativeEngine.loadAvg
            // Peak-hold, and reading clears them, so this is the only place
            // that may ask. Held across polls rather than shown raw: at 80 ms
            // a reading would flick past before it could be read off a screen.
            // **Pressing play zeroes them.** They are cumulative and they were
            // not, which made two readings taken in one session - the same
            // song at two quality settings, say - impossible to compare: the
            // second contained the first. A play-through is the unit somebody
            // measures in, so it is the unit these count in.
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
            // three are shown as a delta from the last time play was pressed.
            lateCallbacks = NativeEngine.lateCallbacks - lateAt
            stalled = NativeEngine.stalledCallbacks - stalledAt
            xruns = NativeEngine.xRunCount - xrunsAt
            fade = NativeEngine.masterFade
            stopAtEnd = NativeEngine.stopAtEnd
            queuedScene = NativeEngine.queuedScene
            com.rm.acidulous.midi.MidiHub.readSync()
            com.rm.acidulous.engine.LinkHub.poll()
            // A track in the launcher that has just come round to the top
            // of its clip: what was recorded into it goes to the engine now,
            // so a loop hears its last pass on its next. The arranger's own
            // playhead is stale in clip mode and cannot say.
            var cycleWrapped = false
            if (com.rm.acidulous.ui.UiPrefs.clipMode) {
                NativeEngine.launchStates(launchPacked)
                val next = launchPacked.map { LaunchState.unpack(it) }
                // Round the *clip*, not the cycle: a cycle is the clip times
                // the scene's repeats, and a loop has to hear its last pass on
                // its next one, not two passes later.
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
            rackPeaks = FloatArray(16) { i -> if (i < song.tracks.size) NativeEngine.readRackPeak(i) else 0f }
            // **Struggling now, not struggling ever.** A cumulative count says
            // a song dropped out once an hour ago; what a light on the screen
            // has to answer is whether it is happening as you watch. So it is
            // the change since the last poll, held for a moment so a single
            // late callback is visible rather than a flicker too short to see - and
            // long enough that a burst of them reads as one steady state and not
            // as blinking, which on a track you are watching would be an alarm.
            val lateNow = NativeEngine.lateCallbacks
            if (lateNow > lateSeen) strainUntil = System.currentTimeMillis() + 2500
            lateSeen = lateNow
            straining = playing && System.currentTimeMillis() < strainUntil

            // **Choosing quality by the two signals, not by the one.**
            //
            // A worst block over its budget is not on its own a reason to give
            // anything up: the block may have been interrupted rather than
            // slow, and lean cannot make the scheduler hand the core back. So
            // lean is asked for only when the blocks that were *not*
            // interrupted are the ones over budget - the song costing more
            // than the device has - and full comes back when they are not.
            //
            // The hysteresis is wide on purpose. Flipping the amp's
            // oversampling is audible, and a watcher that changed its mind at
            // the edge of the budget would do it every few seconds; over is
            // 100% of a block and back is 70%, and each has to hold for a
            // stretch before anything moves.
            if (com.rm.acidulous.ui.UiPrefs.autoQuality && playing) {
                // **The decaying figure, not the peak-hold.** `worstBlockUs`
                // is cleared by whoever reads it, and the Settings window is
                // the reader it was written for - a watcher polling it every
                // eighty milliseconds would leave that window showing nothing.
                // `recentCallbackUs` decays instead of clearing, so there can
                // be two readers, and the callback is the span with the
                // deadline anyway.
                val budgetUs = NativeEngine.callbackBudgetUs.toFloat()
                val recent = NativeEngine.recentCallbackUs
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
                // Switched off, or never on: what you chose is what runs.
                com.rm.acidulous.ui.UiPrefs.applyAutoQuality(com.rm.acidulous.ui.UiPrefs.fullQuality)
                leanSince = 0L
                fullSince = 0L
            }
            // **Worked out here, not in composition.** As a `val` up there it
            // was read once before the engine had opened a stream, when the
            // sample rate is still nought - so the budget came out as sixty-four
            // million microseconds and nothing was ever a large enough share of
            // it to mark. A rate that is not known yet is not a rate to divide
            // by.
            val rate = NativeEngine.sampleRate
            val blockBudgetUs = if (rate > 0) 1_000_000f * 64f / rate else 0f
            // **Hysteresis, or it blinks.** The cost decays continuously, so a
            // track sitting near the line crosses it several times a second
            // and the grid flickers between different tracks - which reads as
            // a fault in the app rather than as a fault in the song. It takes
            // a third of a block to light and has to fall to under a quarter
            // to go out again.
            rackHot = BooleanArray(16) { i ->
                if (blockBudgetUs <= 0f || i >= song.tracks.size) {
                    false
                } else {
                    val share = NativeEngine.rackCostUs(i) / blockBudgetUs
                    if (rackHot[i]) share > 0.22f else share > 0.33f
                }
            }
            if (armed || playing) applyRecorded(recorder.poll(song, position, playing, sceneIdOf, cycleWrapped))
            // A take is one pass of armed and playing; stopping either ends it.
            if (!(armed && playing)) editor.endTake()
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
    // `worst` is the number a dropout is actually about: the longest a single
    // callback took, against the time that callback had. `load` beside it is a
    // smoothed average - useful for "is it working hard", useless for "why did
    // it click", because a block over budget decays out of it in 27 ms and
    // this line is redrawn every 80.
    val budgetUs = NativeEngine.callbackBudgetUs.coerceAtLeast(1)
    val diagnostics = ("%s · load %.0f%% · worst %.1f/%.1fms cpu %.1f · late %d stall %d · xruns %d · " +
        "peak %.3f · fade %.2f · on %d off %d%s")
        .format(
            status, load, worstUs / 1000f, budgetUs / 1000f, worstCpuUs / 1000f,
            lateCallbacks, stalled, xruns, peak, fade, notesOn, notesOff,
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
            performTrack = midiTrack,
            looper = looper,
            countInBeats = countInBeats,
            clipMode = com.rm.acidulous.ui.UiPrefs.clipMode,
            launchStates = launchStates,
            onClipMode = onClipMode,
            loopScene = loopScene, stopAtEnd = stopAtEnd, queuedScene = queuedScene,
            bpm = bpm, diagnostics = diagnostics,
            rackPeaks = rackPeaks, masterPeak = peak, clickOn = clickOn,
            straining = straining, rackHot = rackHot,
            onClick = { on -> clickOn = on; EngineSync.setMetronome(on, com.rm.acidulous.ui.UiPrefs.clickVolume, com.rm.acidulous.ui.UiPrefs.clickVoice, com.rm.acidulous.ui.UiPrefs.clickDivision, com.rm.acidulous.ui.UiPrefs.clickWhen) },
            onArm = onArm, onLoopScene = onLoopScene,
            onOpenClip = { track, sceneId -> screen = Screen.Edit(track, sceneId) },
            onSave = { SongStore.save(context, song); Log.i(TAG, "saved ${song.name}") },
            onSaveAs = { name -> val renamed = song.copy(name = name); editor.replace(renamed); SongStore.save(context, renamed); Log.i(TAG, "saved as $name") },
            onNew = { name ->
                val fresh = com.rm.acidulous.ui.UiPrefs.newSong(name)
                swapSong(fresh)
                SongStore.save(context, fresh)
            },
            // **The same swap, and for the same reason.** Loading had the
            // identical fault a new song had: the transport carried straight
            // on into a song it had never seen, playing whatever scenes
            // happened to be at those indices, with the old song's tails
            // ringing over the top. It was left alone when `onNew` was fixed
            // because only new songs had been asked about; it is the same two
            // lines and there was never a reason for them to differ.
            onLoad = { name ->
                runCatching { SongStore.load(context, name) }
                    .onSuccess { swapSong(it) }
                    .onFailure { Log.w(TAG, "load failed", it) }
            },
            onDelete = { name -> SongStore.delete(context, name); Log.i(TAG, "deleted $name") },
            songNames = { SongStore.list(context) },
            onExport = { if (!playing) exportAsk = true },
            onImport = { importPicker.launch(arrayOf("*/*")) },
            onShareSong = { shareSong() },
            onShareExport = { done -> share(context, done.uris, done.mime, done.fileName) },
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
            title = stringResource(R.string.app_soundfont_preset),
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
        return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { context.getString(R.string.app_the_folder) }
    }
    var display = uri.lastPathSegment ?: context.getString(R.string.app_the_file)
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) display = c.getString(i)
    }
    display
}.getOrDefault(context.getString(R.string.app_the_file))
