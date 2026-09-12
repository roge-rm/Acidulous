package com.rm.acidulous.model

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A song and everything it needs, in one file.
 *
 * A saved song is only the JSON: every sample, SoundFont and multisample it
 * uses is named by a path relative to the user folder and lives outside it.
 * That is right for working - one copy of a drum hit serves forty songs -
 * and useless for moving a song to another device, where the JSON arrives
 * and every pad is silent. So a bundle is the old answer: the document plus
 * its media, zipped, and unzipped back into place at the other end.
 *
 * **Which files come along is decided by looking, not by guessing.** Sample
 * paths hide in several shapes - a plain setting, one field of a
 * pipe-separated zone line, one of sixteen numbered pad settings - and a
 * list of the keys that are paths today is a list that will be wrong after
 * the next machine. So every token of every setting is resolved against the
 * user folder, and whatever turns out to be a real file is a real file.
 */
object SongBundle {

    private const val SONG_ENTRY = "song.json"

    /** Writes [song] and its media to [out]. Returns how many media files went in. */
    fun write(song: Song, userRoot: File, out: File): Int {
        val media = referenced(song, userRoot)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SONG_ENTRY))
            zip.write(SongStore.encode(song).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (relative in media) {
                val file = File(userRoot, relative)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return media.size
    }

    /**
     * Unpacks a bundle: the media go back under [userRoot] at the same
     * relative paths the song already refers to, so it simply works.
     *
     * A zip entry naming its way out of the folder is refused. Nothing here
     * makes hostile bundles likely, but an archive is somebody else's file
     * and ".." is the oldest trick there is.
     */
    fun read(bundle: File, userRoot: File): Song? {
        var song: Song? = null
        val rootPath = userRoot.canonicalFile
        ZipInputStream(bundle.inputStream().buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                if (entry.name == SONG_ENTRY) {
                    song = runCatching { SongStore.decode(zip.readBytes().decodeToString()) }.getOrNull()
                } else {
                    val target = File(userRoot, entry.name).canonicalFile
                    if (!target.path.startsWith(rootPath.path + File.separator)) { zip.closeEntry(); continue }
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        return song
    }

    /** Every file under [userRoot] that some setting in [song] names. */
    fun referenced(song: Song, userRoot: File): List<String> {
        val found = linkedSetOf<String>()
        for (track in song.tracks) {
            for (value in track.machine.settings.values) {
                for (token in value.split('\n', '|', ',')) {
                    val candidate = token.trim()
                    if (candidate.isEmpty() || candidate.startsWith("/")) continue
                    if (File(userRoot, candidate).isFile) found += candidate
                }
            }
        }
        return found.toList()
    }
}
