package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.AppHost
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.safeFileName
import com.rm.acidulous.engine.uniqueIn
import com.rm.acidulous.io.*
import com.rm.acidulous.model.VenueRooms
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** The file types Venue's "open…" offers. The decoder checks the bytes, so this is a hint. */
private val IMPULSE_TYPES = arrayOf(
    "audio/*",
    "audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/wave",
    "audio/aiff", "audio/x-aiff",
    "audio/flac", "audio/x-flac",
)

/** The longest impulse kept on import, in seconds: twice Venue's longest length, for a stretch of one half. */
private const val IMPULSE_SECONDS = 20

/** Your impulses, by their path under the user folder, sorted by name. */
private fun yourImpulses(): List<String> {
    val dir = File(EngineAssets.userRoot(), VenueRooms.FOLDER)
    return (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.extension.lowercase() in setOf("wav", "flac", "aif", "aiff") }
        .map { "${VenueRooms.FOLDER}/${it.name}" }
        .sortedBy { it.lowercase() }
}

/**
 * Venue's impulse card: which impulse it plays, a list of ours and yours to
 * pick from, "open…" to bring in a WAV, FLAC or AIFF file, which is
 * copied into your impulses and picked, and "capture…" to record a room's.
 */
@Composable
internal fun VenueImpulseCard(impulse: String, onPick: (String) -> Unit) {
    Group("impulse", perLine = 3, centred = true, background = Acid.colors.cardAlt) {
        VenueImpulseControls(impulse, onPick)
    }
}

/**
 * The room capture window open on a screen, as what to do with a saved
 * impulse; null when it's closed. The edit screen provides one above its
 * upright and turned layouts. Null means each card keeps its own.
 */
internal val LocalRoomCapture = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<((String) -> Unit)?>?> { null }

/** The card's buttons: the room, "open…" and "capture…". */
@Composable
internal fun VenueImpulseControls(impulse: String, onPick: (String) -> Unit) {
    val c = Acid.colors
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val picker = com.rm.acidulous.rememberOpenDocument { doc ->
        if (doc == null) return@rememberOpenDocument
        importing = true
        failed = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(EngineAssets.userRoot(), VenueRooms.FOLDER).apply { mkdirs() }
                    val dest = File(dir, uniqueIn(dir, safeFileName(AppHost.current.docName(doc, "impulse.wav"), "impulse.wav")))
                    AppHost.current.copyFromDoc(doc, dest)
                    val converted = NativeEngine.importAudio(dest.absolutePath, IMPULSE_SECONDS)
                    if (converted.isFailure) dest.delete()
                    "${VenueRooms.FOLDER}/${File(converted.getOrThrow().path).name}"
                }
            }
            importing = false
            result.onSuccess { onPick(it) }.onFailure { failed = it.message.orEmpty() }
        }
    }
    val none = stringResource(Res.string.venue_none)
    val shown = VenueRooms.displayName(impulse).ifEmpty { none }
    Box {
        ButtonSquare(
            panelWord("room"), if (importing) "…" else shown.take(14), lit = false,
        ) { menu = true }
        val scroll = rememberScrollState()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ScaledMenu(scroll) {
                val yours = remember(menu) { yourImpulses() }
                @Composable
                fun heading(text: String) = Text(
                    text, color = c.teal, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
                )
                @Composable
                fun item(text: String, setting: String) = DropdownMenuItem(
                    text = { Text(text, fontSize = 12.sp, color = if (setting == impulse) c.accent else c.text) },
                    onClick = { menu = false; onPick(setting) },
                )
                heading(stringResource(Res.string.venue_rooms))
                for (room in VenueRooms.ROOMS) {
                    val setting = VenueRooms.FACTORY + room
                    item(VenueRooms.displayName(setting), setting)
                }
                if (yours.isNotEmpty()) {
                    heading(stringResource(Res.string.venue_yours))
                    for (path in yours) item(VenueRooms.displayName(path), path)
                }
                item(none, "")
            }
        }
    }
    ButtonSquare("", stringResource(Res.string.venue_open), lit = false) { picker(IMPULSE_TYPES) }
    // The edit screen holds the capture window above its layouts, so a turn keeps it open.
    val held = LocalRoomCapture.current
    var capturing by remember { mutableStateOf(false) }
    ButtonSquare("", stringResource(Res.string.venue_capture), lit = false) {
        if (held != null) held.value = onPick else capturing = true
    }
    if (held == null && capturing) RoomCaptureDialog(onDismiss = { capturing = false }, onSaved = onPick)
    failed?.let { why ->
        Text(stringResource(Res.string.venue_open_failed, why), color = c.red, fontSize = 10.sp, maxLines = 2)
    }
}
