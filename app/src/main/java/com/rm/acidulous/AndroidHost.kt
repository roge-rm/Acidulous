package com.rm.acidulous

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import com.rm.acidulous.res.*
import com.rm.acidulous.util.PrefStore
import com.rm.acidulous.util.androidPrefs
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The Android app's [AppHost]: package info, assets, files and crash reports. */
class AndroidHost(private val context: Context) : AppHost {
    private val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    // The activity starts again in the new language: AppLanguage makes its context.
    override fun applyLanguage(tag: String?) {
        (context as? android.app.Activity)?.recreate()
    }

    override val debugBuild: Boolean = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override val cleansInput: Boolean get() = true
    /** On the clean input's session while it's open. Not every phone has both. */
    private var inputEffects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var effectSession = 0

    override fun inputSession(id: Int) {
        if (id == effectSession) return
        inputEffects.forEach { runCatching { it.release() } }
        inputEffects = emptyList()
        effectSession = id
        if (id <= 0) return
        inputEffects = listOfNotNull(
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) runCatching { android.media.audiofx.NoiseSuppressor.create(id) }.getOrNull() else null,
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) runCatching { android.media.audiofx.AutomaticGainControl.create(id) }.getOrNull() else null,
        )
        inputEffects.forEach { runCatching { it.enabled = true } }
    }

    override val versionName: String? get() = info?.versionName

    /** The app that installed this one, like F-Droid, or null for a file opened by hand. */
    private val installer: String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    // F-Droid and the clients that use its repos tell people about updates
    // themselves. A debug build is a test build.
    override val checksForUpdates: Boolean
        get() = !debugBuild && installer?.let { "fdroid" in it || it == "com.looker.droidify" } != true

    override fun latestRelease(): String? = latestReleaseOnGitHub("Acidulous/${versionName ?: "?"} (Android)")
    override val versionLong: String?
        get() = info?.let { "%s (%d)".format(it.versionName, androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)) }

    override fun licenceText(path: String): String? =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    override fun latestCrashReport(): File? = CrashReports.latest(context)
    override fun shareCrashReport(report: File) = shareCrashReport(context, report)

    override fun audioInputs(): List<AudioInput> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { d ->
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInput.Kind.BuiltIn
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioInput.Kind.Headset
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioInput.Kind.Usb
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioInput.Kind.Bluetooth
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioInput.Kind.Line
                AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_FM_TUNER -> AudioInput.Kind.NotAnEar
                else -> AudioInput.Kind.Other
            }
            AudioInput(d.id, kind, d.productName?.toString())
        }
    }

    override fun docName(doc: Doc, fallback: String): String {
        var display = fallback
        context.contentResolver.query(doc.uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) display = c.getString(i) ?: display
        }
        return display
    }

    /**
     * Tree URIs have no display name (querying one returns the whole document
     * id), so the folder name is taken from the end of the id.
     */
    override fun placeName(doc: Doc): String = runCatching {
        val uri = doc.uri
        if (android.provider.DocumentsContract.isTreeUri(uri)) {
            val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
            return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { AppStrings.getString(Res.string.app_the_folder) }
        }
        var display = uri.lastPathSegment ?: AppStrings.getString(Res.string.app_the_file)
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) display = c.getString(i) ?: display
        }
        display
    }.getOrDefault(AppStrings.getString(Res.string.app_the_file))

    private fun openInput(doc: Doc): InputStream = context.contentResolver.openInputStream(doc.uri)!!
    private fun openOutput(doc: Doc): OutputStream = context.contentResolver.openOutputStream(doc.uri, "wt")!!
    override fun readDoc(doc: Doc): ByteArray = openInput(doc).use { it.readBytes() }
    override fun copyFromDoc(doc: Doc, file: File) {
        openInput(doc).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    override fun copyToDoc(file: File, doc: Doc) {
        openOutput(doc).use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    override fun createIn(folder: Doc, mime: String, name: String): Doc? {
        val tree = folder.uri
        val parentId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        return android.provider.DocumentsContract.createDocument(context.contentResolver, parent, mime, name)?.let { Doc(it) }
    }

    override fun share(docs: List<Doc>, mime: String, title: String) = share(context, docs.map { it.uri }, mime, title)

    override fun shareFile(file: File, mime: String, title: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        share(context, listOf(uri), mime, title)
    }

    override fun prefs(name: String): PrefStore = androidPrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    override fun unreadCrashReport(): File? = CrashReports.unread(context)
    override fun markCrashReportRead() = CrashReports.markRead(context)

    override fun transportChanged(playing: Boolean, stop: () -> Unit) {
        PlaybackService.follow(context, playing)
        // Stop for a call, another app's music, or unplugged headphones. See
        // media/AudioFocus.
        com.rm.acidulous.media.AudioFocus.follow(context, playing, stop)
    }

    override fun encodeAac(pcm: File, out: File, bitrate: Int): String =
        com.rm.acidulous.media.AacEncoder.encode(pcm, out, bitrate)

    private val Doc.uri: Uri get() = handle as Uri
}
