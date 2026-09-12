package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.delay

/**
 * How hard the audio thread is working, and whether it has just failed.
 *
 * [level] is the share of each block's 1333 µs that rendering it actually
 * took, 0..1 - the figure that says how much room is left before the phone
 * starts dropping blocks. [dropped] is a dropout: it lights when the xrun
 * counter moves and stays lit for a few seconds, because the sound of one
 * is gone before you look up.
 *
 * Shared rather than duplicated, because two things show it now: the
 * panic button fills with it, and the editors carry a bar. The smoothing
 * has to be the same in both or they disagree on screen.
 */
@Immutable
data class EngineLoad(val level: Float, val dropped: Boolean)

@Composable
fun rememberEngineLoad(): EngineLoad {
    var load by remember { mutableStateOf(0f) }
    var dropped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var lastXruns = NativeEngine.xRunCount
        var lit = 0
        while (true) {
            // A slow follower upward and a slower one down: the raw figure
            // flickers by several percent a block, and a meter that will
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
    return EngineLoad((load / 100f).coerceIn(0f, 1f), dropped)
}

/**
 * Teal to about half, amber past that, red past four fifths - which is
 * roughly where a phone with anything else running starts to miss. A
 * dropout is red whatever the load, because it has already happened.
 */
@Composable
fun loadColour(load: EngineLoad): Color {
    val c = Acid.colors
    return when {
        load.dropped -> c.red
        load.level > 0.8f -> c.red
        load.level > 0.5f -> c.accent
        else -> c.teal
    }
}

/**
 * The bar, for a header with no panic button in it.
 *
 * The number that used to sit beside this is gone. It was the widest thing
 * in a run that the header packs around the camera hole, and it changed
 * width as it ticked past 99, which moved the buttons; and what it answered
 * - "how much room is left" - the status line answers too, without costing
 * the header anything. What is left is the part you read at a glance.
 */
@Composable
fun LoadMeter(modifier: Modifier = Modifier) {
    val c = Acid.colors
    val load = rememberEngineLoad()
    val colour = loadColour(load)
    Canvas(modifier.padding(horizontal = 3.dp).width(5.dp).height(22.dp)) {
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(c.raised, Offset.Zero, size, radius)
        val h = size.height * load.level
        drawRoundRect(colour, Offset(0f, size.height - h), Size(size.width, h), radius)
    }
}
