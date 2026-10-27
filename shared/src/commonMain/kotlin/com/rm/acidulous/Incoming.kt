package com.rm.acidulous

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    /** Imports in flight. */
    var running by mutableIntStateOf(0)

    /** True while a dialog an import opened is waiting for an answer. Reads state, so it can be watched. */
    var dialogOpen: () -> Boolean = { false }

    /** True when the last file is done with and the next may be handed over. */
    fun idle(): Boolean = doc == null && running == 0 && !dialogOpen()

    /** A file name no other import is using. */
    fun tempName(stem: String, ext: String): String =
        "$stem-${kotlin.random.Random.nextLong().toULong().toString(36)}.$ext"

    /** A window the system asked for, like the Mac's app menu: the song screen opens it. */
    var window by mutableStateOf<AppWindow?>(null)
}

enum class AppWindow { Settings, About }
