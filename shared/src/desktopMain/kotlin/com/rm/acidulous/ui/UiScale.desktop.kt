package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The window's height in the outer dp, as Android's screenHeightDp is: by
 * the density outside the interface's own scale. Inside it the current
 * density is already scaled, and ScaledMenu divides by the scale itself - so
 * at "largest" a menu was capped at the window's height over the scale twice,
 * and scrolled with room to spare.
 */
@Composable
internal actual fun windowHeightDp(): Float =
    with(LocalBaseDensity.current ?: LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
