package com.rm.acidulous.model

/**
 * Writes the working song to the session file (SongStore.saveSession).
 *
 * [saveSessionLater] encodes and writes off the UI thread where there is one
 * to spare. Saves are one at a time and the newest song wins: an older song
 * still waiting is dropped, and one that loses the race is never written over
 * a newer one.
 *
 * [saveSessionNow] waits for a save in flight, then writes [song] before it
 * returns. For going to the background and quitting, when the process may end
 * right after. Called on the UI thread.
 */
expect fun saveSessionLater(song: Song)

expect fun saveSessionNow(song: Song)
