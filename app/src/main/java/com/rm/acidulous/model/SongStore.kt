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

    fun list(context: Context): List<String> =
        directory(context).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "untitled" }
}
