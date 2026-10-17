package com.rm.acidulous.model

import com.rm.acidulous.engine.EngineAssets
import com.rm.acidulous.io.*
import com.rm.acidulous.model.voice.VoiceBank
import com.rm.acidulous.model.voice.VoiceImport
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A preset file (.acpreset): one patch, or all of a machine's or effect's
 * patches, with the user's own files they point at. A zip:
 *
 *     preset.json        {"version":1,"kind":"machine"|"effect","unit":"Reflux","patches":["patches/1.json",...]}
 *     patches/<n>.json   the patch as PatchStore keeps it, its file settings pointing into media/
 *     media/...          the files, at their paths under the user folder
 *     media/voices/<name>/...   a recorded voice: its bank.json and takes, unpacked
 *     engine/<n>.txt     the engine calls that apply patch n to a fresh track 1 (engine lines)
 *
 * Settings that name files: sample, slice_sample, pNN_sample, sf2, zones
 * (the first field of each line), voice and morphvoice. Built-in assets
 * aren't settings, so nothing of theirs is copied.
 *
 * engine/ is there only when the platform can record (not the browser).
 * Its lines name media as zip:media/..., files that are in the zip as they
 * are, a voice's takes too.
 *
 * The engine reads and writes these too, without media or engine/, for hosts
 * that drive it directly (engine/core/PresetFile.h). PresetFixtureTest keeps
 * the two alike.
 */
object PresetFile {
    const val EXTENSION = "acpreset"
    private const val MANIFEST = "preset.json"
    private const val MEDIA = "media/"
    private const val VERSION = 1

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    private data class Manifest(@EncodeDefault val version: Int = VERSION, val kind: String, val unit: String, val patches: List<String>)

    /** What [write] made. [missing] are files a patch names that weren't there, as "patch: path". */
    class Written(val file: File, val count: Int, val media: Int, val missing: List<String>, val engineLines: Boolean, val unportable: List<String>)

    /** What [read] added. [unit] is the machine or effect type, [kind] "machine" or "effect". */
    class Imported(val kind: String, val unit: String, val names: List<String>, val media: Int, val missing: List<String>)

    /** Whether a setting of this name holds a path to one of the user's files. */
    fun isMediaKey(key: String): Boolean =
        key == "sample" || key == "slice_sample" || key.endsWith("_sample") || key == "sf2" || key == "zones" ||
            key == VoiceBank.SETTING || key == VoiceBank.SETTING_MORPH

    /** Every path a setting holds, in the form it's stored. */
    private fun pathsOf(key: String, value: String): List<String> = when {
        value.isEmpty() -> emptyList()
        key == "zones" -> value.split('\n').map { it.substringBefore('|') }.filter { it.isNotEmpty() }
        else -> listOf(value)
    }

    /** [settings] with every path in a file setting passed through [map]. */
    private fun mapPaths(settings: Map<String, String>, map: (String) -> String): Map<String, String> =
        settings.mapValues { (key, value) ->
            when {
                value.isEmpty() || !isMediaKey(key) -> value
                key == "zones" -> value.split('\n').joinToString("\n") { line ->
                    val rest = line.substringAfter('|', "")
                    val path = line.substringBefore('|')
                    if (path.isEmpty()) line else map(path) + (if (line.contains('|')) "|$rest" else "")
                }
                else -> map(value)
            }
        }

    /**
     * Writes [patches] of [key] (a machine's type, or PatchStore.effectKey for an
     * effect) to [out]. [engine] gives the engine lines for a patch, or null.
     * Files a patch names that don't exist are left as they were and listed in
     * [Written.missing]; the file is still written.
     */
    suspend fun write(
        key: String,
        patches: List<Patch>,
        userRoot: File,
        out: File,
        engine: (suspend (Patch) -> PresetEngine.Recording?)? = null,
    ): Written {
        val isEffect = key.startsWith(PatchStore.FX)
        val unit = key.removePrefix(PatchStore.FX)
        val zip = ZipWriter(out)
        val missing = mutableListOf<String>()
        val unportable = linkedSetOf<String>()
        val packed = linkedSetOf<String>()
        var withEngine = false
        try {
            val entries = mutableListOf<String>()
            for ((i, patch) in patches.withIndex()) {
                val n = i + 1
                val rewritten = mapPathsPacking(patch, userRoot, zip, packed, missing)
                zip.add("patches/$n.json", json.encodeToString(Patch.serializer(), patch.copy(
                    machine = key, settings = rewritten, version = RateMigration.PATCH_VERSION,
                )).encodeToByteArray())
                entries += "patches/$n.json"
                val recorded = engine?.invoke(patch)
                if (recorded != null) {
                    zip.add("engine/$n.txt", recorded.text.encodeToByteArray())
                    unportable += recorded.unportable
                    withEngine = true
                }
            }
            zip.add(MANIFEST, json.encodeToString(Manifest.serializer(), Manifest(
                VERSION, if (isEffect) "effect" else "machine", unit, entries,
            )).encodeToByteArray())
        } finally {
            zip.close()
        }
        return Written(out, patches.size, packed.size, missing, withEngine, unportable.toList())
    }

    /** Packs the files [patch] names into [zip] (once each) and returns its settings pointing at them. */
    private fun mapPathsPacking(
        patch: Patch, userRoot: File, zip: ZipWriter, packed: MutableSet<String>, missing: MutableList<String>,
    ): Map<String, String> = mapPaths(patch.settings) { path ->
        val voice = VoiceBank.nameOf(path)
        when {
            path.startsWith("/") || path.contains("..") -> { missing += "${patch.name}: $path"; path }
            voice != null -> {
                // The voice's own files, where the engine lines find them: media/voices/<name>/.
                if (voice == "." || voice == ".." || VoiceBank.load(VoiceBank.folderOf(userRoot, voice)) == null) { missing += "${patch.name}: $path"; return@mapPaths path }
                for (rel in VoiceBank.filesOf(userRoot, path)) {
                    if (rel.startsWith("/") || rel.contains("..")) missing += "${patch.name}: $rel"
                    else if (File(userRoot, rel).isFile) { if (packed.add(MEDIA + rel)) zip.addFile(MEDIA + rel, File(userRoot, rel)) }
                    else missing += "${patch.name}: $rel"
                }
                MEDIA + path
            }
            File(userRoot, path).isFile -> {
                val entry = MEDIA + path
                if (packed.add(entry)) zip.addFile(entry, File(userRoot, path))
                entry
            }
            else -> { missing += "${patch.name}: $path"; path }
        }
    }

    /** Whether [zip] has a preset manifest at its root. */
    suspend fun isPreset(zip: File): Boolean {
        var found = false
        runCatching { readZip(zip) { name, isDirectory, _ -> if (!isDirectory && name == MANIFEST) found = true } }
        return found
    }

    /**
     * Adds the patches in [zip] to the user's bank for its machine or effect,
     * and its files to the user folder. Returns null, having changed nothing,
     * if [zip] isn't a preset file.
     *
     * A patch whose name is taken becomes "Name 2". A file that is already
     * there with other contents is saved as "name (2).wav" and the patch
     * points at that; one with the same contents is shared. A voice goes
     * through [VoiceImport], so it too gets a free name.
     */
    suspend fun read(zip: File, userRoot: File): Imported? {
        var manifest: Manifest? = null
        val patchJson = HashMap<String, String>()
        val names = HashSet<String>()
        val ok = runCatching {
            readZip(zip) { name, isDirectory, bytes ->
                if (isDirectory) return@readZip
                names += name
                if (name == MANIFEST) manifest = json.decodeFromString(Manifest.serializer(), bytes().decodeToString())
                else if (name.startsWith("patches/") && name.endsWith(".json")) patchJson[name] = bytes().decodeToString()
            }
        }.isSuccess
        val m = manifest
        if (!ok || m == null || m.version > VERSION) return null
        if (m.kind != "machine" && m.kind != "effect") return null
        if (!Regex("[A-Za-z0-9 _.-]{1,40}").matches(m.unit) || m.unit.startsWith(".")) return null
        val key = if (m.kind == "effect") PatchStore.effectKey(m.unit) else m.unit
        val incoming = m.patches.mapNotNull { entry ->
            patchJson[entry]?.let { text ->
                runCatching { json.decodeFromString(Patch.serializer(), text) }.getOrNull()
            }
        }
        if (incoming.isEmpty() || incoming.size != m.patches.size) return null
        val migrated = incoming.map { RateMigration.patch(it.copy(machine = key)) }

        // Each media entry a patch names, to where it ended up under the user folder.
        val wanted = linkedSetOf<String>()
        for (p in migrated) for ((k, v) in p.settings) if (isMediaKey(k)) for (path in pathsOf(k, v)) {
            if (path in names && path.startsWith(MEDIA) && !path.contains("..")) wanted += path
        }
        val placed = HashMap<String, String>()
        for (entry in wanted) {
            val setting = entry.removePrefix(MEDIA)
            val voice = VoiceBank.nameOf(setting) ?: continue
            VoiceImport.readFolder(zip, "${MEDIA}voices/$voice/", userRoot)?.let { placed[entry] = VoiceBank.settingOf(it) }
        }
        val rootPath = userRoot.canonicalFile
        readZipFiles(zip) { name, isDirectory, extractTo ->
            if (isDirectory || name !in wanted) return@readZipFiles
            val relative = name.removePrefix(MEDIA)
            if (relative.startsWith("voices/")) return@readZipFiles // voices are rebuilt above
            val target = File(userRoot, relative).canonicalFile
            if (!target.path.startsWith(rootPath.path + FILE_SEPARATOR)) return@readZipFiles
            target.parentFile?.mkdirs()
            // Streamed to a file beside the target, then kept, shared or numbered.
            val incoming = File(target.parentFile, ".import.tmp")
            extractTo(incoming)
            var final = target
            if (!target.exists()) {
                incoming.renameTo(target)
            } else if (incoming.sameContent(target)) {
                incoming.delete()
            } else {
                final = freeFile(target)
                incoming.renameTo(final)
            }
            placed[name] = final.relativeTo(rootPath).invariantSeparatorsPath
        }

        val taken = PatchStore.list(key).map { PatchStore.fileName(it).lowercase() }.toMutableSet()
        val added = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (p in migrated) {
            val name = freeName(p.name.ifBlank { m.unit }, taken)
            taken += PatchStore.fileName(name).lowercase()
            // A file that isn't in the zip is left as a plain relative path; anything that could point elsewhere is cleared.
            val settings = mapPaths(p.settings) { path ->
                placed[path] ?: if (path.startsWith("/") || path.contains("..") || path.startsWith(MEDIA)) {
                    missing += "$name: $path"
                    ""
                } else path
            }
            PatchStore.save(p.copy(name = name, settings = settings))
            added += name
        }
        return Imported(m.kind, m.unit, added, placed.size, missing)
    }

    private fun freeName(wanted: String, taken: Set<String>): String {
        if (PatchStore.fileName(wanted).lowercase() !in taken) return wanted
        var n = 2
        while (PatchStore.fileName("$wanted $n").lowercase() in taken) n++
        return "$wanted $n"
    }

    /** "take.wav" taken: "take (2).wav", then (3). */
    private fun freeFile(taken: File): File {
        var n = 2
        while (true) {
            val next = File(taken.parentFile, "${taken.nameWithoutExtension} ($n).${taken.extension}")
            if (!next.exists()) return next
            n++
        }
    }
}
