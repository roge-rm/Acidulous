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
 * The outputs, kept in globalThis.acidOutput for the engine's side
 * (acid_output_attach in the web AudioDriver): the list, read now and again
 * whenever a device comes or goes; the one wanted, by a number made from the
 * browser's id for it, which is what the setting stores; and pick(), which
 * finds it - or the default while it is unplugged - and moves the sound
 * there. A browser names its outputs only once the microphone is allowed, and
 * one it will not name is left out, so before that the list is empty and
 * Settings shows no choice.
 */
private fun watchOutputs(): Unit = js(
    "(() => { const o = (globalThis.acidOutput ??= {}); o.list = []; " +
        "o.idOf = (s) => { let h = 7; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return (h & 0x7fffffff) || 1; }; " +
        "o.pick = () => { const d = o.list.find((d) => o.idOf(d.deviceId) === o.wanted); " +
        "o.sinkId = d ? d.deviceId : ''; o.sinkLabel = d ? d.label : ''; o.apply && o.apply(); }; " +
        "const md = navigator.mediaDevices; if (!md || !md.enumerateDevices) return; " +
        "o.read = () => md.enumerateDevices().then((all) => { " +
        "o.list = all.filter((d) => d.kind === 'audiooutput' && d.label && d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications'); " +
        "o.pick(); }).catch(() => {}); " +
        "md.addEventListener('devicechange', o.read); o.read(); })()",
)
private fun outputCount(): Int = js("globalThis.acidOutput?.list?.length ?? 0")
private fun outputId(i: Int): Int = js("globalThis.acidOutput.idOf(globalThis.acidOutput.list[i].deviceId)")
private fun outputLabel(i: Int): String = js("globalThis.acidOutput.list[i].label")
/** Read again for next time: a list read before the microphone was allowed has no names. */
private fun rereadOutputs(): Unit = js("globalThis.acidOutput?.read?.()")
private fun wantOutput(id: Int): Unit = js("(() => { const o = (globalThis.acidOutput ??= {}); o.wanted = id; o.pick && o.pick(); })()")

/**
 * The browser's [AppHost]. A [Doc] is a [WebDoc]: a chosen file already
 * copied in, or a download waiting for its bytes - so writing one downloads
 * it, and sharing downloads it too, there being no share sheet to hand to.
 */
class WebHost : AppHost {
    init {
        watchOutputs()
    }

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

    override fun audioOutputs(): List<Pair<Int, String>> {
        rereadOutputs()
        return (0 until outputCount()).map { outputId(it) to outputLabel(it) }
    }
    override fun chooseAudioOutput(id: Int) = wantOutput(id)

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
    override val timesAudioPrecisely: Boolean = false

    private val Doc.web: WebDoc get() = handle as WebDoc
}
