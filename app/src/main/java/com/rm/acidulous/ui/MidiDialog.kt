package com.rm.acidulous.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.ui.theme.Acid

/**
 * Where a keyboard gets plugged in.
 *
 * USB devices are simply there, so they are listed and switched on. A
 * Bluetooth device has to be hunted: the scan looks for the MIDI service
 * specifically, so what comes back is instruments rather than every phone
 * and pair of headphones in the room. Tapping one opens it, and from then
 * on it appears in the same list as anything plugged in.
 */
@Composable
fun MidiDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ports = MidiHub.ports
    val found = MidiHub.discovered

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) MidiHub.scanBluetooth(context)
    }
    LaunchedEffect(Unit) { MidiHub.refresh() }

    AlertDialog(
        onDismissRequest = { MidiHub.stopScan(); onDismiss() },
        title = { Text("MIDI", fontSize = 15.sp) },
        text = {
            val scroll = rememberScrollState()
            Column(
                Modifier.heightIn(max = 420.dp).verticalScrollWithBar(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!MidiHub.supported) {
                    Text("This device has no MIDI support.", color = Acid.colors.red, fontSize = 12.sp)
                }
                Text("devices", color = Acid.colors.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (ports.isEmpty()) {
                    Text("nothing connected", color = Acid.colors.textDim, fontSize = 12.sp)
                }
                ports.forEach { port ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (port.open) Acid.colors.green.copy(alpha = 0.2f) else Acid.colors.overlay)
                            .clickable { MidiHub.toggle(port.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (port.bluetooth) "ᛒ" else "⎓", color = Acid.colors.accent, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(port.name, color = Acid.colors.text, fontSize = 12.sp, maxLines = 1)
                            if (port.maker.isNotEmpty()) {
                                Text(port.maker, color = Acid.colors.textDim, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                        Text(if (port.open) "listening" else "tap to open",
                            color = if (port.open) Acid.colors.teal else Acid.colors.textDim, fontSize = 10.sp)
                    }
                }

                Text("bluetooth", color = Acid.colors.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val missing = MidiHub.bluetoothPermissions().filter {
                            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) MidiHub.scanBluetooth(context) else permission.launch(missing.toTypedArray())
                    }) { Text(if (MidiHub.scanning) "scanning…" else "scan", color = Acid.colors.accent, fontSize = 12.sp) }
                    if (MidiHub.scanning) {
                        TextButton(onClick = { MidiHub.stopScan() }) { Text("stop", fontSize = 12.sp) }
                    }
                    if (!MidiHub.bluetoothReady(context)) {
                        Text("Bluetooth is off", color = Acid.colors.red, fontSize = 11.sp)
                    }
                }
                // Why the list looks the way it does. A scan that finds
                // nothing and says nothing is indistinguishable from a scan
                // that is broken, which is exactly how a wrong service UUID
                // went unnoticed.
                if (MidiHub.scanStatus.isNotEmpty()) {
                    Text(MidiHub.scanStatus, color = Acid.colors.textDim, fontSize = 10.sp)
                }
                found.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(Acid.colors.overlay)
                            .clickable { MidiHub.connectBluetooth(context, device.address) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A device that actually advertised the MIDI service
                        // is worth saying so about, because in a widened scan
                        // everything else is a guess.
                        Text(if (device.midi) "ᛒ" else "·", color = Acid.colors.accent, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, color = Acid.colors.text, fontSize = 12.sp, maxLines = 1)
                            Text(
                                if (device.midi) device.address else device.address + "  (no MIDI service)",
                                color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text("connect", color = Acid.colors.accent, fontSize = 10.sp)
                    }
                }

                // --- out ---------------------------------------------------
                Text("send to", color = Acid.colors.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp))
                if (MidiHub.destinations.isEmpty()) {
                    Text("nothing to send to", color = Acid.colors.textDim, fontSize = 12.sp)
                }
                MidiHub.destinations.forEach { dest ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (dest.open) Acid.colors.green.copy(alpha = 0.2f) else Acid.colors.overlay)
                            .clickable { MidiHub.toggleDestination(dest.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("→", color = Acid.colors.accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(dest.name, color = Acid.colors.text, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        Text(if (dest.open) "sending" else "tap to open",
                            color = if (dest.open) Acid.colors.teal else Acid.colors.textDim, fontSize = 10.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoutingChip("clock out", MidiHub.clockOut) { UiPrefs.chooseClockOut(!MidiHub.clockOut) }
                    Spacer(Modifier.width(8.dp))
                    Text("ahead", color = Acid.colors.textDim, fontSize = 10.sp)
                    TextButton(onClick = { UiPrefs.chooseMidiOffset(MidiHub.outOffsetMs - 1) },
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("−", fontSize = 14.sp) }
                    Text("%d ms".format(MidiHub.outOffsetMs), color = Acid.colors.text, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { UiPrefs.chooseMidiOffset(MidiHub.outOffsetMs + 1) },
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+", fontSize = 14.sp) }
                }
                // The numbers to report when something sounds loose. A silent
                // failure that looks like an empty room cost an evening once.
                Text(
                    buildString {
                        append("${MidiHub.produced} out · ${MidiHub.sent} sent")
                        append(" · late %.1f ms".format(MidiHub.outLateMs))
                        append(if (MidiHub.anchored) " · timed to the audio" else " · no anchor yet")
                    },
                    color = Acid.colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                )

                Text("routing", color = Acid.colors.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutingChip("selected track", MidiHub.routing == MidiHub.Routing.SelectedTrack) {
                        MidiHub.routing = MidiHub.Routing.SelectedTrack
                    }
                    RoutingChip("channel = track", MidiHub.routing == MidiHub.Routing.ChannelToRack) {
                        MidiHub.routing = MidiHub.Routing.ChannelToRack
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { MidiHub.testNote() }) {
                        Text("test note", color = Acid.colors.accent, fontSize = 12.sp)
                    }
                    Text(
                        if (MidiHub.received == 0) "nothing received yet"
                        else "${MidiHub.received} messages · ${MidiHub.lastMessage}",
                        color = Acid.colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { MidiHub.stopScan(); onDismiss() }) { Text("Done") } },
    )
}

@Composable
private fun RoutingChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Acid.colors.onAccent else Acid.colors.textHi,
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) Acid.colors.teal else Acid.colors.overlay)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
