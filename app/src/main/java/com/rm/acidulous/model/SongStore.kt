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
    fun decode(text: String): Song = json.decodeFromString(Song.serializer(), text)

    fun directory(context: Context): File = File(EngineAssets.userRoot(context), "songs").apply { mkdirs() }

    fun fileFor(context: Context, name: String): File = File(directory(context), "${safeName(name)}.json")

    fun save(context: Context, song: Song): File =
        fileFor(context, song.name).also { it.writeText(encode(song)) }

    fun load(context: Context, name: String): Song = decode(fileFor(context, name).readText())

    /**
     * The working song, saved continuously and reloaded on the next start.
     * Separate from the named songs in [directory]: this is "what was open",
     * not "what was saved", so minimising the app never loses an edit.
     */
    fun sessionFile(context: Context): File = File(EngineAssets.userRoot(context), "session.json")

    fun saveSession(context: Context, song: Song) {
        val f = sessionFile(context)
        val tmp = File(f.parentFile, "session.json.tmp")
        tmp.writeText(encode(song))
        tmp.renameTo(f) // a kill mid-write leaves the previous session intact
    }

    fun loadSession(context: Context): Song? =
        sessionFile(context).takeIf { it.isFile }?.let { runCatching { decode(it.readText()) }.getOrNull() }

    fun delete(context: Context, name: String): Boolean = fileFor(context, name).delete()

    fun exists(context: Context, name: String): Boolean = fileFor(context, name).isFile

    /** A new song: one scene, one Subvert track, nothing in it. */
    fun blank(name: String, tempo: Float = 120f, signature: Signature = Signature()): Song = Song(
        name = name,
        tempo = tempo,
        signature = signature,
        tracks = listOf(Track(id = newId("t"), name = "Bass", machine = Machine("Subvert"))),
        scenes = listOf(Scene(id = newId("s"), name = "Scene 1")),
    )

    fun list(context: Context): List<String> =
        directory(context).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "untitled" }
}
