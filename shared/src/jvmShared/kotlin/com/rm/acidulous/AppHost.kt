package com.rm.acidulous

import java.io.File

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

    companion object {
        lateinit var current: AppHost
    }
}

/** An audio input as the recorder names it: what kind of thing, and the device's own name for an unusual one. */
data class AudioInput(val id: Int, val kind: Kind, val name: String?) {
    enum class Kind { BuiltIn, Headset, Usb, Bluetooth, Line, NotAnEar, Other }
}
