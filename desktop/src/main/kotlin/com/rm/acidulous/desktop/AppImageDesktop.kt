package com.rm.acidulous.desktop

import com.rm.acidulous.util.Log
import java.io.File

/**
 * Puts a running AppImage in the desktop's menu and file types, the way the
 * Debian package's files in /usr do: a menu entry that runs this AppImage
 * where it is, its icon, and the preset file type, all under the user's own
 * data folder (~/.local/share).
 *
 * The entry and the type are taken from the files the image carries, so they
 * match the package. They're only written when they differ from what's there
 * already, which is when the AppImage moved or is a new version, so most
 * starts write nothing.
 *
 * Nothing is done when the package is installed (its entry is in /usr) or when
 * an integration tool already added this AppImage to the menu.
 */
object AppImageDesktop {
    /** Our files' name, kept apart from the package's acidulous.desktop. */
    const val NAME = "acidulous-appimage"

    /**
     * Prefixes of the menu entries integration tools write: AppImageLauncher and
     * appimaged use appimagekit_<md5>-<name>.desktop, Gear Lever gearlever_<name>_<id>.desktop.
     */
    private val TOOL_PREFIXES = listOf("appimagekit_", "gearlever_")

    /** A file to write, and its content. */
    data class Entry(val file: File, val text: ByteArray)

    /**
     * Why nothing is written for [appImage], or null if it should be. [covered]
     * means something else already puts Acidulous in the menu, so our own files go.
     */
    data class Skip(val reason: String, val covered: Boolean)

    fun skipReason(appImage: String?, dataHome: File, dataDirs: List<File>): Skip? {
        if (appImage.isNullOrBlank()) return Skip("not an AppImage", covered = false)
        if (dataDirs.any { File(it, "applications/acidulous.desktop").isFile }) return Skip("the package is installed", covered = true)
        val apps = File(dataHome, "applications").listFiles().orEmpty()
        for (f in apps) {
            if (!f.name.endsWith(".desktop") || f.name == "$NAME.desktop") continue
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            val runs = lines.filter { it.startsWith("Exec=") || it.startsWith("TryExec=") }
                .mapNotNull { firstArgument(it.substringAfter('=')) }
            // Any entry that runs this same file, whatever made it.
            if (appImage in runs) return Skip("${f.name} runs it already", covered = true)
            // A tool's entry for another copy, as long as that copy is still there.
            val lower = f.name.lowercase()
            if (TOOL_PREFIXES.any { lower.startsWith(it) } && "acidulous" in lower && runs.any { File(it).isFile }) {
                return Skip("${f.name} is in the menu already", covered = true)
            }
        }
        return null
    }

    /**
     * The program an Exec or TryExec [value] runs, unescaped as the desktop entry
     * spec says: string escapes first, then quoting. Null if it's empty or a quote isn't closed.
     */
    internal fun firstArgument(value: String): String? {
        val s = buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (val n = value[i + 1]) {
                        's' -> append(' ')
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        '\\' -> append('\\')
                        else -> append('\\').append(n)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }.trimStart()
        if (s.isEmpty()) return null
        val arg = if (s[0] == '"') {
            val out = StringBuilder()
            var i = 1
            while (true) {
                if (i >= s.length) return null
                val c = s[i]
                if (c == '"') break
                if (c == '\\' && i + 1 < s.length) {
                    out.append(s[i + 1])
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            out.toString()
        } else {
            s.substringBefore(' ')
        }
        return arg.replace("%%", "%").ifEmpty { null }
    }

    /** Our three files under [dataHome]. */
    fun files(dataHome: File): List<File> = listOf(
        File(dataHome, "icons/hicolor/scalable/apps/$NAME.svg"),
        File(dataHome, "mime/packages/$NAME.xml"),
        File(dataHome, "applications/$NAME.desktop"),
    )

    /** Deletes our files under [dataHome]. True if there were any. */
    fun remove(dataHome: File): Boolean = files(dataHome).filter { it.isFile }.map { it.delete() }.isNotEmpty()

    /** True if our files are there but the databases don't know them, say after they were rebuilt without them. */
    fun stale(dataHome: File): Boolean {
        if (!File(dataHome, "applications/$NAME.desktop").isFile) return false
        fun has(file: String, text: String) = runCatching { File(dataHome, file).readText() }.getOrDefault("").contains(text)
        return !has("applications/mimeinfo.cache", "$NAME.desktop") || !has("mime/globs2", "*.acpreset")
    }

    /**
     * What to write for [appImage]: the image's [desktop] entry made to run it,
     * the [mime] type and the [icon], all under [dataHome].
     */
    fun entries(appImage: String, desktop: String, mime: String, icon: ByteArray, dataHome: File): List<Entry> {
        val lines = desktop.lines().map {
            when {
                it.startsWith("Exec=") -> "Exec=${execValue(appImage)} %F"
                it.startsWith("TryExec=") -> null
                it.startsWith("Icon=") -> "Icon=$NAME"
                else -> it
            }
        }.filterNotNull().toMutableList()
        // Hides the entry if the AppImage is deleted or moved, until it runs again.
        val at = lines.indexOfFirst { it.startsWith("Exec=") }
        lines.add(at + 1, "TryExec=${stringValue(appImage)}")
        val text = lines.joinToString("\n").trimEnd() + "\n"
        val (iconFile, mimeFile, desktopFile) = files(dataHome)
        return listOf(Entry(iconFile, icon), Entry(mimeFile, mime.toByteArray()), Entry(desktopFile, text.toByteArray()))
    }

    /** [path] as one quoted Exec argument, escaped as the desktop entry spec says. */
    internal fun execValue(path: String): String {
        val quoted = buildString {
            append('"')
            for (c in path) {
                if (c == '"' || c == '`' || c == '$' || c == '\\') append('\\')
                append(c)
            }
            append('"')
        }
        return stringValue(quoted).replace("%", "%%")
    }

    /** [s] escaped as a desktop entry string value. */
    private fun stringValue(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

    /** Writes the entries that changed. True if any did. */
    fun write(entries: List<Entry>): Boolean {
        var changed = false
        for (e in entries) {
            if (e.file.isFile && e.file.readBytes().contentEquals(e.text)) continue
            e.file.parentFile.mkdirs()
            val temp = File(e.file.parentFile, ".${e.file.name}.new")
            temp.writeBytes(e.text)
            if (!temp.renameTo(e.file)) {
                temp.delete()
                error("could not write ${e.file}")
            }
            changed = true
        }
        return changed
    }

    /** Call at start on Linux. Works on its own thread; failures are only logged. */
    fun installSoon() {
        val appImage = System.getenv("APPIMAGE")
        if (appImage.isNullOrBlank()) return
        Thread({ runCatching { install(appImage) }.onFailure { Log.w(TAG, "could not add the AppImage to the menu", it) } }, "appimage-desktop")
            .apply { isDaemon = true; start() }
    }

    private fun install(appImage: String) {
        val home = System.getProperty("user.home")
        // The spec says to ignore a relative path in these.
        val dataHome = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/share")
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share")
            .split(':').filter { it.startsWith("/") }.map(::File)
        val skip = skipReason(appImage, dataHome, dataDirs)
        if (skip != null) {
            Log.i(TAG, "not added to the menu: ${skip.reason}")
            if (skip.covered && remove(dataHome)) {
                Log.i(TAG, "removed our own menu entry")
                refresh(dataHome)
            }
            return
        }
        // The image's own files, where the AppImage runtime mounted it.
        val appDir = File(System.getenv("APPDIR")?.takeIf { it.isNotBlank() } ?: return)
        val desktop = File(appDir, "acidulous.desktop").readText()
        val mime = File(appDir, "usr/share/mime/packages/acidulous.xml").readText()
        val icon = File(appDir, "acidulous.svg").readBytes()
        if (write(entries(appImage, desktop, mime, icon, dataHome))) {
            Log.i(TAG, "added $appImage to the menu")
        } else if (!stale(dataHome)) {
            return
        }
        refresh(dataHome)
    }

    private fun refresh(dataHome: File) {
        run("update-mime-database", File(dataHome, "mime").path)
        run("update-desktop-database", File(dataHome, "applications").path)
    }

    /** Runs a tool if it's installed, ignoring how it went. */
    private fun run(vararg command: String) {
        runCatching {
            val p = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            p.waitFor()
        }
    }

    private const val TAG = "Acidulous.AppImage"
}
