package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// Small platform details for the browser. Mostly the same as the desktop,
// since it's usually running on one.

/** A page has no camera cutout. */
@Composable
actual fun rememberTopCutout(): TopCutout? = null

/** Screen readers can't read a canvas, so this is always false. */
@Composable
actual fun rememberTalkBack(): Boolean = false

/** A browser is almost always on a computer, with a keyboard. */
@Composable
actual fun rememberBeyondTouch(): Boolean = true

/** Whether the page may use the microphone: it was granted before, or a stream is open now. */
private fun micGranted(): Boolean = js("!!(globalThis.acidMicGranted || (globalThis.acidInput && globalThis.acidInput.stream && globalThis.acidInput.stream.active))")

/**
 * Shows the browser's microphone prompt and stores the stream where the audio
 * driver picks it up (globalThis.acidInput, platform/web/drivers/AudioDriver.cpp),
 * so the next input the app opens uses it without asking again.
 */
private fun askMic(done: (Boolean) -> Unit): Unit = js(
    """(() => {
        const s = (globalThis.acidInput ??= {});
        if (!navigator.mediaDevices) { done(false); return; }
        navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
            .then((stream) => {
                if (s.stream) s.stream.getTracks().forEach((t) => t.stop());
                s.stream = stream;
                globalThis.acidMicGranted = true;
                s.connect && s.connect();
                done(true);
            })
            .catch(() => done(false));
    })()""",
)

private fun watchMicPermission(): Unit = js(
    """(() => {
        if (!navigator.permissions) return;
        navigator.permissions.query({ name: 'microphone' }).then((p) => {
            globalThis.acidMicGranted = p.state === 'granted';
            p.onchange = () => { globalThis.acidMicGranted = p.state === 'granted'; };
        }).catch(() => {});
    })()""",
)

/**
 * Checks whether the microphone was granted on an earlier visit, so the
 * recorder can open straight to its meter. The browser answers
 * asynchronously, so this runs at start-up before any window needs it.
 */
fun watchMicrophonePermission() = watchMicPermission()

/** Only the microphone needs asking for in a browser. Everything else a phone asks for, a page already has. */
@Composable
actual fun rememberPermissions(onResult: (Boolean) -> Unit): Permissions {
    val result = rememberUpdatedState(onResult)
    return remember {
        object : Permissions {
            override fun has(name: String) = name != Permissions.RECORD_AUDIO || micGranted()
            override fun ask(vararg names: String) {
                if (Permissions.RECORD_AUDIO in names) askMic { result.value(it) } else result.value(true)
            }
        }
    }
}

/** The page's height in the outer dp (see the desktop version). */
@Composable
internal actual fun windowHeightDp(): Float =
    with(LocalBaseDensity.current ?: LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }

/**
 * Windows are drawn in the page's one canvas, so their keys already go
 * through [previewKey] and [fallbackKey]. This only counts open windows so
 * Esc goes to the window while one is open, as on the desktop.
 */
@Composable
internal actual fun WindowKeys() {
    DisposableEffect(Unit) {
        openWindows++
        onDispose { openWindows-- }
    }
}

private var openWindows = 0
private val KeyPress.esc get() = keyCode == KeyCodes.KEYCODE_ESCAPE

/** The page's key preview, which goes to the hub first. */
fun previewKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) {
        KeyHub.usingKeys = true
        return false
    }
    return KeyHub.preview(press)
}

/** Keys the focused control didn't use go back to the hub. */
fun fallbackKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) return false
    return KeyHub.fallback(press)
}

@androidx.compose.runtime.Composable
internal actual fun HideSystemBars() {}
