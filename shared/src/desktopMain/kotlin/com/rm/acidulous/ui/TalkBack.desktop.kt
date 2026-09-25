package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/** No screen reader explores a desktop window by touch; the layout is the sighted one. */
@Composable
actual fun rememberTalkBack(): Boolean = false
