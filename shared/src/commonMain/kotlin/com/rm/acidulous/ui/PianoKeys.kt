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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors
import com.rm.acidulous.res.*

/**
 * The keyboard. With no scale running it's a normal piano. With a scale
 * running the out-of-scale notes are left out and the rest are packed edge
 * to edge, still coloured like piano keys, so more notes fit and nothing can
 * be played wrong. Plays polyphonically, one note per finger.
 */
@Composable
fun PianoKeys(
    rack: Int,
    scalePitchClasses: Set<Int>?,
    scaleRoot: Int?,
    octave: Int,
    modifier: Modifier = Modifier,
    /** How the running scale spells its notes; empty is chromatic. */
    noteSpelling: Map<Int, String> = emptyMap(),
) {
    val c = Acid.colors
    val measurer = rememberTextMeasurer()
    var held by remember { mutableStateOf(mapOf<Long, Int>()) }
    // How hard each sounding note was struck, 0 at the front edge of its key
    // and 1 at the back. Owned by the gesture like `held`; composition only
    // draws it.
    var strikes by remember { mutableStateOf(mapOf<Int, Float>()) }
    val base = 12 * (octave + 1)
    val octaves = UiPrefs.keyboardOctaves
    val scale = scalePitchClasses?.takeIf { it.isNotEmpty() }?.sorted()

    // The pointer area is the whole box and the keys are drawn inset, so a
    // touch in the small gap next to the wheels still hits the outermost key.
    Box(
        modifier.pointerInput(base, scale, octaves) {
            val grab = EdgeGrab.toPx()
                // The loop owns which keys are down, not composition. Touches arrive
                // faster than recomposition, so reading from drawn state brought back
                // fingers that had already lifted and left keys stuck lit.
                val down = HashMap<Long, Int>()
                val force = HashMap<Int, Float>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Measured against the drawn keys, not the box, with the touch
                            // moved into their space.
                            val layout = Layout(
                                size.width.toFloat() - grab * 2f, size.height.toFloat(),
                                base, MinKey.toPx(), scale, octaves, RoomyKey.toPx(),
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
                                            // Higher on the key is harder, like a pad. Read once when the
                                            // note starts; sliding onto the next key plays it from where
                                            // the finger crossed, which gives a glissando.
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
                    // Changing octave or scale restarts this loop and leaving the
                    // screen cancels it. Either way the fingers that were down never
                    // report going up, so release their notes here.
                    for (note in down.values) NativeEngine.noteOff(rack, note)
                    down.clear()
                    force.clear()
                    held = emptyMap()
                    strikes = emptyMap()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = EdgeGrab).clip(RoundedCornerShape(3.dp))) {
            val layout = Layout(size.width, size.height, base, MinKey.toPx(), scale, octaves, RoomyKey.toPx())
            val down = held.values.toSet()
            // How far up a sounding key to light it. A soft note lights from the
            // front edge to where the finger landed, full strength lights the whole
            // key, so you can see which mode you're in.
            fun lit(note: Int): Float = strikes[note] ?: 1f
            val nameStyle = TextStyle(color = c.keyLabel, fontSize = 8.sp, fontFamily = FontFamily.Monospace)

            if (layout.scaleKeys != null) {
                // Scale mode: one key per usable note, packed together.
                // The tonic is named.
                val root = scaleRoot
                for ((i, note) in layout.scaleKeys.withIndex()) {
                    val x = i * layout.keyW
                    val black = isBlackKey(note)
                    val colour = when {
                        down.contains(note) -> if (black) c.green else c.teal
                        // Lighter than true black, which disappears on this background.
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
                    // The tonic gets its name so the scale has a landmark.
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
        // After the drawing, so the TalkBack keys lie over it. They draw
        // nothing themselves.
        KeysForTalkBack(rack, base, scale, noteSpelling)
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
 * The scale chip beside the keys, turned on its side to save width. A tap
 * switches the scale off and on, a long press opens the selector.
 */
@Composable
fun ScaleChip(
    label: String?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** On its side next to the keys; flat in a strip. */
    vertical: Boolean = true,
    /**
     * A glyph to show instead of the label when the row is short on room. The
     * scale name is dropped too, since it wouldn't fit. Holding the chip
     * shows which scale it is.
     */
    icon: String? = null,
) = SlotChip(
    icon ?: label ?: stringResource(Res.string.scale_chip), label != null, onToggle, onOpen, modifier, vertical, icon != null,
    said = label ?: stringResource(Res.string.scale_chip),
)

/**
 * The chip used for everything between what you play and what sounds (the
 * scale and the modifiers): a tap turns it on or off, a long press opens it.
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
     * The text is a single glyph standing in for a word, so it's drawn at glyph
     * size and never ellipsised.
     */
    icon: Boolean = false,
    /** The TalkBack label, for when [text] is a glyph. */
    said: String = text,
) {
    val c = Acid.colors
    // pointerInput only restarts when its key (`on`) changes, so the callbacks
    // must be read through rememberUpdatedState. Otherwise the block keeps stale
    // lambdas: holding the arp chip on an empty slot fills it bypassed, `on`
    // stays false, and the next hold refills the slot and loses its settings.
    val cb by rememberUpdatedState(onToggle to onOpen)
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.accentDim else c.card)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { cb.second() }, onTap = { cb.first() }) }
            .button(
                said, stringResource(if (on) Res.string.a11y_on else Res.string.a11y_off),
                listOf(action(stringResource(Res.string.a11y_open_it)) { cb.second() }),
                onClick = { cb.first() },
            ),
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

/** A key narrower than this is too hard to hit. */
private val MinKey = 23.dp
/** On a wide screen, more octaves are added only while the white keys stay this wide. */
private val RoomyKey = 40.dp

/**
 * How far past the drawn keys a touch still counts. Same as the gap between
 * the keyboard and the wheels, so the first and last key get it.
 */
private val EdgeGrab = 3.dp

/** Semitone offsets of one octave's white keys. */
private val WhiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/**
 * Key layout for both modes. Piano mode shows as many octaves as [octaves]
 * asks for, or with 0 the most that keep the keys wide enough: up to two
 * where they can be [minKey], and up to five where there's room for
 * [roomyKey]. Scale mode fits as many in-scale notes as it can.
 */
private class Layout(
    val width: Float, val height: Float, val base: Int, minKey: Float, scale: List<Int>?,
    octaves: Int = 0, roomyKey: Float = minKey,
) {
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
        octaves > 0 -> 7 * octaves
        width / 35f >= roomyKey -> 35 // five octaves, on a wide screen
        width / 28f >= roomyKey -> 28
        width / 21f >= roomyKey -> 21
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

    /** The black key under a touch, if any. */
    private fun blackAt(p: Offset): Int? {
        if (scaleKeys != null || p.y > blackH) return null
        for ((x, note) in blacks()) if (p.x >= x && p.x <= x + blackW) return note
        return null
    }

    fun noteAt(p: Offset): Int? {
        // Sideways, coerceIn below maps a touch in the margin past either end to
        // the outermost key. Above or below the keys is nothing.
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
     * back. This is the velocity, and how far the key is lit.
     *
     * Measured along the key that was hit, not the whole keyboard. Black keys
     * only cover the back two thirds, so otherwise they could never be played
     * softly.
     */
    fun strikeAt(p: Offset): Float {
        val h = if (blackAt(p) != null) blackH else height
        return if (h > 0f) (1f - p.y / h).coerceIn(0f, 1f) else 1f
    }
}

// --- The scale selector ------------------------------------------------------------
//
// Opened by holding the scale chip. The key across the top, how the scale is
// applied, then the scales grouped by family. The row of dots shows the
// selected scale's notes.

/** How a Scale modifier is set up, as the dialog sees it. */
data class ScaleSetting(val on: Boolean, val key: Int, val scale: Int, val degree: Boolean, val snap: Int)

private val ScaleGroups = listOf(
    Res.string.scale_group_modes to 0..6,
    Res.string.scale_group_minor to 7..8,
    Res.string.scale_group_pentatonic to 9..13,
    Res.string.scale_group_world to 14..23,
    Res.string.scale_group_jazz to 24..29,
    Res.string.scale_group_symmetric to 30..32,
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
    // Open on the current scale's family.
    var tab by rememberSaveable(current.scale) {
        mutableStateOf(ScaleGroups.indexOfFirst { current.scale in it.second }.coerceAtLeast(0))
    }

    /**
     * The key, mode and notes rows shown above the scales on every page.
     * `TabbedDialog` has no slot above the tabs, so each page draws it.
     */
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Twelve keys sharing the width by weight, so all are reachable.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until 12) {
                    val on = i == s.key
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (on) c.green else c.controlAlt)
                            .clickable { s = s.copy(key = i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            com.rm.acidulous.model.Scales.rootName(i, s.scale),
                            color = if (on) Color.White else c.textMid,
                            fontSize = 12.sp, maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            // Centred, since these three are the main choice in the window.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            ) {
                Pill(stringResource(Res.string.scale_off), !s.on) { s = s.copy(on = false) }
                Pill(stringResource(Res.string.scale_snap), s.on && !s.degree) { s = s.copy(on = true, degree = false) }
                Pill(stringResource(Res.string.scale_degrees), s.on && s.degree) { s = s.copy(on = true, degree = true) }
            }
            if (s.on && !s.degree) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                ) {
                    stringArrayResource(Res.array.scale_snap_choices).forEachIndexed { i, n ->
                        Pill(n, s.snap == i) { s = s.copy(snap = i) }
                    }
                }
            }
            // The notes in the scale, centred under the keys.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (pc in 0 until 12) {
                    if (pc !in pitches) continue
                    Box(
                        Modifier.width(30.dp).height(24.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (pc == s.key) c.accent else c.green),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            spelling[pc] ?: com.rm.acidulous.model.Scales.keyNames[pc],
                            color = Color.White, fontSize = 10.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    TabbedDialog(
        title = stringResource(Res.string.scale_title),
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.cancel),
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = { onApply(s) },
        // Tab chips wrap and size to their words instead of sharing the width,
        // which cut six family names short.
        chips = {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                ScaleGroups.forEachIndexed { i, (title, _) -> Pill(stringResource(title), i == tab) { tab = i } }
            }
        },
        // Scales grouped into families behind tabs, like the machine picker.
        pages = ScaleGroups.map { (_, range) ->
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    header()
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (i in range) {
                            val on = i == s.scale
                            Box(
                                Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(if (on) c.green else c.control)
                                    .clickable { s = s.copy(scale = i, on = true) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    com.rm.acidulous.model.Scales.names[i],
                                    color = if (on) Color.White else c.textHi, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.green else c.controlAlt)
            .clickable(onClick = onClick).choice(label, on).padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(label, color = if (on) Color.White else c.textMid, fontSize = 11.sp) }
}

/**
 * One TalkBack node per key, since the canvas is a single picture. Each is
 * laid over its key, named for its note, and a double tap plays it briefly.
 */
@Composable
private fun KeysForTalkBack(rack: Int, base: Int, scale: List<Int>?, spelling: Map<Int, String>) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val resources = AppStrings
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = EdgeGrab)) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val layout = Layout(w, h, base, with(density) { MinKey.toPx() }, scale, UiPrefs.keyboardOctaves, with(density) { RoomyKey.toPx() })
        // In pitch order, which is the order TalkBack walks them. Black keys are
        // raised over the whites so a touch on a black key's right half doesn't
        // hit the next white key.
        val keys = ArrayList<Triple<Int, Float, Float>>() // note, x, width
        val blackH: Float
        if (layout.scaleKeys != null) {
            layout.scaleKeys.forEachIndexed { i, note -> keys += Triple(note, i * layout.keyW, layout.keyW) }
            blackH = 0f
        } else {
            for (i in 0 until layout.whiteCount) keys += Triple(layout.whiteNote(i), i * layout.keyW, layout.keyW)
            for ((x, note) in layout.blacks()) keys += Triple(note, x, layout.blackW)
            blackH = layout.blackH
        }
        for ((note, x, kw) in keys.sortedBy { it.first }) {
            val black = layout.scaleKeys == null && ((note % 12) in intArrayOf(1, 3, 6, 8, 10))
            Box(
                Modifier
                    .zIndex(if (black) 1f else 0f)
                    .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), 0) }
                    .size(with(density) { kw.toDp() }, with(density) { (if (black) blackH else h).toDp() })
                    .button(spokenNote(note, spelling, resources), onClick = {
                        NativeEngine.noteOn(rack, note, 100)
                        scope.launch { kotlinx.coroutines.delay(300); NativeEngine.noteOff(rack, note) }
                    }),
            )
        }
    }
}

/** A note as it's spoken: "C sharp 4", not "C#4". */
internal fun spokenNote(pitch: Int, spelling: Map<Int, String>, resources: AppStrings): String {
    val written = noteName(pitch, spelling)
    val octave = pitch / 12 - 1
    val name = written.dropLast(octave.toString().length)
    val letter = name.take(1)
    return when {
        name.contains('#') || name.contains('♯') -> resources.getString(Res.string.a11y_note_sharp, letter, octave)
        name.length > 1 && (name[1] == 'b' || name[1] == '♭') -> resources.getString(Res.string.a11y_note_flat, letter, octave)
        else -> resources.getString(Res.string.a11y_note, letter, octave)
    }
}
