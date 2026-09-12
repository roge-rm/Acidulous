package com.rm.acidulous.engine

import android.content.Context
import java.io.File

/** Where the app's own files live. Bundled content (kits, patches) returns with M8. */
object EngineAssets {

    /** Writable songs, patches and imported samples. */
    fun userRoot(context: Context): File = File(context.filesDir, "user").apply { mkdirs() }

    fun install(context: Context) {
        userRoot(context)
    }
}
