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
        // An audio cell, over the same bar lines.
        //
        // **The lanes share the height rather than each taking a band of their
        // own**, because four bands in a cell forty pixels tall is ten pixels a
        // waveform, and a waveform ten pixels tall is a smudge. Stacked on top
        // of each other, translucent, they read as what they are: layers of the
        // same passage, and the loud lane is the bright one.
        clip.audio?.let { audio ->
            val lanes = (0 until 4).mapNotNull { audio.lane(it) }.filter { it.peaks.size >= 4 }
            if (lanes.isEmpty()) return@let
            val mid = size.height / 2f
            val half = size.height / 2f - 2f
            val alpha = if (clip.mute) 0.25f else (0.9f / lanes.size + 0.25f).coerceAtMost(0.85f)
            for (take in lanes) {
                // Where in the cell the take begins, which a punch-in moves,
                // and how much of the cell it covers.
                //
                // **Both the width and how much of the shape to draw come from
                // the take's own length**, not from the cell's. The peaks
                // describe the whole file; a cell two bars long holding an
                // eight-bar take must draw the first quarter of them across its
                // whole width, and one holding a two-beat take must draw all of
                // them across an eighth of it. Drawing the whole shape across
                // the whole cell - which is what a first pass does - makes every
                // take look exactly as long as the cell it is in, which is the
                // one thing the picture is there to say.
                val takeTicks = take.lengthTicks()
                // **The cell's width is the take's whole cycle, not one pass of
                // the clip.** A scene set to repeat twice plays a tape straight
                // through both passes - that is the whole of `rackCycleTick` -
                // so a cell drawn against `clip.bars` would show the first half
                // of what it sounds and hide the rest. `ticks` on the take is
                // the cycle it was recorded against, which is exactly this
                // number, and it is stored for want of anywhere else that knows
                // the repeat.
                val axis = if (take.ticks > 0) take.ticks else total
                val from = (take.startTick.toFloat() / axis).coerceIn(0f, 1f) * size.width
                val span = size.width * takeTicks / axis
                val width = minOf(span, size.width - from)
                if (width <= 0f) continue
                val all = take.peaks.size / 2
                val columns = (all * width / span).toInt().coerceIn(1, all)
                val colW = width / columns
                for (i in 0 until columns) {
                    val lo = take.peaks[i * 2].coerceIn(-1f, 1f)
                    val hi = take.peaks[i * 2 + 1].coerceIn(-1f, 1f)
                    val top = mid - hi * half
                    val bottom = mid - lo * half
                    drawRect(
                        accent.copy(alpha = alpha),
                        Offset(from + i * colW, top),
                        Size(max(1f, colW), max(1f, bottom - top)),
                    )
                }
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
