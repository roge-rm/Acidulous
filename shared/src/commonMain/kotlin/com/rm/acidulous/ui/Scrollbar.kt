package com.rm.acidulous.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rm.acidulous.ui.theme.Acid

/**
 * A thin position bar along the far edge of a scrolling container, shown
 * whenever there's something to scroll to. It draws in the container's
 * unscrolled frame, so it must come before the scroll modifier in the chain.
 * Use the two helpers below instead of composing it by hand.
 */
fun Modifier.scrollbar(state: ScrollState, vertical: Boolean = true, color: Color): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val viewport = if (vertical) size.height else size.width
        if (viewport <= 0f) return@drawWithContent
        val content = viewport + max
        val thickness = 3.dp.toPx()
        val inset = 1.dp.toPx()
        val minThumb = 20.dp.toPx()
        // Floor then ceiling. coerceIn(minThumb, viewport) throws when the
        // container is shorter than the minimum thumb, which a squeezed one can
        // be. PianoRoll has the same fix.
        val thumb = (viewport * viewport / content).coerceAtLeast(minThumb).coerceAtMost(viewport)
        val travel = viewport - thumb
        val pos = travel * state.value / max
        val radius = CornerRadius(thickness / 2f)
        if (vertical) drawRoundRect(color, Offset(size.width - thickness - inset, pos), Size(thickness, thumb), radius)
        else drawRoundRect(color, Offset(pos, size.height - thickness - inset), Size(thumb, thickness), radius)
    }

// Composable so the bar can read the theme, which a Modifier factory can't.
// Otherwise it would stay pale grey and vanish on the light theme.
@Composable
fun Modifier.verticalScrollWithBar(state: ScrollState): Modifier =
    scrollbar(state, vertical = true, color = Acid.colors.scrollbar).verticalScroll(state)

@Composable
fun Modifier.horizontalScrollWithBar(state: ScrollState): Modifier =
    scrollbar(state, vertical = false, color = Acid.colors.scrollbar).horizontalScroll(state)
