package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.rm.acidulous.model.Clip
import kotlin.math.max
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/** A clip at a glance: bar lines and its notes, pitch range fitted to the cell. */
@Composable
fun ClipThumbnail(clip: Clip, ticksPerBar: Int, accent: Color, modifier: Modifier = Modifier) {
    // Read in composition and captured: a Canvas lambda draws, it does not
    // compose, so it cannot reach a CompositionLocal itself.
    val c = Acid.colors
    Canvas(modifier) {
        val total = (clip.bars * ticksPerBar).coerceAtLeast(1)
        val pxPerTick = size.width / total
        drawRect(c.card)
        for (b in 1 until clip.bars) {
            val x = b * ticksPerBar * pxPerTick
            drawLine(c.raised, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        if (clip.notes.isEmpty()) return@Canvas
        val lo = clip.notes.minOf { it.pitch }
        val hi = clip.notes.maxOf { it.pitch }
        val span = max(1, hi - lo + 1)
        val rowH = (size.height - 4f) / span
        val noteH = rowH.coerceIn(2f, 6f)
        val color = if (clip.mute) accent.copy(alpha = 0.35f) else accent
        for (n in clip.notes) {
            if (n.tick >= total) continue
            val x = n.tick * pxPerTick
            val w = max(2f, (n.length.coerceAtMost(total - n.tick)) * pxPerTick)
            val y = 2f + (hi - n.pitch) * rowH + (rowH - noteH) / 2f
            drawRect(color, Offset(x, y), Size(w, noteH))
        }
    }
}
