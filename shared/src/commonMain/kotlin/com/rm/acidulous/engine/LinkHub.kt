package com.rm.acidulous.engine

import com.rm.acidulous.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ableton Link, Kotlin side.
 *
 * The session runs in the engine. What's needed up here is Android's
 * multicast lock: Wi-Fi chips drop multicast while asleep by default, so
 * without a `MulticastLock` Link finds no peers and reports no error. That's
 * why the lock's state is shown on screen.
 *
 * The lock costs battery, so it's only held while Link is on.
 */
object LinkHub {
    var enabled by mutableStateOf(false)
        private set
    /** Other machines in the session. */
    var peers by mutableStateOf(0)
        private set
    /** The session's tempo, or 0 when nothing is running. */
    var sessionTempo by mutableStateOf(0f)
        private set
    /** Whether a peer's play and stop also start and stop our transport. */
    var startStop by mutableStateOf(true)
        private set
    /** Whether the Wi-Fi chip is letting multicast through. */
    var multicast by mutableStateOf(false)
        private set
    /**
     * How far our bar line is from the session's, in milliseconds. Positive
     * means we're ahead. This shows whether sync really works, since the
     * tempo can look right while the phase drifts.
     */
    var phaseMs by mutableStateOf(0f)
        private set

    /**
     * What lets multicast through while Link is on: Android's Wi-Fi
     * MulticastLock, set by the platform at startup. Null where nothing
     * filters it (desktop), which counts as always let through.
     */
    var multicastLock: MulticastLock? = null

    /** A lock on the Wi-Fi chip's multicast filter: see [multicastLock]. */
    interface MulticastLock {
        val isHeld: Boolean
        fun acquire()
        fun release()
    }

    private const val TAG = "LinkHub"

    fun chooseEnabled(on: Boolean) {
        enabled = on
        if (on) acquire() else release()
        NativeEngine.setLink(on)
        if (!on) {
            peers = 0
            sessionTempo = 0f
        }
    }

    fun chooseStartStop(on: Boolean) {
        startStop = on
        NativeEngine.setLinkStartStop(on)
    }

    /** Called from the main poll every 80 ms. Cheap: two atomics and a count. */
    fun poll() {
        if (!enabled) return
        val packed = NativeEngine.linkStatus()
        peers = (packed shr 32).toInt()
        sessionTempo = (packed and 0xffffffffL).toInt() / 100f
        multicast = multicastLock?.isHeld ?: true
        // The engine publishes the phase error packed the same way as the
        // MIDI clock follower's.
        phaseMs = (NativeEngine.syncState() and 0xffffffffL).toInt() / 1000f
    }

    private fun acquire() {
        val lock = multicastLock ?: run { multicast = true; return }
        if (lock.isHeld) return
        runCatching { lock.acquire() }.onFailure {
            Log.w(TAG, "no multicast lock: peers may never appear", it)
        }
        multicast = lock.isHeld
    }

    private fun release() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicast = false
    }
}
