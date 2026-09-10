package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.DrumVoice

/** Pads in place of the keyboard for a drum machine: two rows, one per voice. */
@Composable
fun DrumPads(rack: Int, voices: List<DrumVoice>, selected: Int = -1, onSelect: (Int) -> Unit = {}, modifier: Modifier = Modifier) {
    val perRow = (voices.size + 1) / 2
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in voices.chunked(perRow)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (v in row) Pad(rack, v, v.note - voices.first().note == selected, { onSelect(v.note - voices.first().note) }, Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun Pad(rack: Int, voice: DrumVoice, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) Color(0xFFFFB454) else if (selected) Color(0xFF3F4A55) else Color(0xFF2E2E33))
            .pointerInput(voice.note, rack) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        val down = currentEvent.changes.any { it.pressed }
                        if (down != pressed) {
                            pressed = down
                            if (down) { NativeEngine.noteOn(rack, voice.note, 110); onSelect() } else NativeEngine.noteOff(rack, voice.note)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(voice.short, color = if (pressed) Color(0xFF1B1B1E) else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
