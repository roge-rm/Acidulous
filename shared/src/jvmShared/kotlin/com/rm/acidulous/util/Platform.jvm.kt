@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package com.rm.acidulous.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object System {
    actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
    actual fun nanoTime(): Long = java.lang.System.nanoTime()
}

/** The member: kotlinx.coroutines' own on the JVM. */
actual val Dispatchers.IO: CoroutineDispatcher get() = this.IO

actual typealias Runnable = java.lang.Runnable

actual fun sleepMs(ms: Long) = Thread.sleep(ms)

actual class SerialWorker actual constructor(name: String) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }
    actual fun execute(task: () -> Unit) = executor.execute(task)
}

actual fun runInBackground(name: String, task: () -> Unit) {
    Thread(task, name).start()
}

actual fun isEngineMissing(e: Throwable): Boolean = e is LinkageError

actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

actual fun dayAndTime(millis: Long): String =
    java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
