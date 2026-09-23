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
                "This deletes the file. If it's the song you have open, it stays open.",
                color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }
}

/**
 * The demo songs: each a different style, and between them most of what the
 * app does. Picking one opens a fresh copy of it in place of the open song,
 * the way loading a song does.
 */
@Composable
fun DemoSongsDialog(current: String, onPick: (com.rm.acidulous.model.Demo) -> Unit, onDismiss: () -> Unit) {
    PlainDialog(title = "Demo songs", onDismiss = onDismiss, dismissLabel = "Close", spacing = 6.dp) {
        for (demo in com.rm.acidulous.model.DemoSongs.all) {
            val open = demo.name == current
            DialogRow(
                mark = if (open) "●" else "♪",
                name = demo.name,
                under = demo.style,
                trailing = if (open) "open" else "",
                on = open,
            ) { onPick(demo) }
        }
    }
}

/**
 * Patches for one machine: a tab per family, and one more for the user's own.
 *
 * A bank of fifty-one in one list is a list nobody reads to the end of, and
 * M45 left several that size. The banks have carried `family=` all along -
 * Mosaic's keys/pad/grain/motion/lead/texture, Pollen's cloud/pitch/bloom -
 * so the shelves already exist and only had to be carried through the
 * generator and drawn.
 *
 * Tabs are in the order the bank introduces them, not alphabetical: a bank is
 * written with its plainest family first and its strangest last, and that is a
 * better order to meet a machine in than one the alphabet chose. A bank with
 * no families at all gets a single "factory" tab rather than an empty row of
 * chips, and a factory patch with no family of its own lands in "other" so
 * that nothing can be made unreachable by a missing attribute.
 *
 * Only the user's tab can delete, which is the whole reason it is a tab of its
 * own rather than a section at the bottom of a long list.
 */
@Composable
fun PatchBrowserDialog(
    machine: String, factory: List<Patch>, user: List<String>,
    onLoad: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    // First-appearance order, and "other" only if something actually needs it.
    val families = remember(factory) {
        val seen = LinkedHashSet<String>()
        for (p in factory) if (p.family.isNotEmpty()) seen.add(p.family)
        val named = seen.toList()
        if (named.isEmpty()) emptyList()
        else named + (if (factory.any { it.family.isEmpty() }) listOf(OTHER) else emptyList())
    }
    val labels = remember(families) {
        (if (families.isEmpty()) listOf("factory") else families) + "user"
    }
    var tab by rememberSaveable(machine) { mutableStateOf(0) }
    if (tab >= labels.size) tab = 0

    val pages: List<@Composable () -> Unit> = labels.mapIndexed { i, label ->
        {
            if (i == labels.lastIndex) {
                if (user.isEmpty()) {
                    Text(
                        "Nothing saved yet - \"save as…\" on the panel.",
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
        title = "$machine patches",
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = "Close",
        chips = { SectionChipsScrolling(labels, tab) { tab = it } },
        pages = pages,
    )
}

/** Where a factory patch with no `family=` of its own is shelved. */
private const val OTHER = "other"

/** What the export is doing; shown until dismissed so the result is read. */
sealed class ExportState {
    data class Running(val seconds: Float, val expectedSeconds: Float) : ExportState()
    data class Done(
        val seconds: Float, val peak: Float, val fileName: String,
        val files: Int = 1, val format: String = "wav", val bits: Int = 24,
        /** Kilobits a second, where the format has a rate rather than a depth. */
        val rate: Int = 0,
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
                if (state.bits > 0 || state.rate > 0) {
                    Readout(
                        "%.1f s · 48 kHz · %s · %s stereo · peak %.3f".format(
                            state.seconds, state.format,
                            when {
                                // A lossy format has no depth to report, and
                                // saying "24-bit" of an MP3 is just wrong.
                                state.rate > 0 -> "%d kbit".format(state.rate)
                                state.bits == 32 -> "32-bit float"
                                else -> "%d-bit".format(state.bits)
                            },
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
