package com.rm.acidulous.model

import com.rm.acidulous.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Each save's number, counted up on every call, so a write can tell it's older than one already made. */
private val counter = AtomicLong()
/** The newest song waiting for the save thread, and its number. */
private val waiting = AtomicReference<Pair<Song, Long>?>(null)
/** Held while a song is encoded and written. */
private val writing = Any()
/** The number of the song in the file now. Read and written holding [writing]. */
private var written = 0L

private val saver = Executors.newSingleThreadExecutor { Thread(it, "session save").apply { isDaemon = true } }

private fun write(song: Song, number: Long) {
    synchronized(writing) {
        if (number > written) runCatching { SongStore.saveSession(song) }
            .onSuccess { written = number }
            .onFailure { Log.w("Acidulous.Session", "session save failed", it) }
    }
}

actual fun saveSessionLater(song: Song) {
    waiting.set(song to counter.incrementAndGet())
    saver.execute { waiting.getAndSet(null)?.let { (s, n) -> write(s, n) } }
}

actual fun saveSessionNow(song: Song) {
    val number = counter.incrementAndGet()
    // Anything still waiting is older than this.
    waiting.set(null)
    write(song, number)
}
