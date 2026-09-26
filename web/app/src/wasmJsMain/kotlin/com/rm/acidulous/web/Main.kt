package com.rm.acidulous.web

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.await
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
import com.rm.acidulous.ui.watchMicrophonePermission
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// Acidulous in a browser. The page has loaded the engine (globalThis.acid)
// and put the app's folder, kept in browser storage, at /data; this is what
// MainActivity.onCreate does on the phone, in the same order, and then the
// app.

/** A file beside the page as a string of chars 0-255: one crossing, then bytes in Kotlin. */
private fun fetchLatin1(url: String): kotlin.js.Promise<JsString> = js(
    """fetch(url).then((r) => r.ok ? r.arrayBuffer() : Promise.reject(r.status)).then((b) => {
        const a = new Uint8Array(b);
        let s = '';
        for (let i = 0; i < a.length; i += 8192) s += String.fromCharCode.apply(null, a.subarray(i, i + 8192));
        return s;
    })""",
)

private suspend fun fetchBytes(url: String): ByteArray {
    val s = fetchLatin1(url).await<JsString>().toString()
    return ByteArray(s.length) { s[it].code.toByte() }
}

private fun onPageHide(action: () -> Unit): Unit = js("addEventListener('pagehide', () => action())")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        // Every string first: nothing on the page's one thread may wait for one later.
        preloadStrings()
        AppHost.current = WebHost()
        watchMicrophonePermission()
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
            // **The symbols, before anything is drawn.** A browser lends no
            // system fonts, so the symbols the app draws as text - the
            // editor's dice, grid and lock, the pills' arrows, the MIDI
            // window's cable - came from whatever the page could find: boxes
            // on one machine, glyphs sitting high or low on another. These
            // two small subsets (fonts/NOTICE.txt) are preloaded as fallbacks,
            // so every browser draws the same ones.
            val fonts = LocalFontFamilyResolver.current
            var fontsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                for ((name, file) in listOf("AcidSymbols" to "fonts/acid-symbols.ttf", "AcidSymbols2" to "fonts/acid-symbols-2.ttf")) {
                    runCatching { fonts.preload(FontFamily(Font(name, fetchBytes(file)))) }
                }
                fontsReady = true
            }
            if (!fontsReady) return@ComposeViewport
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
