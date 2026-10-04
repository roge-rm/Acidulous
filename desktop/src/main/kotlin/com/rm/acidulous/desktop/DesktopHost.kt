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
 * The desktop [AppHost]. A [Doc] is a java.io.File here, so reading and
 * writing is plain file I/O. There's no share sheet, so sharing opens the
 * folder the file is in.
 */
class DesktopHost(private val configDir: File, private val crashes: CrashReports) : AppHost {
    override val versionName: String? = VERSION_NAME
    override val versionLong: String? = "$VERSION_NAME ($VERSION_CODE)"

    override fun licenceText(path: String): String? =
        javaClass.classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    // Crash reports: see CrashReports. Sharing one opens its folder, like any
    // other share here.
    override fun latestCrashReport(): File? = crashes.latest()
    override fun shareCrashReport(report: File) = openFolder(report.parentFile)
    override fun unreadCrashReport(): File? = crashes.unread()
    override fun markCrashReportRead() = crashes.markRead()

    override fun audioInputs(): List<AudioInput> = DesktopAudio.inputs()
    override fun audioOutputs(): List<Pair<Int, String>> = DesktopAudio.outputs()
    override fun chooseAudioOutput(id: Int) = DesktopAudio.chooseOutput(id)

    override fun docName(doc: Doc, fallback: String): String = doc.file.name.ifEmpty { fallback }
    override fun placeName(doc: Doc): String = doc.file.name
    private fun openInput(doc: Doc): InputStream = doc.file.inputStream()
    private fun openOutput(doc: Doc): OutputStream = doc.file.outputStream()
    override fun readDoc(doc: Doc): ByteArray = openInput(doc).use { it.readBytes() }
    override fun copyFromDoc(doc: Doc, file: File) {
        openInput(doc).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    override fun copyToDoc(file: File, doc: Doc) {
        openOutput(doc).use { out -> file.inputStream().use { it.copyTo(out) } }
    }
    override fun createIn(folder: Doc, mime: String, name: String): Doc? =
        File(folder.file, name).takeIf { runCatching { it.createNewFile() || it.isFile }.getOrDefault(false) }?.let { Doc(it) }

    override fun share(docs: List<Doc>, mime: String, title: String) {
        docs.firstOrNull()?.file?.parentFile?.let { openFolder(it) }
    }

    /** Where Android would share it: copied to "acidulous-shared" next to the config folder, which is then opened. */
    override fun shareFile(file: File, mime: String, title: String) {
        val kept = File(File(configDir.parentFile, "acidulous-shared").apply { mkdirs() }, file.name)
        file.copyTo(kept, overwrite = true)
        openFolder(kept.parentFile)
    }

    override fun prefs(name: String): PrefStore = FilePrefs(File(configDir, "$name.properties"))

    /** Nothing to do: desktops don't stop a playing app in the background. */
    override fun transportChanged(playing: Boolean, stop: () -> Unit) {}

    override val platformName: String = if (onWindows) "Windows" else "Linux"
    override val hasAlsa: Boolean = !onWindows
    override val hasDriverSdk: Boolean = onWindows
    override val usesMouse: Boolean = true
    override val onDesktop: Boolean = true
    /** Set only by `gradle :desktop:run` (desktop/build.gradle.kts), never by the packages. */
    override val debugBuild: Boolean = System.getProperty("acidulous.debug") == "true"
    /** The app doesn't hold off the screen saver on desktop. */
    override val canKeepScreenOn: Boolean = false
    override val canEncodeAac: Boolean = false
    override val audioStream = com.rm.acidulous.AudioStream.Miniaudio

    override fun encodeAac(pcm: File, out: File, bitrate: Int): String =
        "AAC export is only on the phone. Choose MP3 or FLAC."

    private fun openFolder(dir: File) {
        runCatching { Desktop.getDesktop().open(dir) }
            .onFailure { Log.w("Acidulous.Desktop", "could not open $dir", it) }
    }

    private val Doc.file: File get() = handle as File
}
