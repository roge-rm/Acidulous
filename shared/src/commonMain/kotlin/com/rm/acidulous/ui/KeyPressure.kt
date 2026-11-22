package com.rm.acidulous.ui

import androidx.compose.ui.unit.dp

/** How far a finger slides up for full pressure. */
val PressureRange = 48.dp

/** Pressure 0..127 for a finger now at [y] that went down at [startY]; up is more. */
fun pressureFor(startY: Float, y: Float, range: Float): Int =
    (((startY - y) / range).coerceIn(0f, 1f) * 127f).toInt()
