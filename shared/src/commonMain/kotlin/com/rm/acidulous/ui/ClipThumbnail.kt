package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.lengthTicks
import kotlin.math.max
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/** A clip at a glance: bar lines and its notes, with the pitch range fitted to the cell. */
@Composable
fun ClipThumbnail(clip: Clip, ticksPerBar: Int, accent: Color, modifier: Modifier = Modifier) {
    // Read during composition and captured, because the Canvas lambda draws
    // outside composition and can't read a CompositionLocal.
    val c = Acid.colors
    Canvas(modifier) {
        val total = (clip.bars * ticksPerBar).coerceAtLeast(1)
        val pxPerTick = size.width / total
        drawRect(c.card)
        for (b in 1 until clip.bars) {
            val x = b * ticksPerBar * pxPerTick
            drawLine(c.raised, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        // An audio cell, over the same bar lines.
        //
        // The lanes overlap using the full height rather than each getting a
        // band, since four bands in a small cell would be too thin to read.
        // Drawn translucent on top of each other, the loudest lane is the
        // brightest.
        clip.audio?.let { audio ->
            val lanes = (0 until 4).mapNotNull { audio.lane(it) }.filter { it.peaks.size >= 4 }
            if (lanes.isEmpty()) return@let
            val mid = size.height / 2f
            val half = size.height / 2f - 2f
            val alpha = if (clip.mute) 0.25f else (0.9f / lanes.size + 0.25f).coerceAtMost(0.85f)
            for (take in lanes) {
                // Where in the cell the take starts (a punch-in moves it) and
                // how much of the cell it covers.
                //
                // Both come from the take's own length, not the cell's. The
                // peaks cover the whole file, so a two-bar cell holding an
                // eight-bar take draws the first quarter of them across its
                // width, and a cell holding a two-beat take draws all of them
                // across a small part of it. Otherwise every take would look
                // as long as its cell.
                val takeTicks = take.lengthTicks()
                // The cell's width is the take's whole cycle, not one pass of
                // the clip. A tape plays straight through a scene's repeats
                // (see `rackCycleTick`), so drawing against `clip.bars` would
                // hide the second half. The take's `ticks` is the cycle it was
                // recorded against, stored because nothing else knows the
                // repeat here.
                val axis = if (take.ticks > 0) take.ticks else total
                val from = (take.startTick.toFloat() / axis).coerceIn(0f, 1f) * size.width
                val span = size.width * takeTicks / axis
                val width = minOf(span, size.width - from)
                if (width <= 0f) continue
                val all = take.peaks.size / 2
                val columns = (all * width / span).toInt().coerceIn(1, all)
                drawShape(take.peaks, 0, columns, from, from + width, mid, half, accent.copy(alpha = alpha))
            }
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
