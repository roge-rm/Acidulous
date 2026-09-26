package com.rm.acidulous

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A file another app opened with this one, or shared to it, waiting for the
 * app to be ready to import it. Filled by the platform (on Android, from the
 * activity's intent); taken by the composition once the session is back, so
 * what arrives is not then replaced by the song that was open last time.
 */
object Incoming {
    var doc by mutableStateOf<Doc?>(null)
}
