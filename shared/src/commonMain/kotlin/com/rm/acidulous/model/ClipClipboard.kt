package com.rm.acidulous.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The clip you last copied, and where it came from.
 *
 * Only kept while the app is running. It isn't saved in `UiPrefs` or in songs.
 *
 * It's Compose state so the paste control shows up as soon as something is
 * copied.
 */
object ClipClipboard {
    /**
     * Stored through [asCopy], so it's already safe to paste anywhere: no
     * freeze pointer and a fresh identity.
     */
    var clip by mutableStateOf<Clip?>(null)
        private set

    /** "Bass · Verse", so the paste control can say what it would paste. */
    var from by mutableStateOf("")
        private set

    fun put(clip: Clip, trackName: String, sceneName: String) {
        this.clip = clip.asCopy()
        from = "$trackName · $sceneName"
    }

    /** A fresh copy each time, so two cells pasted from one copy stay separate. */
    fun take(): Clip? = clip?.asCopy()

    val has: Boolean get() = clip != null
}
