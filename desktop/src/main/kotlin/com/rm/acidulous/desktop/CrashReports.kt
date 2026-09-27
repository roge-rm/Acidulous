package com.rm.acidulous.desktop

import com.rm.acidulous.model.writeTextSafely
import com.rm.acidulous.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports for the desktop app. Works like Android's CrashReports, with
 * two sources:
 *
 * - A Kotlin crash is caught by the default handler and written out with its
 *   stack and the app's recent log, then passed on as normal.
 * - A native crash (in the engine or the JVM) is written by the JVM as an
 *   `hs_err` file, which the launcher points at this folder
 *   (`-XX:ErrorFile`). Each one becomes a report on the next start.
 *
 * Freezes leave nothing on desktop, since the user closes the app by hand.
 *
 * The last [KEEP] reports are kept in the app's data folder and only leave it
 * if the user shares one. The notice for the newest unread one is in the
 * shared code, like on Android.
 */
class CrashReports(dataDir: File) {
    val directory: File = File(dataDir, "crashes").apply { mkdirs() }
    private val unreadMark = File(directory, ".unread")

    /** Call first thing at start: catches what the app's own code throws. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                write(
                    "crash",
                    "Crashed on thread \"${t.name}\" (pid ${ProcessHandle.current().pid()}).\n\n$trace\n" +
                        "--- what it last logged ---\n${Log.recent()}\n",
                )
            }
            previous?.uncaughtException(t, e) ?: e.printStackTrace()
        }
    }

    /** On start: turn the JVM's crash files from earlier runs into reports. */
    fun collect() {
        val files = directory.listFiles { f -> f.name.startsWith("hs_err") && f.name.endsWith(".log") }.orEmpty()
        for (f in files.sortedBy { it.lastModified() }) {
            runCatching {
                write("native", "The app ended: native crash.\n\n" + f.readText().take(60_000), f.lastModified())
                f.delete()
            }.onFailure { Log.w(TAG, "could not read ${f.name}", it) }
        }
    }

    /** The newest report the user hasn't been told about, if any. */
    fun unread(): File? =
        runCatching { unreadMark.readText().trim() }.getOrNull()
            ?.let { File(directory, it) }?.takeIf { it.isFile }

    fun markRead() {
        unreadMark.delete()
    }

    /** The newest report, read or not. Used by About. */
    fun latest(): File? = reports().maxByOrNull { it.name }

    private fun reports(): List<File> = directory.listFiles { f -> f.extension == "txt" }.orEmpty().toList()

    private fun write(kind: String, body: String, whenMs: Long = System.currentTimeMillis()) {
        val name = "${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(whenMs))}-$kind.txt"
        File(directory, name).writeTextSafely(header(whenMs) + body)
        unreadMark.writeText(name)
        reports().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    private fun header(whenMs: Long): String = buildString {
        append("Acidulous $VERSION_NAME ($VERSION_CODE), desktop\n")
        append(distribution()).append(", ")
        append("${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, ")
        append("Java ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})\n")
        append("When: ${stamp(whenMs)}\n\n")
    }

    /** For example "Debian GNU/Linux 13 (trixie)", from /etc/os-release. */
    private fun distribution(): String = runCatching {
        File("/etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.trim('"')
    }.getOrNull() ?: "unknown system"

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    private companion object {
        const val TAG = "Acidulous.Crash"
        const val KEEP = 10
    }
}
