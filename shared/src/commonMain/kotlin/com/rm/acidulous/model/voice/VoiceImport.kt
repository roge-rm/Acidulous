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

    /**
     * A voice stored unpacked in [zip] under [prefix] ("media/voices/Ana/",
     * with its bank.json and takes), into [root]/voices. A voice you already
     * have with the same name or a numbered copy and the same index and takes
     * is reused; otherwise it goes in under a free name. Returns the name it
     * is under, or null if the index can't be read.
     */
    suspend fun readFolder(zip: File, prefix: String, root: File): String? {
        var bank: VoiceBank? = null
        readZip(zip) { name, isDirectory, bytes ->
            if (!isDirectory && name == prefix + VoiceBank.INDEX) {
                bank = runCatching { json.decodeFromString(VoiceBank.serializer(), bytes().decodeToString()) }.getOrNull()
            }
        }
        val read = bank ?: return null
        // Only plain file names, in the index as well as on disk.
        val kept = read.takes.filterValues { it.isNotBlank() && '/' !in it && '\\' !in it && it != ".." && it != "." }
        val shared = read.copy(takes = kept, cuts = read.cuts.filterKeys { it in kept })
        val takes = kept.values.toSet()
        val wanted = shared.name.ifBlank { prefix.removeSuffix("/").substringAfterLast('/') }
            .replace('/', ' ').replace('\\', ' ').trim().ifBlank { "voice" }
        val scratch = File(root, ".voice-compare.tmp")

        // Whether the voice in [dir] is this one: the same index and the same takes.
        suspend fun same(dir: File): Boolean {
            val have = VoiceBank.load(dir) ?: return false
            if (have.copy(name = "") != shared.copy(name = "")) return false
            var ok = true
            var found = 0
            readZipFiles(zip) { entry, isDirectory, extractTo ->
                if (isDirectory || !entry.startsWith(prefix)) return@readZipFiles
                val file = entry.removePrefix(prefix)
                if (file !in takes) return@readZipFiles
                found++
                val there = File(dir, file)
                if (!there.isFile) { ok = false; return@readZipFiles }
                extractTo(scratch)
                if (!scratch.sameContent(there)) ok = false
            }
            scratch.delete()
            return ok && found == takes.size
        }

        var candidate = wanted
        var n = 2
        while (VoiceBank.folderOf(root, candidate).exists()) {
            if (same(VoiceBank.folderOf(root, candidate))) return candidate
            candidate = "$wanted ($n)"
            n++
        }
        val dir = VoiceBank.folderOf(root, candidate)
        dir.mkdirs()
        val dirPath = dir.canonicalFile.path
        readZipFiles(zip) { entry, isDirectory, extractTo ->
            if (isDirectory || !entry.startsWith(prefix)) return@readZipFiles
            val file = entry.removePrefix(prefix)
            if (file !in takes) return@readZipFiles
            val target = File(dir, file).canonicalFile
            if (!target.path.startsWith(dirPath + FILE_SEPARATOR)) return@readZipFiles
            extractTo(target)
        }
        VoiceBank.save(dir, shared.copy(name = candidate))
        return candidate
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
