package com.rm.acidulous.engine

import com.rm.acidulous.io.*


/** Where the app's own files live. */
object EngineAssets {

    /**
     * The platform's two folders, set once by [install] before anything asks
     * for a path: Android's filesDir and cacheDir, or the desktop equivalents.
     */
    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    /** User songs, patches and imported samples. */
    fun userRoot(): File = File(filesDir, "user").apply { mkdirs() }

    /**
     * Frozen clips. They get their own folder because they can all be
     * rebuilt, so deleting them only costs the time to freeze again.
     */
    fun freezeRoot(): File = File(filesDir, "freeze").apply { mkdirs() }

    /**
     * Where takes too long to hold in memory are converted to, once.
     *
     * It's in `cacheDir` because everything here can be rebuilt from the
     * original recording, so the system may delete it when it needs space.
     */
    fun reelCache(): File =
        File(cacheDir, "reel").apply { mkdirs() }

    /** The platform's cache folder: scratch space for renders and imports. */
    fun cacheRoot(): File = cacheDir

    fun install(files: File, cache: File) {
        filesDir = files
        cacheDir = cache
        userRoot()
        renamePatchFolders()
    }

    /**
     * Machines renamed since a version that could save patches.
     *
     * User patches live in a folder named after their machine, so after a
     * rename the old folder has to be moved or the patches can't be found.
     * Files are moved one at a time rather than renaming the folder, so a
     * name used by both (an old Subvert patch and a new Reflux one) keeps
     * both instead of failing on a folder that already exists.
     */
    private val RENAMED = mapOf("Subvert" to "Reflux")

    private fun renamePatchFolders() {
        val patches = File(userRoot(), "patches")
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
 * A typed name as a file name. Used everywhere a file is named, so the
 * importer, the recorder and SongStore all agree.
 *
 * Dots are kept because extensions use them, but not a leading dot, which
 * would hide the file.
 */
fun safeFileName(typed: String, fallback: String): String =
    typed.trim().trimStart('.').replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { fallback }

/**
 * [wanted] if nothing in [dir] has that name, otherwise "name 2.wav",
 * "name 3.wav" and so on. Stops imports from overwriting, for example two
 * kits that both contain `snare.wav`.
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

/** "take 1", "take 2" and so on: the first one not already in [dir]. */
fun nextTakeName(dir: File): String {
    val used = (dir.listFiles() ?: emptyArray()).map { it.name.lowercase() }.toSet()
    var n = 1
    while (used.contains("take $n.wav")) n++
    return "take $n"
}
