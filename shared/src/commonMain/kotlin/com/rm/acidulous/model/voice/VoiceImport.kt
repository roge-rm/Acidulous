package com.rm.acidulous.model.voice

import com.rm.acidulous.io.*
import kotlinx.serialization.json.Json

/**
 * A voice someone shared, back into the voices folder.
 *
 * **share** on the voice page writes a zip with one folder in it, named for
 * the voice, holding its index (bank.json) and its takes. Opened anywhere
 * else, that zip went to the song importer, which unpacked it into the user
 * folder before finding it had no song in it: the voice never showed up and
 * a stray folder was left behind.
 *
 * Here a zip is looked at before anything is written: a song bundle has a
 * song.json at the top; a voice has one folder with a bank.json in it. A
 * voice goes into voices/, under a name no voice there has already, and only
 * its index and the takes its index names come out of the zip.
 */
object VoiceImport {

    enum class Kind { Song, Voice, Neither }

    private val json = Json { ignoreUnknownKeys = true }

    /** What [zip] holds, from its entries' names alone. */
    suspend fun kindOf(zip: File): Kind {
        var song = false
        val indexes = mutableSetOf<String>()
        readZip(zip) { name, isDirectory, _ ->
            if (isDirectory) return@readZip
            if (name == SONG_ENTRY) song = true
            folderOfIndex(name)?.let { indexes += it }
        }
        return when {
            song -> Kind.Song
            indexes.size == 1 -> Kind.Voice
            else -> Kind.Neither
        }
    }

    /**
     * Unpacks the voice in [zip] into [root]/voices. Returns the name it
     * went in under (the shared name, or "name (2)" if a voice has that one),
     * or null if [zip] isn't a shared voice or its index can't be read.
     */
    suspend fun read(zip: File, root: File): String? {
        if (kindOf(zip) != Kind.Voice) return null
        var folder: String? = null
        var bank: VoiceBank? = null
        readZip(zip) { name, isDirectory, bytes ->
            if (isDirectory || bank != null) return@readZip
            val f = folderOfIndex(name) ?: return@readZip
            folder = f
            bank = runCatching { json.decodeFromString(VoiceBank.serializer(), bytes().decodeToString()) }.getOrNull()
        }
        val from = folder ?: return null
        val shared = bank ?: return null
        // Only plain file names: a take can't be anywhere but its own folder.
        val takes = shared.takes.values.filter { it.isNotBlank() && '/' !in it && '\\' !in it && it != ".." && it != "." }.toSet()
        val name = freeName(root, shared.name.ifBlank { from }.replace('/', ' ').replace('\\', ' ').trim().ifBlank { "voice" })
        val dir = VoiceBank.folderOf(root, name)
        dir.mkdirs()
        val dirPath = dir.canonicalFile.path
        readZip(zip) { entry, isDirectory, bytes ->
            if (isDirectory || !entry.startsWith("$from/")) return@readZip
            val file = entry.removePrefix("$from/")
            if (file !in takes) return@readZip
            val target = File(dir, file).canonicalFile
            if (!target.path.startsWith(dirPath + FILE_SEPARATOR)) return@readZip
            target.writeBytesSafely(bytes())
        }
        VoiceBank.save(dir, shared.copy(name = name))
        return name
    }

    /** "Ana", or if a voice has that name, "Ana (2)", then (3). */
    private fun freeName(root: File, wanted: String): String {
        if (!VoiceBank.folderOf(root, wanted).exists()) return wanted
        var n = 2
        while (VoiceBank.folderOf(root, "$wanted ($n)").exists()) n++
        return "$wanted ($n)"
    }

    /** The folder an entry is a voice's index in ("Ana/bank.json" -> "Ana"), or null. */
    private fun folderOfIndex(name: String): String? {
        val parts = name.split('/')
        return if (parts.size == 2 && parts[1] == VoiceBank.INDEX && parts[0].isNotBlank() && parts[0] != "..") parts[0] else null
    }

    private const val SONG_ENTRY = "song.json"
}
