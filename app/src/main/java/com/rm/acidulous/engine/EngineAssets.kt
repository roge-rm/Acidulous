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
