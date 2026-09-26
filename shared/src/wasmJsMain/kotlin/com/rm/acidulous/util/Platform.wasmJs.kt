package com.rm.acidulous.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// A browser's page has one thread for all of this - the engine's own threads
// are C++'s. What the JVM does elsewhere happens here soon, in order; what it
// waits for, the page waits for on the clock.

private fun nowMs(): Double = js("performance.timeOrigin + performance.now()")
private fun monotonicMs(): Double = js("performance.now()")
private fun later(task: () -> Unit): Unit = js("setTimeout(task, 0)")
private fun localDayAndTime(ms: Double): String = js(
    "new Date(ms).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false })",
)

actual object System {
    actual fun currentTimeMillis(): Long = nowMs().toLong()
    actual fun nanoTime(): Long = (monotonicMs() * 1_000_000.0).toLong()
}

actual val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default

actual fun interface Runnable {
    actual fun run()
}

actual fun sleepMs(ms: Long) {
    val until = monotonicMs() + ms
    while (monotonicMs() < until) { /* the page's one thread, waiting */ }
}

actual class SerialWorker actual constructor(name: String) {
    actual fun execute(task: () -> Unit) = later(task)
}

actual fun runInBackground(name: String, task: () -> Unit) = later(task)

/** The engine is always there in a browser: the page loads it before the app. */
actual fun isEngineMissing(e: Throwable): Boolean = false

actual inline fun <T> locked(lock: Any, block: () -> T): T = block()

actual fun dayAndTime(millis: Long): String = localDayAndTime(millis.toDouble())

internal fun postLater(task: () -> Unit) = later(task)
