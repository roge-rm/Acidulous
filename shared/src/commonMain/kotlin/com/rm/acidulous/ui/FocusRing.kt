package com.rm.acidulous.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The ring a focused control gets: accent, or pink while a knob is grabbed. */
fun ContentDrawScope.focusRing(color: Color) {
    // Just inside the edge. On the edge, a control that clips its corners
    // lost half the ring, and one that draws its own border (a clip in the
    // grid) painted over the rest.
    val w = 2.dp.toPx()
    val inset = 2.dp.toPx()
    drawRoundRect(
        color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(w),
    )
}

/**
 * The app's press indication (Material's ripple) with a focus ring on top, so
 * every clickable shows keyboard focus without extra code. Provided in
 * AcidulousTheme.
 */
class FocusRingIndication(private val base: IndicationNodeFactory, private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        RingNode(interactionSource, base.create(interactionSource), color)

    override fun equals(other: Any?) = other is FocusRingIndication && other.base == base && other.color == color
    override fun hashCode() = base.hashCode() * 31 + color.hashCode()
}

private class RingNode(
    private val source: InteractionSource,
    inner: DelegatableNode,
    private val color: Color,
) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init { delegate(inner) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) focusRing(color)
    }
}
