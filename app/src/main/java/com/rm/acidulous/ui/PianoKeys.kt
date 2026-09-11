package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The keyboard, in two shapes.
 *
 * With no scale running it is an ordinary piano: white keys across the
 * bottom, black keys overlaid where they belong, every C named.
 *
 * With a scale running the out-of-scale notes are simply gone, and what is
 * left is packed edge to edge - still coloured as the piano colours them, so
 * the shape of the scale stays legible, but no longer laid out as a piano.
 * Nothing can be played wrong, and two or three times as many usable notes
 * fit in the same width.
 *
 * Either way it plays polyphonically, one note per finger.
 */
@Composable
fun PianoKeys(
    rack: Int,
    scalePitchClasses: Set<Int>?,
    scaleRoot: Int?,
    octave: Int,
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    val measurer = rememberTextMeasurer()
    var held by remember { mutableStateOf(mapOf<Long, Int>()) }
    val base = 12 * (octave + 1)
    val scale = scalePitchClasses?.takeIf { it.isNotEmpty() }?.sorted()

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).pointerInput(base, scale) {
                // What is down is owned by this loop, not by composition.
                // Rebuilding it from the drawn state each event was the bug:
                // touches arrive faster than recomposition, so a finger that
                // had just lifted was read back out of a stale snapshot and
                // put down again, and its key stayed lit with nothing on it.
                val down = HashMap<Long, Int>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val layout =
                                Layout(size.width.toFloat(), size.height.toFloat(), base, MinKey.toPx(), scale)
                            var changed = false
                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    val note = layout.noteAt(change.position)
                                    if (down[id] != note) {
                                        down[id]?.let { NativeEngine.noteOff(rack, it) }
                                        if (note != null) {
                                            NativeEngine.noteOn(rack, note, 100)
                                            down[id] = note
                                        } else {
                                            down.remove(id)
                                        }
                                        changed = true
                                    }
                                } else if (down.containsKey(id)) {
                                    down.remove(id)?.let { NativeEngine.noteOff(rack, it) }
                                    changed = true
                                }
                                change.consume()
                            }
                            if (changed) held = HashMap(down)
                        }
                    }
                } finally {
                    // Changing octave or scale restarts this loop, and leaving
                    // the screen cancels it. Either way the fingers that were
                    // down will never report going up, so let go of them here
                    // rather than leaving notes sounding and keys lit.
                    for (note in down.values) NativeEngine.noteOff(rack, note)
                    down.clear()
                    held = emptyMap()
                }
            },
        ) {
            val layout = Layout(size.width, size.height, base, MinKey.toPx(), scale)
            val down = held.values.toSet()
            val nameStyle = TextStyle(color = c.keyLabel, fontSize = 8.sp, fontFamily = FontFamily.Monospace)

            if (layout.scaleKeys != null) {
                // Scale mode: one key per usable note, packed together.
                // The tonic is named, not merely the lowest key on screen.
                val root = scaleRoot
                for ((i, note) in layout.scaleKeys.withIndex()) {
                    val x = i * layout.keyW
                    val black = isBlackKey(note)
                    val colour = when {
                        down.contains(note) -> if (black) c.green else c.teal
                        // Light enough to read as a key rather than a gap: on
                        // this background a true black note disappears.
                        black -> c.keyBlack
                        else -> c.keyWhite
                    }
                    drawRect(colour, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height))
                    if (black) {
                        drawRect(c.keyEdge, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height),
                            style = Stroke(1f))
                    }
                    // The tonic gets its name, so the scale has a landmark.
                    if (root != null && ((note % 12) + 12) % 12 == root) {
                        val laid = measurer.measure(
                            AnnotatedString(noteName(note)),
                            nameStyle.copy(color = if (black) c.keyLabelBlack else c.keyLabel),
                        )
                        if (laid.size.width < layout.keyW - 2f) {
                            drawText(laid, topLeft = Offset(x + (layout.keyW - laid.size.width) / 2f,
                                                            size.height - laid.size.height - 3f))
                        }
                    }
                }
            } else {
                for (i in 0 until layout.whiteCount) {
                    val note = layout.whiteNote(i)
                    val x = i * layout.keyW
                    val colour = if (down.contains(note)) c.teal else c.keyWhite
                    drawRect(colour, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height))
                    if (note % 12 == 0) {
                        val laid = measurer.measure(AnnotatedString(noteName(note)), nameStyle)
                        if (laid.size.width < layout.keyW - 2f) {
                            drawText(laid, topLeft = Offset(x + (layout.keyW - laid.size.width) / 2f,
                                                            size.height - laid.size.height - 3f))
                        }
                    }
                }
                for ((x, note) in layout.blacks()) {
                    val colour = if (down.contains(note)) c.green else c.blackKey
                    drawRect(colour, Offset(x, 0f), Size(layout.blackW, layout.blackH))
                    drawRect(c.blackKeyEdge, Offset(x, 0f), Size(layout.blackW, layout.blackH), style = Stroke(1f))
                }
            }
        }
        // Octave up and down, stacked so they cost one narrow column rather
        // than two, which is width the keys would rather have.
    }
}

@Composable
private fun OctaveKey(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(c.controlAlt)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) c.text else c.textFaint, fontSize = 10.sp) }
}

/**
 * The chip beside the keys, turned on its side so it costs almost no width:
 * every millimetre it gives back is another key. A tap switches the scale
 * off and on, a long press opens the selector.
 */
@Composable
fun ScaleChip(
    label: String?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Turned on its side when it stands beside the keys; flat in a strip. */
    vertical: Boolean = true,
) = SlotChip(label ?: "scale off", label != null, onToggle, onOpen, modifier, vertical)

/**
 * The chip grammar the keyboard strip uses for everything that sits between
 * what you play and what sounds: **a tap turns it on or off, a long press
 * opens it**. The common question - is this running? - costs one tap and is
 * answerable at a glance; choosing and configuring is rare and lives a level
 * down. The scale chip established it; the eventor chips either side of it
 * follow it exactly, because two controls doing the same job should not want
 * two different gestures.
 */
@Composable
fun SlotChip(
    text: String,
    on: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
) {
    val c = Acid.colors
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.accentDim else c.card)
            .pointerInput(on) { detectTapGestures(onLongPress = { onOpen() }, onTap = { onToggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (on) c.accent else c.textDim,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
            // Laid out long and then turned: rotation does not change a
            // layout's size, so the width has to be demanded before it spins.
            modifier = if (vertical) Modifier.requiredWidth(96.dp).rotate(-90f) else Modifier,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Narrower than this and a key is harder to hit than it is worth. */
private val MinKey = 23.dp

/** Semitone offsets of one octave's white keys. */
private val WhiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/**
 * Key geometry for both shapes. Piano mode picks two octaves, an octave and
 * a half, or one, by what leaves a key wide enough to hit; scale mode simply
 * takes as many in-scale notes as fit.
 */
private class Layout(val width: Float, val height: Float, val base: Int, minKey: Float, scale: List<Int>?) {
    val scaleKeys: List<Int>? = scale?.let { pcs ->
        val count = ((width / minKey).toInt()).coerceIn(5, 28)
        val out = ArrayList<Int>(count)
        var note = base
        while (out.size < count && note < 128) {
            if (pcs.contains(((note % 12) + 12) % 12)) out += note
            ++note
        }
        out
    }

    val whiteCount: Int = when {
        scaleKeys != null -> scaleKeys.size.coerceAtLeast(1)
        width / 14f >= minKey -> 14 // two octaves
        width / 11f >= minKey -> 11 // an octave and a half
        else -> 7
    }
    val keyW = width / whiteCount
    val whiteW = keyW
    val blackW = keyW * 0.62f
    val blackH = height * 0.62f

    fun whiteSemitone(i: Int): Int = 12 * (i / 7) + WhiteSteps[i % 7]
    fun whiteNote(i: Int): Int = base + whiteSemitone(i)

    /** Left edge and note of every black key that has white keys either side. */
    fun blacks(): List<Pair<Float, Int>> {
        val out = ArrayList<Pair<Float, Int>>()
        for (i in 0 until whiteCount - 1) {
            if (whiteSemitone(i + 1) - whiteSemitone(i) != 2) continue // E-F and B-C have none
            out += ((i + 1) * keyW - blackW / 2f) to (whiteNote(i) + 1)
        }
        return out
    }

    fun noteAt(p: Offset): Int? {
        if (p.x < 0f || p.x > width || p.y < 0f || p.y > height) return null
        scaleKeys?.let { keys ->
            val i = (p.x / keyW).toInt().coerceIn(0, keys.size - 1)
            return keys.getOrNull(i)
        }
        if (p.y <= blackH) {
            for ((x, note) in blacks()) if (p.x >= x && p.x <= x + blackW) return note
        }
        return whiteNote((p.x / keyW).toInt().coerceIn(0, whiteCount - 1))
    }
}

// --- The scale selector ------------------------------------------------------------
//
// Reached by holding the scale chip. It is a picker, not a panel: the key
// across the top, how the scale is applied, then the scales themselves in the
// families they belong to. The row of twelve dots shows the shape of whatever
// is selected, so an unfamiliar name still tells you something.

/** How a Scale eventor is set up, as the dialog sees it. */
data class ScaleSetting(val on: Boolean, val key: Int, val scale: Int, val degree: Boolean, val snap: Int)

private val ScaleGroups = listOf(
    "Modes" to 0..6,
    "Minor variants" to 7..8,
    "Pentatonic & blues" to 9..13,
    "World & exotic" to 14..23,
    "Jazz" to 24..29,
    "Symmetric" to 30..32,
)

@Composable
fun ScaleDialog(current: ScaleSetting, onDismiss: () -> Unit, onApply: (ScaleSetting) -> Unit) {
    val c = Acid.colors
    var s by remember(current) { mutableStateOf(current) }
    val pitches = remember(s.key, s.scale) {
        (com.rm.acidulous.model.Scales.intervals.getOrNull(s.scale) ?: emptyList())
            .map { (it + s.key) % 12 }.toSet()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scale", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScrollWithBar(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    com.rm.acidulous.model.Scales.keyNames.forEachIndexed { i, name ->
                        Pill(name, i == s.key) { s = s.copy(key = i) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Pill("off", !s.on) { s = s.copy(on = false) }
                    Pill("snap", s.on && !s.degree) { s = s.copy(on = true, degree = false) }
                    Pill("degrees", s.on && s.degree) { s = s.copy(on = true, degree = true) }
                }
                if (s.on && !s.degree) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("nearest", "down", "up").forEachIndexed { i, n ->
                            Pill(n, s.snap == i) { s = s.copy(snap = i) }
                        }
                    }
                }
                // The shape of the scale: twelve semitones, the ones it keeps lit.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (pc in 0 until 12) {
                        Box(
                            Modifier.width(18.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(
                                when {
                                    pc == s.key -> c.accent
                                    pc in pitches -> c.green
                                    else -> c.controlAlt
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(com.rm.acidulous.model.Scales.keyNames[pc].take(2),
                                color = if (pc in pitches) Color.White else c.textFaint, fontSize = 7.sp)
                        }
                    }
                }
                Column(
                    Modifier.height(220.dp).verticalScrollWithBar(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for ((title, range) in ScaleGroups) {
                        Text(title, color = Acid.colors.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                        for (i in range) {
                            val on = i == s.scale
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))
                                    .background(if (on) c.green else c.control)
                                    .clickable { s = s.copy(scale = i, on = true) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Text(com.rm.acidulous.model.Scales.names[i],
                                    color = if (on) Color.White else c.textHi, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.Button(onClick = { onApply(s) }) { Text("OK") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.green else c.controlAlt)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(label, color = if (on) Color.White else c.textMid, fontSize = 11.sp) }
}
