package com.rm.acidulous.model

import com.rm.acidulous.util.Log

// The browser has one thread, so both write here and now.
actual fun saveSessionLater(song: Song) = saveSessionNow(song)

actual fun saveSessionNow(song: Song) {
    runCatching { SongStore.saveSession(song) }.onFailure { Log.w("Acidulous.Session", "session save failed", it) }
}
