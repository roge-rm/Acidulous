package com.rm.acidulous.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The clip you last copied, and where it came from.
 *
 * **Not in `UiPrefs`, and not saved.** A clipboard is not a setting - it is
 * what you are in the middle of doing - so it lives for as long as the process
 * does and no longer. Nothing about it belongs in a song file or in the
 * device's preferences.
 *
 * Compose state rather than a plain field, because the paste control has to
 * appear the moment something is copied and there is no other signal that it
 * happened.
 */
object ClipClipboard {
    /**
     * Stored through [asCopy], so what is held is already safe to paste
     * anywhere: no freeze pointer, and a fresh identity. Doing it here rather
     * than at paste means there is one place to reason about it.
     */
    var clip by mutableStateOf<Clip?>(null)
        private set

    /** "Bass · Verse", for the control to say what it would paste. */
    var from by mutableStateOf("")
        private set

    fun put(clip: Clip, trackName: String, sceneName: String) {
        this.clip = clip.asCopy()
        from = "$trackName · $sceneName"
    }

    /** A fresh instance each time, so two cells pasted from one copy stay separable. */
    fun take(): Clip? = clip?.asCopy()

    val has: Boolean get() = clip != null
}
