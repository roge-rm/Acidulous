package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun windowHeightDp(): Float = LocalConfiguration.current.screenHeightDp.toFloat()
