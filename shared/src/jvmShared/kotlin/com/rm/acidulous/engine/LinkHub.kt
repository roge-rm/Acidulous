package com.rm.acidulous.engine

import com.rm.acidulous.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ableton Link, from Kotlin's side of the wall.
 *
 * The session itself lives in the engine; what has to happen up here is the
 * part Android insists on. **Multicast is off by default on a sleeping
 * Wi-Fi chip**: without a `MulticastLock` the discovery packets that find
 * peers are dropped by the hardware before anything in the app sees them,
 * and Link sits there with nought peers and no error - which is why the lock
 * is reported on screen rather than quietly taken.
 *
 * The lock costs battery, so it is held only while Link is switched on.
 */
object LinkHub {
    var enabled by mutableStateOf(false)
        private set
    /** Other machines in the session. */
    var peers by mutableStateOf(0)
        private set
    /** What the session's tempo is, or 0 when nothing is running. */
    var sessionTempo by mutableStateOf(0f)
        private set
    /** Does a peer's play and stop move our transport too? */
    var startStop by mutableStateOf(true)
        private set
    /** Is the Wi-Fi chip letting multicast through? */
    var multicast by mutableStateOf(false)
        private set
    /**
     * How far our bar line is from the session's, in milliseconds. Positive
     * is us ahead. It is the number that says whether this is really
     * working: a tempo can read right while the phase wanders.
     */
    var phaseMs by mutableStateOf(0f)
        private set

    /**
     * What lets multicast through while Link is on: Android's Wi-Fi
     * MulticastLock, set by the platform at startup. Null where nothing
     * filters it (a desktop), which counts as always let through.
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

    /** From the main poll, every 80 ms. Cheap: two atomics and a count. */
    fun poll() {
        if (!enabled) return
        val packed = NativeEngine.linkStatus()
        peers = (packed shr 32).toInt()
        sessionTempo = (packed and 0xffffffffL).toInt() / 100f
        multicast = multicastLock?.isHeld ?: true
        // The engine publishes the phase error in the same packing the MIDI
        // follower uses, because it is the same question asked of a
        // different master.
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
