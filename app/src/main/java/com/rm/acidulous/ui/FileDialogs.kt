package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
    PlainDialog(title = "Songs", onDismiss = onDismiss, dismissLabel = "Close", spacing = 6.dp) {
        if (names.isEmpty()) Text("Nothing saved yet.", color = Acid.colors.textDim, fontSize = 12.sp)
        for (n in names) {
            DialogRow(
                mark = if (n == current) "●" else "♪",
                name = n,
                trailing = if (n == current) "open" else "",
                on = n == current,
                onRemove = { confirm = n },
            ) { onLoad(n) }
        }
    }
    confirm?.let { name ->
        PlainDialog(
            title = "Delete \"$name\"?",
            onDismiss = { confirm = null },
            confirmLabel = "Delete",
            onConfirm = { onDelete(name); confirm = null },
        ) {
            Text(
                "The file is removed. The song stays open if it is the one you are editing.",
                color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }
}

/** Patches for one machine: factory ones first (fixed), then the user's (deletable). */
@Composable
fun PatchBrowserDialog(
    machine: String, factory: List<String>, user: List<String>,
    onLoad: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    PlainDialog(title = "$machine patches", onDismiss = onDismiss, dismissLabel = "Close") {
        if (factory.isNotEmpty()) {
            ListSection("factory") {
                for (n in factory) DialogRow(mark = "◆", name = n) { onLoad(n) }
            }
        }
        ListSection("yours", if (user.isEmpty()) "None saved yet - \"save as…\" on the panel." else "") {
            for (n in user) {
                DialogRow(mark = "◇", name = n, onRemove = { onDelete(n) }) { onLoad(n) }
            }
        }
    }
}

/** What the export is doing; shown until dismissed so the result is read. */
sealed class ExportState {
    data class Running(val seconds: Float, val expectedSeconds: Float) : ExportState()
    data class Done(
        val seconds: Float, val peak: Float, val fileName: String,
        val files: Int = 1, val format: String = "wav", val bits: Int = 24,
    ) : ExportState()
    data class Failed(val error: String) : ExportState()
}

@Composable
fun ExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val running = state is ExportState.Running
    PlainDialog(
        title = "Export",
        // While it renders the only thing to do is stop it, so the one
        // button says so; afterwards the only thing to do is read it.
        onDismiss = { if (running) onCancel() else onDismiss() },
        dismissLabel = if (running) "Cancel" else "OK",
        spacing = 8.dp,
    ) {
        when (state) {
            is ExportState.Running -> {
                val frac = if (state.expectedSeconds > 0f) {
                    (state.seconds / state.expectedSeconds).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Readout("rendering %.1f s of about %.1f s".format(state.seconds, state.expectedSeconds))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }
            is ExportState.Done -> {
                Readout(
                    if (state.files > 1) "%d files in %s/".format(state.files, state.fileName) else state.fileName,
                    good = true,
                )
                if (state.bits > 0) {
                    Readout(
                        "%.1f s · 48 kHz · %s · %s stereo · peak %.3f".format(
                            state.seconds, state.format,
                            if (state.bits == 32) "32-bit float" else "%d-bit".format(state.bits),
                            state.peak,
                        ),
                    )
                } else {
                    Readout(state.format)
                }
            }
            is ExportState.Failed -> Text(
                "Export failed: ${state.error}",
                color = Acid.colors.red, fontSize = 12.sp, lineHeight = 15.sp,
            )
        }
    }
}
