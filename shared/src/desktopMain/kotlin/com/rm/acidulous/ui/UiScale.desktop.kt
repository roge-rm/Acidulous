package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
internal actual fun windowHeightDp(): Float =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
