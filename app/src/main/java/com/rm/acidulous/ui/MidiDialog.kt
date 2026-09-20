package com.rm.acidulous.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Mapping
import com.rm.acidulous.model.Mappings
import com.rm.acidulous.model.Song
import com.rm.acidulous.ui.theme.Acid

/**
 * Everything MIDI, in four tabs.
 *
 * It began as one page listing whatever was plugged in, and grew a direction
 * at a time until it was a scroll nobody could find anything in. The four
 * tabs are the four questions actually being asked: what is connected, what
 * comes in, what goes out, and who is keeping time. They use the shared
 * window shell, so this looks like the machine picker and the settings
 * window rather than like a third thing.
 *
 * Every tab ends in numbers. The Bluetooth bug that cost an evening was
 * invisible because a wrong service UUID looks exactly like an empty room,
 * and neither direction of MIDI can be tested from here at all - so each
 * one says out loud what it thinks is happening.
 */
@Composable
fun MidiDialog(song: Song, onDismiss: () -> Unit) {
    val trackNames = song.tracks.map { it.name }
    // Read here, in the ordinary composition, and handed down.
    //
    // The pages are subcomposed inside a SubcomposeLayout's measure block so
    // the window can size itself to the tallest of them, and a value that
    // changes on its own - which member channels are holding a note - does
    // not reliably re-measure from in there. Read at this level it is a
    // plain state read, the window recomposes, and the page is rebuilt.
    val mpeHeld = MidiHub.mpeHeld
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { MidiHub.refresh() }

    TabbedDialog(
        title = "MIDI",
        selected = tab,
        onDismiss = { MidiHub.stopScan(); onDismiss() },
        spacing = 14.dp,
        chips = { SectionChips(TABS, tab) { tab = it } },
        pages = listOf(
            { DevicesTab(context) },
            { InTab(trackNames, mpeHeld) },
            { MapTab(song) },
            { OutTab() },
            { SyncTab(context) },
        ),
    )
}

// Everything arriving first and together - what is plugged in, where its
// notes land, what its knobs drive - then what leaves, then the clock, which
// is the one thing that goes both ways. `map` was on the far side of `out`,
// which put two halves of the same question either side of an unrelated one.
private val TABS = listOf("devices", "in", "map", "out", "sync")

/** What is plugged in, and the hunt for what is not. */
@Composable
private fun DevicesTab(context: android.content.Context) {
    val ports = MidiHub.ports
    val found = MidiHub.discovered
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) MidiHub.scanBluetooth(context)
    }

    if (!MidiHub.supported) {
        Text("This device has no MIDI support.", color = Acid.colors.red, fontSize = 12.sp)
    }

    // Scanning is the only thing on this tab you *do*; the rest is what came
    // back. A cable appears in the list by itself, so the one case where you
    // opened this window to act - a Bluetooth instrument that is not here
    // yet - should not be below the list of things that are.
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            val missing = MidiHub.bluetoothPermissions().filter {
                context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) MidiHub.scanBluetooth(context) else permission.launch(missing.toTypedArray())
        }) {
            Text(if (MidiHub.scanning) "scanning…" else "scan for bluetooth",
                color = Acid.colors.accent, fontSize = 12.sp)
        }
        if (MidiHub.scanning) {
            TextButton(onClick = { MidiHub.stopScan() }) { Text("stop", fontSize = 12.sp) }
        }
        if (!MidiHub.bluetoothReady(context)) {
            Text("Bluetooth is off", color = Acid.colors.red, fontSize = 11.sp)
        }
    }
    // Why the list looks the way it does. A scan that finds nothing and says
    // nothing is indistinguishable from a scan that is broken, which is
    // exactly how a wrong service UUID went unnoticed.
    if (MidiHub.scanStatus.isNotEmpty()) Readout(MidiHub.scanStatus)

    ListSection(
        "connected",
        if (ports.isEmpty()) "Plug in over USB, or scan for Bluetooth above."
        else "⎓ cable, ᛒ Bluetooth.",
    ) {
        ports.forEach { port ->
            DialogRow(
                mark = if (port.bluetooth) "ᛒ" else "⎓",
                name = port.name,
                under = port.maker,
                trailing = if (port.open) "listening" else "tap to open",
                on = port.open,
            ) { MidiHub.toggle(port.id) }
        }
        if (ports.isEmpty()) Text("nothing connected", color = Acid.colors.textDim, fontSize = 12.sp)
    }

    if (found.isNotEmpty()) {
        ListSection("found") {
            found.forEach { device ->
                // A device that actually advertised the MIDI service is worth
                // saying so about: in a widened scan everything else is a guess.
                DialogRow(
                    mark = if (device.midi) "ᛒ" else "·",
                    name = device.name,
                    under = if (device.midi) device.address else device.address + "  (no MIDI service)",
                    trailing = "connect",
                    monoUnder = true,
                ) { MidiHub.connectBluetooth(context, device.address) }
            }
        }
    }
}

/** Notes arriving: where they land, and proof that they do. */
@Composable
private fun InTab(trackNames: List<String>, mpeHeld: Int) {
    // **The question, then its answer, then the protocol.** MPE used to sit
    // in the middle: five controls for a whole specification standing between
    // "where arriving notes go" and the readout that tells you whether
    // anything is arriving at all - so with a zone on, the answer to the
    // first question was below the fold, under settings belonging to a
    // different one. MPE is an *in* concern and belongs on this tab; it just
    // does not belong first.
    MidiRoutingSection(trackNames)
    ListSection("is anything arriving?") {
        Readout(
            if (MidiHub.received == 0) "nothing received yet"
            else "${MidiHub.received} messages · ${MidiHub.lastMessage}",
            good = MidiHub.received > 0,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { MidiHub.testNote() }) {
                Text("test note", color = Acid.colors.accent, fontSize = 12.sp)
            }
            TextButton(onClick = { MidiHub.testWheel() }) {
                Text("test wheel", color = Acid.colors.accent, fontSize = 12.sp)
            }
        }
    }
    MpeSection(mpeHeld)
}

/** Notes leaving: to whom, how early, and whether they got there in time. */
@Composable
private fun OutTab() {
    ListSection(
        "send to",
        if (MidiHub.destinations.isEmpty()) "A keyboard that only plays is input-only."
        else "A track only sends if its own switch says so.",
    ) {
        MidiHub.destinations.forEach { dest ->
            DialogRow(
                mark = "→",
                name = dest.name,
                trailing = if (dest.open) "sending" else "tap to open",
                on = dest.open,
            ) { MidiHub.toggleDestination(dest.id) }
        }
        if (MidiHub.destinations.isEmpty()) {
            Text("nothing to send to", color = Acid.colors.textDim, fontSize = 12.sp)
        }
    }

    ListSection(
        "send ahead by",
        "Raise it if the external part drags behind what you hear.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { UiPrefs.chooseMidiOffset(MidiHub.outOffsetMs - 1) },
                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("−", fontSize = 14.sp) }
            Text("%d ms".format(MidiHub.outOffsetMs), color = Acid.colors.text, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
            TextButton(onClick = { UiPrefs.chooseMidiOffset(MidiHub.outOffsetMs + 1) },
                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+", fontSize = 14.sp) }
        }
    }

    // The numbers to report when something sounds loose. "late" is how far
    // past its own timestamp a message was handed to the framework: if that
    // grows, the trim is not the problem.
    ListSection("is it getting out?") {
        Readout(
            "${MidiHub.produced} out · ${MidiHub.sent} sent · late %.1f ms".format(MidiHub.outLateMs) +
                (if (MidiHub.anchored) " · timed to the audio" else " · no anchor yet"),
            good = MidiHub.sent > 0 && MidiHub.anchored,
        )
    }
}

/** Who keeps time: this app, or something else. */
@Composable
private fun SyncTab(context: android.content.Context) {
    Section(
        "clock out",
        if (MidiHub.clockOut) "24 ppq to every open destination, with start, stop and position."
        else "",
    ) {
        Choice("send clock", MidiHub.clockOut) { UiPrefs.chooseClockOut(true) }
        Choice("off", !MidiHub.clockOut) { UiPrefs.chooseClockOut(false) }
    }

    Section(
        "follow external clock",
        when {
            com.rm.acidulous.engine.LinkHub.enabled ->
                "Link has the tempo. Switching this on turns Link off."
            MidiHub.clockIn -> "Scene tempos and Smooth ramps do nothing meanwhile."
            else -> ""
        },
    ) {
        Choice("follow clock", MidiHub.clockIn && !com.rm.acidulous.engine.LinkHub.enabled) {
            // One master at a time, on screen as well as in the engine.
            if (com.rm.acidulous.engine.LinkHub.enabled) {
                UiPrefs.chooseLink(false)
                com.rm.acidulous.engine.LinkHub.setEnabled(context, false)
            }
            UiPrefs.chooseExternalSync(true)
        }
        Choice("off", !MidiHub.clockIn || com.rm.acidulous.engine.LinkHub.enabled) { UiPrefs.chooseExternalSync(false) }
    }

    // A tempo can look right while the phase wanders, so the second number
    // is the one that says whether this is really working: it is the last
    // pulse's distance from where the loop expected it.
    if (MidiHub.clockIn) {
        ListSection("what is arriving") {
            Readout(
                if (MidiHub.followBpm <= 0f) {
                    "following · nothing coming in"
                } else {
                    "following %.2f bpm · %s · phase %+.2f ms".format(
                        MidiHub.followBpm,
                        if (MidiHub.followLocked) "locked" else "settling",
                        MidiHub.followErrorMs,
                    )
                },
                good = MidiHub.followLocked,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { MidiHub.testClock() }) {
                    Text("test clock", color = Acid.colors.accent, fontSize = 12.sp)
                }
                Text("ten seconds of a perfect 120, from inside the app", color = Acid.colors.textDim, fontSize = 11.sp)
            }
        }
    }
}

/**
 * What the hardware has been pointed at.
 *
 * Mapping is learned by touching things, not by filling in a table, so this
 * is a receipt rather than an editor: it says what is bound, which notes are
 * no longer free to play, and offers the one destructive thing that has no
 * home on a knob - forget the lot.
 *
 * The song's mappings and the device's are listed apart because they behave
 * differently: the device's travel with the controller and the song's travel
 * with the music, and when both claim a controller the song wins.
 */
@Composable
private fun MapTab(song: Song) {
    val device = UiPrefs.mappings
    ListSection("mapping mode") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { UiPrefs.chooseMapMode(!UiPrefs.mapMode) }) {
                Text(
                    if (UiPrefs.mapMode) "mapping: on" else "mapping: off",
                    color = if (UiPrefs.mapMode) Acid.colors.accent else Acid.colors.textMid,
                    fontSize = 12.sp,
                )
            }
        }
        // Four lines cut to one. What survives is the half nothing on screen
        // can tell you: that the mode has a gesture of its own, and where.
        // The rest - tap a control, then move the knob that should drive it -
        // is what you find out the moment you turn it on, because every
        // mappable control lights up and says so.
        Text(
            "Also on a long press of redo (↷), anywhere.",
            color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
        )
    }

    MappingList("this song", song.mappings, song)
    MappingList("this device", device, song)

    ListSection("notes in use") {
        val claimed = Mappings.claimedNotes(song, device)
        Readout(
            if (claimed.isEmpty()) "none - every note still plays"
            else claimed.joinToString(", ") { Mapping(note = it).sourceLabel().removePrefix("note ") } +
                " · these fire their mapping instead of sounding",
            good = claimed.isEmpty(),
        )
    }

    ListSection("reset") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = device.isNotEmpty(),
                onClick = { UiPrefs.chooseMappings(emptyList()); UiPrefs.chooseMapWaiting(null) },
            ) { Text("forget device mappings", color = Acid.colors.red, fontSize = 12.sp) }
        }
        Readout("${device.size} on this device, ${song.mappings.size} in this song")
    }
}

@Composable
private fun MappingList(title: String, mappings: List<Mapping>, song: Song) {
    ListSection(title) {
        if (mappings.isEmpty()) {
            Readout("nothing mapped")
            return@ListSection
        }
        for (m in mappings.sortedWith(compareBy({ it.cc ?: 1000 }, { it.note ?: 1000 }))) {
            val track = m.rack?.let { song.tracks.getOrNull(it) } ?: song.tracks.firstOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.sourceLabel().padEnd(10),
                    color = Acid.colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text(
                    m.targetLabel(track) + if (m.rack != null) "  (track ${m.rack + 1})" else "",
                    color = Acid.colors.text, fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * MPE: one instrument played on many channels, a finger to each.
 *
 * A zone is a property of what is plugged in rather than of the music, so
 * it lives here with the routing and not in the song. While one is on, the
 * member channels are fingers and cannot also be tracks - routing still
 * chooses *which* track the zone plays, but by-channel has nothing left to
 * mean.
 */
@Composable
private fun MpeSection(mpeHeld: Int) {
    val zone = MidiHub.mpeZone
    Section(
        "mpe",
        when (zone) {
            1 -> "Channel 1 is the zone; the ${MidiHub.mpeMembers} above it are fingers."
            2 -> "Channel 16 is the zone; the ${MidiHub.mpeMembers} below it are fingers."
            else -> ""
        },
    ) {
        Choice("off", zone == 0) { UiPrefs.chooseMpe(zone = 0) }
        Choice("lower", zone == 1) { UiPrefs.chooseMpe(zone = 1) }
        Choice("upper", zone == 2) { UiPrefs.chooseMpe(zone = 2) }
    }
    if (zone != 0) {
        SliderSection(
            "member channels", "${MidiHub.mpeMembers}",
            "15 unless the controller says otherwise.",
            (MidiHub.mpeMembers - 1) / 14f, 0f..1f, steps = 13,
        ) { UiPrefs.chooseMpe(members = (it * 14f).toInt() + 1) }
        SliderSection(
            "bend range", "±%.0f st".format(MidiHub.mpeBendSemis),
            "Per finger. The spec says 48; many controllers ship 24.",
            (MidiHub.mpeBendSemis - 1f) / 95f, 0f..1f,
        ) { UiPrefs.chooseMpe(bendSemis = 1f + it * 95f) }
        Section(
            "cc 74 is",
            if (MidiHub.mpeTimbre) "CC 74 drives each machine's slide knob."
            else "CC 74 is an ordinary controller, free to be mapped.",
        ) {
            Choice("timbre", MidiHub.mpeTimbre) { UiPrefs.chooseMpe(timbre = true) }
            Choice("plain cc", !MidiHub.mpeTimbre) { UiPrefs.chooseMpe(timbre = false) }
        }
        ListSection("is it reaching the voices?") {
            val mask = mpeHeld
            val held = (0 until 16).filter { (mask shr it) and 1 == 1 }.map { it + 1 }
            Readout(
                if (held.isEmpty()) "no member channel is holding a note"
                else "channels ${held.joinToString(", ")} are holding a note",
                good = held.isNotEmpty(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { MidiHub.testMpe() }) {
                    Text("test mpe", color = Acid.colors.accent, fontSize = 12.sp)
                }
            }
            Text(
                "Two notes on the first two member channels, then a bend, a press and a slide " +
                    "on the first of them only. If both notes move, the expression is not " +
                    "reaching the voice that owns it.",
                color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }
}
