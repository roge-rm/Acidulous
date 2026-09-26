package com.rm.acidulous.web

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.window.ComposeViewport
import com.rm.acidulous.AppHost
import com.rm.acidulous.AppRoot
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.LinkHub
import com.rm.acidulous.io.File
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Names
import com.rm.acidulous.res.AppStrings
import com.rm.acidulous.res.Res
import com.rm.acidulous.res.name_copy
import com.rm.acidulous.res.name_scene
import com.rm.acidulous.res.preloadStrings
import com.rm.acidulous.ui.UiPrefs
import com.rm.acidulous.ui.fallbackKey
import com.rm.acidulous.ui.previewKey
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// Acidulous in a browser. The page has loaded the engine (globalThis.acid)
// and put the app's folder, kept in browser storage, at /data; this is what
// MainActivity.onCreate does on the phone, in the same order, and then the
// app.

private fun onPageHide(action: () -> Unit): Unit = js("addEventListener('pagehide', () => action())")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        // Every string first: nothing on the page's one thread may wait for one later.
        preloadStrings()
        AppHost.current = WebHost()
        UiPrefs.init(LocalPrefs("ui"))
        Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
        Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
        MidiHub.start(WebMidi())
        // A Launchpad goes back to its own mode when the page goes, as it
        // does when the desktop's window closes.
        onPageHide {
            MidiHub.clearPads()
            MidiHub.releaseLaunchpad()
        }
        LinkHub.multicastLock = null
        EngineAssets.install(File("/data"), File("/tmp/cache").apply { mkdirs() })

        ComposeViewport("root") {
            // Every key through the hub first, as the desktop's window does;
            // what the focused control leaves comes back for the shortcuts.
            // Focused itself at the start, so a key goes somewhere before
            // anything has been clicked.
            val focus = remember { FocusRequester() }
            Box(
                Modifier.fillMaxSize()
                    .onPreviewKeyEvent { previewKey(it) }
                    .onKeyEvent { fallbackKey(it) }
                    .focusRequester(focus)
                    .focusable(),
            ) { AppRoot() }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}
