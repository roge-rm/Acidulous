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
 * What is left behind when the desktop app dies: the phone's CrashReports,
 * with a desktop's two sources.
 *
 * - **A Kotlin crash** is caught by the default handler and written out on
 *   the spot - the stack, and what the app last logged - before the crash
 *   carries on exactly as it would have.
 * - **A native crash** - the engine, or the JVM itself - is written up by the
 *   JVM as an `hs_err` file, which the launcher points into this folder
 *   (`-XX:ErrorFile`). On the next start each one becomes a report.
 *
 * A freeze leaves nothing on a desktop: nobody kills it, and it is closed by
 * hand.
 *
 * Reports stay in the app's data folder, the last [KEEP] of them, and go
 * nowhere unless the person shares one. The notice for the newest unread one
 * is the shared code's, as on the phone.
 */
class CrashReports(dataDir: File) {
    val directory: File = File(dataDir, "crashes").apply { mkdirs() }
    private val unreadMark = File(directory, ".unread")

    /** First thing at start: catch what the app's own code throws. */
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

    /** On start: the JVM's own crash files from the runs before, as reports. */
    fun collect() {
        val files = directory.listFiles { f -> f.name.startsWith("hs_err") && f.name.endsWith(".log") }.orEmpty()
        for (f in files.sortedBy { it.lastModified() }) {
            runCatching {
                write("native", "The app ended: native crash.\n\n" + f.readText().take(60_000), f.lastModified())
                f.delete()
            }.onFailure { Log.w(TAG, "could not read ${f.name}", it) }
        }
    }

    /** The newest report nobody has seen the notice for, if any. */
    fun unread(): File? =
        runCatching { unreadMark.readText().trim() }.getOrNull()
            ?.let { File(directory, it) }?.takeIf { it.isFile }

    fun markRead() {
        unreadMark.delete()
    }

    /** The newest report there is, read or not: for About. */
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

    /** "Debian GNU/Linux 13 (trixie)", from the one file every distribution keeps it in. */
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
