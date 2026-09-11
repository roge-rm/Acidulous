package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.ThemeMode

/**
 * Everything that belongs to the person rather than to the song.
 *
 * One dialog of titled sections, in the same vocabulary as a machine panel:
 * a section is a heading and a row of choices, and a new section is a new
 * [Section] block rather than a new dialog.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val c = Acid.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScrollWithBar(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section("appearance") {
                    Choice("auto", UiPrefs.theme == ThemeMode.Auto) { UiPrefs.chooseTheme(ThemeMode.Auto) }
                    Choice("light", UiPrefs.theme == ThemeMode.Light) { UiPrefs.chooseTheme(ThemeMode.Light) }
                    Choice("dark", UiPrefs.theme == ThemeMode.Dark) { UiPrefs.chooseTheme(ThemeMode.Dark) }
                }
                Text(
                    when (UiPrefs.theme) {
                        ThemeMode.Auto -> "Follows the phone's own light and dark setting."
                        ThemeMode.Light -> "Always light, whatever the phone is set to."
                        ThemeMode.Dark -> "Always dark, whatever the phone is set to."
                    },
                    color = c.textDim, fontSize = 11.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Acid.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** One of a set: filled when it is the one in force. */
@Composable
private fun Choice(label: String, on: Boolean, onPick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent else c.control)
            .clickable(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) c.onAccent else c.textMid, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
