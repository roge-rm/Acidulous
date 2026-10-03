package com.rm.acidulous.model

import com.rm.acidulous.io.*


/**
 * A song and everything it needs, in one file.
 *
 * A saved song is just JSON. Its samples, SoundFonts and multisamples are
 * referenced by paths relative to the user folder, which is good for sharing
 * one drum hit between forty songs but means a song moved to another device
 * arrives with every pad silent. A bundle zips the song with its media and
 * unzips them back into place at the other end.
 *
 * Which files to include is found by looking rather than by a list of keys.
 * Sample paths show up in several forms (a plain setting, a field in a zone
 * line, numbered pad settings), and a key list would go wrong with the next
 * machine. So every token of every setting is checked against the user
 * folder, and anything that's a real file goes in.
 */
object SongBundle {

    private const val SONG_ENTRY = "song.json"

    /** Writes [song] and its media to [out]. Returns how many media files went in. */
    fun write(song: Song, userRoot: File, out: File): Int {
        val media = referenced(song, userRoot)
        val zip = ZipWriter(out)
        try {
            zip.add(SONG_ENTRY, SongStore.encode(song).encodeToByteArray())
            for (relative in media) {
                val file = File(userRoot, relative)
                if (!file.isFile) continue
                zip.addFile(relative, file)
            }
        } finally {
            zip.close()
        }
        return media.size
    }

    /**
     * Unpacks a bundle. The media go back under [userRoot] at the same
     * relative paths the song already uses, so it just works.
     *
     * Unless you already have a different file at that path. A bundle and
     * your phone could both have a `samples/take 1.wav`, and overwriting
     * yours would change your songs without telling you. An identical file
     * is reused. A different one is saved under a new name and the song is
     * pointed at that instead.
     *
     * Zip entries with paths leading outside the folder are skipped, and a
     * zip with no song in it unpacks nothing (a shared voice used to leave
     * its folder behind on the way to "not a bundle").
     */
    suspend fun read(bundle: File, userRoot: File): Song? {
        var hasSong = false
        readZip(bundle) { name, isDirectory, _ -> if (!isDirectory && name == SONG_ENTRY) hasSong = true }
        if (!hasSong) return null
        var song: Song? = null
        val rootPath = userRoot.canonicalFile
        val renamed = LinkedHashMap<String, String>()
        readZip(bundle) { name, isDirectory, bytes ->
            if (isDirectory) return@readZip
            if (name == SONG_ENTRY) {
                song = runCatching { SongStore.decode(bytes().decodeToString()) }.getOrNull()
                return@readZip
            }
            val target = File(userRoot, name).canonicalFile
            if (!target.path.startsWith(rootPath.path + FILE_SEPARATOR)) return@readZip
            target.parentFile?.mkdirs()
            val incoming = bytes()
            if (!target.exists()) {
                target.writeBytesSafely(incoming)
            } else if (!target.readBytes().contentEquals(incoming)) {
                val fresh = freeName(target)
                fresh.writeBytesSafely(incoming)
                renamed[name] = fresh.relativeTo(rootPath).invariantSeparatorsPath
            }
        }
        return song?.let { if (renamed.isEmpty()) it else repoint(it, renamed) }
    }

    /** If "take 1.wav" is taken, tries "take 1 (2).wav", then (3), and so on. */
    private fun freeName(taken: File): File {
        var n = 2
        while (true) {
            val next = File(taken.parentFile, "${taken.nameWithoutExtension} ($n).${taken.extension}")
            if (!next.exists()) return next
            n++
        }
    }

    /** The song, with every setting that named a renamed file pointing at its new name. */
    fun repoint(song: Song, renamed: Map<String, String>): Song = song.copy(
        tracks = song.tracks.map { t ->
            t.copy(machine = t.machine.copy(settings = t.machine.settings.mapValues { (_, value) ->
                // Token by token, on the same separators `referenced` uses, so
                // "samples/a.wav" is never matched inside "samples/a.wav2".
                Regex("[^\n|,]+").replace(value) { m ->
                    val token = m.value
                    val trimmed = token.trim()
                    renamed[trimmed]?.let { token.replace(trimmed, it) } ?: token
                }
            }))
        },
    )

    /** Every file under [userRoot] that a setting in [song] names. */
    fun referenced(song: Song, userRoot: File): List<String> {
        val found = linkedSetOf<String>()
        for (track in song.tracks) {
            for (value in track.machine.settings.values) {
                for (token in value.split('\n', '|', ',')) {
                    val candidate = token.trim()
                    if (candidate.isEmpty() || candidate.startsWith("/")) continue
                    if (!File(userRoot, candidate).isFile) continue
                    found += candidate
                    // A recorded voice is named by its index: its takes go too.
                    found += com.rm.acidulous.model.voice.VoiceBank.filesOf(userRoot, candidate)
                        .filter { File(userRoot, it).isFile }
                }
            }
        }
        return found.toList()
    }
}
