package com.rm.acidulous.desktop

import com.rm.acidulous.AppHost
import com.rm.acidulous.AppWindow
import com.rm.acidulous.Incoming
import java.awt.Desktop
import java.awt.EventQueue

/**
 * The parts of a Mac app that come from the system: the app menu's About,
 * Settings (Cmd+,) and Quit (Cmd+Q, and Quit in the Dock), and files opened
 * from Finder. Hide and Hide Others are the system's own.
 */
internal object MacApp {
    /** Before the window is up, so a file that started the app isn't missed. */
    fun install() {
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
        fun supports(action: Desktop.Action) = runCatching { desktop.isSupported(action) }.getOrDefault(false)
        if (supports(Desktop.Action.APP_OPEN_FILE)) desktop.setOpenFileHandler { e -> OpenFiles.add(e.files) }
        if (supports(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler { Incoming.window = AppWindow.About }
        if (supports(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler { Incoming.window = AppWindow.Settings }
        if (supports(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response ->
            // The window's own close path, which saves the session first.
            val quit = (AppHost.current as? DesktopHost)?.onQuit
            if (quit == null) {
                response.performQuit()
            } else {
                runOnUi(quit)
                response.performQuit()
            }
        }
    }

    /** Runs [action] on the UI thread and waits for it. */
    private fun runOnUi(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action() else EventQueue.invokeAndWait(action)
    }

    /**
     * The files Finder sends a second copy of the app, which arrive a moment
     * after it starts: whatever came in [waitMs], for handing over.
     */
    fun filesSoon(waitMs: Long): List<java.io.File> {
        val until = System.currentTimeMillis() + waitMs
        val got = ArrayList<java.io.File>()
        while (System.currentTimeMillis() < until) {
            got += OpenFiles.drain()
            Thread.sleep(50)
        }
        return got + OpenFiles.drain()
    }
}
