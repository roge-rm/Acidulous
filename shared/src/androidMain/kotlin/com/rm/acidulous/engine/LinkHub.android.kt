package com.rm.acidulous.engine

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Android's multicast lock for [LinkHub]. **Multicast is off by default on a
 * sleeping Wi-Fi chip**: without this, the discovery packets that find peers
 * are dropped by the hardware before anything in the app sees them.
 *
 * Not reference counted: this is one lock held while a switch is on, not a
 * nesting of borrowers.
 */
fun wifiMulticastLock(context: Context): LinkHub.MulticastLock {
    val lock = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("acidulous-link").apply { setReferenceCounted(false) }
    }
    return object : LinkHub.MulticastLock {
        override val isHeld: Boolean get() = lock.getOrNull()?.isHeld == true
        // No Wi-Fi to lock: acquiring fails, and LinkHub says so rather than claiming multicast.
        override fun acquire() = lock.getOrThrow().acquire()
        override fun release() { lock.getOrNull()?.release() }
    }
}
