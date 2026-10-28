package com.rm.acidulous.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppImageDesktopTest {
    private fun dir(): File = Files.createTempDirectory("appimage").toFile().apply { deleteOnExit() }

    // The package's own entry and type, as the image carries them.
    private val desktop = File("deb/acidulous.desktop").readText()
    private val mime = File("deb/acidulous-mime.xml").readText()

    private fun entryText(entries: List<AppImageDesktop.Entry>): String =
        String(entries.single { it.file.name.endsWith(".desktop") }.text)

    @Test
    fun theEntryRunsThisAppImage() {
        val home = dir()
        val entries = AppImageDesktop.entries("/home/me/Apps/acidulous-0.13.0-x86_64.AppImage", desktop, mime, byteArrayOf(1, 2), home)
        val names = entries.map { it.file.relativeTo(home).path }
        assertEquals(
            listOf(
                "icons/hicolor/scalable/apps/acidulous-appimage.svg",
                "mime/packages/acidulous-appimage.xml",
                "applications/acidulous-appimage.desktop",
            ),
            names,
        )
        val text = entryText(entries)
        val lines = text.lines()
        assertTrue("Exec=\"/home/me/Apps/acidulous-0.13.0-x86_64.AppImage\" %F" in lines)
        assertTrue("TryExec=/home/me/Apps/acidulous-0.13.0-x86_64.AppImage" in lines)
        assertTrue("Icon=acidulous-appimage" in lines)
        // The same type as the package, and no zips.
        assertTrue("MimeType=application/x-acidulous-preset;" in lines)
        assertFalse("zip" in text)
        assertEquals(1, lines.count { it.startsWith("Exec=") })
        assertEquals(mime, String(entries[1].text))
    }

    @Test
    fun oddPathsAreEscaped() {
        assertEquals("\"/a b/100% \\\\\"x\\\\\" \\\\\$y\"", AppImageDesktop.execValue("/a b/100% \"x\" \$y").replace("%%", "%"))
        assertTrue(AppImageDesktop.execValue("/a/100%").endsWith("100%%\""))
        assertEquals("\"/a/b\\\\\\\\c\"", AppImageDesktop.execValue("/a/b\\c"))
    }

    @Test
    fun writesOnlyWhatChanged() {
        val home = dir()
        val first = AppImageDesktop.entries("/opt/one.AppImage", desktop, mime, byteArrayOf(1), home)
        assertTrue(AppImageDesktop.write(first))
        assertFalse(AppImageDesktop.write(first))
        // Moved: the entry changes, so it's written again.
        val moved = AppImageDesktop.entries("/opt/two.AppImage", desktop, mime, byteArrayOf(1), home)
        assertTrue(AppImageDesktop.write(moved))
        assertTrue("Exec=\"/opt/two.AppImage\" %F" in File(home, "applications/acidulous-appimage.desktop").readLines())
    }

    @Test
    fun skipsWhenNotAnAppImageOrAlreadyThere() {
        val home = dir()
        val system = dir()
        assertNotNull(AppImageDesktop.skipReason(null, home, listOf(system)))
        assertNotNull(AppImageDesktop.skipReason("", home, listOf(system)))
        assertNull(AppImageDesktop.skipReason("/opt/a.AppImage", home, listOf(system)))

        // Our own entry from an earlier start doesn't count.
        AppImageDesktop.write(AppImageDesktop.entries("/opt/a.AppImage", desktop, mime, byteArrayOf(1), home))
        assertNull(AppImageDesktop.skipReason("/opt/a.AppImage", home, listOf(system)))

        // The package is installed.
        File(system, "applications").mkdirs()
        File(system, "applications/acidulous.desktop").writeText(desktop)
        assertTrue(AppImageDesktop.skipReason("/opt/a.AppImage", home, listOf(system))!!.covered)
        assertFalse(AppImageDesktop.skipReason(null, home, listOf(system))!!.covered)
    }

    @Test
    fun skipsWhenAToolAddedIt() {
        val system = dir()
        val other = File(dir(), "Acidulous-0.12.AppImage").apply { writeText("") }
        for (name in listOf("appimagekit_0123abcd-acidulous.desktop", "appimagekit_0123abcd-Acidulous.desktop", "gearlever_acidulous_ab12.desktop")) {
            val home = dir()
            File(home, "applications").mkdirs()
            File(home, "applications/$name").writeText("[Desktop Entry]\nExec=${other.path} %F\n")
            val skip = AppImageDesktop.skipReason("/opt/a.AppImage", home, listOf(system))
            assertNotNull(name, skip)
            assertTrue(skip!!.covered)
        }
    }

    @Test
    fun aToolEntryForADeletedCopyDoesNotCount() {
        val home = dir()
        File(home, "applications").mkdirs()
        File(home, "applications/appimagekit_0123abcd-acidulous.desktop")
            .writeText("[Desktop Entry]\nExec=/gone/Acidulous.AppImage %F\nTryExec=/gone/Acidulous.AppImage\n")
        assertNull(AppImageDesktop.skipReason("/opt/a.AppImage", home, listOf(dir())))
    }

    @Test
    fun anEntryRunningThisPathCountsWholePathsOnly() {
        val system = dir()
        fun skipWith(exec: String): AppImageDesktop.Skip? {
            val home = dir()
            File(home, "applications").mkdirs()
            File(home, "applications/my-music.desktop").writeText("[Desktop Entry]\nExec=$exec\n")
            return AppImageDesktop.skipReason("/opt/my apps/a.AppImage", home, listOf(system))
        }
        assertNotNull(skipWith("\"/opt/my apps/a.AppImage\" %U"))
        assertNotNull(skipWith("\"/opt/my\\sapps/a.AppImage\" %U"))
        assertNull(skipWith("\"/opt/my apps/a.AppImage.old\" %U"))
        assertNull(skipWith("/opt/my"))
    }

    @Test
    fun firstArgumentUndoesTheEscapes() {
        for (path in listOf("/a b/100% \"x\" \$y", "/a/b\\c", "/plain/a.AppImage")) {
            assertEquals(path, AppImageDesktop.firstArgument(AppImageDesktop.execValue(path) + " %F"))
        }
        assertEquals("/usr/bin/foo", AppImageDesktop.firstArgument("/usr/bin/foo --bar %u"))
        assertNull(AppImageDesktop.firstArgument("\"/unclosed"))
        assertNull(AppImageDesktop.firstArgument(""))
    }

    @Test
    fun oursGoWhenCoveredAndStaleIsSeen() {
        val home = dir()
        assertFalse(AppImageDesktop.remove(home))
        AppImageDesktop.write(AppImageDesktop.entries("/opt/a.AppImage", desktop, mime, byteArrayOf(1), home))
        // Written but the databases weren't rebuilt.
        assertTrue(AppImageDesktop.stale(home))
        File(home, "applications/mimeinfo.cache").writeText("[MIME Cache]\napplication/x-acidulous-preset=acidulous-appimage.desktop;\n")
        File(home, "mime/globs2").writeText("50:application/x-acidulous-preset:*.acpreset\n")
        assertFalse(AppImageDesktop.stale(home))
        assertTrue(AppImageDesktop.remove(home))
        assertTrue(AppImageDesktop.files(home).none { it.exists() })
        assertFalse(AppImageDesktop.stale(home))
    }
}
