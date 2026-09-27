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

/** Whether the screen is smaller than the window's 1280 x 800 would need. */
private fun smallScreen(): Boolean = runCatching {
    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    bounds.width < 1300 || bounds.height < 840
}.getOrDefault(false)

private fun windowIcon(): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("acidulous.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/** Windows, where the folders, the MIDI and the libraries' loading differ. */
internal val onWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Where the XDG spec says, or its default under the home folder. */
private fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), "acidulous")

/** Windows' own: settings and songs roam with the account, the cache stays on this machine. */
private fun windowsDir(variable: String, vararg under: String): File =
    under.fold(File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), "Acidulous")) { dir, name -> File(dir, name) }

fun main() {
    // Windows looks for a DLL's own DLLs beside java.exe, not beside it, so
    // LAME is loaded first and the engine finds it already there.
    if (onWindows) System.loadLibrary("mp3lame")
    val config = (if (onWindows) windowsDir("APPDATA", "config") else xdg("XDG_CONFIG_HOME", ".config")).apply { mkdirs() }
    val data = (if (onWindows) windowsDir("APPDATA", "data") else xdg("XDG_DATA_HOME", ".local/share")).apply { mkdirs() }
    val cache = (if (onWindows) windowsDir("LOCALAPPDATA", "cache") else xdg("XDG_CACHE_HOME", ".cache")).apply { mkdirs() }
    // Before anything else can throw, as on the phone; and the last runs'
    // native crashes, which the JVM wrote where the launcher told it to.
    val crashes = CrashReports(data).apply { install(); collect() }

    // What MainActivity.onCreate does on the phone, in the same order.
    AppHost.current = DesktopHost(config, crashes)
    UiPrefs.init(FilePrefs(File(config, "ui.properties")))
    // The output chosen last time, before the engine opens a stream.
    DesktopAudio.chooseOutput(UiPrefs.outputDevice)
    Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
    Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
    // ALSA's sequencer, which sees every device and program; Java Sound's raw
    // MIDI where there is none, and on Windows. See AlsaSeqMidi.
    MidiHub.start((if (onWindows) null else AlsaSeqMidi.open()) ?: JavaSoundMidi())
    // Nothing on a desktop filters multicast, so Link needs no lock.
    LinkHub.multicastLock = null
    EngineAssets.install(data, cache)
    watchPointer()
    // The right button is the phone's long press: see RightClickHold.
    RightClickHold.install()

    application {
        // Maximised where the screen is smaller than the window would be - a
        // Pi's 720-pixel square - rather than opening past its edges.
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
            // The app's own in the title bar and the taskbar, rather than
            // Java's cup: Linux's menu entry names it, Windows has only this.
            icon = remember { windowIcon() },
            state = window,
            // Every key through the hub first, as dispatchKeyEvent does on the
            // phone; whatever the focused control leaves comes back for the
            // plain-letter shortcuts. The windows' keys too: see WindowKeys.
            //
            // Except F11, which is the window's own on a desktop: full screen
            // and back, as a browser or a video player has it. Not Alt+Enter,
            // which already opens a focused control's hold actions.
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
            // The screen scale, where one is chosen (Settings > display): the
            // density everything below is laid out in, the app's own size
            // setting on top of it. See UiPrefs.screenScale.
            val system = LocalDensity.current
            val chosen = UiPrefs.screenScale
            CompositionLocalProvider(LocalDensity provides if (chosen > 0f) Density(chosen, system.fontScale) else system) {
                AppRoot()
            }
        }
    }
}
