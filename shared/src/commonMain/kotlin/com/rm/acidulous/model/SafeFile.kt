package com.rm.acidulous.model

import com.rm.acidulous.io.*

/**
 * Replace a file's contents so that it is either the old file or the new one,
 * never half of each - see File.writeBytesSafely, which is the platform's.
 * Writing straight over the file - which is how named songs were saved - left
 * a truncated song behind a kill or a full disk, and a song that does not
 * parse is a song that is gone.
 */
fun File.writeTextSafely(text: String) = writeBytesSafely(text.encodeToByteArray())
