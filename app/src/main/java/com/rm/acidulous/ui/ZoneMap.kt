package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Zone
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The map itself: key across, velocity up, one rectangle per zone. Tapping a
 * rectangle selects it. This is the one view a sampler cannot do without, and
 * it is the reason Mosaic's map section puts a picture above its knobs rather
 * than only knobs.
 */
@Composable
fun ZoneMapView(
    zones: List<Zone>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    val pick by rememberUpdatedState(onSelect)
    val list by rememberUpdatedState(zones)
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.bg)
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    val key = (at.x / size.width * 128f).toInt().coerceIn(0, 127)
                    val vel = (127f - at.y / size.height * 127f).toInt().coerceIn(0, 127)
                    // Topmost match wins, so overlapping layers stay reachable.
                    val hit = list.indexOfLast { key >= it.lowKey && key <= it.highKey && vel >= it.lowVel && vel <= it.highVel }
                    if (hit >= 0) pick(hit)
                }
            },
    ) {
        val w = size.width / 128f
        val h = size.height / 127f
        // Octave lines, so the keyboard is readable at a glance.
        for (key in 0..127 step 12) {
            drawRect(c.controlAlt, Offset(key * w, 0f), Size(1f, size.height))
        }
        list.forEachIndexed { i, z ->
            val x = z.lowKey * w
            val y = (127 - z.highVel) * h
            val rw = ((z.highKey - z.lowKey + 1) * w).coerceAtLeast(2f)
            val rh = ((z.highVel - z.lowVel + 1) * h).coerceAtLeast(2f)
            val on = i == selected
            drawRect(if (on) c.zoneOn else c.zoneOff, Offset(x, y), Size(rw, rh))
            drawRect(if (on) c.teal else c.zoneOffEdge, Offset(x, y), Size(rw, rh),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (on) 2f else 1f))
        }
        // Middle C, the reference everyone reads a map against.
        drawRect(c.accent, Offset(60 * w, size.height - 3f), Size(w, 3f))
    }
}

/** Editing one zone: the fields a map needs and nothing else. */
@Composable
fun ZoneDialog(zone: Zone, onDismiss: () -> Unit, onConfirm: (Zone) -> Unit, onDelete: () -> Unit) {
    var z by remember(zone) { mutableStateOf(zone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(z.name.ifEmpty { "Zone" }, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Field("low key", z.lowKey, 0, 127) { z = z.copy(lowKey = it, highKey = maxOf(it, z.highKey)) }
                Field("high key", z.highKey, 0, 127) { z = z.copy(highKey = it, lowKey = minOf(it, z.lowKey)) }
                Field("root key", z.rootKey, 0, 127) { z = z.copy(rootKey = it) }
                Field("low vel", z.lowVel, 0, 127) { z = z.copy(lowVel = it, highVel = maxOf(it, z.highVel)) }
                Field("high vel", z.highVel, 0, 127) { z = z.copy(highVel = it, lowVel = minOf(it, z.lowVel)) }
                FloatField("tune", z.tuneCents, -1200f, 1200f, "%.0f¢") { z = z.copy(tuneCents = it) }
                FloatField("gain", z.gain, 0f, 2f, "%.2f") { z = z.copy(gain = it) }
                FloatField("pan", z.pan, -1f, 1f, "%+.2f") { z = z.copy(pan = it) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("loop", color = Acid.colors.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 8.dp))
                    TextButton(onClick = { z = z.copy(loop = !z.loop) }) { Text(if (z.loop) "on" else "off") }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(z) }) { Text("OK") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Acid.colors.red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Field(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("%-9s %3d".format(label, value), color = Acid.colors.textHi, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(), modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun FloatField(label: String, value: Float, min: Float, max: Float, fmt: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("%-9s %s".format(label, fmt.format(value)), color = Acid.colors.textHi, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max,
            modifier = Modifier.fillMaxWidth().height(28.dp))
    }
}
