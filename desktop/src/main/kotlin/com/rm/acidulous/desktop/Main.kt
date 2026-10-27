package com.rm.acidulous.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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

/** A Steam Deck, LCD or OLED, by the names its firmware gives it. */
internal val steamDeck: Boolean = runCatching {
    java.io.File("/sys/class/dmi/id/sys_vendor").readText().trim() == "Valve" &&
        java.io.File("/sys/class/dmi/id/product_name").readText().trim() in setOf("Jupiter", "Galileo")
}.getOrDefault(false)

/** Running in SteamOS's Game Mode, where there's no desktop and the app should fill the screen. */
private val gameMode: Boolean =
    System.getenv("XDG_CURRENT_DESKTOP") == "gamescope" || System.getenv("SteamGamepadUI") != null

/**
 * The Deck's own scale when none is chosen: its 7 inch screen at the desktop's
 * usual density would make a control about 5 mm across.
 */
private const val DECK_SCALE = 1.5f

private fun windowIcon(): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("acidulous.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/** Running on Windows, where folders, MIDI and library loading differ. */
internal val onWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** How long a second copy on a Mac waits for the files Finder sends it, to hand them over. */
private const val MAC_FILES_WAIT_MS = 1500L

/** Running on macOS, where folders and MIDI differ. */
internal val onMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** The XDG folder from [variable], or its default under the home folder. */
private fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), "acidulous")

/** macOS folders: settings and songs in Application Support, the cache in Caches. */
private fun macDir(vararg under: String): File =
    under.fold(File(System.getProperty("user.home"), "Library")) { dir, name -> File(dir, name) }

/** Windows folders: settings and songs roam with the account, the cache stays on this machine. */
private fun windowsDir(variable: String, vararg under: String): File =
    under.fold(File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), "Acidulous")) { dir, name -> File(dir, name) }

fun main(args: Array<String>) {
    // Before AWT starts: a Mac's menus go in the menu bar at the top of the
    // screen, and Cmd is the shortcut key (see KeyPress.desktop.kt).
    if (onMac) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "Acidulous")
        com.rm.acidulous.ui.macKeys = true
    }
    // Windows looks for a DLL's dependencies next to java.exe, not next to
    // the DLL, so load LAME first and the engine finds it already loaded.
    if (onWindows) System.loadLibrary("mp3lame")
    // On the Deck, drawing with OpenGL crashes on the first frame (in Skia's
    // flush, on its Mesa driver), so it draws in software unless told otherwise.
    if (steamDeck && System.getenv("SKIKO_RENDER_API") == null && System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    }
    val config = when {
        onWindows -> windowsDir("APPDATA", "config")
        onMac -> macDir("Application Support", "Acidulous", "config")
        else -> xdg("XDG_CONFIG_HOME", ".config")
    }.apply { mkdirs() }
    val data = when {
        onWindows -> windowsDir("APPDATA", "data")
        onMac -> macDir("Application Support", "Acidulous", "data")
        else -> xdg("XDG_DATA_HOME", ".local/share")
    }.apply { mkdirs() }
    val cache = when {
        onWindows -> windowsDir("LOCALAPPDATA", "cache")
        onMac -> macDir("Caches", "Acidulous")
        else -> xdg("XDG_CACHE_HOME", ".cache")
    }.apply { mkdirs() }
    // Files the app was opened with. On a Mac they come from Finder, a
    // moment after the start, so the handler goes in before anything else.
    OpenFiles.add(OpenFiles.fromArgs(args))
    if (onMac) MacApp.install()
    // A second launch hands its files to the running copy and brings it forward.
    if (!SingleInstance.claim(data) { OpenFiles.add(it) }) {
        val files = OpenFiles.drain() + if (onMac) MacApp.filesSoon(MAC_FILES_WAIT_MS) else emptyList()
        SingleInstance.handOver(data, files)
        kotlin.system.exitProcess(0)
    }
    // Before anything else can throw, like on Android. Also collects native
    // crashes from earlier runs, which the JVM wrote where the launcher said.
    val crashes = CrashReports(data).apply { install(); collect() }

    // Same setup as MainActivity.onCreate on Android, in the same order.
    AppHost.current = DesktopHost(config, crashes)
    UiPrefs.init(FilePrefs(File(config, "ui.properties")))
    // The app's language: the one chosen in settings, or the system's.
    DesktopHost.systemLocale = java.util.Locale.getDefault()
    UiPrefs.language.tag?.let { java.util.Locale.setDefault(java.util.Locale.forLanguageTag(it)) }
    // Restore the last chosen output before the engine opens a stream.
    DesktopAudio.chooseOutput(UiPrefs.outputDevice)
    Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
    Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
    // ALSA's sequencer on Linux and CoreMIDI on macOS, which see every device
    // and program. Java Sound's MIDI if those won't open, and on Windows.
    // See AlsaSeqMidi and CoreMidi.
    MidiHub.start(
        when {
            onWindows -> null
            onMac -> CoreMidi.open()
            else -> AlsaSeqMidi.open()
        } ?: JavaSoundMidi(),
    )
    // Game controllers, read from /dev/input. Linux only for now.
    if (!onWindows && !onMac) {
        LinuxControllers.start(object : PadSink {
            override fun button(device: String, keyCode: Int, down: Boolean) =
                com.rm.acidulous.ui.PadEvents.button(device, keyCode, down)
            override fun moved(device: String, axes: List<com.rm.acidulous.ui.Pad.Axis>) =
                com.rm.acidulous.ui.PadEvents.moved(device, axes)
        })
    }
    // Desktops don't filter multicast, so Link needs no lock.
    LinkHub.multicastLock = null
    EngineAssets.install(data, cache)
    watchPointer()
    // Right-click acts as a long press: see RightClickHold.
    RightClickHold.install()

    application {
        // The window's close button (Alt+F4), holding B on the song screen, and
        // Quit on a Mac. The session's last edits are saved first.
        fun quit() {
            com.rm.acidulous.OnQuit.run()
            MidiHub.clearPads()
            MidiHub.releaseLaunchpad()
            exitApplication()
        }
        (AppHost.current as? DesktopHost)?.onQuit = ::quit
        // Open maximised if the screen is smaller than the window (like a
        // Pi's 720 pixel screen) instead of running off the edges.
        val window = rememberWindowState(
            size = DpSize(1280.dp, 800.dp),
            placement = when {
                gameMode -> WindowPlacement.Fullscreen
                smallScreen() -> WindowPlacement.Maximized
                else -> WindowPlacement.Floating
            },
        )
        // What F11 goes back to: the window as it was, maximised or not.
        var before by remember { mutableStateOf(WindowPlacement.Floating) }
        Window(
            onCloseRequest = ::quit,
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
            // Another launch while running brings this window forward.
            val frame = this.window
            // Files handed to the app go to the import one at a time.
            LaunchedEffect(Unit) {
                OpenFiles.running = true
                snapshotFlow { com.rm.acidulous.Incoming.idle() }.collect { if (it) OpenFiles.next() }
            }
            if (onMac) MacMenus(window, toggleFullScreen = {
                if (window.placement == WindowPlacement.Fullscreen) {
                    window.placement = before
                } else {
                    before = window.placement
                    window.placement = WindowPlacement.Fullscreen
                }
            })
            DisposableEffect(Unit) {
                SingleInstance.onRaise = {
                    java.awt.EventQueue.invokeLater {
                        window.isMinimized = false
                        frame.toFront()
                        frame.requestFocus()
                    }
                }
                onDispose { SingleInstance.onRaise = null }
            }
            val system = LocalDensity.current
            val chosen = UiPrefs.screenScale.takeIf { it > 0f } ?: if (steamDeck) DECK_SCALE else 0f
            CompositionLocalProvider(LocalDensity provides if (chosen > 0f) Density(chosen, system.fontScale) else system) {
                AppRoot()
            }
        }
    }
}
