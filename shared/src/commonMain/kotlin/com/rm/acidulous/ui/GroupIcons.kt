package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.acidulous.model.MachineUi.GroupIcon

/**
 * A machine group's icon for the picker's tabs: line drawings on a 24 unit
 * square, centred on 12,12, in one colour.
 */
@Composable
fun GroupIconView(icon: GroupIcon, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 24f, pivot = Offset.Zero) { drawGroupIcon(icon, color) }
    }
}

private fun DrawScope.drawGroupIcon(icon: GroupIcon, color: Color) {
    val line = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = line)
    val c = 12f
    when (icon) {
        // A saw wave.
        GroupIcon.Synths -> path {
            moveTo(c - 10, c + 4); lineTo(c - 4, c - 5); lineTo(c - 4, c + 4); lineTo(c + 2, c - 5)
            lineTo(c + 2, c + 4); lineTo(c + 8, c - 5); lineTo(c + 8, c + 4)
        }
        // A drum and two sticks.
        GroupIcon.Drums -> {
            drawOval(color, Offset(c - 9, c - 5), Size(18f, 6f), style = line)
            path { moveTo(c - 9, c - 2); lineTo(c - 9, c + 5) }
            path { moveTo(c + 9, c - 2); lineTo(c + 9, c + 5) }
            drawArc(color, 0f, 180f, false, Offset(c - 9, c + 2), Size(18f, 6f), style = line)
            path { moveTo(c - 6, c - 10); lineTo(c - 1, c - 4); moveTo(c + 6, c - 10); lineTo(c + 1, c - 4) }
        }
        // Three white keys and two black.
        GroupIcon.Keys -> {
            drawRoundRect(color, Offset(c - 10, c - 7), Size(20f, 14f),
                androidx.compose.ui.geometry.CornerRadius(1.5f), style = line)
            path { moveTo(c - 3.3f, c - 7); lineTo(c - 3.3f, c + 7); moveTo(c + 3.3f, c - 7); lineTo(c + 3.3f, c + 7) }
            drawRect(color, Offset(c - 5.5f, c - 7), Size(4f, 8f))
            drawRect(color, Offset(c + 1.5f, c - 7), Size(4f, 8f))
        }
        // An acoustic guitar standing up: body, sound hole, neck and head.
        GroupIcon.Strings -> scale(1.15f, pivot = Offset(c, c)) {
            drawPath(Path().apply {
                moveTo(c, c - 2)
                cubicTo(c - 4, c - 2, c - 5, c + 1, c - 3.5f, c + 3)
                cubicTo(c - 6.5f, c + 4, c - 6.5f, c + 10, c, c + 10)
                cubicTo(c + 6.5f, c + 10, c + 6.5f, c + 4, c + 3.5f, c + 3)
                cubicTo(c + 5, c + 1, c + 4, c - 2, c, c - 2)
                close()
            }, color, style = line)
            drawPath(Path().apply { moveTo(c, c - 2); lineTo(c, c - 11) }, color, style = line)
            drawRoundRect(color, Offset(c - 1.6f, c - 13), Size(3.2f, 3f),
                androidx.compose.ui.geometry.CornerRadius(0.8f))
            drawCircle(color, 1.4f, Offset(c, c + 3.5f), style = line)
        }
        // A horn: mouthpiece, tube and bell.
        GroupIcon.Winds -> {
            path { moveTo(c - 11, c - 1); lineTo(c + 2, c - 1); moveTo(c - 11, c + 1); lineTo(c + 2, c + 1) }
            path { moveTo(c + 2, c - 1); lineTo(c + 10, c - 7); lineTo(c + 10, c + 7); lineTo(c + 2, c + 1) }
        }
        // A leaf.
        GroupIcon.Nature -> {
            path {
                moveTo(c - 9, c + 8)
                cubicTo(c - 10, c - 4, c, c - 9, c + 9, c - 9)
                cubicTo(c + 9, c + 1, c + 3, c + 9, c - 9, c + 8)
                close()
            }
            path { moveTo(c - 9, c + 8); lineTo(c + 3, c - 3) }
        }
        // A waveform in a clip.
        GroupIcon.Samples -> {
            drawRoundRect(color, Offset(c - 11, c - 8), Size(22f, 16f),
                androidx.compose.ui.geometry.CornerRadius(2f), style = line)
            val bars = floatArrayOf(3f, 7f, 5f, 9f, 4f, 8f, 3f)
            for ((i, h) in bars.withIndex()) {
                val x = c - 7.5f + i * 2.5f
                drawLine(color, Offset(x, c - h / 2), Offset(x, c + h / 2), 1.4f, StrokeCap.Round)
            }
        }
        // Two jacks and a patch cable.
        GroupIcon.Beyond -> {
            drawCircle(color, 2.6f, Offset(c - 8, c + 5), style = line)
            drawCircle(color, 2.6f, Offset(c + 8, c - 5), style = line)
            path { moveTo(c - 6, c + 3); cubicTo(c - 2, c + 10, c + 2, c - 10, c + 6, c - 3) }
        }
    }
}
