package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.model.EngineParams
import com.rm.acidulous.model.Mixer
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track

/**
 * The reference sequencer's mixer section as a slide-up panel: a strip per track, then the
 * master. Fader drags are one undo step (per track, or song-level for the
 * master) and also go straight to the engine for immediacy.
 */
@Composable
fun MixerPanel(
    song: Song,
    editor: SongEditor,
    rackPeaks: FloatArray,
    masterPeak: Float,
    clickOn: Boolean,
    onClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Color(0xFF202024)).horizontalScroll(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        song.tracks.forEachIndexed { index, track ->
            ChannelStrip(track, index, rackPeaks.getOrElse(index) { 0f }, editor, trackColour(index))
        }
        MasterStrip(song, editor, masterPeak, clickOn, onClick)
    }
}

@Composable
private fun ChannelStrip(track: Track, index: Int, peak: Float, editor: SongEditor, colour: Color) {
    val m = track.mixer
    fun live(name: String, v01: Float) = com.rm.acidulous.engine.NativeEngine.setParam(index, "channel", name, v01)
    fun gesture(name: String, v01: Float, update: (Mixer) -> Mixer) {
        live(name, v01)
        editor.updateGesture { t -> t.copy(mixer = update(t.mixer)) }
    }
    fun tap(update: (Mixer) -> Mixer) = editor.edit(index) { t -> t.copy(mixer = update(t.mixer)) }

    Column(
        Modifier.width(STRIP_W).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2F)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(track.name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.height(FADER_H), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Meter(peak, Modifier.width(8.dp).height(FADER_H))
            VerticalFader(
                value = EngineParams.volume01(m.volume),
                modifier = Modifier.width(36.dp).height(FADER_H),
                accent = colour,
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("gain", v) { it.copy(volume = EngineParams.volumeFrom01(v)) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("pan") {
            MiniSlider(
                value = EngineParams.pan01(m.pan), centered = true, modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("pan", v) { it.copy(pan = EngineParams.panFrom01(v)) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("rev") {
            MiniSlider(
                value = m.sendReverb, modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("sendreverb", v) { it.copy(sendReverb = v) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("dly") {
            MiniSlider(
                value = m.sendDelay, modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("senddelay", v) { it.copy(sendDelay = v) } },
                onEnd = { editor.endGesture() },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip("M", m.mute, Color(0xFFE74C3C)) { tap { it.copy(mute = !it.mute) } }
            ToggleChip("S", m.solo, Color(0xFFFFB454)) { tap { it.copy(solo = !it.solo) } }
        }
    }
}

@Composable
private fun MasterStrip(song: Song, editor: SongEditor, peak: Float, clickOn: Boolean, onClick: (Boolean) -> Unit) {
    val master = song.master
    fun live(name: String, v01: Float) = com.rm.acidulous.engine.NativeEngine.setParam(0, "master", name, v01)
    fun gesture(name: String, v01: Float, update: (Song) -> Song) {
        live(name, v01)
        editor.updateSongGesture(update)
    }

    Column(
        Modifier.width(MASTER_W).clip(RoundedCornerShape(6.dp)).background(Color(0xFF33333A)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("master", color = Color.White, fontSize = 11.sp)
        Row(Modifier.height(FADER_H), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Meter(peak, Modifier.width(8.dp).height(FADER_H))
            VerticalFader(
                value = EngineParams.volume01(master.volume),
                modifier = Modifier.width(36.dp).height(FADER_H),
                accent = Color(0xFFE8E8E4),
                onStart = { editor.beginSongGesture() },
                onChange = { v -> gesture("volume", v) { s -> s.copy(master = s.master.copy(volume = EngineParams.volumeFrom01(v))) } },
                onEnd = { editor.endSongGesture() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("reverb", master.reverb.on, Color(0xFF7FD1B9)) {
                    editor.editSong { s -> s.copy(master = s.master.copy(reverb = s.master.reverb.copy(on = !s.master.reverb.on))) }
                }
                ToggleChip("delay", master.delay.on, Color(0xFF7FD1B9)) {
                    editor.editSong { s -> s.copy(master = s.master.copy(delay = s.master.delay.copy(on = !s.master.delay.on))) }
                }
                ToggleChip("limiter", master.limiter.on, Color(0xFF7FD1B9)) {
                    editor.editSong { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(on = !s.master.limiter.on))) }
                }
                ToggleChip("♩ click", clickOn, Color(0xFFFFB454)) { onClick(!clickOn) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Labeled("rev size") {
                    MiniSlider(master.reverb.size, Modifier.width(88.dp).height(20.dp),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("reverbsize", v) { s -> s.copy(master = s.master.copy(reverb = s.master.reverb.copy(size = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
                Labeled("rev damp") {
                    MiniSlider(master.reverb.damp, Modifier.width(88.dp).height(20.dp),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("reverbdamp", v) { s -> s.copy(master = s.master.copy(reverb = s.master.reverb.copy(damp = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
                Labeled("limit drive") {
                    MiniSlider(master.limiter.drive, Modifier.width(88.dp).height(20.dp),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("limiterdrive", v) { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(drive = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
            }
            Column {
                Labeled("dly time") {
                    TextButton(onClick = {
                        editor.editSong { s -> s.copy(master = s.master.copy(delay = s.master.delay.copy(time = (s.master.delay.time + 1) % EngineParams.DELAY_TIMES))) }
                    }) { Text(EngineParams.DELAY_TIME_NAMES[master.delay.time.coerceIn(0, EngineParams.DELAY_TIMES - 1)], fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
                Labeled("dly fb") {
                    MiniSlider(master.delay.feedback, Modifier.width(88.dp).height(20.dp),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("delayfeedback", v) { s -> s.copy(master = s.master.copy(delay = s.master.delay.copy(feedback = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
                Labeled("dly tone") {
                    MiniSlider(master.delay.tone, Modifier.width(88.dp).height(20.dp),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("delaytone", v) { s -> s.copy(master = s.master.copy(delay = s.master.delay.copy(tone = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, color = Color(0xFF9A9AA2), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        content()
    }
}

// Material buttons carry a 40 dp minimum height; these have to stack four high
// beside a fader, so they are plain boxes.
@Composable
private fun ToggleChip(label: String, on: Boolean, colour: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else Color(0xFF3A3A40))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else Color(0xFFBBBBBB), fontSize = 11.sp, maxLines = 1) }
}

internal fun trackColour(index: Int): Color = PALETTE[index % PALETTE.size]

private val PALETTE = listOf(
    Color(0xFF7FD1B9), Color(0xFFFFB454), Color(0xFFE07A9A), Color(0xFF8AB4F8),
    Color(0xFFC3E88D), Color(0xFFFF8A65), Color(0xFFB39DDB), Color(0xFF80DEEA),
)

private val STRIP_W = 76.dp
private val MASTER_W = 232.dp
private val FADER_H = 110.dp
