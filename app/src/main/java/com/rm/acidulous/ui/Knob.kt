package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A knob: 270° arc, vertical drag (200 px for the full range), label above,
 * value below. Value is 0..1; the caller formats it. Reports gesture start
 * and end so the document can coalesce a turn into one undo step.
 */
@Composable
fun Knob(
    label: String,
    value: Float,
    display: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    accent: Color = Color(0xFF7FD1B9),
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: () -> Unit = {},
) {
    val cb by rememberUpdatedState(Triple(onStart, onChange, onEnd))
    val current by rememberUpdatedState(value)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF9A9AA2), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Canvas(
            Modifier.size(size).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startValue = current
                    val startY = down.position.y
                    cb.first()
                    drag(down.id) { change ->
                        change.consume()
                        val dv = (startY - change.position.y) / 200f
                        cb.second((startValue + dv).coerceIn(0f, 1f))
                    }
                    cb.third()
                }
            },
        ) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val stroke = r * 0.22f
            val start = 135f
            val sweep = 270f
            drawArc(Color(0xFF3A3A40), start, sweep, false, Offset(c.x - r + stroke, c.y - r + stroke),
                Size((r - stroke) * 2f, (r - stroke) * 2f), style = Stroke(stroke))
            drawArc(accent, start, sweep * value.coerceIn(0f, 1f), false, Offset(c.x - r + stroke, c.y - r + stroke),
                Size((r - stroke) * 2f, (r - stroke) * 2f), style = Stroke(stroke))
            val a = Math.toRadians((start + sweep * value.coerceIn(0f, 1f)).toDouble())
            val inner = r * 0.35f
            val outer = r - stroke * 1.6f
            drawLine(Color(0xFFE8E8E4), Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()), 3f)
        }
        Text(display, color = Color(0xFFFFB454), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}
