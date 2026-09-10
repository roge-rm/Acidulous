package com.rm.acidulous

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.engine.Recorder
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.SongStore
import com.rm.acidulous.ui.EditScreen
import com.rm.acidulous.ui.MainScreen
import com.rm.acidulous.ui.theme.AcidulousTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineAssets.install(this)
        enableEdgeToEdge()
        setContent {
            AcidulousTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    App(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private const val TAG = "Acidulous.UI"

private sealed class Screen {
    object Main : Screen()
    data class Edit(val track: Int, val sceneId: String) : Screen()
}

@Composable
private fun App(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Referential, not structural: Song equality is by value (rev is outside
    // equals on purpose), so a value-equal load or edit would otherwise be a
    // silently dropped write.
    var song by remember { mutableStateOf(DemoSong.build(), referentialEqualityPolicy()) }
    var lastPushMs by remember { mutableStateOf(0L) }
    val editor = remember {
        SongEditor(song) { edited, pushNow ->
            song = edited
            // Taps and gesture ends push at once; mid-gesture updates throttle to ~15 Hz.
            val now = SystemClock.uptimeMillis()
            if (pushNow || now - lastPushMs >= 66) {
                EngineSync.sync(edited)
                lastPushMs = now
            }
        }
    }
    val recorder = remember { Recorder() }
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var status by remember { mutableStateOf("starting…") }

    DisposableEffect(Unit) {
        if (NativeEngine.start()) {
            // Start from the demo, round-tripped through the store so the file
            // format is exercised on every launch.
            val built = DemoSong.build()
            SongStore.save(context, built)
            val loaded = runCatching { SongStore.load(context, built.name) }.getOrElse { built }
            editor.replace(loaded)
            status = "${NativeEngine.sampleRate / 1000}k · burst ${NativeEngine.framesPerBurst}"
        } else {
            status = "engine failed to start"
        }
        onDispose { NativeEngine.stop() }
    }

    var peak by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Position(0, 0, 0)) }
    var bpm by remember { mutableStateOf(120f) }
    var armed by remember { mutableStateOf(false) }
    var loopScene by remember { mutableStateOf(false) }
    var notesOn by remember { mutableStateOf(0) }
    var notesOff by remember { mutableStateOf(0) }
    var load by remember { mutableStateOf(0f) }
    var xruns by remember { mutableStateOf(0L) }

    val sceneIdOf: (Long) -> String? = { id -> song.scenes.firstOrNull { it.engineId == id }?.id }
    fun applyRecorded(result: Recorder.Result) {
        if (result.song !== song) {
            result.song.tracks.forEachIndexed { i, t -> if (t !== song.tracks.getOrNull(i)) editor.edit(i, push = false) { t } }
        }
        if (result.push) EngineSync.sync(editor.song)
    }
    val onArm: (Boolean) -> Unit = { on ->
        NativeEngine.recordArmed = on
        if (!on) applyRecorded(recorder.flush(song, sceneIdOf))
    }
    val onLoopScene: (Boolean) -> Unit = { on ->
        loopScene = on
        NativeEngine.setLoopScene(on)
    }

    LaunchedEffect(Unit) {
        while (true) {
            peak = NativeEngine.readPeakLevel()
            playing = NativeEngine.isPlaying
            position = Position.unpack(NativeEngine.positionPacked)
            bpm = NativeEngine.tempo
            armed = NativeEngine.recordArmed
            notesOn = NativeEngine.notesOn(0)
            notesOff = NativeEngine.notesOff(0)
            load = NativeEngine.loadAvg
            xruns = NativeEngine.xRunCount
            if (armed || playing) applyRecorded(recorder.poll(song, position, playing, sceneIdOf))
            delay(80)
        }
    }

    val diagnostics = "%s · peak %.3f · load %.0f%% · xruns %d · on %d off %d".format(status, peak, load, xruns, notesOn, notesOff)

    when (val s = screen) {
        Screen.Main -> MainScreen(
            song = song, editor = editor, position = position, playing = playing, armed = armed,
            loopScene = loopScene, bpm = bpm, diagnostics = diagnostics,
            onArm = onArm, onLoopScene = onLoopScene,
            onOpenClip = { track, sceneId -> screen = Screen.Edit(track, sceneId) },
            onSave = { SongStore.save(context, song); Log.i(TAG, "saved ${song.name}") },
            onLoad = { name -> runCatching { SongStore.load(context, name) }.onSuccess { editor.replace(it) }.onFailure { Log.w(TAG, "load failed", it) } },
            songNames = { SongStore.list(context) },
            modifier = modifier,
        )
        is Screen.Edit -> EditScreen(
            song = song, editor = editor, trackIndex = s.track, sceneId = s.sceneId,
            position = position, playing = playing, armed = armed, onArm = onArm,
            onBack = { screen = Screen.Main },
            modifier = modifier,
        )
    }
}
