package com.rm.acidulous.model

import com.rm.acidulous.io.*

/**
 * Replaces a file's contents so it's either the old file or the new one, never
 * half of each. See File.writeBytesSafely for the platform part. Writing
 * straight over the file can leave a truncated song after a crash or a full
 * disk, and a song that doesn't parse is lost.
 */
fun File.writeTextSafely(text: String) = writeBytesSafely(text.encodeToByteArray())
