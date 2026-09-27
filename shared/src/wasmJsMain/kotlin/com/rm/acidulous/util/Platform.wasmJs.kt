package com.rm.acidulous.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// The page has one thread for all of this (the engine's threads are C++).
// Work the JVM runs on other threads is queued here to run soon, in order,
// and waits are busy-waits on the clock.

private fun nowMs(): Double = js("performance.timeOrigin + performance.now()")
private fun monotonicMs(): Double = js("performance.now()")
private fun later(task: () -> Unit): Unit = js("setTimeout(task, 0)")
private fun localDayAndTime(ms: Double): String = js(
    "new Date(ms).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false })",
)

actual object System {
    actual fun currentTimeMillis(): Long = nowMs().toLong()
    // Uses the engine's time base, not the page's. Emscripten counts time from
    // 1970 (performance.timeOrigin + now) so the page and its workers agree.
    // The audio stream's timestamps use that base and MIDI out is scheduled
    // against this. Nanoseconds since 1970 is about 1.8e18, which fits a Long.
    actual fun nanoTime(): Long = (nowMs() * 1_000_000.0).toLong()
}

actual val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default

actual fun interface Runnable {
    actual fun run()
}

actual fun sleepMs(ms: Long) {
    val until = monotonicMs() + ms
    while (monotonicMs() < until) { /* busy-wait on the page's only thread */ }
}

actual class SerialWorker actual constructor(private val name: String) {
    private val queue = ArrayDeque<suspend () -> Unit>()
    private var running = false

    actual fun execute(task: suspend () -> Unit) {
        queue.addLast(task)
        if (running) return
        running = true
        CoroutineScope(Dispatchers.Default).launch {
            while (queue.isNotEmpty()) {
                // A failing task is logged and the rest still run.
                runCatching { queue.removeFirst()() }.onFailure { Log.w(name, "a task failed", it) }
            }
            running = false
        }
    }
}

actual fun runInBackground(name: String, task: () -> Unit) = later(task)

/** The engine is always there in a browser, since the page loads it before the app. */
actual fun isEngineMissing(e: Throwable): Boolean = false

actual inline fun <T> locked(lock: Any, block: () -> T): T = block()

actual fun dayAndTime(millis: Long): String = localDayAndTime(millis.toDouble())

internal fun postLater(task: () -> Unit) = later(task)
