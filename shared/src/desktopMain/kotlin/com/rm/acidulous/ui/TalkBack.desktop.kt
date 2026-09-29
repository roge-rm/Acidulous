package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/** No desktop screen reader explores by touch, so the normal layout is used. */
@Composable
actual fun rememberTalkBack(): Boolean = false

/** A computer always has a keyboard. */
@Composable
actual fun rememberBeyondTouch(): Boolean = true
