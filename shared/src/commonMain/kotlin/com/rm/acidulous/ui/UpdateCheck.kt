package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.AppHost
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.System
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Looks for a new version once a day and keeps the answer, so the reminder
 * shows on the next start too. Asks GitHub's releases page, and only where
 * the platform does (AppHost.checksForUpdates) and the player hasn't turned
 * it off in Settings.
 */
object UpdateCheck {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val RELEASES = "https://github.com/roge-rm/Acidulous/releases/latest"

    /** A newer version to tell the player about, or null. */
    var newer by mutableStateOf<String?>(null)
        private set

    /** Whether [a] is a later version than [b], comparing "0.11.3" part by part. */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun refresh(current: String) {
        val latest = UiPrefs.updateLatest
        newer = latest?.takeIf { isNewer(it, current) && it != UiPrefs.updateDismissed }
    }

    /** Run once at start. */
    suspend fun run() {
        val host = AppHost.current
        val current = host.versionName ?: return
        if (!host.checksForUpdates || !UiPrefs.updateChecks) {
            newer = null
            return
        }
        refresh(current)
        val now = System.currentTimeMillis()
        if (now - UiPrefs.updateCheckedAt < DAY_MS) return
        val latest = withContext(Dispatchers.Default) { host.latestRelease() } ?: return
        UiPrefs.noteUpdate(latest, now)
        refresh(current)
    }

    fun dismiss() {
        newer?.let { UiPrefs.dismissUpdate(it) }
        newer = null
    }
}

/** A line saying a new version is out, with where to get it and a close. Nothing when there isn't one. */
@Composable
fun UpdateReminder(modifier: Modifier = Modifier) {
    val version = UpdateCheck.newer ?: return
    val uri = LocalUriHandler.current
    Row(
        modifier.fillMaxWidth().background(Acid.colors.card).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.update_out, version), color = Acid.colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(Res.string.update_get), color = Acid.colors.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { runCatching { uri.openUri(UpdateCheck.RELEASES) } }.button(stringResource(Res.string.update_get))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            "×", color = Acid.colors.textDim, fontSize = 16.sp,
            modifier = Modifier.clickable { UpdateCheck.dismiss() }.button(stringResource(Res.string.update_close))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
