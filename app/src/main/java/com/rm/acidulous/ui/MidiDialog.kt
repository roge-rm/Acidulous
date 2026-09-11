package com.rm.acidulous.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        title = { Text("MIDI in", fontSize = 15.sp) },
        text = {
            val scroll = rememberScrollState()
            Column(
                Modifier.heightIn(max = 420.dp).verticalScrollWithBar(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!MidiHub.supported) {
                    Text("This device has no MIDI support.", color = Color(0xFFE74C3C), fontSize = 12.sp)
                }
                Text("devices", color = Color(0xFF7FD1B9), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (ports.isEmpty()) {
                    Text("nothing connected", color = Color(0xFF9A9AA2), fontSize = 12.sp)
                }
                ports.forEach { port ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (port.open) Color(0x333F7D5E) else Color(0x22FFFFFF))
                            .clickable { MidiHub.toggle(port.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (port.bluetooth) "ᛒ" else "⎓", color = Color(0xFFFFB454), fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(port.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                            if (port.maker.isNotEmpty()) {
                                Text(port.maker, color = Color(0xFF9A9AA2), fontSize = 9.sp, maxLines = 1)
                            }
                        }
                        Text(if (port.open) "listening" else "tap to open",
                            color = if (port.open) Color(0xFF7FD1B9) else Color(0xFF9A9AA2), fontSize = 10.sp)
                    }
                }

                Text("bluetooth", color = Color(0xFF7FD1B9), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val missing = MidiHub.bluetoothPermissions().filter {
                            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) MidiHub.scanBluetooth(context) else permission.launch(missing.toTypedArray())
                    }) { Text(if (MidiHub.scanning) "scanning…" else "scan", color = Color(0xFFFFB454), fontSize = 12.sp) }
                    if (MidiHub.scanning) {
                        TextButton(onClick = { MidiHub.stopScan() }) { Text("stop", fontSize = 12.sp) }
                    }
                    if (!MidiHub.bluetoothReady(context)) {
                        Text("Bluetooth is off", color = Color(0xFFE74C3C), fontSize = 11.sp)
                    }
                }
                found.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(Color(0x22FFFFFF))
                            .clickable { MidiHub.connectBluetooth(context, device.address) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                            Text(device.address, color = Color(0xFF9A9AA2), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text("connect", color = Color(0xFFFFB454), fontSize = 10.sp)
                    }
                }

                Text("routing", color = Color(0xFF7FD1B9), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
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
                        Text("test note", color = Color(0xFFFFB454), fontSize = 12.sp)
                    }
                    Text(
                        if (MidiHub.received == 0) "nothing received yet"
                        else "${MidiHub.received} messages · ${MidiHub.lastMessage}",
                        color = Color(0xFF9A9AA2), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
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
        color = if (on) Color(0xFF191B1E) else Color(0xFFDDDDE2),
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) Color(0xFF7FD1B9) else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
