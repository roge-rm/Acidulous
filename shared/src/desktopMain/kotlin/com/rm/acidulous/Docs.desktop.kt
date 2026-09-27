package com.rm.acidulous

import androidx.compose.runtime.Composable
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

// The desktop's file pickers use AWT's native file dialog (GTK on Linux),
// which ignores MIME types and shows every file. The decoder decides what
// loads, as on Android. A Doc here is a java.io.File.

private fun chooseFiles(multiple: Boolean): List<File> {
    val dialog = FileDialog(null as Frame?, "Acidulous", FileDialog.LOAD)
    dialog.isMultipleMode = multiple
    dialog.isVisible = true
    return dialog.files.orEmpty().toList()
}

@Composable
actual fun rememberOpenDocument(onResult: (Doc?) -> Unit): (Array<String>) -> Unit =
    { _ -> onResult(chooseFiles(false).firstOrNull()?.let { Doc(it) }) }

@Composable
actual fun rememberOpenDocuments(onResult: (List<Doc>) -> Unit): (Array<String>) -> Unit =
    { _ -> onResult(chooseFiles(true).map { Doc(it) }) }

@Composable
actual fun rememberCreateDocument(onResult: (Doc?) -> Unit): (name: String, mime: String) -> Unit = { name, _ ->
    val dialog = FileDialog(null as Frame?, "Acidulous", FileDialog.SAVE)
    dialog.file = name
    dialog.isVisible = true
    val chosen = dialog.file?.let { File(dialog.directory, it) }
    onResult(chosen?.let { Doc(it) })
}

@Composable
actual fun rememberOpenFolder(onResult: (Doc?) -> Unit): () -> Unit = {
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    val picked = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    onResult(picked?.let { Doc(it) })
}

/** Nothing to do yet: a desktop doesn't put a playing window to sleep. */
@Composable
actual fun KeepScreenOn(on: Boolean) {}

/** A desktop app isn't killed in the background; the session saves on edits and on close. */
@Composable
actual fun OnBackground(action: () -> Unit) {}

/** A desktop has no back button; Esc is a key binding (KeyAction.Back) like any other. */
@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) {}
