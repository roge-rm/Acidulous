package com.rm.acidulous

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A file another app opened with this one or shared to it, waiting to be
 * imported. The platform fills it (on Android, from the activity's intent),
 * and the UI takes it once the last session is restored, so the song that
 * was open last time doesn't replace it.
 */
object Incoming {
    var doc by mutableStateOf<Doc?>(null)
}
