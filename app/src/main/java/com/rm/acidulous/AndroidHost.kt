package com.rm.acidulous

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.io.File

/** The Android app's [AppHost]: the package manager, the assets, and the crash reports. */
class AndroidHost(private val context: Context) : AppHost {
    private val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()

    override val versionName: String? get() = info?.versionName
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
}
