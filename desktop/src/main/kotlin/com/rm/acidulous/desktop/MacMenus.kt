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

/**
 * The Edit and Window menus in a Mac's menu bar. The app menu (About,
 * Settings, Hide, Quit) is the system's: see MacApp.
 *
 * Edit's items run the same actions as their keys, which stay with the app's
 * own key handling (and can be changed in the keys window), so the items carry
 * no shortcut of their own. They're greyed out where the screen has no use
 * for them.
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
                else Item(word(title), enabled = action in live, onClick = { KeyHub.run(action) })
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
