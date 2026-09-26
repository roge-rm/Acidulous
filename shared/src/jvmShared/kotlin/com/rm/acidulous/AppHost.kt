package com.rm.acidulous

import com.rm.acidulous.io.File

/**
 * What the shared UI asks of the app around it: the things only the platform
 * knows or can do. The Android app's is AndroidHost; the desktop's is its own.
 * Set once at startup, before anything is drawn.
 */
interface AppHost {
    /**
     * The installed version, "0.9.7", or null when it cannot be read. Asked
     * of the platform rather than compiled in, so it is the version of what
     * is actually installed and not of the module that happened to be built.
     */
    val versionName: String?
    /** The version with its build number, "0.9.7 (18)", or null. */
    val versionLong: String?
    /** One of the bundled licence texts, by its path ("licences/gpl-3.0.txt"), or null. */
    fun licenceText(path: String): String?
    /** The last crash report, while there is one; null where there are none. */
    fun latestCrashReport(): File?
    fun shareCrashReport(report: File)
    /** The audio inputs plugged in now, for the recorder's choice of ear; empty when the platform will not say. */
    fun audioInputs(): List<AudioInput>

    /**
     * The outputs there are to choose from, by id and name, or none where the
     * platform does the choosing - the phone routes its own sound. The
     * desktop's, with [chooseAudioOutput]: see Settings > audio.
     */
    fun audioOutputs(): List<Pair<Int, String>> = emptyList()
    fun chooseAudioOutput(id: Int) {}

    // --- Files the platform's pickers handed over: see [Doc] ------------------

    /** What the platform calls a file, or [fallback] when it will not say. */
    fun docName(doc: Doc, fallback: String): String
    /** Where something was written, for a "done" line: a folder's own name, or a file's. */
    fun placeName(doc: Doc): String
    fun openInput(doc: Doc): java.io.InputStream
    /** Open for writing, replacing what was there. */
    fun openOutput(doc: Doc): java.io.OutputStream
    /** A new file in a folder the picker gave, or null when it could not be made. */
    fun createIn(folder: Doc, mime: String, name: String): Doc?
    /** The share sheet, with what was written. */
    fun share(docs: List<Doc>, mime: String, title: String)
    /** The share sheet, with a file of the app's own. */
    fun shareFile(file: File, mime: String, title: String)

    // --- The rest of the platform ---------------------------------------------

    /** A small named store of the app's own flags. */
    fun prefs(name: String): com.rm.acidulous.util.PrefStore
    /** The crash report the last run left and nobody has seen, or null. */
    fun unreadCrashReport(): File?
    fun markCrashReportRead()
    /**
     * The transport started or stopped: what the platform does about it (on
     * Android, the playback service and audio focus). [stop] is how the
     * platform stops it in turn - for a call, or headphones pulled out.
     */
    fun transportChanged(playing: Boolean, stop: () -> Unit)
    /** Encode a 16-bit WAV to AAC at [bitrate]; "" when it worked, else why not. */
    fun encodeAac(pcm: File, out: File, bitrate: Int): String
    /** Whether [encodeAac] can: the export window leaves AAC out where it cannot. */
    val canEncodeAac: Boolean get() = true
    /**
     * The platform's name where it is not the phone - "Linux" - for the About
     * window to say which build this is. Null on Android, the app's home, where
     * nothing needs adding.
     */
    val platformName: String? get() = null

    /**
     * The pointer is a mouse rather than a finger: the wheel moves and zooms
     * the grids, and the few words that say "tap" say "click". False on the
     * phone, whose words and gestures these are.
     */
    val usesMouse: Boolean get() = false

    /**
     * A computer rather than a phone, for the few words and pages that are
     * about which one this is: "this phone", the share sheet, TalkBack. False
     * on the phone, whose words these are.
     */
    val onDesktop: Boolean get() = false

    /** Whether the screen can be kept on while playing: the phone's window flag. */
    val canKeepScreenOn: Boolean get() = true
    /** Whether the audio stream is Oboe's (the phone) rather than miniaudio's (the desktop), for the About window's credits. */
    val usesOboe: Boolean get() = true

    companion object {
        lateinit var current: AppHost
    }
}

/** An audio input as the recorder names it: what kind of thing, and the device's own name for an unusual one. */
data class AudioInput(val id: Int, val kind: Kind, val name: String?) {
    enum class Kind { BuiltIn, Headset, Usb, Bluetooth, Line, NotAnEar, Other }
}
