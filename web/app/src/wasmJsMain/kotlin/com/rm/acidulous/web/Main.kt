package com.rm.acidulous.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.coroutines.delay

// The engine, as the page's script put it: globalThis.acid, the Emscripten
// module whose memory the audio worklet shares.
private fun enginePeak(): Double = js("globalThis.acid._acid_peak()")
private fun engineResume(): Unit = js("globalThis.acid._acid_resume()")
private fun engineNoteOn(rack: Int, note: Int, velocity: Int): Unit = js("globalThis.acid._acid_note_on(rack, note, velocity)")
private fun engineNoteOff(rack: Int, note: Int): Unit = js("globalThis.acid._acid_note_off(rack, note)")
private fun engineMount(rack: Int, type: String): Int = js(
    "(() => { const m = globalThis.acid; const n = m.lengthBytesUTF8(type) + 1; const p = m._malloc(n); " +
        "m.stringToUTF8(type, p, n); const r = m._acid_mount_machine(rack, p); m._free(p); return r; })()",
)
private fun autoRun(): Boolean = js("location.search.includes('auto')")
private fun report(line: String): Unit = js("fetch('/log', { method: 'POST', body: line }).catch(() => {})")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("root") { Spike() }
}

@Composable
private fun Spike() {
    var peak by remember { mutableStateOf(0.0) }
    var mounted by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    fun toggle() {
        engineResume()
        if (!mounted) mounted = engineMount(0, "Reflux") == 1
        if (playing) engineNoteOff(0, 45) else engineNoteOn(0, 45, 110)
        playing = !playing
    }
    LaunchedEffect(Unit) {
        if (autoRun()) {
            delay(1000)
            toggle()
            report("compose: note on, mounted=$mounted")
            repeat(6) { delay(250); report("compose: peak=" + (enginePeak() * 10000).toInt() / 10000.0) }
            toggle()
            report("compose: done")
        }
        while (true) {
            peak = enginePeak()
            delay(50)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Acidulous · the engine in a browser", color = Color.White)
        Button(onClick = { toggle() }) { Text(if (playing) "Stop the note" else "Play a note") }
        Box(Modifier.width(300.dp).height(16.dp).background(Color(0xFF2A2B30))) {
            Box(Modifier.fillMaxWidth(peak.toFloat().coerceIn(0f, 1f)).height(16.dp).background(Color(0xFF4FB39A)))
        }
    }
}
