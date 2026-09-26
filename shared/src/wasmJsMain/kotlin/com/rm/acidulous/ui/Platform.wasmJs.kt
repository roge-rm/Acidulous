package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// The small platform questions, as a browser answers them: mostly as the
// desktop does, for it is a desktop's window more often than not.

/** A page has no camera in it. */
@Composable
actual fun rememberTopCutout(): TopCutout? = null

/** A screen reader reads a canvas as nothing, so the layout is the sighted one. */
@Composable
actual fun rememberTalkBack(): Boolean = false

/** Whether the page may use the microphone: it was granted before, or a stream is open now. */
private fun micGranted(): Boolean = js("!!(globalThis.acidMicGranted || (globalThis.acidInput && globalThis.acidInput.stream && globalThis.acidInput.stream.active))")

/**
 * The browser's microphone prompt, answered into the stream the audio driver
 * connects (globalThis.acidInput, platform/web/drivers/AudioDriver.cpp) - so
 * the input the app opens next is the one just granted, with nothing asked twice.
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
 * Whether the microphone was granted on an earlier visit, so the recorder
 * opens on its meter rather than on a button asking. The browser answers
 * later, so this is asked at start-up, before any window wants it.
 */
fun watchMicrophonePermission() = watchMicPermission()

/** The microphone is the browser's to grant; everything else a phone asks for, a page already has. */
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

/** The page's height in the outer dp: see the desktop's. */
@Composable
internal actual fun windowHeightDp(): Float =
    with(LocalBaseDensity.current ?: LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }

/**
 * A window is drawn in the page's one canvas, so its keys pass through the
 * page's handlers - [previewKey] and [fallbackKey] - and what is left is to
 * say that one is open: while it is, Esc is the window's. As the desktop.
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

/** The page's preview of a key: the hub first. */
fun previewKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) {
        KeyHub.usingKeys = true
        return false
    }
    return KeyHub.preview(press)
}

/** What the focused control left, back to the hub. */
fun fallbackKey(event: KeyEvent): Boolean {
    val press = event.press
    if (openWindows > 0 && press.esc) return false
    return KeyHub.fallback(press)
}
