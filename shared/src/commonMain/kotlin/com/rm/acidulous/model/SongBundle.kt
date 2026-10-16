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
 * folder, and anything that's a real file goes in. Audio track takes are
 * named by their clips instead, and go in too.
 *
 * A bundle can also hold engine.txt at its root: the engine lines that build
 * the song (EngineLines.kt), with media paths inside the zip, for hosts that
 * play songs without Kotlin. Reading a bundle ignores it.
 *
 * With frozen audio, the frozen clips' WAVs go in under freeze/, as they
 * are (32-bit float stereo at 48 kHz), for engine.txt's MountFrozen lines.
 * Reading a bundle skips them too: the app freezes those clips again.
 */
object SongBundle {

    private const val SONG_ENTRY = "song.json"
    const val ENGINE_ENTRY = "engine.txt"
    /** Where frozen clips' WAVs go in a bundle. */
    const val FREEZE_DIR = "freeze/"

    /**
     * Writes [song] and its media to [out], and [engineLines] as engine.txt
     * if given (SongEngine.record). With [freezeRoot], the WAVs of the
     * frozen clips that play as audio go in too ([frozenFiles]). Returns how
     * many media files went in, not counting those.
     */
    fun write(song: Song, userRoot: File, out: File, engineLines: String? = null, freezeRoot: File? = null): Int {
        val media = referenced(song, userRoot)
        val zip = ZipWriter(out)
        try {
            zip.add(SONG_ENTRY, SongStore.encode(song).encodeToByteArray())
            if (engineLines != null) zip.add(ENGINE_ENTRY, engineLines.encodeToByteArray())
            for (relative in media) {
                val file = File(userRoot, relative)
                if (!file.isFile) continue
                zip.addFile(relative, file)
            }
            if (freezeRoot != null) {
                for (name in frozenFiles(song, freezeRoot)) zip.addFile(FREEZE_DIR + name, File(freezeRoot, name))
            }
        } finally {
            zip.close()
        }
        return media.size
    }

    /**
     * The files in [freezeRoot] of the frozen clips that play as audio
     * (Freeze.current), the ones engine lines mount.
     */
    fun frozenFiles(song: Song, freezeRoot: File): List<String> =
        Freeze.current(song).map { it.second.file }.distinct().filter { File(freezeRoot, it).isFile }

    /** How many bytes [frozenFiles] adds to a bundle, before compression. */
    fun frozenBytes(song: Song, freezeRoot: File): Long =
        frozenFiles(song, freezeRoot).sumOf { File(freezeRoot, it).length() }

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
            if (name == ENGINE_ENTRY || name.startsWith(FREEZE_DIR)) return@readZip
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

    /**
     * A song from a bundle, ready to open, the clips to freeze again once it's
     * open, and the bundle's track ids to the song's new ones.
     */
    data class Arrival(val song: Song, val refreeze: List<Freeze.Target>, val ids: Map<String, String>)

    /**
     * Gets a song read from a bundle ready for this device.
     *
     * Freezes don't travel, so every frozen clip loses its freeze and is
     * listed to be rendered again here. The tracks get new ids
     * ([Freeze.copyPlan]), so a bundle of a song you already have can't write
     * over its renders or delete them when the copy is thawed.
     */
    fun arrive(song: Song): Arrival {
        val plan = Freeze.copyPlan(song)
        val fresh = Freeze.applied(song, plan)
        val refreeze = ArrayList<Freeze.Target>()
        val tracks = fresh.tracks.mapIndexed { i, t ->
            for (scene in fresh.scenes) if (t.clips[scene.id]?.frozen != null) refreeze += Freeze.Target(i, scene.id)
            t.copy(clips = t.clips.mapValues { (_, clip) -> if (clip.frozen == null) clip else clip.copy(frozen = null) })
        }
        return Arrival(fresh.copy(tracks = tracks), refreeze, plan.ids)
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

    /** The song, with every setting and take that named a renamed file pointing at its new name. */
    fun repoint(song: Song, renamed: Map<String, String>): Song = song.copy(
        tracks = song.tracks.map { t ->
            t.copy(clips = t.clips.mapValues { (_, clip) ->
                val audio = clip.audio ?: return@mapValues clip
                clip.copy(audio = audio.copy(lanes = audio.lanes.map { take ->
                    take?.let { renamed[it.file.trim()]?.let { name -> it.copy(file = name) } ?: it }
                }))
            }, machine = t.machine.copy(settings = t.machine.settings.mapValues { (_, value) ->
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
            for (clip in track.clips.values) {
                for (take in clip.audio?.lanes.orEmpty()) {
                    val file = take?.file?.trim() ?: continue
                    if (file.isNotEmpty() && !file.startsWith("/") && File(userRoot, file).isFile) found += file
                }
            }
        }
        return found.toList()
    }
}
