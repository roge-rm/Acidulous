package com.rm.acidulous.desktop

import com.rm.acidulous.midi.MidiWorker
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The desktop's MIDI thread: Android's Handler, as far as the hub uses one,
 * on a scheduled executor - and the waiting for a timestamped send, which a
 * port does itself on Android and nothing does here.
 */
internal class MidiThread : MidiWorker {
    val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "midi").apply { isDaemon = true } }
    private val pending = HashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    override fun post(task: Runnable) = postDelayed(task, 0)

    override fun postDelayed(task: Runnable, delayMs: Long) {
        lateinit var future: ScheduledFuture<*>
        future = executor.schedule({
            synchronized(pending) { pending[task]?.remove(future) }
            task.run()
        }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        synchronized(pending) { pending.getOrPut(task) { mutableListOf() } += future }
    }

    override fun removeCallbacks(task: Runnable) {
        val futures = synchronized(pending) { pending.remove(task) } ?: return
        futures.forEach { it.cancel(false) }
    }

    /** [send] a copy of the bytes at [timestamp], System.nanoTime's base: now, if that has passed. */
    fun sendAt(bytes: ByteArray, offset: Int, count: Int, timestamp: Long, send: (ByteArray, Int, Int) -> Unit) {
        val copy = bytes.copyOfRange(offset, offset + count)
        val waitMs = (timestamp - System.nanoTime()) / 1_000_000
        if (waitMs <= 0) send(copy, 0, copy.size) else postDelayed({ send(copy, 0, copy.size) }, waitMs)
    }

    /** Every [periodMs], for as long as the app runs. */
    fun every(periodMs: Long, task: () -> Unit) {
        executor.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
    }
}
