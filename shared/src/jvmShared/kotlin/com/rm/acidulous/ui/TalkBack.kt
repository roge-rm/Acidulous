package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/**
 * Whether TalkBack is on - or any screen reader that explores by touch - and
 * read again when it is turned on or off, which can happen with the app open.
 * For the few places a screen is laid out differently for it, not for what
 * anything says.
 */
@Composable
expect fun rememberTalkBack(): Boolean
