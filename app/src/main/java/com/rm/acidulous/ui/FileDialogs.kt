package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid

/**
 * The song browser: every saved song, load on tap, delete behind a confirm.
 * The current song is marked so "delete" cannot be mistaken for "discard".
 */
@Composable
fun SongBrowserDialog(
    names: List<String>, current: String,
    onLoad: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Songs") },
        text = {
            Column(Modifier.verticalScrollWithBar(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (names.isEmpty()) Text("Nothing saved yet.", fontSize = 12.sp)
                for (n in names) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onLoad(n) }, modifier = Modifier.weight(1f)) {
                        Text(if (n == current) "● $n" else n, maxLines = 1)
                    }
                    TextButton(onClick = { confirm = n }) { Text("✕", color = Acid.colors.red) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    confirm?.let { name ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("The file is removed. The song stays open if it is the one you are editing.", fontSize = 12.sp) },
            confirmButton = { Button(onClick = { onDelete(name); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Patches for one machine: factory ones first (fixed), then the user's (deletable). */
@Composable
fun PatchBrowserDialog(
    machine: String, factory: List<String>, user: List<String>,
    onLoad: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$machine patches") },
        text = {
            Column(Modifier.verticalScrollWithBar(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (factory.isNotEmpty()) Text("factory", color = Acid.colors.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                for (n in factory) OutlinedButton(onClick = { onLoad(n) }, modifier = Modifier.fillMaxWidth()) { Text(n, maxLines = 1) }
                Text("yours", color = Acid.colors.accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (user.isEmpty()) Text("None saved yet - \"save as…\" on the panel.", fontSize = 12.sp)
                for (n in user) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onLoad(n) }, modifier = Modifier.weight(1f)) { Text(n, maxLines = 1) }
                    TextButton(onClick = { onDelete(n) }) { Text("✕", color = Acid.colors.red) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** What the export is doing; shown until dismissed so the result is read. */
sealed class ExportState {
    data class Running(val seconds: Float, val expectedSeconds: Float) : ExportState()
    data class Done(val seconds: Float, val peak: Float, val fileName: String) : ExportState()
    data class Failed(val error: String) : ExportState()
}

@Composable
fun ExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (state !is ExportState.Running) onDismiss() },
        title = { Text("Export WAV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is ExportState.Running -> {
                        val frac = if (state.expectedSeconds > 0f) (state.seconds / state.expectedSeconds).coerceIn(0f, 1f) else 0f
                        Text("rendering %.1f s of about %.1f s".format(state.seconds, state.expectedSeconds), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    }
                    is ExportState.Done -> Text(
                        "%s\n%.1f s · 48 kHz · 24-bit stereo · peak %.3f".format(state.fileName, state.seconds, state.peak),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    is ExportState.Failed -> Text("Export failed: ${state.error}", fontSize = 12.sp, color = Acid.colors.red)
                }
            }
        },
        confirmButton = {
            if (state is ExportState.Running) TextButton(onClick = onCancel) { Text("Cancel") }
            else Button(onClick = onDismiss) { Text("OK") }
        },
    )
}
