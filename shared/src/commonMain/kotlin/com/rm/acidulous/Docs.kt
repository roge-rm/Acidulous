package com.rm.acidulous

import androidx.compose.runtime.Composable

/**
 * A file or folder from the platform's own picker: a content Uri on Android,
 * a path on desktop. Only the platform reads it (through [AppHost]), so the
 * app doesn't need to know which.
 */
class Doc(val handle: Any)

/** The system's "open a file" picker; call the result with the MIME types to offer. */
@Composable
expect fun rememberOpenDocument(onResult: (Doc?) -> Unit): (Array<String>) -> Unit

/** The same, but picking several files. */
@Composable
expect fun rememberOpenDocuments(onResult: (List<Doc>) -> Unit): (Array<String>) -> Unit

/** The system's "save as" picker: call the result with a suggested name and the MIME type. */
@Composable
expect fun rememberCreateDocument(onResult: (Doc?) -> Unit): (name: String, mime: String) -> Unit

/** The system's folder picker. */
@Composable
expect fun rememberOpenFolder(onResult: (Doc?) -> Unit): () -> Unit

/** Keep the screen on while [on]. */
@Composable
expect fun KeepScreenOn(on: Boolean)

/** Run [action] when the app goes to the background, where a phone may close it without warning. */
@Composable
expect fun OnBackground(action: () -> Unit)

/** Handle the platform's back (Android's button or gesture) while [enabled]. */
@Composable
expect fun SystemBack(enabled: Boolean, onBack: () -> Unit)
