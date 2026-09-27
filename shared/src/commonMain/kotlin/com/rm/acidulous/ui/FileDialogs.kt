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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Patch
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.res.*

/**
 * The song browser: every saved song, tap to load, delete asks first. The
 * current song is marked so "delete" can't be mistaken for "discard".
 */
@Composable
fun SongBrowserDialog(
    names: List<String>, current: String,
    onLoad: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<String?>(null) }
    PlainDialog(title = stringResource(Res.string.songs_title), onDismiss = onDismiss, dismissLabel = stringResource(Res.string.close), spacing = 6.dp) {
        WindowWidth(600.dp)
        if (names.isEmpty()) Text(stringResource(Res.string.songs_none), color = Acid.colors.textDim, fontSize = 12.sp)
        for (n in names) {
            DialogRow(
                mark = if (n == current) "●" else "♪",
                name = n,
                trailing = if (n == current) stringResource(Res.string.songs_open) else "",
                on = n == current,
                onRemove = { confirm = n },
            ) { onLoad(n) }
        }
    }
    confirm?.let { name ->
        PlainDialog(
            title = stringResource(Res.string.songs_delete_title, name),
            onDismiss = { confirm = null },
            confirmLabel = stringResource(Res.string.songs_delete),
            onConfirm = { onDelete(name); confirm = null },
        ) {
            Text(
                stringResource(Res.string.songs_delete_note),
                color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }
}

/**
 * Patches for one machine: a tab per family, and one more for the user's own.
 *
 * Families come from the bank's family= attribute. Tabs are in the order the
 * bank introduces them, not alphabetical, since banks list their plainest
 * family first. A bank with no families gets a single "factory" tab, and a
 * factory patch without a family goes in "other" so nothing is unreachable.
 *
 * Only the user's tab can delete patches.
 */
@Composable
fun PatchBrowserDialog(
    machine: String, factory: List<Patch>, user: List<String>,
    onLoad: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    // First-appearance order, and "other" only if something needs it.
    val families = remember(factory) {
        val seen = LinkedHashSet<String>()
        for (p in factory) if (p.family.isNotEmpty()) seen.add(p.family)
        val named = seen.toList()
        if (named.isEmpty()) emptyList()
        else named + (if (factory.any { it.family.isEmpty() }) listOf(OTHER) else emptyList())
    }
    // The families are named by the bank, the three tabs the app adds are ours.
    val labels = (if (families.isEmpty()) listOf(stringResource(Res.string.patches_factory)) else families.map {
        if (it == OTHER) stringResource(Res.string.patches_other) else it
    }) + stringResource(Res.string.patches_user)
    var tab by rememberSaveable(machine) { mutableStateOf(0) }
    if (tab >= labels.size) tab = 0

    val pages: List<@Composable () -> Unit> = labels.mapIndexed { i, label ->
        {
            if (i == labels.lastIndex) {
                if (user.isEmpty()) {
                    Text(
                        stringResource(Res.string.patches_user_none),
                        color = Acid.colors.textDim, fontSize = 12.sp,
                    )
                }
                for (n in user) {
                    DialogRow(mark = "◇", name = n, onRemove = { onDelete(n) }) { onLoad(n) }
                }
            } else {
                val shown = if (families.isEmpty()) factory else {
                    val want = families[i]
                    factory.filter { if (want == OTHER) it.family.isEmpty() else it.family == want }
                }
                for (p in shown) DialogRow(mark = "◆", name = p.name) { onLoad(p.name) }
            }
        }
    }

    TabbedDialog(
        title = stringResource(Res.string.patches_title, machine),
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.close),
        chips = { SectionChipsScrolling(labels, tab) { tab = it } },
        pages = pages,
    )
}

/** The tab for a factory patch with no family=. */
private const val OTHER = "other"

/**
 * Export progress and result, shown until dismissed so the result gets read.
 */
sealed class ExportState {
    data class Running(val seconds: Float, val expectedSeconds: Float) : ExportState()
    data class Done(
        val seconds: Float, val peak: Float, val fileName: String,
        val files: Int = 1, val format: String = "wav", val bits: Int = 24,
        /**
         * Kilobits per second, for formats with a bitrate instead of a bit depth.
         */
        val rate: Int = 0,
        /**
         * The written files as platform handles for the share sheet (content
         * Uris on Android). Empty when there's nothing to share.
         */
        val uris: List<Any> = emptyList(),
        val mime: String = "*/*",
    ) : ExportState()
    data class Failed(val error: String) : ExportState()
}

@Composable
fun ExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit, onShare: (ExportState.Done) -> Unit = {}) {
    val running = state is ExportState.Running
    // When it's done, the files can go straight to the share sheet.
    val done = (state as? ExportState.Done)?.takeIf { it.uris.isNotEmpty() }
    PlainDialog(
        title = stringResource(Res.string.export_title),
        // While rendering the button cancels, afterwards it closes.
        onDismiss = { if (running) onCancel() else onDismiss() },
        dismissLabel = stringResource(if (running) Res.string.cancel else Res.string.ok),
        confirmLabel = if (done != null) stringResource(Res.string.export_share) else "",
        onConfirm = done?.let { d -> { onShare(d) } },
        spacing = 8.dp,
    ) {
        when (state) {
            is ExportState.Running -> {
                val frac = if (state.expectedSeconds > 0f) {
                    (state.seconds / state.expectedSeconds).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Readout(stringResource(Res.string.export_rendering, state.seconds, state.expectedSeconds))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }
            is ExportState.Done -> {
                Readout(
                    if (state.files > 1) pluralStringResource(Res.plurals.export_files_in, state.files, state.files, state.fileName) else state.fileName,
                    good = true,
                )
                if (state.bits > 0 || state.rate > 0) {
                    Readout(
                        stringResource(
                            Res.string.export_done,
                            state.seconds, state.format,
                            when {
                                // A lossy format has no bit depth, so show its
                                // bitrate.
                                state.rate > 0 -> stringResource(Res.string.export_kbit, state.rate)
                                state.bits == 32 -> stringResource(Res.string.export_bit_float)
                                else -> stringResource(Res.string.export_bit, state.bits)
                            },
                            state.peak,
                        ),
                    )
                } else {
                    Readout(state.format)
                }
            }
            is ExportState.Failed -> Text(
                stringResource(Res.string.export_failed, state.error),
                color = Acid.colors.red, fontSize = 12.sp, lineHeight = 15.sp,
            )
        }
    }
}
