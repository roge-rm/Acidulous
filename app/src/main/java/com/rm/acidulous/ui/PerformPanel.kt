package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.REPEAT_LENGTHS
import com.rm.acidulous.model.STOP_LENGTHS
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.THROW_TIMES
import com.rm.acidulous.ui.theme.Acid

/**
 * The held effects: repeat, tape stop, and the pad.
 *
 * Nothing here stays on. Each one is on while a finger is down and goes back
 * to rest when it lifts, and while recording every press is written into
 * [track]'s clip as a lane, so the song plays the performance back.
 */
@Composable
fun PerformPanel(song: Song, editor: SongEditor, track: Int, modifier: Modifier = Modifier) {
    val c = Acid.colors
    // From [song], not `editor.song`: the editor's copy is not observed, so a
    // setting read from it never redrew when it changed.
    val settings = song.master.perform
    fun send(name: String, v: Float) = NativeEngine.setParam(track, "perform", name, v, record = true)

    Row(modifier.background(c.panelAlt).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption("repeat")
            RepeatStrip(Modifier.fillMaxWidth().weight(1f)) { k -> send("repeat", k / REPEAT_LENGTHS.size.toFloat()) }
            Caption("tape stop")
            HoldPad("stop", Modifier.fillMaxWidth().weight(1f)) { on -> send("stop", if (on) 1f else 0f) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Setting("stop", STOP_LENGTHS[settings.stopLen], Modifier.weight(1f)) {
                    editor.editSong { s ->
                        s.copy(master = s.master.copy(perform = s.master.perform.copy(stopLen = (s.master.perform.stopLen + 1) % STOP_LENGTHS.size)))
                    }
                }
                Setting("echo", THROW_TIMES[settings.throwTime], Modifier.weight(1f)) {
                    editor.editSong { s ->
                        s.copy(master = s.master.copy(perform = s.master.perform.copy(throwTime = (s.master.perform.throwTime + 1) % THROW_TIMES.size)))
                    }
                }
            }
            Caption("feedback")
            MiniSlider(
                settings.feedback / 0.9f, Modifier.fillMaxWidth().height(20.dp),
                onStart = { editor.beginSongGesture() },
                onChange = { v ->
                    NativeEngine.setParam(track, "perform", "feedback", v, record = false)
                    editor.updateSongGesture { s ->
                        s.copy(master = s.master.copy(perform = s.master.perform.copy(feedback = v * 0.9f)))
                    }
                },
                onEnd = { editor.endSongGesture() },
            )
        }
        XyPad(Modifier.weight(1f).fillMaxHeight()) { x, y ->
            send("x", x)
            send("y", y)
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
}

/** A setting you tap through: its name, and what it is set to. */
@Composable
private fun Setting(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(4.dp)).background(c.raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("$label $value", color = c.textMid, fontSize = 11.sp, maxLines = 1, softWrap = false) }
}

/**
 * The five slice lengths side by side, played as one strip: press one to start
 * repeating, slide along to change the length without letting go, lift to stop.
 */
@Composable
private fun RepeatStrip(modifier: Modifier, onRepeat: (Int) -> Unit) {
    val c = Acid.colors
    val cb by rememberUpdatedState(onRepeat)
    var held by remember { mutableStateOf(0) }
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).pointerInput(Unit) {
            awaitEachGesture {
                fun at(x: Float) = ((x / size.width) * REPEAT_LENGTHS.size).toInt().coerceIn(0, REPEAT_LENGTHS.size - 1) + 1
                val down = awaitFirstDown()
                down.consume()
                held = at(down.position.x)
                cb(held)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val k = at(change.position.x)
                    if (k != held) { held = k; cb(k) }
                }
                held = 0
                cb(0)
            }
        },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        REPEAT_LENGTHS.forEachIndexed { i, label ->
            val on = held == i + 1
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (on) c.accent.copy(alpha = 0.35f) else c.raised),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) c.accent else c.textMid, fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }
}

/** On while a finger is on it. */
@Composable
private fun HoldPad(label: String, modifier: Modifier, onHold: (Boolean) -> Unit) {
    val c = Acid.colors
    val cb by rememberUpdatedState(onHold)
    var held by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (held) c.red.copy(alpha = 0.3f) else c.raised)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    held = true
                    cb(true)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                    held = false
                    cb(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (held) c.red else c.textMid, fontSize = 13.sp) }
}

/**
 * Across, a filter: low pass to the left of the middle, high pass to the
 * right. Up, how much of the mix is thrown into the echo. Let go and both go
 * back to rest, and the echo rings out.
 */
@Composable
private fun XyPad(modifier: Modifier, onMove: (Float, Float) -> Unit) {
    val c = Acid.colors
    val cb by rememberUpdatedState(onMove)
    var finger by remember { mutableStateOf<Offset?>(null) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(c.raised)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    fun send(p: Offset) {
                        val x = (p.x / size.width).coerceIn(0f, 1f)
                        val y = (1f - p.y / size.height).coerceIn(0f, 1f)
                        finger = Offset(x, y)
                        cb(x, y)
                    }
                    val down = awaitFirstDown()
                    down.consume()
                    send(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        send(change.position)
                    }
                    finger = null
                    cb(0.5f, 0f)
                }
            },
        ) {
            val mid = size.width / 2f
            drawLine(c.line, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.dp.toPx())
            finger?.let { f ->
                drawCircle(c.accent, radius = 14.dp.toPx(), center = Offset(f.x * size.width, (1f - f.y) * size.height))
            }
        }
        Text("low", color = c.textDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
        Text("high", color = c.textDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        Text("throw", color = c.textDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopCenter).padding(6.dp))
    }
}
