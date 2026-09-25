package com.rm.acidulous

import com.rm.acidulous.ui.toPress
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
import com.rm.acidulous.res.*

/** A file another app opened with this one, or shared to it: see [Incoming]. */
internal fun incomingFrom(intent: android.content.Intent?) {
    intent ?: return
    val uri = when (intent.action) {
        android.content.Intent.ACTION_VIEW -> intent.data
        android.content.Intent.ACTION_SEND ->
            @Suppress("DEPRECATION") (intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri)
        else -> null
    } ?: return
    Incoming.doc = Doc(uri)
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
        share(context, listOf(uri), "text/plain", AppStrings.getString(Res.string.app_crash_report_subject))
    }.onFailure { Log.w("Acidulous.Crash", "could not share the report", it) }
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        incomingFrom(intent)
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
        if (savedInstanceState == null) incomingFrom(intent)
        AppHost.current = AndroidHost(this)
        com.rm.acidulous.ui.UiPrefs.init(com.rm.acidulous.util.androidPrefs(getSharedPreferences("ui", MODE_PRIVATE)))
        com.rm.acidulous.model.Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
        com.rm.acidulous.model.Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
        com.rm.acidulous.midi.androidMidi(this)?.let { com.rm.acidulous.midi.MidiHub.start(it) }
        com.rm.acidulous.engine.LinkHub.multicastLock = com.rm.acidulous.engine.wifiMulticastLock(this)
        EngineAssets.install(filesDir, cacheDir)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        goFullScreen()
        setContent {
            AppRoot(onLightTheme = { light ->
                // The bars are hidden, but a swipe brings them back, so their
                // icons still have to be readable against whichever theme is
                // running.
                WindowCompat.getInsetsController(window, window.decorView).run {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            })
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
        if (com.rm.acidulous.ui.KeyHub.preview(event.toPress())) return true
        if (super.dispatchKeyEvent(event)) return true
        return com.rm.acidulous.ui.KeyHub.fallback(event.toPress())
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
            android.view.KeyboardShortcutInfo(AppStrings.getString(action.label), chord.key, mods)
        }
        if (items.isNotEmpty()) data += android.view.KeyboardShortcutGroup(AppStrings.getString(Res.string.keys_title), items)
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
