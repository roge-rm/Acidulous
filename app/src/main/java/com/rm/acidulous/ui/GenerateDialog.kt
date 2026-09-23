package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.DrumVoice
import com.rm.acidulous.model.Generate
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.ui.theme.Acid

/**
 * The generators, over the clip being edited.
 *
 * The clip changes as the settings do, so it can be heard while the song
 * plays, and the whole visit is one step of undo. Each page starts again from
 * the clip as it was when the window opened, so moving between pages tries
 * one generator and then another rather than stacking them. OK keeps what
 * the page you are on made; Cancel puts the clip back.
 *
 * Nothing changes until something is touched: opening the window to look
 * must not cost the clip its notes.
 */
@Composable
fun GenerateDialog(
    editor: SongEditor,
    trackIndex: Int,
    sceneId: String,
    base: Clip,
    clipTicks: Int,
    ticksPerBeat: Int,
    /** The track's scale, or the song's key; null for neither. */
    pitchClasses: Set<Int>?,
    /** Empty for a melodic machine. */
    voices: List<DrumVoice>,
    spelling: Map<Int, String>,
    onDismiss: () -> Unit,
) {
    val drums = voices.isNotEmpty()
    val tabs = if (drums) listOf("rhythm", "scatter", "mutate") else listOf("rhythm", "line", "mutate")
    var tab by remember { mutableStateOf(GenerateMemory.tab.coerceIn(0, tabs.size - 1)) }
    var touched by remember { mutableStateOf(false) }
    val m = GenerateMemory
    if (drums && voices.none { it.note == m.voice }) m.voice = voices.first().note
    // Where this clip already lives, so what is made lands in the part of
    // the roll that is on screen rather than wherever the last clip was.
    remember {
        if (!drums && base.notes.isNotEmpty()) {
            val lowest = base.notes.minOf { it.pitch }
            m.line = m.line.copy(low = lowest.coerceIn(24, 84))
            m.euclid = m.euclid.copy(pitch = lowest.coerceIn(24, 96))
        }
    }

    DisposableEffect(Unit) {
        editor.beginGesture(trackIndex)
        // Leaving any other way than OK - back, a tap outside - is Cancel.
        onDispose { editor.cancelGesture() }
    }

    fun apply() {
        touched = true
        val notes = when (tab) {
            0 -> {
                val made = Generate.euclidNotes(m.euclid.copy(pitch = if (drums) m.voice else m.euclid.pitch), clipTicks)
                // A drum pattern is one voice's, laid over the others; a
                // melodic one is the whole clip.
                if (drums) base.notes.filter { it.pitch != m.voice } + made else made
            }
            1 -> if (drums) {
                val made = Generate.lineNotes(m.line.copy(low = m.voice, octaves = 1, leap = 0f), setOf(m.voice % 12), clipTicks, ticksPerBeat)
                    .map { it.copy(pitch = m.voice) }
                base.notes.filter { it.pitch != m.voice } + made
            } else {
                Generate.lineNotes(m.line, pitchClasses, clipTicks, ticksPerBeat)
            }
            else -> Generate.mutate(base.notes, m.mutation, pitchClasses, clipTicks, base.grid, drums)
        }
        editor.updateGestureClip(sceneId, pushNow = true) { it.copy(notes = notes.sortedBy { n -> n.tick }) }
    }

    TabbedDialog(
        title = "generate",
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "OK",
        onConfirm = {
            editor.endGesture()
            onDismiss()
        },
        chips = {
            SectionChips(tabs, tab) {
                tab = it
                m.tab = it
                if (touched) apply()
            }
        },
        pages = listOf(
            { RhythmPage(drums, voices, spelling) { apply() } },
            { if (drums) ScatterPage(voices) { apply() } else LinePage(spelling) { apply() } },
            { MutatePage { apply() } },
        ),
    )
}

/**
 * What the window was last set to, for the next time it opens. Not saved
 * with anything: it is how you were working, not a property of the music.
 */
private object GenerateMemory {
    var tab by mutableStateOf(0)
    var euclid by mutableStateOf(Generate.Euclid(pitch = 48))
    var line by mutableStateOf(Generate.Line())
    var mutation by mutableStateOf(Generate.Mutation())
    var voice by mutableStateOf(36)
}

private val STEPS = listOf("1/8" to PPQN / 2, "1/16" to PPQN / 4, "1/32" to PPQN / 8, "1/8T" to PPQN / 3, "1/16T" to PPQN / 6)

/**
 * The pages are the arp window's shape: titled cards of knobs and switches,
 * four to a line, all of it in view at once. Dan asked for exactly that after
 * the first version, which was a column of sliders you had to scroll.
 */
@Composable
private fun Cards(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPanelStacked provides true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) =
    Group(title, perLine = 4, centred = true, background = Acid.colors.cardAlt, content = content)

/** A knob over a share, in steps of five per cent for the same reason. */
@Composable
private fun ShareKnob(label: String, value: Float, min: Float = 0f, set: (Float) -> Unit) =
    CountKnob(label, (value * 20f).roundToInt(), (min * 20f).roundToInt()..20, "%.0f%%".format(value * 100f)) { set(it / 20f) }

@Composable
private fun StepSwitch(ticks: Int, pick: (Int) -> Unit) =
    SwitchGrid("step", STEPS.map { it.first }, STEPS.indexOfFirst { it.second == ticks }, columns = 3) { pick(STEPS[it].second) }

@Composable
private fun VoiceSwitch(voices: List<DrumVoice>, changed: () -> Unit) =
    SwitchGrid(
        "voice", voices.map { it.short }, voices.indexOfFirst { it.note == GenerateMemory.voice },
        columns = ((voices.size + 1) / 2).coerceAtMost(8),
    ) { GenerateMemory.voice = voices[it].note; changed() }

/** A new roll of the dice, as a one-cell switch so it sits in a card like the rest. */
@Composable
private fun RollButton(seed: Int, roll: () -> Unit) = SwitchGrid("seed $seed", listOf("roll"), -1) { roll() }

@Composable
private fun RhythmPage(drums: Boolean, voices: List<DrumVoice>, spelling: Map<Int, String>, changed: () -> Unit) {
    val m = GenerateMemory
    val e = m.euclid
    fun set(next: Generate.Euclid) { m.euclid = next; changed() }
    Cards {
        Card("pattern") {
            CountKnob("hits", e.hits, 0..e.steps, "${e.hits}/${e.steps}", choices = (0..e.steps).map { "$it of ${e.steps}" }) { set(e.copy(hits = it)) }
            CountKnob("steps", e.steps, 2..32, choices = (2..32).map { "$it" }) { n -> set(e.copy(steps = n, hits = e.hits.coerceAtMost(n), rotate = e.rotate.coerceAtMost(n - 1))) }
            CountKnob("turn", e.rotate, 0..(e.steps - 1).coerceAtLeast(1)) { set(e.copy(rotate = it.coerceAtMost(e.steps - 1))) }
            StepSwitch(e.stepTicks) { set(e.copy(stepTicks = it)) }
        }
        // The pattern as it will fall, one character a step.
        Text(
            Generate.euclid(e.hits, e.steps, e.rotate).joinToString("") { if (it) "x" else "·" },
            color = Acid.colors.accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1,
        )
        Card("note") {
            if (drums) VoiceSwitch(voices, changed)
            else CountKnob("note", e.pitch, 24..96, noteName(e.pitch, spelling), PanelAmber, choices = (24..96).map { noteName(it, spelling) }) { set(e.copy(pitch = it)) }
            CountKnob("velocity", e.velocity, 1..127) { set(e.copy(velocity = it)) }
        }
    }
}

@Composable
private fun LinePage(spelling: Map<Int, String>, changed: () -> Unit) {
    val m = GenerateMemory
    val l = m.line
    fun set(next: Generate.Line) { m.line = next; changed() }
    Cards {
        Card("notes") {
            ShareKnob("density", l.density, 0.05f) { set(l.copy(density = it)) }
            ShareKnob("leaps", l.leap) { set(l.copy(leap = it)) }
            RollButton(l.seed) { set(l.copy(seed = l.seed + 1)) }
        }
        Card("range") {
            CountKnob("lowest", l.low, 24..84, noteName(l.low, spelling), PanelAmber, choices = (24..84).map { noteName(it, spelling) }) { set(l.copy(low = it)) }
            SwitchGrid("octaves", listOf("1", "2", "3"), l.octaves - 1, columns = 3) { set(l.copy(octaves = it + 1)) }
        }
        Card("time") {
            SwitchGrid("length", listOf("short", "step", "tied"), l.length, columns = 3) { set(l.copy(length = it)) }
            StepSwitch(l.stepTicks) { set(l.copy(stepTicks = it)) }
        }
    }
}

/** The line's dice on one drum. */
@Composable
private fun ScatterPage(voices: List<DrumVoice>, changed: () -> Unit) {
    val m = GenerateMemory
    val l = m.line
    fun set(next: Generate.Line) { m.line = next; changed() }
    Cards {
        Card("hits") {
            ShareKnob("density", l.density, 0.05f) { set(l.copy(density = it)) }
            RollButton(l.seed) { set(l.copy(seed = l.seed + 1)) }
            StepSwitch(l.stepTicks) { set(l.copy(stepTicks = it)) }
        }
        Card("note") { VoiceSwitch(voices, changed) }
    }
}

@Composable
private fun MutatePage(changed: () -> Unit) {
    val m = GenerateMemory
    val mu = m.mutation
    fun set(next: Generate.Mutation) { m.mutation = next; changed() }
    Cards {
        Card("mutate") {
            ShareKnob("amount", mu.amount) { set(mu.copy(amount = it)) }
            RollButton(mu.seed) { set(mu.copy(seed = mu.seed + 1)) }
        }
    }
}
