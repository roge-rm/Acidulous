package com.rm.acidulous.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replace a file's contents so that it is either the old file or the new one,
 * never half of each.
 *
 * Written to a hidden file beside it, flushed to the disk, then renamed over
 * it: a rename within a directory is atomic, so a kill, a crash or a full
 * disk mid-write leaves the previous version whole. Writing straight over the
 * file - which is how named songs were saved - left a truncated song behind
 * any of those, and a song that does not parse is a song that is gone.
 */
fun File.writeBytesSafely(bytes: ByteArray) {
    val tmp = File(parentFile, ".$name.tmp")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(this)) throw IOException("could not replace $name")
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

fun File.writeTextSafely(text: String) = writeBytesSafely(text.toByteArray(Charsets.UTF_8))
