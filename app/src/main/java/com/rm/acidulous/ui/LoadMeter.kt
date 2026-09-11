package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.delay

/**
 * How hard the audio thread is working, in the corner of every header.
 *
 * The number is the engine's own measurement - the share of each block's
 * 1333 µs that rendering it actually took - so it answers the question that
 * matters when a patch gets heavy: how much room is left before the phone
 * starts dropping blocks. The bar is the same number for glancing at, and
 * the dot is a dropout: it lights when the xrun counter moves and stays lit
 * for a few seconds, because the sound of one is gone before you look up.
 *
 * Teal to about half, amber past that, red past four fifths - which is
 * roughly where a phone with anything else running starts to miss.
 */
@Composable
fun LoadMeter(modifier: Modifier = Modifier) {
    val c = Acid.colors
    var load by remember { mutableStateOf(0f) }
    var dropped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var lastXruns = NativeEngine.xRunCount
        var lit = 0
        while (true) {
            // A slow follower upward and a slower one down: the raw figure
            // flickers by several percent a block, and a number that will
            // not sit still cannot be read at all.
            val now = NativeEngine.loadAvg
            load += (now - load) * (if (now > load) 0.6f else 0.2f)
            val xruns = NativeEngine.xRunCount
            if (xruns != lastXruns) { lastXruns = xruns; lit = 15 }
            if (lit > 0) --lit
            dropped = lit > 0
            delay(200)
        }
    }
    val level = (load / 100f).coerceIn(0f, 1f)
    val colour = when {
        dropped -> c.red
        level > 0.8f -> c.red
        level > 0.5f -> c.accent
        else -> c.teal
    }
    Row(modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "%2.0f%%".format(load), color = colour, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
        )
        Canvas(Modifier.padding(start = 3.dp).width(5.dp).height(22.dp)) {
            val radius = CornerRadius(2.dp.toPx())
            drawRoundRect(c.raised, Offset.Zero, size, radius)
            val h = size.height * level
            drawRoundRect(colour, Offset(0f, size.height - h), Size(size.width, h), radius)
        }
    }
}
