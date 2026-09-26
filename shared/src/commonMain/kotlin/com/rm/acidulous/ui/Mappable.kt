package com.rm.acidulous.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rm.acidulous.model.Mapping
import com.rm.acidulous.model.Mappings
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mapping mode, and the one modifier every mappable thing wears.
 *
 * A knob, a fader and a transport button have nothing in common except this:
 * in mapping mode each says it can be mapped, takes a tap to arm itself, and
 * takes a long press to forget what drives it. Putting that in one modifier
 * is what keeps three very different controls behaving identically, and
 * stops the next control that wants mapping from having to be told twice.
 *
 * A target is a string because it has to survive being parked in settings
 * while you reach for the hardware: `"3:machine:cutoff"` for a parameter on
 * a track, `"action:Panic"` for a button.
 */
object MapTargets {
    fun param(rack: Int, unit: String, name: String): String = "$rack:$unit:$name"
    fun action(action: String): String = "action:$action"
}

/**
 * The open song's own mappings, so a control can say whether it is mapped
 * without every one of six hundred call sites being handed the song.
 */
val LocalSongMappings = androidx.compose.runtime.staticCompositionLocalOf { emptyList<Mapping>() }

/** Is anything mapped to this target now? */
@Composable
fun mappedTo(target: String): Mapping? {
    val parts = target.split(":")
    val all = LocalSongMappings.current + UiPrefs.mappings
    return when {
        parts.size == 2 -> all.firstOrNull { it.action == parts[1] }
        parts.size == 3 -> all.firstOrNull { it.unit == parts[1] && it.name == parts[2] }
        else -> null
    }
}

/**
 * The three states a control shows in mapping mode.
 *
 * Colours are roles: mappable is teal, waiting blinks in the accent, mapped
 * is green - the same green that means "this is doing something" everywhere
 * else in the app.
 */
@Composable
fun Modifier.mappable(target: String): Modifier = composed {
    if (!UiPrefs.mapMode) return@composed this

    val waiting = UiPrefs.mapWaiting == target
    val existing = mappedTo(target)
    val blink by rememberInfiniteTransition(label = "map").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink",
    )
    val colour: Color = when {
        waiting -> Acid.colors.accent
        existing != null -> Acid.colors.green
        else -> Acid.colors.teal
    }
    this
        .alpha(if (waiting) blink else 1f)
        .border(2.dp, colour, RoundedCornerShape(6.dp))
        // In mapping mode a control is a thing you are pointing at, not a
        // thing you are using - so its own gesture never runs, and a tap
        // cannot turn a knob or start the transport by accident.
        //
        // Consuming on the Initial pass is what makes that true. The obvious
        // detectTapGestures works on the Main pass, which travels child to
        // parent: a knob's drag or a chip's clickable would see the touch
        // first and eat it, and the switches - where the modifier sits on a
        // Column of buttons rather than the button itself - would never arm
        // at all. Initial travels parent to child, so this gets there first.
        .pointerInput(target, existing) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                var up = false
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) { up = true; break }
                    }
                }
                if (up) {
                    UiPrefs.chooseMapWaiting(target)
                } else {
                    clearMapping(target)
                    // Swallow the rest of the press, or the release lands on
                    // whatever is underneath once the highlight has gone.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
        }
}

/**
 * A long press on something that already does something on tap.
 *
 * Mapping mode hangs off a long press of *redo*, which is a Material button
 * with a `clickable` of its own inside it. A `combinedClickable` on the
 * outside would never be reached, and disabling the button - redo is
 * disabled most of the time - would take the long press with it.
 *
 * So this watches the Initial pass, which travels parent to child, and
 * consumes nothing until the press has lasted long enough to be a long one.
 * Up to that moment the button underneath behaves exactly as it did; after
 * it, the rest of the gesture is eaten so the release does not also redo.
 */
fun Modifier.onLongPress(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).changes.none { it.pressed }) break
            }
        }
        if (lifted != null) return@awaitEachGesture // an ordinary tap; not ours
        action()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** Long press: forget whatever drives this target, in the song and the device alike. */
private fun clearMapping(target: String) {
    val parts = target.split(":")
    val unit = if (parts.size == 3) parts[1] else null
    val name = if (parts.size == 3) parts[2] else null
    val action = if (parts.size == 2) parts[1] else null
    UiPrefs.chooseMappings(Mappings.clearTarget(UiPrefs.mappings, unit, name, action))
    UiPrefs.chooseMapWaiting(null)
}
