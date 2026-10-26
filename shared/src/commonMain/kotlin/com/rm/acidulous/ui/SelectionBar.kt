package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * What the little bar next to selected notes does. [onPaste] gets the tick a
 * tap chose, or null to paste where the notes came from (or at the keyboard
 * cursor).
 */
class SelectionActions(
    val onDelete: () -> Unit,
    val onCopy: () -> Unit,
    val onDuplicate: () -> Unit,
    val canPaste: Boolean,
    val onPaste: (tick: Int?) -> Unit,
)

/** Delete, copy and duplicate when [hasNotes], and paste when there is something to paste. */
@Composable
fun SelectionBar(actions: SelectionActions, hasNotes: Boolean, pasteTick: Int? = null, modifier: Modifier = Modifier) {
    val c = Acid.colors
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.clip(shape).background(c.panelAlt).border(1.dp, c.accent, shape)) {
        if (hasNotes) {
            BarKey("✕", stringResource(Res.string.a11y_delete_notes)) { actions.onDelete() }
            BarKey("⎘", stringResource(Res.string.a11y_copy_notes)) { actions.onCopy() }
            BarKey("⧉", stringResource(Res.string.a11y_duplicate_notes)) { actions.onDuplicate() }
        }
        if (actions.canPaste) BarKey("⇩", stringResource(Res.string.a11y_paste_notes)) { actions.onPaste(pasteTick) }
    }
}

@Composable
private fun BarKey(glyph: String, said: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 42.dp, height = 40.dp).clickable(onClick = onClick).button(said),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Acid.colors.text, fontSize = 18.sp)
    }
}

/**
 * Puts a bar above [target], or below it if there's no room above, and keeps
 * it on screen. [target] is worked out against the parent's size when it's laid
 * out, so it follows scrolling and zooming.
 */
fun Modifier.anchoredTo(gapPx: Float = 8f, target: (Size) -> Rect?): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val r = target(size)
    layout(p.width, p.height) {
        if (r != null) {
            val x = (r.center.x - p.width / 2f).coerceIn(0f, (size.width - p.width).coerceAtLeast(0f))
            var y = r.top - p.height - gapPx
            if (y < 0f) y = r.bottom + gapPx
            y = y.coerceIn(0f, (size.height - p.height).coerceAtLeast(0f))
            p.place(IntOffset(x.roundToInt(), y.roundToInt()))
        }
    }
}
