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
        val mark = 1.5.dp.toPx()
        drawRect(c.accent, Offset(60 * w, size.height - mark), Size(w, mark))
    }
}

/** Editing one zone: the fields a map needs and nothing else. */
@Composable
fun ZoneDialog(zone: Zone, onDismiss: () -> Unit, onConfirm: (Zone) -> Unit, onDelete: () -> Unit) {
    var z by remember(zone) { mutableStateOf(zone) }
    PlainDialog(
        title = z.name.ifEmpty { "Zone" },
        onDismiss = onDismiss,
        confirmLabel = "OK",
        onConfirm = { onConfirm(z) },
        spacing = 10.dp,
    ) {
        SliderSection("low key", "${z.lowKey}", "", z.lowKey.toFloat(), 0f..127f) {
            z = z.copy(lowKey = it.toInt(), highKey = maxOf(it.toInt(), z.highKey))
        }
        SliderSection("high key", "${z.highKey}", "", z.highKey.toFloat(), 0f..127f) {
            z = z.copy(highKey = it.toInt(), lowKey = minOf(it.toInt(), z.lowKey))
        }
        SliderSection("root key", "${z.rootKey}", "", z.rootKey.toFloat(), 0f..127f) {
            z = z.copy(rootKey = it.toInt())
        }
        SliderSection("low velocity", "${z.lowVel}", "", z.lowVel.toFloat(), 0f..127f) {
            z = z.copy(lowVel = it.toInt(), highVel = maxOf(it.toInt(), z.highVel))
        }
        SliderSection("high velocity", "${z.highVel}", "", z.highVel.toFloat(), 0f..127f) {
            z = z.copy(highVel = it.toInt(), lowVel = minOf(it.toInt(), z.lowVel))
        }
        SliderSection("tune", "%.0f¢".format(z.tuneCents), "", z.tuneCents, -1200f..1200f) {
            z = z.copy(tuneCents = it)
        }
        SliderSection("gain", "%.2f".format(z.gain), "", z.gain, 0f..2f) { z = z.copy(gain = it) }
        SliderSection("pan", "%+.2f".format(z.pan), "", z.pan, -1f..1f) { z = z.copy(pan = it) }

        Section("loop") {
            Choice("off", !z.loop) { z = z.copy(loop = false) }
            Choice("on", z.loop) { z = z.copy(loop = true) }
        }
        // Deleting is not the window's action, so it is not the window's
        // button - a third thing beside OK and Cancel is the one you hit by
        // accident.
        ListSection("remove") {
            DialogRow(mark = "✕", name = "delete this zone", onClick = onDelete)
        }
    }
}

