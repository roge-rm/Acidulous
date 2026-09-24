package com.rm.acidulous

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.rm.acidulous.model.writeTextSafely
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What is left behind when the app dies, so a crash on somebody's phone is
 * something that can be fixed rather than a thing that happened once.
 *
 * Two sources:
 *
 * - **A Kotlin crash** is caught by the default handler and written out on
 *   the spot, stack and all, before the crash carries on exactly as it would
 *   have.
 * - **Everything else** - a native crash in the engine, the app frozen long
 *   enough for Android to kill it - is asked about on the next start. From
 *   Android 11 the system keeps the reasons a process ended; a crash or a
 *   freeze since the last one we saw becomes a report with the system's
 *   description, the freeze's trace, what the dead process last logged, and
 *   on 12 and up the readable parts of the crash record.
 *
 * No signal handler of our own: the runtime uses its own for null checks,
 * and a second one in an audio app is a risk taken to learn nothing the
 * system does not already keep.
 *
 * Reports stay on the phone, the last ten of them, and go nowhere unless the
 * person shares one.
 */
object CrashReports {
    private const val TAG = "Acidulous.Crash"
    private const val KEEP = 10
    private const val PREFS = "crash"
    private const val SEEN_EXIT = "seen_exit"
    private const val UNREAD = "unread"

    fun directory(context: Context): File = File(context.filesDir, "crashes").apply { mkdirs() }

    /** First thing in onCreate: catch what the app's own code throws. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val app: Context, val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                write(app, "crash", "Crashed on thread \"${t.name}\" (pid ${android.os.Process.myPid()}).\n\n$trace")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * On start: turn the system's record of how the last runs ended into
     * reports. Crashes our handler already wrote up are not written twice.
     */
    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(SEEN_EXIT, 0L)
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            var newest = seen
            for (x in exits.sortedBy { it.timestamp }) {
                if (x.timestamp <= seen) continue
                newest = maxOf(newest, x.timestamp)
                val kind = when (x.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    ApplicationExitInfo.REASON_ANR -> "froze"
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    else -> continue
                }
                // A Kotlin crash the handler caught is already written, whole.
                if (x.reason == ApplicationExitInfo.REASON_CRASH && alreadyWritten(context, x.pid)) continue
                val body = buildString {
                    append("The app ended: $kind (pid ${x.pid}) at ${stamp(x.timestamp)}.\n")
                    x.description?.let { append("System: $it\n") }
                    append("\n")
                    trace(x)?.let { append(it).append("\n\n") }
                    logOf(x.pid)?.let { append("--- what it last logged ---\n").append(it) }
                }
                write(context, if (x.reason == ApplicationExitInfo.REASON_ANR) "froze" else "crash", body, x.timestamp)
            }
            if (newest > seen) prefs.edit().putLong(SEEN_EXIT, newest).apply()
        }.onFailure { Log.w(TAG, "could not read how the last run ended", it) }
    }

    /** The newest report nobody has seen the notice for, if any. */
    fun unread(context: Context): File? {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(UNREAD, null) ?: return null
        return File(directory(context), name).takeIf { it.isFile }
    }

    fun markRead(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(UNREAD).apply()
    }

    /** The newest report there is, read or not: for About. */
    fun latest(context: Context): File? =
        directory(context).listFiles { f -> f.extension == "txt" }?.maxByOrNull { it.name }

    private fun write(context: Context, kind: String, body: String, whenMs: Long = System.currentTimeMillis()) {
        val name = "${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(whenMs))}-$kind.txt"
        File(directory(context), name).writeTextSafely(header(context, whenMs) + body)
        // commit, not apply: this may be the last thing a dying process does.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(UNREAD, name).commit()
        directory(context).listFiles { f -> f.extension == "txt" }
            ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun header(context: Context, whenMs: Long): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildString {
            append("Acidulous ${info?.versionName ?: "?"} (${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: "?"})\n")
            append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ")
            append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?").append("\n")
            append("When: ${stamp(whenMs)}\n\n")
        }
    }

    private fun alreadyWritten(context: Context, pid: Int): Boolean =
        directory(context).listFiles()?.any { it.readText().contains("(pid $pid)") } == true

    /**
     * What the system kept: a freeze's trace is text; a native crash's record
     * from Android 12 is a protocol buffer, of which the runs of readable text
     * - the libraries, the functions, the abort message - are the useful part.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun trace(x: ApplicationExitInfo): String? = runCatching {
        val bytes = x.traceInputStream?.use { it.readBytes() } ?: return null
        if (x.reason == ApplicationExitInfo.REASON_ANR) {
            bytes.decodeToString().take(24_000)
        } else {
            "--- crash record (readable parts) ---\n" + readable(bytes).take(16_000)
        }
    }.getOrNull()

    private fun readable(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) {
                run.append(c.toChar())
            } else {
                if (run.length >= 4) out.append(run).append('\n')
                run.setLength(0)
            }
        }
        if (run.length >= 4) out.append(run)
        return out.toString()
    }

    /** The dead process's own last log lines, while the log still has them. */
    private fun logOf(pid: Int): String? = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "300", "--pid=$pid").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }?.takeLast(40_000)
    }.getOrNull()

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))
}
