package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rm.acidulous.model.laneUnit
import com.rm.acidulous.model.laneParam
import com.rm.acidulous.model.Mixer
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The mixer section as a slide-up panel: a strip per track, then the
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
    val c = Acid.colors
    Row(
        modifier.background(c.panelAlt).horizontalScrollWithBar(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        song.tracks.forEachIndexed { index, track ->
            // Which of this channel's controls a clip is driving. A lane wins
            // over the fader on every pass, so a channel with one is a channel
            // whose fader will not appear to work - and nothing here said so.
            val automated = remember(track) {
                track.clips.values
                    .flatMap { it.automation.keys }
                    .filter { laneUnit(it) == "channel" }
                    .map { laneParam(it) }
                    .distinct()
                    .sorted()
            }
            ChannelStrip(track, index, rackPeaks.getOrElse(index) { 0f }, editor, trackColour(index), automated)
        }
        MasterStrip(song, editor, masterPeak, clickOn, onClick)
    }
}

@Composable
private fun ChannelStrip(
    track: Track, index: Int, peak: Float, editor: SongEditor, colour: Color,
    /** Channel controls some clip of this track has a lane for. */
    automated: List<String> = emptyList(),
) {
    val c = Acid.colors
    var askClear by remember { mutableStateOf(false) }
    val m = track.mixer
    fun live(name: String, v01: Float) = com.rm.acidulous.engine.NativeEngine.setParam(index, "channel", name, v01)
    fun gesture(name: String, v01: Float, update: (Mixer) -> Mixer) {
        live(name, v01)
        editor.updateGesture { t -> t.copy(mixer = update(t.mixer)) }
    }
    fun tap(update: (Mixer) -> Mixer) = editor.edit(index) { t -> t.copy(mixer = update(t.mixer)) }
    fun map(name: String) = MapTargets.param(index, "channel", name)

    if (askClear) {
        PlainDialog(
            title = "Clear automation on ${track.name}?",
            onDismiss = { askClear = false },
            confirmLabel = "Clear",
            onConfirm = {
                askClear = false
                // Every clip on this track, because a lane in a scene you are
                // not playing will bite you the moment that scene comes round.
                editor.edit(index) { t ->
                    t.copy(clips = t.clips.mapValues { (_, clip) ->
                        val kept = clip.automation.filterKeys { laneUnit(it) != "channel" }
                        if (kept.size == clip.automation.size) clip else clip.copy(automation = kept)
                    })
                }
            },
        ) {
            Text(
                "This channel's " + automated.joinToString(", ") + " " +
                    (if (automated.size == 1) "is" else "are") +
                    " being driven by recorded movement, which is why the control does not stay where you put it. " +
                    "Clearing removes those lanes from every clip on this track; the notes are untouched.",
                color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    Column(
        Modifier.width(STRIP_W).clip(RoundedCornerShape(6.dp)).background(c.cardAlt).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(track.name, color = c.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A lane on this channel, said out loud. Without it a fader that is
        // being overwritten every pass simply looks broken, which is exactly
        // how it was found: "the level didn't seem to respond to my controls".
        if (automated.isNotEmpty()) {
            Text(
                "\u223F " + automated.joinToString(" "),
                color = c.accent, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.accentDim)
                    .clickable { askClear = true }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.height(FADER_H), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Meter(peak, Modifier.width(8.dp).height(FADER_H))
            VerticalFader(
                value = EngineParams.volume01(m.volume),
                modifier = Modifier.width(36.dp).height(FADER_H).mappable(map("gain")),
                accent = colour,
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("gain", v) { it.copy(volume = EngineParams.volumeFrom01(v)) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("pan") {
            MiniSlider(
                value = EngineParams.pan01(m.pan), centered = true,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("pan")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("pan", v) { it.copy(pan = EngineParams.panFrom01(v)) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("rev") {
            MiniSlider(
                value = m.sendReverb,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("sendreverb")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("sendreverb", v) { it.copy(sendReverb = v) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("dly") {
            MiniSlider(
                value = m.sendDelay,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("senddelay")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("senddelay", v) { it.copy(sendDelay = v) } },
                onEnd = { editor.endGesture() },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip("M", m.mute, c.red, Modifier.mappable(map("mute"))) { tap { it.copy(mute = !it.mute) } }
            ToggleChip("S", m.solo, c.accent, Modifier.mappable(map("solo"))) { tap { it.copy(solo = !it.solo) } }
        }
        // Where this track's notes go. Off, both, or out only - and at "out"
        // the machine is not asked at all, which is how driving something
        // else gives the CPU back. The channel sits beside it because one
        // without the other is no use.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = when (m.midiMode) { 1 -> "both"; 2 -> "out"; else -> "midi" }
            ToggleChip(label, m.midiMode != 0, c.teal) {
                tap { it.copy(midiMode = (it.midiMode + 1) % 3) }
            }
            if (m.midiMode != 0) {
                ToggleChip("${m.midiChannel + 1}", true, c.accentDim) {
                    tap { it.copy(midiChannel = (it.midiChannel + 1) % 16) }
                }
            }
        }
    }
}

@Composable
private fun MasterStrip(song: Song, editor: SongEditor, peak: Float, clickOn: Boolean, onClick: (Boolean) -> Unit) {
    val c = Acid.colors
    val master = song.master
    fun live(name: String, v01: Float) = com.rm.acidulous.engine.NativeEngine.setParam(0, "master", name, v01)
    fun gesture(name: String, v01: Float, update: (Song) -> Song) {
        live(name, v01)
        editor.updateSongGesture(update)
    }
    // The master is rack 0 by convention - it has no rack of its own, and a
    // mapping to it never follows the routing.
    fun map(name: String) = MapTargets.param(0, "master", name)

    Column(
        Modifier.width(MASTER_W).clip(RoundedCornerShape(6.dp)).background(c.cardHi).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("master", color = c.text, fontSize = 11.sp)
        Row(Modifier.height(FADER_H), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Meter(peak, Modifier.width(8.dp).height(FADER_H))
            VerticalFader(
                value = EngineParams.volume01(master.volume),
                modifier = Modifier.width(36.dp).height(FADER_H).mappable(map("volume")),
                accent = Acid.colors.knobPointer,
                onStart = { editor.beginSongGesture() },
                onChange = { v -> gesture("volume", v) { s -> s.copy(master = s.master.copy(volume = EngineParams.volumeFrom01(v))) } },
                onEnd = { editor.endSongGesture() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("reverb", master.reverb.on, c.teal, Modifier.mappable(map("reverbon"))) {
                    editor.editSong { s -> s.copy(master = s.master.copy(reverb = s.master.reverb.copy(on = !s.master.reverb.on))) }
                }
                ToggleChip("delay", master.delay.on, c.teal, Modifier.mappable(map("delayon"))) {
                    editor.editSong { s -> s.copy(master = s.master.copy(delay = s.master.delay.copy(on = !s.master.delay.on))) }
                }
                ToggleChip("limiter", master.limiter.on, c.teal, Modifier.mappable(map("limiteron"))) {
                    editor.editSong { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(on = !s.master.limiter.on))) }
                }
                ToggleChip("♩ click", clickOn, c.accent) { onClick(!clickOn) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Labeled("rev size") {
                    MiniSlider(master.reverb.size, Modifier.width(88.dp).height(20.dp).mappable(map("reverbsize")),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("reverbsize", v) { s -> s.copy(master = s.master.copy(reverb = s.master.reverb.copy(size = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
                Labeled("rev damp") {
                    MiniSlider(master.reverb.damp, Modifier.width(88.dp).height(20.dp).mappable(map("reverbdamp")),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("reverbdamp", v) { s -> s.copy(master = s.master.copy(reverb = s.master.reverb.copy(damp = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
                Labeled("limit drive") {
                    MiniSlider(master.limiter.drive, Modifier.width(88.dp).height(20.dp).mappable(map("limiterdrive")),
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
                    MiniSlider(master.delay.feedback, Modifier.width(88.dp).height(20.dp).mappable(map("delayfeedback")),
                        onStart = { editor.beginSongGesture() },
                        onChange = { v -> gesture("delayfeedback", v) { s -> s.copy(master = s.master.copy(delay = s.master.delay.copy(feedback = v))) } },
                        onEnd = { editor.endSongGesture() })
                }
                Labeled("dly tone") {
                    MiniSlider(master.delay.tone, Modifier.width(88.dp).height(20.dp).mappable(map("delaytone")),
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
        Text(label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        content()
    }
}

// Material buttons carry a 40 dp minimum height; these have to stack four high
// beside a fader, so they are plain boxes.
@Composable
private fun ToggleChip(
    label: String,
    on: Boolean,
    colour: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else c.textMid, fontSize = 11.sp, maxLines = 1) }
}

internal fun trackColour(index: Int): Color = PALETTE[index % PALETTE.size]

// A track's stripe is how that track is recognised at a glance, so it is
// the same colour in both themes - it belongs to the track, not to the
// interface. Mid-saturation hues that hold up on white and on black.
private val PALETTE = listOf(
    Color(0xFF3FA98D), Color(0xFFE09A3C), Color(0xFFD4688A), Color(0xFF5B8FE0),
    Color(0xFF8CC04E), Color(0xFFE0714A), Color(0xFF9B7BD4), Color(0xFF3FAFC0),
)

private val STRIP_W = 76.dp
private val MASTER_W = 232.dp
private val FADER_H = 110.dp
