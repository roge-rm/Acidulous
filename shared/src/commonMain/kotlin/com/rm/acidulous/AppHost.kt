package com.rm.acidulous

import com.rm.acidulous.io.*


/**
 * What the shared UI needs from the platform it runs on. Android's is
 * AndroidHost and the desktop has its own. Set once at startup, before
 * anything is drawn.
 */
interface AppHost {
    /**
     * The installed version, like "0.9.7", or null if it can't be read. It's
     * read from the platform rather than compiled in, so it's the version
     * that's actually installed.
     */
    val versionName: String?
    /** The version with its build number, like "0.9.7 (18)", or null. */
    val versionLong: String?
    /** One of the bundled licence texts by path ("licences/gpl-3.0.txt"), or null. */
    fun licenceText(path: String): String?
    /** The last crash report, or null if there are none. */
    fun latestCrashReport(): File?
    fun shareCrashReport(report: File)
    /** The audio inputs plugged in now, for the recorder. Empty if the platform won't say. */
    fun audioInputs(): List<AudioInput>

    /**
     * The outputs to choose from, by id and name, or none where the platform
     * chooses itself (Android routes its own sound). Used on desktop and in
     * the browser with [chooseAudioOutput]: see Settings > audio.
     */
    fun audioOutputs(): List<Pair<Int, String>> = emptyList()
    fun chooseAudioOutput(id: Int) {}

    // --- Files from the platform's pickers: see [Doc] -------------------------

    /** The file's name, or [fallback] if the platform won't say. */
    fun docName(doc: Doc, fallback: String): String
    /** Where something was written, for a "done" message: a folder's name, or a file's. */
    fun placeName(doc: Doc): String
    /** The whole of [doc]. */
    fun readDoc(doc: Doc): ByteArray
    /** Copies [doc] into [file], for things that may be too big to hold in memory. */
    fun copyFromDoc(doc: Doc, file: File)
    /** Copies [file] over [doc], replacing what was there. */
    fun copyToDoc(file: File, doc: Doc)
    /** A new file in a folder from the picker, or null if it couldn't be made. */
    fun createIn(folder: Doc, mime: String, name: String): Doc?
    /** The share sheet, with the files that were written. */
    fun share(docs: List<Doc>, mime: String, title: String)
    /** The share sheet, with one of the app's own files. */
    fun shareFile(file: File, mime: String, title: String)

    // --- The rest of the platform ---------------------------------------------

    /** A small named store for the app's own settings. */
    fun prefs(name: String): com.rm.acidulous.util.PrefStore
    /** The crash report from the last run that the user hasn't been told about, or null. */
    fun unreadCrashReport(): File?
    fun markCrashReportRead()
    /**
     * The transport started or stopped, so the platform can react (on
     * Android, the playback service and audio focus). The platform calls
     * [stop] to stop playback itself, for a call or unplugged headphones.
     */
    fun transportChanged(playing: Boolean, stop: () -> Unit)
    /** Encode a 16-bit WAV to AAC at [bitrate]. Returns "" on success, otherwise the reason. */
    fun encodeAac(pcm: File, out: File, bitrate: Int): String
    /** Whether [encodeAac] works here. The export window hides AAC if not. */
    val canEncodeAac: Boolean get() = true
    /**
     * The platform's name, like "Linux", shown in the About window. Null on
     * Android.
     */
    val platformName: String? get() = null

    /**
     * The pointer is a mouse rather than a finger: the wheel scrolls and
     * zooms the grids, and text says "click" instead of "tap". False on
     * Android.
     */
    val usesMouse: Boolean get() = false

    /**
     * Running on a computer rather than a phone, for the few texts that
     * depend on it ("this phone", the share sheet, TalkBack). False on
     * Android.
     */
    val onDesktop: Boolean get() = false

    /** Whether the screen can be kept on while playing (Android's window flag). */
    val canKeepScreenOn: Boolean get() = true
    /**
     * Whether Ableton Link is available. Not in a browser, since a page can't
     * send or receive the local multicast Link uses to find peers. Without
     * it the tempo window has no Link page.
     */
    val hasLink: Boolean get() = true

    /** MIDI through ALSA's sequencer (Linux desktop), for the About window's list. */
    val hasAlsa: Boolean get() = false

    /** Steinberg's ASIO SDK for audio interfaces' own drivers (Windows), for the same list. */
    val hasDriverSdk: Boolean get() = false
    /**
     * Whether the audio thread can time itself precisely enough for its peak
     * readouts (worst block, late count, dropouts) to mean anything. Not in a
     * browser: an AudioWorklet has no clock of its own, the one it can use
     * ticks in callback-sized steps, and it can't see dropouts. There only
     * the average load is shown.
     */
    val timesAudioPrecisely: Boolean get() = true
    /** Which library provides the audio stream, for the About window's credits. */
    val audioStream: AudioStream get() = AudioStream.Oboe

    companion object {
        lateinit var current: AppHost
    }
}

/** Oboe on Android, miniaudio on desktop (with ALSA MIDI), or the browser's Web Audio, which needs no credit. */
enum class AudioStream { Oboe, Miniaudio, Browser }

/** An audio input as the recorder shows it: its kind, and the device's own name for unusual ones. */
data class AudioInput(val id: Int, val kind: Kind, val name: String?) {
    enum class Kind { BuiltIn, Headset, Usb, Bluetooth, Line, NotAnEar, Other }
}
