package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.DrumVoice
import com.rm.acidulous.model.Note

/**
 * A drum machine's step grid: a row per voice, a column per grid step, one
 * bar at a time. Tap toggles a hit; long-press toggles its accent. It is a
 * view over the ordinary clip, so the piano roll shows the same hits.
 */
@Composable
fun DrumGrid(
    clip: Clip,
    ticksPerBar: Int,
    voices: List<DrumVoice>,
    playheadTick: Long?,
    onSetHit: (tick: Int, note: Int, hit: Note?) -> Unit, // null clears
    modifier: Modifier = Modifier,
) {
    val grid = clip.grid.coerceAtLeast(1)
    val stepsPerBar = (ticksPerBar / grid).coerceAtLeast(1)
    var bar by remember { mutableStateOf(0) }
    bar = bar.coerceIn(0, clip.bars - 1)

    Column(modifier.background(Color(0xFF1B1B1E)).padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (bar > 0) bar-- }) { Text("◀", color = Color.White) }
            Text("bar ${bar + 1}/${clip.bars}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { if (bar < clip.bars - 1) bar++ }) { Text("▶", color = Color.White) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (voice in voices) {
                Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(voice.short, color = Color(0xFFBBBBBB), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp))
                    for (s in 0 until stepsPerBar) {
                        val tick = bar * ticksPerBar + s * grid
                        val hit = clip.notes.firstOrNull { it.tick == tick && it.pitch == voice.note }
                        val accent = hit != null && hit.velocity >= 100
                        val active = playheadTick != null && playheadTick >= tick && playheadTick < tick + grid
                        val beat = (s % 4) == 0
                        Box(
                            Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        accent -> Color(0xFFFFB454)
                                        hit != null -> Color(0xFF3F7D5E)
                                        active -> Color(0xFF33333A)
                                        beat -> Color(0xFF2A2A2F)
                                        else -> Color(0xFF232326)
                                    },
                                )
                                .combinedClickable(
                                    onClick = { onSetHit(tick, voice.note, if (hit == null) Note(tick, 30, voice.note, 90) else null) },
                                    onLongClick = { hit?.let { onSetHit(tick, voice.note, it.copy(velocity = if (accent) 90 else 110)) } },
                                ),
                        )
                    }
                }
            }
        }
    }
}
