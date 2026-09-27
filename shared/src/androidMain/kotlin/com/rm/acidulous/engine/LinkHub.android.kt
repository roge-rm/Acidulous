package com.rm.acidulous.engine

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Android's multicast lock for [LinkHub]. Wi-Fi chips drop multicast while
 * asleep by default, so without it the packets Link uses to find peers never
 * reach the app.
 *
 * Not reference counted, since it's one lock held while Link is switched on.
 */
fun wifiMulticastLock(context: Context): LinkHub.MulticastLock {
    val lock = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("acidulous-link").apply { setReferenceCounted(false) }
    }
    return object : LinkHub.MulticastLock {
        override val isHeld: Boolean get() = lock.getOrNull()?.isHeld == true
        // No Wi-Fi to lock: acquire fails and LinkHub reports it rather than claiming multicast works.
        override fun acquire() = lock.getOrThrow().acquire()
        override fun release() { lock.getOrNull()?.release() }
    }
}
