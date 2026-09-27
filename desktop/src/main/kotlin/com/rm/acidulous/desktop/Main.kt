package com.rm.acidulous.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.rm.acidulous.AppHost
import com.rm.acidulous.AppRoot
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.LinkHub
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Names
import com.rm.acidulous.res.AppStrings
import com.rm.acidulous.res.Res
import com.rm.acidulous.res.name_copy
import com.rm.acidulous.res.name_scene
import com.rm.acidulous.ui.UiPrefs
import com.rm.acidulous.ui.fallbackKey
import com.rm.acidulous.ui.previewKey
import com.rm.acidulous.ui.watchPointer
import com.rm.acidulous.util.FilePrefs
import java.io.File

/** Whether the screen is too small for a 1280 x 800 window. */
private fun smallScreen(): Boolean = runCatching {
    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    bounds.width < 1300 || bounds.height < 840
}.getOrDefault(false)

private fun windowIcon(): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("acidulous.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/** Running on Windows, where folders, MIDI and library loading differ. */
internal val onWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** The XDG folder from [variable], or its default under the home folder. */
private fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), "acidulous")

/** Windows folders: settings and songs roam with the account, the cache stays on this machine. */
private fun windowsDir(variable: String, vararg under: String): File =
    under.fold(File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), "Acidulous")) { dir, name -> File(dir, name) }

fun main() {
    // Windows looks for a DLL's dependencies next to java.exe, not next to
    // the DLL, so load LAME first and the engine finds it already loaded.
    if (onWindows) System.loadLibrary("mp3lame")
    val config = (if (onWindows) windowsDir("APPDATA", "config") else xdg("XDG_CONFIG_HOME", ".config")).apply { mkdirs() }
    val data = (if (onWindows) windowsDir("APPDATA", "data") else xdg("XDG_DATA_HOME", ".local/share")).apply { mkdirs() }
    val cache = (if (onWindows) windowsDir("LOCALAPPDATA", "cache") else xdg("XDG_CACHE_HOME", ".cache")).apply { mkdirs() }
    // Before anything else can throw, like on Android. Also collects native
    // crashes from earlier runs, which the JVM wrote where the launcher said.
    val crashes = CrashReports(data).apply { install(); collect() }

    // Same setup as MainActivity.onCreate on Android, in the same order.
    AppHost.current = DesktopHost(config, crashes)
    UiPrefs.init(FilePrefs(File(config, "ui.properties")))
    // Restore the last chosen output before the engine opens a stream.
    DesktopAudio.chooseOutput(UiPrefs.outputDevice)
    Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
    Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
    // ALSA's sequencer, which sees every device and program. Java Sound's raw
    // MIDI if there's no sequencer, and on Windows. See AlsaSeqMidi.
    MidiHub.start((if (onWindows) null else AlsaSeqMidi.open()) ?: JavaSoundMidi())
    // Desktops don't filter multicast, so Link needs no lock.
    LinkHub.multicastLock = null
    EngineAssets.install(data, cache)
    watchPointer()
    // Right-click acts as a long press: see RightClickHold.
    RightClickHold.install()

    application {
        // Open maximised if the screen is smaller than the window (like a
        // Pi's 720 pixel screen) instead of running off the edges.
        val window = rememberWindowState(
            size = DpSize(1280.dp, 800.dp),
            placement = if (smallScreen()) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        // What F11 goes back to: the window as it was, maximised or not.
        var before by remember { mutableStateOf(WindowPlacement.Floating) }
        Window(
            onCloseRequest = {
                MidiHub.clearPads()
                MidiHub.releaseLaunchpad()
                exitApplication()
            },
            title = "Acidulous",
            // The app's icon in the title bar and taskbar instead of Java's.
            // Linux also gets it from the menu entry, Windows only from this.
            icon = remember { windowIcon() },
            state = window,
            // Every key goes through the hub first, like dispatchKeyEvent on
            // Android, and whatever the focused control doesn't use comes back
            // for the single-letter shortcuts. Other windows too: see WindowKeys.
            //
            // Except F11, which toggles full screen like in a browser. Not
            // Alt+Enter, which already opens a focused control's hold actions.
            onPreviewKeyEvent = {
                if (it.key == Key.F11) {
                    if (it.type == KeyEventType.KeyDown) {
                        if (window.placement == WindowPlacement.Fullscreen) {
                            window.placement = before
                        } else {
                            before = window.placement
                            window.placement = WindowPlacement.Fullscreen
                        }
                    }
                    true
                } else previewKey(it)
            },
            onKeyEvent = { fallbackKey(it) },
        ) {
            // The screen scale if one is chosen (Settings > display) sets the
            // density, and the app's own size setting applies on top. See
            // UiPrefs.screenScale.
            val system = LocalDensity.current
            val chosen = UiPrefs.screenScale
            CompositionLocalProvider(LocalDensity provides if (chosen > 0f) Density(chosen, system.fontScale) else system) {
                AppRoot()
            }
        }
    }
}
