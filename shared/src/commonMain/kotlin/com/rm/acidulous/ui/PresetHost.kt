package com.rm.acidulous.ui

import com.rm.acidulous.Doc

/**
 * What the patch list's share and import buttons call. The app sets them
 * (App.kt), since writing a preset file, sharing it and reporting the result
 * need its scope and its notice window. Null until it has.
 */
object PresetHost {
    /**
     * Shares a patch of [key] (a machine type, or PatchStore.effectKey) by name,
     * from the factory patches when [factory] is true, otherwise the user's. All
     * the user's when [name] is null.
     */
    var share: ((key: String, name: String?, factory: Boolean) -> Unit)? = null

    /** Adds the preset file [doc] to the user's patches. [done] runs when it has, so a list can read again. */
    var import: ((doc: Doc, done: () -> Unit) -> Unit)? = null
}
