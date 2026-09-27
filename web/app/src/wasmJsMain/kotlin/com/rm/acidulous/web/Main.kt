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

// Acidulous in a browser. The page has already loaded the engine
// (globalThis.acid) and mounted the app's folder from browser storage at
// /data. This does what MainActivity.onCreate does on the phone, in the same
// order, then starts the app.

/** Fetches a file next to the page as a string of chars 0-255, so it crosses to Kotlin in one go. */
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
        // Load every string first. Nothing on the page's single thread can wait for one later.
        preloadStrings()
        AppHost.current = WebHost()
        watchMicrophonePermission()
        UiPrefs.init(LocalPrefs("ui"))
        // The output chosen last time, selected once the browser lists it.
        AppHost.current.chooseAudioOutput(UiPrefs.outputDevice)
        Names.scene = { AppStrings.getString(Res.string.name_scene, it) }
        Names.copyOf = { AppStrings.getString(Res.string.name_copy, it) }
        MidiHub.start(WebMidi())
        // Put a Launchpad back in its own mode when the page closes, like the
        // desktop does when its window closes.
        onPageHide {
            MidiHub.clearPads()
            MidiHub.releaseLaunchpad()
        }
        LinkHub.multicastLock = null
        EngineAssets.install(File("/data"), File("/tmp/cache").apply { mkdirs() })

        ComposeViewport("root") {
            // Load the symbol fonts before drawing anything. Browsers don't
            // lend system fonts, so symbols drawn as text (the editor's dice,
            // grid and lock, the pill arrows, the MIDI cable) showed as boxes
            // or sat at the wrong height. These two small subsets
            // (fonts/NOTICE.txt) are preloaded as fallbacks so every browser
            // draws the same ones.
            val fonts = LocalFontFamilyResolver.current
            var fontsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                for ((name, file) in listOf("AcidSymbols" to "fonts/acid-symbols.ttf", "AcidSymbols2" to "fonts/acid-symbols-2.ttf")) {
                    runCatching { fonts.preload(FontFamily(Font(name, fetchBytes(file)))) }
                }
                fontsReady = true
            }
            if (!fontsReady) return@ComposeViewport
            // Every key goes through the hub first, like the desktop window.
            // Whatever the focused control doesn't use comes back for the
            // shortcuts. The box takes focus at the start so keys work before
            // anything is clicked.
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
