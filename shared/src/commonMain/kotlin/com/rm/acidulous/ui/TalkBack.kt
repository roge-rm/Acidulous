package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/**
 * Whether TalkBack (or any touch-exploring screen reader) is on. Updates
 * when it's turned on or off with the app open. Only for the few places
 * where the layout changes for it.
 */
@Composable
expect fun rememberTalkBack(): Boolean
