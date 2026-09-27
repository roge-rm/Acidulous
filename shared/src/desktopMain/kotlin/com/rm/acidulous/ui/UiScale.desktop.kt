package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The window's height in outer dp, like Android's screenHeightDp: using the
 * density before the UI scale. ScaledMenu divides by the scale itself, so
 * using the already scaled density would divide by it twice.
 */
@Composable
internal actual fun windowHeightDp(): Float =
    with(LocalBaseDensity.current ?: LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
