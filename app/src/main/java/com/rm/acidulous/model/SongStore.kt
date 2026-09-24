package com.rm.acidulous.model

import android.content.Context
import com.rm.acidulous.engine.EngineAssets
import kotlinx.serialization.json.Json
import java.io.File

/** One JSON document per song under the engine's writable user root. */
object SongStore {

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true // a newer file opened by an older build loses only what it cannot understand
    }

    fun encode(song: Song): String = json.encodeToString(Song.serializer(), song)
    fun decode(text: String): Song = normalise(json.decodeFromString(Song.serializer(), text))

    /**
     * Put each modifier in the slot its control owns.
     *
     * Modifiers used to go wherever there was room, because only two of the
     * three could run at once. Now chord, scale and arp have a chip each and
     * a slot each, and a song written before that has, say, an Arp sitting
     * in the chord's slot - where the chord chip would read it as absent and
     * the first tap would quietly replace it. Moving them on the way in is
     * cheaper than teaching every control to look everywhere, and it costs
     * nothing for a song that is already in order.
     */
    private fun normalise(song: Song): Song {
        // The sends were two fixed boxes before they were slots; an old song
        // still carries them that way and is brought forward here, where every
        // other shape change to a saved song is.
        @Suppress("NAME_SHADOWING") var song = song.copy(master = song.master.migrated())
        // Groups were Bus tracks in 0.7.0; they are mixer strips now.
        song = song.busTracksToGroups()
        song = renamed(song)
        // A song written before swing existed carries the old default of
        // nought in a field that now means a percentage, and nought is not a
        // swing at all - fifty is. Read on the way in, like the rest.
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
     * Machines that have been renamed since a song could have been saved.
     *
     * A type string is the only thing a saved track says about its machine,
     * so a rename with nothing here opens the song with a dead track: the
     * registry does not know the name, no engine machine is made, and the
     * part is silent with its notes still on the screen. The map is the whole
     * migration, and it is read on the way in and never written.
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

    fun directory(context: Context): File = File(EngineAssets.userRoot(context), "songs").apply { mkdirs() }

    fun fileFor(context: Context, name: String): File = File(directory(context), "${safeName(name)}.json")

    fun save(context: Context, song: Song): File =
        fileFor(context, song.name).also { it.writeTextSafely(encode(song)) }

    fun load(context: Context, name: String): Song = decode(fileFor(context, name).readText())

    /**
     * The working song, saved continuously and reloaded on the next start.
     * Separate from the named songs in [directory]: this is "what was open",
     * not "what was saved", so minimising the app never loses an edit.
     */
    fun sessionFile(context: Context): File = File(EngineAssets.userRoot(context), "session.json")

    fun saveSession(context: Context, song: Song) {
        // A kill mid-write leaves the previous session intact.
        sessionFile(context).writeTextSafely(encode(song))
    }

    fun loadSession(context: Context): Song? =
        sessionFile(context).takeIf { it.isFile }?.let { runCatching { decode(it.readText()) }.getOrNull() }

    fun delete(context: Context, name: String): Boolean = fileFor(context, name).delete()

    fun exists(context: Context, name: String): Boolean = fileFor(context, name).isFile

    /**
     * A new song: one scene, one track, nothing in it.
     *
     * The machine is the caller's - `UiPrefs.newSong` passes whatever the
     * settings say, which is Hexbeat unless it has been changed - and the
     * track takes the machine's own name, which is what `uniqueTrackName`
     * gives the first track that uses a machine. So the first track of a new
     * song and the second track of an old one are named by the same rule,
     * rather than one of them being called "Bass" whatever is in it.
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

    fun list(context: Context): List<String> =
        directory(context).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "untitled" }
}
