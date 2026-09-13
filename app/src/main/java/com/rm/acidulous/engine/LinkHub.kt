package com.rm.acidulous.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
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

    private var lock: WifiManager.MulticastLock? = null

    private const val TAG = "LinkHub"

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        if (on) acquire(context) else release()
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
        multicast = lock?.isHeld == true
        // The engine publishes the phase error in the same packing the MIDI
        // follower uses, because it is the same question asked of a
        // different master.
        phaseMs = (NativeEngine.syncState() and 0xffffffffL).toInt() / 1000f
    }

    private fun acquire(context: Context) {
        if (lock?.isHeld == true) return
        val held = runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Not reference counted: this is one lock held while a switch is
            // on, not a nesting of borrowers.
            wifi.createMulticastLock("acidulous-link").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrElse {
            Log.w(TAG, "no multicast lock: peers may never appear", it)
            null
        }
        lock = held
        multicast = held?.isHeld == true
    }

    private fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
        multicast = false
    }
}
