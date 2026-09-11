package com.rm.acidulous.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.acidulous.ui.theme.ThemeMode

/**
 * The handful of view settings that belong to the person, not to the track.
 *
 * Folding the automation strip away is a statement about how someone works,
 * not about the clip in front of them, so it follows them to the next track
 * and to the next session rather than resetting each time a screen opens.
 * Kept in one object with Compose-observable state so any screen reading it
 * recomposes when another changes it.
 */
object UiPrefs {
    private var store: SharedPreferences? = null

    var automationFolded by mutableStateOf(false)
        private set

    /** Auto follows the phone; the other two ignore it. */
    var theme by mutableStateOf(ThemeMode.Dark)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        store = p
        automationFolded = p.getBoolean(KEY_AUTO_FOLDED, false)
        // Stored by name: the order of an enum is not a promise.
        theme = runCatching { ThemeMode.valueOf(p.getString(KEY_THEME, null) ?: "Dark") }
            .getOrDefault(ThemeMode.Dark)
    }

    fun foldAutomation(folded: Boolean) {
        automationFolded = folded
        store?.edit()?.putBoolean(KEY_AUTO_FOLDED, folded)?.apply()
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        store?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    private const val KEY_AUTO_FOLDED = "automation_folded"
    private const val KEY_THEME = "theme"
}
