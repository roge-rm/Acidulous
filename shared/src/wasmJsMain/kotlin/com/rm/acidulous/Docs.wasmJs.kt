package com.rm.acidulous

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.rm.acidulous.io.File

// A browser's pickers. What a page is given to open, it may read only
// asynchronously, and the app reads a Doc when it likes - so a chosen file is
// copied into the engine's file system first, and the Doc is that copy. What
// the app saves goes to the browser's downloads: there is no folder to choose,
// and a Doc to write to is a download waiting for its bytes.

/** What a [Doc] holds in a browser: the copy of a chosen file, or a download to make. */
class WebDoc(val name: String, val copy: File?, val mime: String = "application/octet-stream")

private fun pickFiles(accept: String, multiple: Boolean, done: (String) -> Unit): Unit = js(
    """(() => {
        const input = document.createElement('input');
        input.type = 'file';
        if (accept) input.accept = accept;
        input.multiple = multiple;
        let answered = false;
        const answer = (s) => { if (!answered) { answered = true; done(s); } };
        input.onchange = async () => {
            const FS = globalThis.acid.FS;
            const out = [];
            for (const f of input.files) {
                const dir = '/tmp/picked/' + Date.now() + '-' + out.length;
                FS.mkdirTree(dir);
                const p = dir + '/' + f.name;
                FS.writeFile(p, new Uint8Array(await f.arrayBuffer()));
                out.push(p);
            }
            answer(out.join('\u0000'));
        };
        input.oncancel = () => answer('');
        input.click();
    })()""",
)

private fun download(path: String, name: String, mime: String): Unit = js(
    """(() => {
        const bytes = globalThis.acid.FS.readFile(path);
        const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    })()""",
)

/** [file] saved by the browser as [name]: its downloads are the page's only way out. */
fun downloadFile(file: File, name: String, mime: String) = download(file.toString(), name, mime)

private fun accepting(mimes: Array<String>): String =
    if (mimes.isEmpty() || mimes.any { it == "*/*" }) "" else mimes.joinToString(",")

private fun picked(joined: String): List<Doc> =
    if (joined.isEmpty()) emptyList() else joined.split('\u0000').map { Doc(WebDoc(it.substringAfterLast('/'), File(it))) }

@Composable
actual fun rememberOpenDocument(onResult: (Doc?) -> Unit): (Array<String>) -> Unit {
    val result = rememberUpdatedState(onResult)
    return { mimes -> pickFiles(accepting(mimes), false) { result.value(picked(it).firstOrNull()) } }
}

@Composable
actual fun rememberOpenDocuments(onResult: (List<Doc>) -> Unit): (Array<String>) -> Unit {
    val result = rememberUpdatedState(onResult)
    return { mimes -> pickFiles(accepting(mimes), true) { result.value(picked(it)) } }
}

@Composable
actual fun rememberCreateDocument(onResult: (Doc?) -> Unit): (name: String, mime: String) -> Unit {
    val result = rememberUpdatedState(onResult)
    return { name, mime -> result.value(Doc(WebDoc(name, null, mime))) }
}

/** The downloads, which is the only folder a page may write to. */
@Composable
actual fun rememberOpenFolder(onResult: (Doc?) -> Unit): () -> Unit {
    val result = rememberUpdatedState(onResult)
    return { result.value(Doc(WebDoc("", null))) }
}

private fun wakeLock(on: Boolean): Unit = js(
    """(() => {
        if (!navigator.wakeLock) return;
        if (on) navigator.wakeLock.request('screen').then((l) => { globalThis.acidWakeLock = l; }).catch(() => {});
        else if (globalThis.acidWakeLock) { globalThis.acidWakeLock.release(); globalThis.acidWakeLock = null; }
    })()""",
)

/** The screen wake lock, where the browser has one. */
@Composable
actual fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        wakeLock(on)
        onDispose { if (on) wakeLock(false) }
    }
}

private fun onHidden(action: () -> Unit): JsAny = js(
    """(() => {
        const f = () => { if (document.visibilityState === 'hidden') action(); };
        document.addEventListener('visibilitychange', f);
        return f;
    })()""",
)
private fun offHidden(f: JsAny): Unit = js("document.removeEventListener('visibilitychange', f)")

/** A tab put away may be closed without a word, as a phone's app may be ended. */
@Composable
actual fun OnBackground(action: () -> Unit) {
    val current = rememberUpdatedState(action)
    DisposableEffect(Unit) {
        val listener = onHidden { current.value() }
        onDispose { offHidden(listener) }
    }
}

/** The browser's back leaves the page; Esc is the app's back, as on the desktop. */
@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) {}
