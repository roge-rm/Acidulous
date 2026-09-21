package com.rm.acidulous.engine

import android.content.Context
import java.io.File

/** Where the app's own files live. Bundled content (kits, patches) returns with M8. */
object EngineAssets {

    /** Writable songs, patches and imported samples. */
    fun userRoot(context: Context): File = File(context.filesDir, "user").apply { mkdirs() }

    /**
     * Frozen clips. Their own directory because they are derived, not
     * authored: deleting the lot costs nothing but the time to freeze again.
     */
    fun freezeRoot(context: Context): File = File(context.filesDir, "freeze").apply { mkdirs() }

    /**
     * Where a take too long to hold in memory is converted to, once.
     *
     * In `cacheDir` and not beside the songs, because that is exactly what it
     * is: everything in here can be made again from the recording it came
     * from, so the system may throw it away when it needs the space and
     * nothing is lost but the second or two it takes to rebuild.
     */
    fun reelCache(context: Context): File =
        File(context.cacheDir, "reel").apply { mkdirs() }

    fun install(context: Context) {
        userRoot(context)
        renamePatchFolders(context)
    }

    /**
     * Machines renamed since a build that could have saved patches.
     *
     * A user patch lives in a folder named after its machine, so a rename
     * leaves the old folder behind and the machine's picker shows factory
     * patches only - the user's own are still on the phone and unreachable.
     * Moved one file at a time rather than by renaming the folder, so that a
     * name used by both (an old Subvert patch and a new Reflux one) keeps
     * both instead of the move failing on a directory that already exists.
     */
    private val RENAMED = mapOf("Subvert" to "Reflux")

    private fun renamePatchFolders(context: Context) {
        val patches = File(userRoot(context), "patches")
        for ((was, now) in RENAMED) {
            val from = File(patches, was)
            if (!from.isDirectory) continue
            val to = File(patches, now).apply { mkdirs() }
            for (file in from.listFiles() ?: emptyArray()) {
                val target = File(to, uniqueIn(to, file.name))
                if (!file.renameTo(target)) file.copyTo(target, overwrite = false).also { file.delete() }
            }
            from.delete()
        }
    }
}

/**
 * A name somebody typed, as a file name.
 *
 * **One rule, in one place.** It was written three times with three different
 * rulesets: the importer allowed dots, the recorder allowed dots and appended
 * `.wav` itself, and `SongStore` stripped dots and called the result
 * "untitled". A file named by one and looked for by another is the kind of
 * fault that only shows up on somebody else's phone.
 *
 * Dots are kept because an extension is a dot; a leading one is not, because
 * a file beginning with a dot is hidden and nobody meant that.
 */
fun safeFileName(typed: String, fallback: String): String =
    typed.trim().trimStart('.').replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { fallback }

/**
 * [wanted] if nothing in [dir] has that name, else "name 2.wav", "name 3.wav".
 *
 * The importer overwrote silently, which means importing two different kits
 * that both contain `snare.wav` leaves one kit playing the other's snare.
 */
fun uniqueIn(dir: File, wanted: String): String {
    if (!File(dir, wanted).exists()) return wanted
    val stem = wanted.substringBeforeLast('.', wanted)
    val ext = wanted.substringAfterLast('.', "")
    var n = 2
    while (true) {
        val next = if (ext.isEmpty()) "$stem $n" else "$stem $n.$ext"
        if (!File(dir, next).exists()) return next
        ++n
    }
}

/** "take 1", "take 2" - the first that is not already in [dir]. */
fun nextTakeName(dir: File): String {
    val used = (dir.listFiles() ?: emptyArray()).map { it.name.lowercase() }.toSet()
    var n = 1
    while (used.contains("take $n.wav")) n++
    return "take $n"
}
