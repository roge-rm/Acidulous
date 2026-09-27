package com.rm.acidulous.model

import com.rm.acidulous.io.*

import com.rm.acidulous.engine.EngineAssets
import kotlinx.serialization.json.Json

/** One JSON file per song under the engine's writable user root. */
object SongStore {

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true // a newer file opened by an older build only loses what it can't read
    }

    fun encode(song: Song): String = json.encodeToString(Song.serializer(), song)
    fun decode(text: String): Song = normalise(json.decodeFromString(Song.serializer(), text))

    /**
     * Brings an old song up to date as it's read, and puts each modifier in
     * the slot its control owns.
     *
     * Older songs put modifiers in any free slot, so an Arp could be in the
     * chord's slot, where the chord chip wouldn't see it and the first tap
     * would replace it. Moving them on load is simpler than making every
     * control search all slots, and does nothing for songs already in order.
     */
    private fun normalise(song: Song): Song {
        // The sends were two fixed effects before they were slots. Old songs
        // are updated here with every other change to the saved format.
        @Suppress("NAME_SHADOWING") var song = song.copy(master = song.master.migrated())
        // Groups were Bus tracks in 0.7.0 and are mixer strips now.
        song = song.busTracksToGroups()
        song = renamed(song)
        // Songs from before swing worked have 0 here, which now means a
        // percentage. 50 is straight.
        if (song.swing < SWING_STRAIGHT) song = song.copy(swing = SWING_STRAIGHT)
        val home = mapOf("Chord" to 0, "Scale" to 1, "Arp" to 2)
        if (song.tracks.none { t -> (0 until MODIFIER_SLOTS).any { home[t.modifierAt(it).type]?.let { h -> h != it } == true } }) {
            return song
        }
        return song.copy(
            tracks = song.tracks.map { track ->
                val placed = arrayOfNulls<UnitSlot>(MODIFIER_SLOTS)
                for (slot in 0 until MODIFIER_SLOTS) {
                    val ev = track.modifierAt(slot)
                    val h = home[ev.type] ?: continue
                    if (placed[h] == null) placed[h] = ev
                }
                track.copy(modifiers = List(MODIFIER_SLOTS) { placed[it] ?: UnitSlot() })
            },
        )
    }

    /**
     * Machines that have been renamed.
     *
     * A saved track only knows its machine by this name, so without an entry
     * here a renamed machine opens as a silent track with its notes still
     * showing. Only used when reading.
     */
    private val RENAMED = mapOf("Subvert" to "Reflux")

    private fun renamed(song: Song): Song {
        if (song.tracks.none { RENAMED.containsKey(it.machine.type) }) return song
        return song.copy(
            tracks = song.tracks.map { track ->
                val now = RENAMED[track.machine.type] ?: return@map track
                track.copy(machine = track.machine.copy(type = now))
            },
        )
    }

    fun directory(): File = File(EngineAssets.userRoot(), "songs").apply { mkdirs() }

    fun fileFor(name: String): File = File(directory(), "${safeName(name)}.json")

    fun save(song: Song): File =
        fileFor(song.name).also { it.writeTextSafely(encode(song)) }

    fun load(name: String): Song = decode(fileFor(name).readText())

    /**
     * The working song, saved all the time and reloaded on the next start.
     * Separate from the named songs in [directory]: this is what was open,
     * not what was saved, so leaving the app never loses an edit.
     */
    fun sessionFile(): File = File(EngineAssets.userRoot(), "session.json")

    fun saveSession(song: Song) {
        // If the app is killed mid-write, the previous session is kept.
        sessionFile().writeTextSafely(encode(song))
    }

    fun loadSession(): Song? =
        sessionFile().takeIf { it.isFile }?.let { runCatching { decode(it.readText()) }.getOrNull() }

    fun delete(name: String): Boolean = fileFor(name).delete()

    fun exists(name: String): Boolean = fileFor(name).isFile

    /**
     * A new song: one scene, one empty track.
     *
     * The caller picks the machine (`UiPrefs.newSong` passes the one from
     * settings, Hexbeat by default). The track is named after the machine,
     * the same rule `uniqueTrackName` uses for the first track of a machine.
     */
    fun blank(
        name: String,
        tempo: Float = 120f,
        signature: Signature = Signature(),
        machine: String = "Hexbeat",
    ): Song = Song(
        name = name,
        tempo = tempo,
        signature = signature,
        tracks = listOf(
            Track(id = newId("t"), name = machine, machine = Machine(machine)),
        ),
        scenes = listOf(Scene(id = newId("s"), name = Names.scene(1))),
    )

    fun list(): List<String> =
        directory().listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "untitled" }
}
