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
 * How hard the audio thread is working, and whether it just dropped out.
 *
 * [level] is the share of each block's 1333 µs that rendering took, 0..1.
 * [dropped] lights when the xrun counter moves and stays lit for a few seconds
 * so you have time to see it.
 *
 * Shared because the panic button and the editors' bar both show it, and they
 * need the same smoothing to agree.
 */
@Immutable
data class EngineLoad(val level: Float, val dropped: Boolean)

@Composable
fun rememberEngineLoad(): EngineLoad {
    // What's shown, in steps of the bar's height: the smoothed figure moves a
    // little every poll, and a state that changes every poll redraws the
    // screen every poll, which on a slow tablet cost more than the audio.
    var level by remember { mutableStateOf(0f) }
    var dropped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var lastXruns = NativeEngine.xRunCount
        var lit = 0
        var load = 0f
        while (true) {
            // Take the worse of the average and the worst case. The engine's
            // average is a one-pole with a 27 ms memory, so a spike has decayed
            // out of it long before the 200 ms poll and the meter would read
            // low while the audio breaks up. The worst callback since the last
            // poll is measured against its budget and used if it's higher.
            //
            // Where the audio thread can't time itself (a browser, see
            // AppHost.timesAudioPrecisely) only the average is available.
            val budget = NativeEngine.callbackBudgetUs.coerceAtLeast(1)
            val worst = if (com.rm.acidulous.AppHost.current.timesAudioPrecisely) NativeEngine.recentCallbackUs * 100f / budget else 0f
            val now = maxOf(NativeEngine.loadAvg, worst)
            // Smooth it, faster up than down, so the meter is readable.
            load += (now - load) * (if (now > load) 0.6f else 0.2f)
            val target = (load / 100f).coerceIn(0f, 1f)
            if (kotlin.math.abs(target - level) >= LEVEL_STEP) level = kotlin.math.round(target / LEVEL_STEP) * LEVEL_STEP
            val xruns = NativeEngine.xRunCount
            if (xruns != lastXruns) { lastXruns = xruns; lit = 15 }
            if (lit > 0) --lit
            dropped = lit > 0
            delay(200)
        }
    }
    return EngineLoad(level, dropped)
}

/** The smallest change the bar shows: a dp of its 22. */
private const val LEVEL_STEP = 1f / 22f

/**
 * Teal to about half, amber past that, red past 80%, which is roughly where a
 * busy phone starts to drop blocks. A dropout is always red.
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
 * The load bar, at the far end of every header.
 *
 * There's no number next to it. It changed width past 99 and moved the header
 * buttons, and the status line shows the figure anyway.
 *
 * A dropout fills the whole bar, otherwise a dropout at low load would only be
 * a short red stub.
 */
@Composable
fun LoadMeter(modifier: Modifier = Modifier) {
    val c = Acid.colors
    val load = rememberEngineLoad()
    val colour = loadColour(load)
    // Changes many times a second, so TalkBack skips it.
    Canvas(modifier.padding(horizontal = 3.dp).width(5.dp).height(22.dp).silent()) {
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(c.raised, Offset.Zero, size, radius)
        val h = size.height * if (load.dropped) 1f else load.level
        drawRoundRect(colour, Offset(0f, size.height - h), Size(size.width, h), radius)
    }
}
