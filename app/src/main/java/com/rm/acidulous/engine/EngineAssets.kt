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

    fun install(context: Context) {
        userRoot(context)
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
