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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    /** How the running scale writes its notes; empty is chromatic. */
    noteSpelling: Map<Int, String> = emptyMap(),
) {
    val c = Acid.colors
    val measurer = rememberTextMeasurer()
    var held by remember { mutableStateOf(mapOf<Long, Int>()) }
    // How hard each sounding note was struck, 0 at the front edge of its key
    // and 1 at the back. Kept beside `held` and for the same reason: the
    // gesture owns it, composition only draws it.
    var strikes by remember { mutableStateOf(mapOf<Int, Float>()) }
    val base = 12 * (octave + 1)
    val scale = scalePitchClasses?.takeIf { it.isNotEmpty() }?.sorted()

    // The pointer area is the whole box and the keys are drawn inset into it,
    // so the gap between the keyboard and the wheel either side belongs to
    // the keys rather than to nobody. The outermost key is the one a finger
    // misses - it is as wide as its neighbours but has a wheel three dp away
    // instead of another key - and this hands it that three dp to be hit in.
    Box(
        modifier.pointerInput(base, scale) {
            val grab = EdgeGrab.toPx()
                // What is down is owned by this loop, not by composition.
                // Rebuilding it from the drawn state each event was the bug:
                // touches arrive faster than recomposition, so a finger that
                // had just lifted was read back out of a stale snapshot and
                // put down again, and its key stayed lit with nothing on it.
                val down = HashMap<Long, Int>()
                val force = HashMap<Int, Float>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Measured against the drawn keys, not the box,
                            // and the touch is moved into their space.
                            val layout = Layout(
                                size.width.toFloat() - grab * 2f, size.height.toFloat(),
                                base, MinKey.toPx(), scale,
                            )
                            var changed = false
                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    val note = layout.noteAt(
                                        Offset(change.position.x - grab, change.position.y),
                                    )
                                    if (down[id] != note) {
                                        down[id]?.let { NativeEngine.noteOff(rack, it); force.remove(it) }
                                        if (note != null) {
                                            // Hit it high for hard and low for
                                            // soft, as a pad is. Read once, as
                                            // the note goes down: a finger that
                                            // slides afterwards has already
                                            // played it, and sliding onto the
                                            // next key plays that one from
                                            // wherever it crossed - which is
                                            // what a glissando is.
                                            val strike = if (UiPrefs.keysFullStrength) 1f else {
                                                layout.strikeAt(
                                                    Offset(change.position.x - grab, change.position.y),
                                                )
                                            }
                                            val vel = if (UiPrefs.keysFullStrength) HARD
                                            else SOFT + (HARD - SOFT) * strike
                                            NativeEngine.noteOn(rack, note, vel.toInt())
                                            down[id] = note
                                            force[note] = strike
                                        } else {
                                            down.remove(id)
                                        }
                                        changed = true
                                    }
                                } else if (down.containsKey(id)) {
                                    down.remove(id)?.let { NativeEngine.noteOff(rack, it); force.remove(it) }
                                    changed = true
                                }
                                change.consume()
                            }
                            if (changed) {
                                held = HashMap(down)
                                strikes = HashMap(force)
                            }
                        }
                    }
                } finally {
                    // Changing octave or scale restarts this loop, and leaving
                    // the screen cancels it. Either way the fingers that were
                    // down will never report going up, so let go of them here
                    // rather than leaving notes sounding and keys lit.
                    for (note in down.values) NativeEngine.noteOff(rack, note)
                    down.clear()
                    force.clear()
                    held = emptyMap()
                    strikes = emptyMap()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = EdgeGrab).clip(RoundedCornerShape(3.dp))) {
            val layout = Layout(size.width, size.height, base, MinKey.toPx(), scale)
            val down = held.values.toSet()
            // How far up a sounding key to light it. **The fill is the only
            // thing that says which mode you are in once your finger is down**
            // - that is the drum pads' note about their own highlight, and it
            // holds here for the same reason - so a key struck softly lights
            // from its front edge to where the finger landed, and full
            // strength lights the whole of it.
            fun lit(note: Int): Float = strikes[note] ?: 1f
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
                    if (down.contains(note)) {
                        val base = if (black) c.keyBlack else c.keyWhite
                        val fill = size.height * lit(note)
                        drawRect(base, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height))
                        drawRect(colour, Offset(x + 0.5f, size.height - fill), Size(layout.keyW - 1f, fill))
                    } else {
                        drawRect(colour, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height))
                    }
                    if (black) {
                        drawRect(c.keyEdge, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height),
                            style = Stroke(1f))
                    }
                    // The tonic gets its name, so the scale has a landmark.
                    if (root != null && ((note % 12) + 12) % 12 == root) {
                        val laid = measurer.measure(
                            AnnotatedString(noteName(note, noteSpelling)),
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
                    drawRect(c.keyWhite, Offset(x + 0.5f, 0f), Size(layout.keyW - 1f, size.height))
                    if (down.contains(note)) {
                        val fill = size.height * lit(note)
                        drawRect(c.teal, Offset(x + 0.5f, size.height - fill), Size(layout.keyW - 1f, fill))
                    }
                    if (note % 12 == 0) {
                        val laid = measurer.measure(AnnotatedString(noteName(note, noteSpelling)), nameStyle)
                        if (laid.size.width < layout.keyW - 2f) {
                            drawText(laid, topLeft = Offset(x + (layout.keyW - laid.size.width) / 2f,
                                                            size.height - laid.size.height - 3f))
                        }
                    }
                }
                for ((x, note) in layout.blacks()) {
                    drawRect(c.blackKey, Offset(x, 0f), Size(layout.blackW, layout.blackH))
                    if (down.contains(note)) {
                        val fill = layout.blackH * lit(note)
                        drawRect(c.green, Offset(x, layout.blackH - fill), Size(layout.blackW, fill))
                    }
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
    /**
     * A glyph to stand in for the whole label, when the row cannot afford it.
     *
     * **And the scale's name goes with the word, not just instead of it.**
     * "C Ionian (Major)" squeezed into a chip the size of a finger is three
     * letters and an ellipsis, which says less than nothing. Lit or unlit
     * already answers the question the chip is there for - is a scale
     * running - and which scale it is, is what holding it open is for.
     */
    icon: String? = null,
) = SlotChip(icon ?: label ?: "scale", label != null, onToggle, onOpen, modifier, vertical, icon != null)

/**
 * The chip grammar the keyboard strip uses for everything that sits between
 * what you play and what sounds: **a tap turns it on or off, a long press
 * opens it**. The common question - is this running? - costs one tap and is
 * answerable at a glance; choosing and configuring is rare and lives a level
 * down. The scale chip established it; the modifier chips either side of it
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
    /**
     * The text is a single glyph standing in for a word, so it is drawn at the
     * size a glyph needs rather than the size three or four letters need - and
     * never ellipsised, because there is nothing to ellipsise.
     */
    icon: Boolean = false,
) {
    val c = Acid.colors
    // **The callbacks have to be the current ones.** `pointerInput` restarts
    // only when its key changes, and the key here was `on` - so the block held
    // whichever lambdas were in scope the last time the chip's lit state
    // changed, and went on calling them for as long as it did not.
    //
    // That cost the arp every setting anybody made. Holding the chip fills an
    // empty slot *bypassed*, so `on` stays false; the captured lambda goes on
    // believing the slot is empty; and the next hold fills it again, which
    // replaces the slot and throws the parameters away. It looked exactly like
    // a window that did not save. `Knob` has guarded against this since it was
    // written, with the same three lines.
    val cb by rememberUpdatedState(onToggle to onOpen)
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.accentDim else c.card)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { cb.second() }, onTap = { cb.first() }) },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (on) c.accent else c.textDim
        if (vertical) {
            SideText(text, tint, 9.sp, length = 96.dp, family = FontFamily.Monospace)
        } else {
            Text(
                text, color = tint,
                fontSize = if (icon) 14.sp else 9.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Narrower than this and a key is harder to hit than it is worth. */
private val MinKey = 23.dp

/**
 * How far past the drawn keys a touch still counts as one.
 *
 * The same three dp that separates the keyboard from the wheel either side,
 * so the gap is drawn rather than merely empty and the first and last key
 * each get it to be hit in.
 */
private val EdgeGrab = 3.dp

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

    /** The black key under a touch, if it is on one. */
    private fun blackAt(p: Offset): Int? {
        if (scaleKeys != null || p.y > blackH) return null
        for ((x, note) in blacks()) if (p.x >= x && p.x <= x + blackW) return note
        return null
    }

    fun noteAt(p: Offset): Int? {
        // Across, the coerceIn below does the work: a touch in the grab margin
        // beyond either end is the outermost key, which is what a finger that
        // slightly missed it meant. Above and below really is nothing.
        if (p.y < 0f || p.y > height) return null
        scaleKeys?.let { keys ->
            val i = (p.x / keyW).toInt().coerceIn(0, keys.size - 1)
            return keys.getOrNull(i)
        }
        blackAt(p)?.let { return it }
        return whiteNote((p.x / keyW).toInt().coerceIn(0, whiteCount - 1))
    }

    /**
     * How far up its own key a touch landed, 0 at the front edge and 1 at the
     * back. This is the velocity, and the fill that draws it.
     *
     * **Up the key it hit, not up the keyboard.** A black key is drawn over
     * the back two thirds of the whites, so measuring against the whole
     * keyboard would leave it nothing below a third and no way to play one
     * softly at all. Against its own length every key has the full range, and
     * the black keys are simply steeper - which is what they feel like under a
     * finger anyway.
     */
    fun strikeAt(p: Offset): Float {
        val h = if (blackAt(p) != null) blackH else height
        return if (h > 0f) (1f - p.y / h).coerceIn(0f, 1f) else 1f
    }
}

// --- The scale selector ------------------------------------------------------------
//
// Reached by holding the scale chip. It is a picker, not a panel: the key
// across the top, how the scale is applied, then the scales themselves in the
// families they belong to. The row of twelve dots shows the shape of whatever
// is selected, so an unfamiliar name still tells you something.

/** How a Scale modifier is set up, as the dialog sees it. */
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
    // C Dorian is C D E♭ F G A B♭, not C D D♯ F G A A♯.
    val spelling = remember(s.key, s.scale) { com.rm.acidulous.model.Scales.spelling(s.key, s.scale) }
    PlainDialog(
        title = "Scale",
        onDismiss = onDismiss,
        confirmLabel = "OK",
        onConfirm = { onApply(s) },
        spacing = 6.dp,
    ) {
        run {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Twelve keys, wrapped rather than scrolled sideways. There is
                // nothing off the end of a wrapped row to go looking for, and
                // a key you cannot see is a key you will not use.
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (i in 0 until 12) {
                        Pill(com.rm.acidulous.model.Scales.rootName(i, s.scale), i == s.key) {
                            s = s.copy(key = i)
                        }
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
                // The shape of the scale: the notes it keeps, and nothing
                // else. It used to show all twelve with the rejected ones
                // greyed, which asked you to read past them to see the
                // scale; the notes that are in it are the answer.
                // A FlowRow, by the usual rule: the widest scale
                // here is eight notes and fits, but a fixed Row that does
                // not fit crushes its children rather than wrapping them.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (pc in 0 until 12) {
                        if (pc !in pitches) continue
                        Box(
                            Modifier.width(26.dp).height(20.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (pc == s.key) c.accent else c.green),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                spelling[pc] ?: com.rm.acidulous.model.Scales.keyNames[pc],
                                color = Color.White, fontSize = 9.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                // **Thirty-three scales, wrapped, in the window's own scroll.**
                //
                // They were full-width rows in a box 220 dp tall - about
                // eleven hundred dp of list in it, so five of the
                // thirty-three showed and the rest were a long drag away. And
                // it was a scroller inside the dialog's scroller, which is its
                // own trap: whichever one takes the gesture, the other looks
                // broken.
                //
                // A scale's name is the width of its name, so wrapped chips
                // fit three or four to a row and the groups keep their
                // headings. What is left over scrolls with the rest of the
                // window, once, with a position bar the shell already draws.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((title, range) in ScaleGroups) {
                        Text(title, color = Acid.colors.teal, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            for (i in range) {
                                val on = i == s.scale
                                Box(
                                    Modifier.clip(RoundedCornerShape(3.dp))
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
            }
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.green else c.controlAlt)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(label, color = if (on) Color.White else c.textMid, fontSize = 11.sp) }
}
