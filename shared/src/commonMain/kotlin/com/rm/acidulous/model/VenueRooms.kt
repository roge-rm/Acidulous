package com.rm.acidulous.model

/**
 * Venue's impulses: which one a slot plays, and the rooms that come with the
 * app.
 *
 * A slot names its impulse in the setting [SETTING]: a path under the user
 * folder for one of yours ("impulses/hall.wav", which song zips carry), or
 * [FACTORY] and a room's id for one of ours. No setting is the default room;
 * an empty one is no impulse.
 */
object VenueRooms {
    const val TYPE = "Venue"
    const val SETTING = "impulse"
    const val FACTORY = "factory:"
    /** Where your impulses are kept, under the user folder. */
    const val FOLDER = "impulses"

    /** Our rooms, by id, as tools/gen_impulses.py makes them (files/impulse-<id>.wav). */
    val ROOMS = listOf("small-room", "wooden-hall", "stone-church", "metal-plate", "closet")
    val DEFAULT = FACTORY + "wooden-hall"

    /**
     * The knobs that shape the impulse before it's built. They take effect
     * when the app loads the impulse again, so they can't be lanes or mod
     * targets, which only reach the running effect.
     */
    val SHAPE = setOf("length", "decay", "stretch")

    /** Whether [name] on an effect of [type] only takes effect on a rebuild, see [SHAPE]. */
    fun isShape(type: String, name: String): Boolean = type == TYPE && name in SHAPE

    /** What [slot] plays: its setting, or the default room. */
    fun impulseOf(slot: UnitSlot): String = slot.settings[SETTING] ?: DEFAULT

    /** A room's resource path, or null when [setting] isn't one of ours. */
    fun factoryAsset(setting: String): String? =
        setting.removePrefix(FACTORY).takeIf { setting.startsWith(FACTORY) && it in ROOMS }?.let { "files/impulse-$it.wav" }

    /** What the window shows for [setting]: a room's name or a file's, without its extension. */
    fun displayName(setting: String): String = when {
        setting.isEmpty() -> ""
        setting.startsWith(FACTORY) -> setting.removePrefix(FACTORY).replace('-', ' ')
        else -> setting.substringAfterLast('/').substringBeforeLast('.')
    }
}
