package com.rm.acidulous.web

import com.rm.acidulous.AppHost
import com.rm.acidulous.AudioInput
import com.rm.acidulous.Doc
import com.rm.acidulous.WebDoc
import com.rm.acidulous.downloadFile
import com.rm.acidulous.io.File
import com.rm.acidulous.io.copyTo
import com.rm.acidulous.io.name
import com.rm.acidulous.io.readBytes
import com.rm.acidulous.res.AppStrings
import com.rm.acidulous.res.Res
import com.rm.acidulous.res.files_downloads_web
import com.rm.acidulous.util.PrefStore

/** A file beside the page, read there and then; null when it is not there. */
private fun fetchTextNow(path: String): String? = js(
    "(() => { try { const r = new XMLHttpRequest(); r.open('GET', path, false); r.send(); " +
        "return r.status === 200 ? r.responseText : null; } catch (e) { return null; } })()",
)
private fun coarsePointer(): Boolean = js("matchMedia('(pointer: coarse)').matches")

/**
 * The browser's [AppHost]. A [Doc] is a [WebDoc]: a chosen file already
 * copied in, or a download waiting for its bytes - so writing one downloads
 * it, and sharing downloads it too, there being no share sheet to hand to.
 */
class WebHost : AppHost {
    override val versionName: String? = VERSION_NAME
    override val versionLong: String? = "$VERSION_NAME ($VERSION_CODE)"
    /** Served beside the page, and read only when somebody opens one. */
    override fun licenceText(path: String): String? = fetchTextNow(path)

    // No crash reports: a page that falls over shows it in the console.
    override fun latestCrashReport(): File? = null
    override fun shareCrashReport(report: File) {}
    override fun unreadCrashReport(): File? = null
    override fun markCrashReportRead() {}

    override fun audioInputs(): List<AudioInput> = emptyList()

    override fun docName(doc: Doc, fallback: String): String = doc.web.name.ifEmpty { fallback }
    override fun placeName(doc: Doc): String = doc.web.name.ifEmpty { AppStrings.getString(Res.string.files_downloads_web) }
    override fun readDoc(doc: Doc): ByteArray = doc.web.copy?.readBytes() ?: ByteArray(0)
    override fun copyFromDoc(doc: Doc, file: File) {
        doc.web.copy?.copyTo(file, overwrite = true)
    }
    override fun copyToDoc(file: File, doc: Doc) = downloadFile(file, doc.web.name.ifEmpty { file.name }, doc.web.mime)
    override fun createIn(folder: Doc, mime: String, name: String): Doc? = Doc(WebDoc(name, null, mime))
    /** Already downloaded when it was written: there is nothing more to hand over. */
    override fun share(docs: List<Doc>, mime: String, title: String) {}
    override fun shareFile(file: File, mime: String, title: String) = downloadFile(file, file.name, mime)

    override fun prefs(name: String): PrefStore = LocalPrefs(name)

    override fun transportChanged(playing: Boolean, stop: () -> Unit) {}

    override val canEncodeAac: Boolean = false
    override fun encodeAac(pcm: File, out: File, bitrate: Int): String =
        "AAC export is the phone's own encoder, which a browser does not have. Choose MP3 or FLAC."

    override val platformName: String = "the web"
    /** A finger on a phone's or a tablet's browser; a mouse anywhere else. */
    override val usesMouse: Boolean = !coarsePointer()
    override val onDesktop: Boolean = !coarsePointer()
    override val canKeepScreenOn: Boolean = true
    override val audioStream = com.rm.acidulous.AudioStream.Browser
    override val hasLink: Boolean = false

    private val Doc.web: WebDoc get() = handle as WebDoc
}
