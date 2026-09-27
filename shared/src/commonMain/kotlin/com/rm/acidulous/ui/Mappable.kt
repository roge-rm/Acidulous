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
 * Mapping mode, and the modifier every mappable control uses.
 *
 * In mapping mode a knob, fader or transport button shows it can be mapped, a
 * tap arms it and a long press clears its mapping. Keeping that in one modifier
 * makes every control behave the same.
 *
 * A target is a string so it can be kept in settings while you reach for the
 * hardware: "3:machine:cutoff" for a parameter on a track, "action:Panic" for a
 * button.
 */
object MapTargets {
    fun param(rack: Int, unit: String, name: String): String = "$rack:$unit:$name"
    fun action(action: String): String = "action:$action"
}

/**
 * The open song's mappings, so a control can check whether it's mapped without
 * passing the song to every call site.
 */
val LocalSongMappings = androidx.compose.runtime.staticCompositionLocalOf { emptyList<Mapping>() }

/** Whether anything is mapped to this target. */
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
 * The three states a control shows in mapping mode: mappable is teal, waiting
 * blinks in the accent colour, and mapped is green, the same green that means
 * active everywhere else in the app.
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
        // In mapping mode a control's own gesture never runs, so a tap can't
        // turn a knob or start the transport by accident.
        //
        // This consumes on the Initial pass (parent to child). On the Main pass
        // a knob's drag or a chip's clickable would get the touch first, and
        // the switches, where the modifier is on a Column of buttons, would
        // never arm.
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
                    // Consume the rest of the press, otherwise the release
                    // lands on whatever is underneath.
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
 * A long press on something that already has a tap action.
 *
 * Mapping mode is opened by long-pressing redo, a Material button with its own
 * clickable inside. A combinedClickable outside would never be reached, and
 * disabling the button (redo is usually disabled) would disable the long press
 * too.
 *
 * So this watches the Initial pass (parent to child) and consumes nothing until
 * the press is long enough. After that the rest of the gesture is consumed so
 * the release doesn't also redo.
 */
fun Modifier.onLongPress(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).changes.none { it.pressed }) break
            }
        }
        if (lifted != null) return@awaitEachGesture // an ordinary tap, not ours
        action()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** Long press: clear whatever drives this target, in the song and on the device. */
private fun clearMapping(target: String) {
    val parts = target.split(":")
    val unit = if (parts.size == 3) parts[1] else null
    val name = if (parts.size == 3) parts[2] else null
    val action = if (parts.size == 2) parts[1] else null
    UiPrefs.chooseMappings(Mappings.clearTarget(UiPrefs.mappings, unit, name, action))
    UiPrefs.chooseMapWaiting(null)
}
