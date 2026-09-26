package com.rm.acidulous.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** java.lang.System's two clocks, which is all the shared code asks of it. */
expect object System {
    fun currentTimeMillis(): Long
    fun nanoTime(): Long
}

/** Where blocking work goes: the JVM's IO pool; a browser, which has one thread, its only dispatcher. */
expect val Dispatchers.IO: CoroutineDispatcher

/** A task to run: java.lang.Runnable on the JVM, which Android's Handler takes. */
expect fun interface Runnable {
    fun run()
}

/**
 * Stop this thread for [ms]. The shared code does it on the main thread, to
 * let the engine's queue drain between batches of parameters; a browser, which
 * cannot sleep a thread, waits on the clock for as long.
 */
expect fun sleepMs(ms: Long)

/**
 * Work taken in turn, off the caller's thread where there are threads to be
 * had. A task may suspend - on an engine call handed to a thread of its own,
 * in a browser - and the next waits until it is done.
 */
expect class SerialWorker(name: String) {
    fun execute(task: suspend () -> Unit)
}

/** [task] on a thread of its own - or, in a browser, soon on the only one. */
expect fun runInBackground(name: String, task: () -> Unit)

/** Whether [e] is the engine's library not being there at all: a unit test, or a tool. */
expect fun isEngineMissing(e: Throwable): Boolean

/** [block] with [lock] held: `synchronized`, where there are threads to hold it against. */
expect inline fun <T> locked(lock: Any, block: () -> T): T

/** A file's date as a list shows it, in the device's own language: "3 Mar 14:05". */
expect fun dayAndTime(millis: Long): String
