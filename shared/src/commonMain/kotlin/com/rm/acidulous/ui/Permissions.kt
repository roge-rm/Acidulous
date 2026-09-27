package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/**
 * Android runtime permissions, for the microphone and Bluetooth scans.
 * Names are Android's manifest names. On desktop everything is granted.
 */
interface Permissions {
    fun has(name: String): Boolean
    /** Ask for [names]; the answer goes to the callback given to [rememberPermissions]. */
    fun ask(vararg names: String)

    companion object {
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}

/** [onResult] gets whether everything asked for was granted. */
@Composable
expect fun rememberPermissions(onResult: (Boolean) -> Unit): Permissions
