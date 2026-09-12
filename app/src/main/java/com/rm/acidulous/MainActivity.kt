package com.rm.acidulous

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.engine.Recorder
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.ui.EditScreen
import android.os.SystemClock
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongStore
import com.rm.acidulous.ui.theme.AcidulousTheme
import kotlinx.coroutines.delay

/**
 * Bring-up harness for the ported engine, not the product UI.
 *
 * M2: the demo song is built, saved to JSON, loaded back, and the loaded copy
 * is what the engine plays - so a green "roundtrip" means the file format
 * carries everything the scheduler needs.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineAssets.install(this)
        enableEdgeToEdge()
        setContent {
            AcidulousTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EngineBringUp(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private const val TAG = "Acidulous.UI"
private const val RACK = 0

@Composable
private fun EngineBringUp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("starting…") }
    // Referential, not structural: Song equality is by value (rev is outside
    // equals on purpose), so a loaded or edited song that happens to be
    // value-equal would otherwise be a silently dropped write - and the engine
    // cache would then be keyed on instances the UI no longer holds.
    var song by remember { mutableStateOf(DemoSong.build(), referentialEqualityPolicy()) }
    var lastPushMs by remember { mutableStateOf(0L) }
    val editor = remember {
        SongEditor(song) { edited, pushNow ->
            song = edited
            // Taps and gesture ends push at once; mid-gesture updates are
            // throttled to ~15 Hz. endGesture() always pushes, so nothing is lost.
            val now = SystemClock.uptimeMillis()
            if (pushNow || now - lastPushMs >= 66) {
                EngineSync.push(edited)
                lastPushMs = now
            }
        }
    }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) } // (track, sceneId)

    DisposableEffect(Unit) {
        EngineAssets.install(context)
        val started = NativeEngine.start()
        status = if (started) {
            // Save → load → play the loaded copy. If the JSON dropped anything
            // the scheduler needs, we hear it.
            val built = DemoSong.build()
            val file = SongStore.save(context, built)
            val loaded = SongStore.load(context, built.name)
            val roundtrip = loaded == built
            Log.i(TAG, "saved ${file.length()} bytes to ${file.name}; roundtrip ${if (roundtrip) "OK" else "MISMATCH"}")
            EngineSync.ensureMachines(loaded)
            editor.replace(loaded) // pushes
            "${NativeEngine.sampleRate} Hz · burst ${NativeEngine.framesPerBurst} · " +
                (if (NativeEngine.isLowLatency) "low-latency" else "normal") +
                " · json roundtrip ${if (roundtrip) "OK" else "MISMATCH"}"
        } else {
            "engine failed to start — check logcat"
        }
        onDispose { NativeEngine.stop() }
    }

    val recorder = remember { Recorder() }
    var armed by remember { mutableStateOf(false) }
    var peak by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Position(0, 0, 0)) }
    var bpm by remember { mutableStateOf(120f) }
    var notesOn by remember { mutableStateOf(0) }
    var notesOff by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            peak = NativeEngine.readPeakLevel()
            playing = NativeEngine.isPlaying
            position = Position.unpack(NativeEngine.positionPacked)
            bpm = NativeEngine.tempo
            notesOn = NativeEngine.notesOn(RACK)
            notesOff = NativeEngine.notesOff(RACK)
            armed = NativeEngine.recordArmed
            // Recording: notes land in the document now, in the engine at the next wrap.
            val sceneIdOf: (Long) -> String? = { id -> song.scenes.firstOrNull { it.engineId == id }?.id }
            val wasPlaying = playing
            val result = if (armed || wasPlaying) recorder.poll(song, position, playing, sceneIdOf) else null
            if (result != null && result.song !== song) {
                result.song.tracks.forEachIndexed { i, t -> if (t !== song.tracks.getOrNull(i)) editor.edit(i, push = false) { t } }
            }
            if (result?.push == true) EngineSync.push(editor.song)
            delay(80)
        }
    }

    val onArm: (Boolean) -> Unit = { on ->
        NativeEngine.recordArmed = on
        if (!on) {
            val r = recorder.flush(song, { id -> song.scenes.firstOrNull { it.engineId == id }?.id })
            if (r.song !== song) r.song.tracks.forEachIndexed { i, t -> if (t !== song.tracks.getOrNull(i)) editor.edit(i, push = false) { t } }
            if (r.push) EngineSync.push(editor.song)
        }
    }

    // The Edit screen owns the whole window; it must not live inside the
    // scrolling column below, where a weight(1f) roll has no height to fill.
    editing?.let { (trackIdx, sceneId) ->
        EditScreen(
            song = song, editor = editor, trackIndex = trackIdx, sceneId = sceneId,
            position = position, playing = playing, armed = armed, onArm = onArm,
            onBack = { editing = null },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Acidulous", style = MaterialTheme.typography.headlineMedium)
        Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        TransportBar(song, playing, position, bpm, notesOn, notesOff, armed, onArm)
        OutlinedButton(onClick = {
            val scene = song.scenes.getOrNull(position.scene) ?: song.scenes.firstOrNull()
            if (scene != null) editing = RACK to scene.id
        }) { Text("Edit ▸ ${song.scenes.getOrNull(position.scene)?.name ?: ""}", fontSize = 12.sp) }
        run {
            val scene = song.scenes.getOrNull(position.scene)
            val clip = scene?.let { song.tracks.getOrNull(RACK)?.clips?.get(it.id) }
            Text(
                "clip notes %d · recorded %d · dropped %d".format(clip?.notes?.size ?: 0, recorder.notesRecorded, NativeEngine.recordedDropped),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
        SceneRow(song, position, playing)
        LiveEditRow(song, position) { edited -> editor.replace(edited) }

        LevelMeter(peak)

        Spacer(Modifier.height(8.dp))

        ParamSlider("cutoff", initial = 0.55f)
        ParamSlider("resonance", initial = 0.55f)
        ParamSlider("envmod", initial = 0.5f)
        ParamSlider("decay", initial = 0.5f)

        Spacer(Modifier.height(16.dp))
        Keyboard(Modifier.fillMaxWidth().height(140.dp))
    }
}

@Composable
private fun TransportBar(
    song: Song,
    playing: Boolean,
    position: Position,
    bpm: Float,
    notesOn: Int,
    notesOff: Int,
    armed: Boolean,
    onArm: (Boolean) -> Unit,
) {
    var tempo by remember { mutableStateOf(song.tempo) }
    var loopScene by remember { mutableStateOf(false) }

    val scene = song.scenes.getOrNull(position.scene)
    val ticksPerBar = scene?.let { song.signatureOf(it).ticksPerBar } ?: (4 * PPQN)
    val bar = position.tickInIteration / ticksPerBar + 1
    val beat = (position.tickInIteration % ticksPerBar) / PPQN + 1
    val tick = position.tickInIteration % PPQN

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay() }) {
                Text(if (playing) "■ Stop" else "▶ Play")
            }
            OutlinedButton(onClick = {
                loopScene = !loopScene
                NativeEngine.setLoopScene(loopScene)
            }) {
                Text(if (loopScene) "⟳ scene" else "⟳ song")
            }
            OutlinedButton(onClick = { onArm(!armed) }) {
                Text(if (armed) "● REC" else "○ rec", color = if (armed) Color(0xFFC0392B) else Color.Unspecified)
            }
            // on == off after a stop means no stuck notes
            Text("on %d off %d".format(notesOn, notesOff), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        // scene · repeat · bar.beat.tick · effective tempo — the M2 readout
        Text(
            "S%d/%d %-6s r%d/%d  %d.%d.%03d  %.1f bpm".format(
                position.scene + 1, song.scenes.size, scene?.name ?: "-",
                position.repeat + 1, scene?.repeat ?: 1,
                bar, beat, tick, bpm,
            ),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Text("song tempo %.0f".format(tempo), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Slider(
            value = tempo,
            valueRange = 60f..200f,
            onValueChange = {
                tempo = it
                NativeEngine.tempo = it
            },
        )
    }
}

/**
 * Edits applied to the document *while it plays*, to prove the swap rules:
 * playback stays on the same scene by id, shrinking re-anchors, and one edited
 * clip is the only one re-marshalled (see the EngineSync log line).
 */
@Composable
private fun LiveEditRow(song: Song, position: Position, onEdit: (Song) -> Unit) {
    var counter by remember { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            counter++
            val fill = Scene(id = "s-fill-$counter", name = "Fill$counter")
            onEdit(song.copy(scenes = listOf(fill) + song.scenes))
        }) { Text("+0", fontSize = 11.sp) }
        OutlinedButton(onClick = {
            val scene = song.scenes.getOrNull(position.scene) ?: return@OutlinedButton
            if (song.scenes.size <= 1) return@OutlinedButton
            onEdit(song.copy(scenes = song.scenes - scene))
        }) { Text("del", fontSize = 11.sp) }
        OutlinedButton(onClick = {
            val scene = song.scenes.getOrNull(position.scene) ?: return@OutlinedButton
            val bar = song.signatureOf(scene).ticksPerBar
            onEdit(song.copy(tracks = song.tracks.map { t ->
                val c = t.clips[scene.id] ?: return@map t
                t.copy(clips = t.clips + (scene.id to c.copy(bars = 1, notes = c.notes.filter { it.tick < bar })))
            }))
        }) { Text("→1bar", fontSize = 11.sp) }
        OutlinedButton(onClick = {
            val scene = song.scenes.getOrNull(position.scene) ?: return@OutlinedButton
            onEdit(song.copy(tracks = song.tracks.map { t ->
                val c = t.clips[scene.id] ?: return@map t
                val notes = c.notes.mapIndexed { i, n -> if (i == 0) n.copy(pitch = n.pitch + 12) else n }
                t.copy(clips = t.clips + (scene.id to c.copy(notes = notes)))
            }))
        }) { Text("note+12", fontSize = 11.sp) }
    }
}

/** One button per scene: play from its top. the reference sequencer's "tap the scene number". */
@Composable
private fun SceneRow(song: Song, position: Position, playing: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        song.scenes.forEachIndexed { idx, scene ->
            val current = playing && position.scene == idx
            OutlinedButton(onClick = { NativeEngine.transportPlay(idx) }) {
                Text((if (current) "▶ " else "") + "${scene.name} ×${scene.repeat}")
            }
        }
    }
}

@Composable
private fun LevelMeter(peak: Float) {
    Column {
        Text("peak %.4f".format(peak), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFFDDDDD8)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(peak.coerceIn(0f, 1f))
                    .height(10.dp)
                    .background(Color(0xFF3F7D5E)),
            )
        }
    }
}

@Composable
private fun ParamSlider(name: String, initial: Float) {
    var value by remember { mutableStateOf(initial) }
    Column {
        Text(name, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = {
                value = it
                NativeEngine.setParam(RACK, "machine", name, it)
            },
        )
    }
}

@Composable
private fun Keyboard(modifier: Modifier = Modifier) {
    val notes = listOf(60, 62, 64, 65, 67, 69, 71, 72)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (note in notes) {
            Key(note, Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
private fun Key(note: Int, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) Color(0xFF7FD1B9) else Color(0xFFE8E8E4))
            .pointerInput(note) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        val down = currentEvent.changes.any { it.pressed }
                        if (down != pressed) {
                            pressed = down
                            if (down) NativeEngine.noteOn(RACK, note) else NativeEngine.noteOff(RACK, note)
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(note.toString(), color = Color(0xFF333333), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}
