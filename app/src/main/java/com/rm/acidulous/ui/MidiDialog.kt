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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.midi.MidiHub
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
fun MidiDialog(trackNames: List<String>, onDismiss: () -> Unit) {
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
            { InTab(trackNames) },
            { OutTab() },
            { SyncTab() },
        ),
    )
}

private val TABS = listOf("devices", "in", "out", "sync")

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
        if (ports.isEmpty()) "Plug something in over USB, or scan for a Bluetooth instrument above."
        else "Tap one to start or stop listening to it. ⎓ is a cable, ᛒ is Bluetooth.",
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
        ListSection("found", "Tap one to open it. It then appears above, like anything plugged in.") {
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
private fun InTab(trackNames: List<String>) {
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
        Text(
            "A middle C, and a sweep of the mod wheel, as though a keyboard had sent them.",
            color = Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
        )
    }
}

/** Notes leaving: to whom, how early, and whether they got there in time. */
@Composable
private fun OutTab() {
    ListSection(
        "send to",
        if (MidiHub.destinations.isEmpty()) "Nothing here can be sent to. A keyboard that only plays is input-only."
        else "Tap one to open it. A track only sends if its own switch says so.",
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
        "ahead by",
        "Every message is stamped with the time it should sound, this far before the app's own audio. " +
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
private fun SyncTab() {
    Section(
        "clock out",
        if (MidiHub.clockOut) "Twenty-four pulses a beat go to every open destination, with start, stop and position."
        else "Nothing is sent. External gear keeps its own time.",
    ) {
        Choice("send clock", MidiHub.clockOut) { UiPrefs.chooseClockOut(true) }
        Choice("off", !MidiHub.clockOut) { UiPrefs.chooseClockOut(false) }
    }

    Section(
        "follow",
        if (MidiHub.clockIn) {
            "The song runs at whatever clock arrives. Scene tempo overrides and Smooth ramps do nothing while it does."
        } else {
            "The song keeps its own tempo and ignores an incoming clock."
        },
    ) {
        Choice("follow clock", MidiHub.clockIn) { UiPrefs.chooseExternalSync(true) }
        Choice("off", !MidiHub.clockIn) { UiPrefs.chooseExternalSync(false) }
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
