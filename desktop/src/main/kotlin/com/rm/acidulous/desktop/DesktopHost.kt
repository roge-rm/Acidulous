package com.rm.acidulous.desktop

import com.rm.acidulous.AppHost
import com.rm.acidulous.AudioInput
import com.rm.acidulous.Doc
import com.rm.acidulous.util.FilePrefs
import com.rm.acidulous.util.Log
import com.rm.acidulous.util.PrefStore
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The desktop's [AppHost]. A [Doc] is a java.io.File here, so reading and
 * writing one is plain file I/O; sharing has no system sheet to hand to, so
 * it opens the folder the file is in instead.
 */
class DesktopHost(private val configDir: File) : AppHost {
    override val versionName: String? = VERSION_NAME
    override val versionLong: String? = "$VERSION_NAME ($VERSION_CODE)"

    override fun licenceText(path: String): String? =
        javaClass.classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    /** Crash reports are the phone's; a desktop run prints its trouble to the terminal. */
    override fun latestCrashReport(): File? = null
    override fun shareCrashReport(report: File) {}
    override fun unreadCrashReport(): File? = null
    override fun markCrashReportRead() {}

    override fun audioInputs(): List<AudioInput> = DesktopAudio.inputs()

    override fun docName(doc: Doc, fallback: String): String = doc.file.name.ifEmpty { fallback }
    override fun placeName(doc: Doc): String = doc.file.name
    override fun openInput(doc: Doc): InputStream = doc.file.inputStream()
    override fun openOutput(doc: Doc): OutputStream = doc.file.outputStream()
    override fun createIn(folder: Doc, mime: String, name: String): Doc? =
        File(folder.file, name).takeIf { runCatching { it.createNewFile() || it.isFile }.getOrDefault(false) }?.let { Doc(it) }

    override fun share(docs: List<Doc>, mime: String, title: String) {
        docs.firstOrNull()?.file?.parentFile?.let { openFolder(it) }
    }

    /** Kept, where the phone would hand it to another app: in the config folder's "shared", which is opened. */
    override fun shareFile(file: File, mime: String, title: String) {
        val kept = File(File(configDir.parentFile, "acidulous-shared").apply { mkdirs() }, file.name)
        file.copyTo(kept, overwrite = true)
        openFolder(kept.parentFile)
    }

    override fun prefs(name: String): PrefStore = FilePrefs(File(configDir, "$name.properties"))

    /** Nothing to hold: a desktop does not stop a playing app behind its back. */
    override fun transportChanged(playing: Boolean, stop: () -> Unit) {}

    override val platformName: String = "Linux"
    override val canEncodeAac: Boolean = false
    override val usesOboe: Boolean = false

    override fun encodeAac(pcm: File, out: File, bitrate: Int): String =
        "AAC export is the phone's own encoder, which the desktop does not have. Choose MP3 or FLAC."

    private fun openFolder(dir: File) {
        runCatching { Desktop.getDesktop().open(dir) }
            .onFailure { Log.w("Acidulous.Desktop", "could not open $dir", it) }
    }

    private val Doc.file: File get() = handle as File
}
