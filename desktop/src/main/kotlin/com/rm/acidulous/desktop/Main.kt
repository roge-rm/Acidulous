package com.rm.acidulous.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
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

/** Where the XDG spec says, or its default under the home folder. */
private fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), "acidulous")

fun main() {
    val config = xdg("XDG_CONFIG_HOME", ".config").apply { mkdirs() }
    val data = xdg("XDG_DATA_HOME", ".local/share").apply { mkdirs() }
    val cache = xdg("XDG_CACHE_HOME", ".cache").apply { mkdirs() }

    // What MainActivity.onCreate does on the phone, in the same order.
    AppHost.current = DesktopHost(config)
    UiPrefs.init(FilePrefs(File(config, "ui.properties")))
    Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
    Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
    // ALSA's sequencer, which sees every device and program; Java Sound's raw
    // MIDI where there is none. See AlsaSeqMidi.
    MidiHub.start(AlsaSeqMidi.open() ?: JavaSoundMidi())
    // Nothing on a desktop filters multicast, so Link needs no lock.
    LinkHub.multicastLock = null
    EngineAssets.install(data, cache)
    watchPointer()
    // The right button is the phone's long press: see RightClickHold.
    RightClickHold.install()

    application {
        Window(
            onCloseRequest = {
                MidiHub.clearPads()
                MidiHub.releaseLaunchpad()
                exitApplication()
            },
            title = "Acidulous",
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
            // Every key through the hub first, as dispatchKeyEvent does on the
            // phone; whatever the focused control leaves comes back for the
            // plain-letter shortcuts. The windows' keys too: see WindowKeys.
            onPreviewKeyEvent = { previewKey(it) },
            onKeyEvent = { fallbackKey(it) },
        ) {
            AppRoot()
        }
    }
}
