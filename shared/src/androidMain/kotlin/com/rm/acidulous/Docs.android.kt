package com.rm.acidulous

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberOpenDocument(onResult: (Doc?) -> Unit): (Array<String>) -> Unit {
    val bottom = com.rm.acidulous.ui.LocalSecondScreen.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickDone(bottom)
        onResult(uri?.let { Doc(it) })
    }
    return { types -> pickStarted(bottom); launcher.launch(types) }
}

@Composable
actual fun rememberOpenDocuments(onResult: (List<Doc>) -> Unit): (Array<String>) -> Unit {
    val bottom = com.rm.acidulous.ui.LocalSecondScreen.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickDone(bottom)
        onResult(uris.orEmpty().map { Doc(it) })
    }
    return { types -> pickStarted(bottom); launcher.launch(types) }
}

/**
 * A picker started from the second screen's composition: that screen stays
 * open until the result comes back, see [AppState.bottomPickPending].
 */
private fun pickStarted(bottom: Boolean) { if (bottom) AppState.bottomPickPending = true }

private fun pickDone(bottom: Boolean) { if (bottom) AppState.bottomPickPending = false }

/**
 * CreateDocument with the MIME type set on each launch. The contract is kept
 * separately from the launcher because the type changes per export and a
 * launcher doesn't give its contract back.
 */
private class CreateAnyDocument : ActivityResultContracts.CreateDocument("*/*") {
    var mime: String = "*/*"
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        super.createIntent(context, input).setType(mime)
}

@Composable
actual fun rememberCreateDocument(onResult: (Doc?) -> Unit): (name: String, mime: String) -> Unit {
    val contract = remember { CreateAnyDocument() }
    val bottom = com.rm.acidulous.ui.LocalSecondScreen.current
    val launcher = rememberLauncherForActivityResult(contract) { uri -> pickDone(bottom); onResult(uri?.let { Doc(it) }) }
    return { name, mime ->
        contract.mime = mime
        pickStarted(bottom)
        launcher.launch(name)
    }
}

@Composable
actual fun rememberOpenFolder(onResult: (Doc?) -> Unit): () -> Unit {
    val bottom = com.rm.acidulous.ui.LocalSecondScreen.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        pickDone(bottom)
        onResult(tree?.let { Doc(it) })
    }
    return { pickStarted(bottom); launcher.launch(null) }
}

@Composable
actual fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun OnBackground(action: () -> Unit) {
    val current by rememberUpdatedState(action)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) current()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
