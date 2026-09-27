package com.rm.acidulous.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** java.lang.System's two clocks, which is all the shared code uses. */
expect object System {
    fun currentTimeMillis(): Long
    fun nanoTime(): Long
}

/** Where blocking work goes: the JVM's IO pool, or in a browser (one thread) its only dispatcher. */
expect val Dispatchers.IO: CoroutineDispatcher

/** A task to run: java.lang.Runnable on the JVM, which Android's Handler takes. */
expect fun interface Runnable {
    fun run()
}

/**
 * Sleeps this thread for [ms]. The shared code does this on the main thread
 * to let the engine's queue drain between batches of parameters. A browser
 * can't sleep a thread, so it waits on the clock instead.
 */
expect fun sleepMs(ms: Long)

/**
 * Runs tasks one at a time, off the caller's thread where there are threads.
 * A task may suspend (in a browser, on an engine call on its own thread) and
 * the next one waits until it's done.
 */
expect class SerialWorker(name: String) {
    fun execute(task: suspend () -> Unit)
}

/** Runs [task] on its own thread, or in a browser, soon on the only one. */
expect fun runInBackground(name: String, task: () -> Unit)

/** Whether [e] means the engine library isn't there at all, as in a unit test or a tool. */
expect fun isEngineMissing(e: Throwable): Boolean

/** Runs [block] with [lock] held: `synchronized` where there are threads. */
expect inline fun <T> locked(lock: Any, block: () -> T): T

/** A file's date as a list shows it, in the device's language: "3 Mar 14:05". */
expect fun dayAndTime(millis: Long): String
