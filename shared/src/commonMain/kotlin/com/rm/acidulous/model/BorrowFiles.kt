package com.rm.acidulous.model

/**
 * The files Abacus can borrow from: one of yours, under the setting "sample"
 * like any one-sample machine ("samples/take 1.wav", which song zips carry),
 * or [FACTORY] and an id for one that comes with the app.
 */
object BorrowFiles {
    const val TYPE = "Abacus"
    const val FACTORY = VenueRooms.FACTORY

    /** Ours, by id, as tools/gen_borrow_files.py makes them (files/borrow-<id>.wav). */
    val FILES = listOf("voice")

    /** A file's resource path, or null when [setting] isn't one of ours. */
    fun factoryAsset(setting: String): String? =
        setting.removePrefix(FACTORY).takeIf { setting.startsWith(FACTORY) && it in FILES }?.let { "files/borrow-$it.wav" }

    /** What the panel shows for [setting]: our file's id or your file's name, without its extension. */
    fun displayName(setting: String): String = when {
        setting.isEmpty() -> ""
        setting.startsWith(FACTORY) -> setting.removePrefix(FACTORY)
        else -> setting.substringAfterLast('/').substringBeforeLast('.')
    }
}
