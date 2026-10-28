package com.rm.acidulous.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.rm.acidulous.res.AppStrings
import com.rm.acidulous.res.Res
import com.rm.acidulous.res.menu_copy
import com.rm.acidulous.res.menu_cut
import com.rm.acidulous.res.menu_duplicate
import com.rm.acidulous.res.menu_edit
import com.rm.acidulous.res.menu_full_screen
import com.rm.acidulous.res.menu_minimise
import com.rm.acidulous.res.menu_paste
import com.rm.acidulous.res.menu_redo
import com.rm.acidulous.res.menu_undo
import com.rm.acidulous.res.menu_window
import com.rm.acidulous.res.menu_zoom
import com.rm.acidulous.ui.KeyAction
import com.rm.acidulous.ui.KeyHub
import com.rm.acidulous.ui.UiPrefs
import com.rm.acidulous.ui.macMenuShortcut

/**
 * The Edit and Window menus in a Mac's menu bar. The app menu (About,
 * Settings, Hide, Quit) is the system's: see MacApp.
 *
 * Edit's items run the same actions as their keys and show the keys they're
 * bound to now (see the keys window). The keys themselves stay with the app's
 * own key handling, which skips shortcuts while a text field has focus, so an
 * item run by its key does nothing (see [menuClickRuns]). They're greyed out
 * where the screen has no use for them.
 */
@Composable
internal fun FrameWindowScope.MacMenus(state: WindowState, toggleFullScreen: () -> Unit) {
    // Read so the menus are rebuilt in a new language.
    UiPrefs.language
    val live = KeyHub.live()
    fun word(res: org.jetbrains.compose.resources.StringResource) = AppStrings.getString(res)
    MenuBar {
        Menu(word(Res.string.menu_edit)) {
            for ((action, title) in listOf(
                KeyAction.Undo to Res.string.menu_undo,
                KeyAction.Redo to Res.string.menu_redo,
                null to null,
                KeyAction.Cut to Res.string.menu_cut,
                KeyAction.Copy to Res.string.menu_copy,
                KeyAction.Paste to Res.string.menu_paste,
                KeyAction.Duplicate to Res.string.menu_duplicate,
            )) {
                if (action == null || title == null) Separator()
                else Item(
                    word(title),
                    enabled = action in live,
                    shortcut = macMenuShortcut(UiPrefs.keyBindings[action].orEmpty()),
                    onClick = {
                        val sinceKey = System.nanoTime() - lastKeyTaken
                        if (menuClickRuns(java.awt.EventQueue.getCurrentEvent(), sinceKey)) KeyHub.run(action)
                    },
                )
            }
        }
        Menu(word(Res.string.menu_window)) {
            Item(word(Res.string.menu_minimise), shortcut = KeyShortcut(Key.M, meta = true), onClick = { state.isMinimized = true })
            Item(word(Res.string.menu_zoom), onClick = {
                state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            })
            Item(word(Res.string.menu_full_screen), shortcut = KeyShortcut(Key.F, ctrl = true, meta = true), onClick = toggleFullScreen)
        }
    }
}

/** When the app's key handling last used a key press, in System.nanoTime. Set by the window. */
@Volatile internal var lastKeyTaken = System.nanoTime() - 1_000_000_000L

/** How soon after a key the app used a menu click is taken as that same key. */
private const val KEY_ECHO_NS = 250_000_000L

/**
 * Whether a menu item's click should run its action, given the event being
 * handled when it came and how long since the app last used a key. A click
 * with the mouse comes as its own event. The item's key comes during the
 * key's own event, which the app's key handling has had first and has run or
 * skipped the action already. Should the system send the key's click a moment
 * later instead, the time catches it.
 */
internal fun menuClickRuns(current: java.awt.AWTEvent?, sinceKeyNs: Long): Boolean =
    current !is java.awt.event.KeyEvent && sinceKeyNs >= KEY_ECHO_NS
