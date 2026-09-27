package com.rm.acidulous.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import kotlin.math.max
import kotlin.math.min

@Composable
actual fun rememberTopCutout(): TopCutout? {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout
    // All four sides and the window size are keys, because a fold, a
    // multi-window resize or a rotation can move the hole in window
    // coordinates without changing the top inset.
    val top = insets.getTop(density)
    val bottom = insets.getBottom(density)
    val left = insets.getLeft(density, direction)
    val right = insets.getRight(density, direction)
    val size = LocalWindowInfo.current.containerSize
    return remember(top, bottom, left, right, size, view) {
        if (top <= 0) return@remember null
        val blocked = TopCutout(0, size.width, top)
        val cutout = ViewCompat.getRootWindowInsets(view)?.displayCutout ?: return@remember blocked
        var l = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = 0
        for (rect in cutout.boundingRects) {
            // Only the top edge. Side cutouts are left to the normal window
            // insets. Two holes on the top edge are treated as one span,
            // which is safe and costs nothing with one stretchy child.
            if (rect.top > 0 || rect.isEmpty) continue
            l = min(l, rect.left)
            r = max(r, rect.right)
            b = max(b, rect.bottom)
        }
        // The rectangles are in display coordinates and the inset is in window
        // coordinates. They match for a full-screen window. If they don't,
        // the rectangles can't be trusted, so block the whole row.
        if (r <= l || b != cutout.safeInsetTop) blocked else TopCutout(l, r, b)
    }
}

