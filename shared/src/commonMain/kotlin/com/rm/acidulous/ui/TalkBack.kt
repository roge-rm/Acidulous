package com.rm.acidulous.ui

import androidx.compose.runtime.Composable

/**
 * Whether TalkBack (or any touch-exploring screen reader) is on. Updates
 * when it's turned on or off with the app open. Only for the few places
 * where the layout changes for it.
 */
@Composable
expect fun rememberTalkBack(): Boolean

/**
 * Whether anything other than a finger may be working the app: an
 * accessibility service (TalkBack, Switch Access, Voice Access), a keyboard,
 * a d-pad or a game controller. Updates as they come and go.
 *
 * Controls that are drawn rather than built (a drum grid's steps) only need
 * their own nodes then. On a touch-only phone they're skipped, which is most
 * of what opening a drum machine cost.
 */
@Composable
expect fun rememberBeyondTouch(): Boolean
