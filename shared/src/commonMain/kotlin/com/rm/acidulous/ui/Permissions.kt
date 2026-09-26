package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/**
 * Runtime permissions: Android's, which the microphone and a Bluetooth scan
 * need. A desktop has none to ask for, so everything is already granted
 * there. Names are Android's manifest names.
 */
interface Permissions {
    fun has(name: String): Boolean
    /** Ask for [names]; the answer goes to the callback given to [rememberPermissions]. */
    fun ask(vararg names: String)

    companion object {
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}

/** A way to ask, remembered in the composition; [onResult] hears whether everything asked for was granted. */
@Composable
expect fun rememberPermissions(onResult: (Boolean) -> Unit): Permissions
