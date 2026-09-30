package com.rm.acidulous.model.voice

import com.rm.acidulous.io.File
import com.rm.acidulous.io.absolutePath
import com.rm.acidulous.io.isDirectory
import com.rm.acidulous.io.readText
import com.rm.acidulous.io.writeBytesSafely
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A voice someone recorded: a folder under voices/ with a take a prompt and
 * this index, bank.json.
 *
 * [note] is the one note every prompt is sung on (MIDI), so each take is
 * already at the same pitch. [takes] maps a prompt's id to its file, in the
 * folder; a prompt without one hasn't been sung yet, which is how a session
 * picks up where it stopped. [cuts] is how each take was cut up, once it has
 * been; a take whose cut found a problem still has to be sung again.
 */
@Serializable
data class VoiceBank(
    val name: String,
    val note: Int = DEFAULT_NOTE,
    val takes: Map<String, String> = emptyMap(),
    val cuts: Map<String, TakeCut> = emptyMap(),
) {
    fun sung(prompt: Prompt): Boolean = prompt.id in takes

    /** What's wrong with a prompt's take, or "" when it's fine or not cut yet. */
    fun problemOf(prompt: Prompt): String = cuts[prompt.id]?.problem.orEmpty()

    /** Sung, and not waiting to be sung again. */
    fun done(prompt: Prompt): Boolean = sung(prompt) && problemOf(prompt).isEmpty()

    /** How many of a stage's prompts have a take that's fine. */
    fun doneIn(stage: Int): Int = VoicePrompts.inStage(stage).count { done(it) }

    /** The first prompt not done, in order, or null when every one is. */
    fun nextToSing(): Prompt? = VoicePrompts.all.firstOrNull { !done(it) }

    companion object {
        /** A low A: comfortable for most voices, between a man's and a woman's. */
        const val DEFAULT_NOTE = 57
        const val INDEX = "bank.json"

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun folderOf(root: File, name: String): File = File(File(root, "voices"), name)

        /** The machine setting a Diction sings a recorded voice by. */
        const val SETTING = "voice"

        /** A voice's setting: its index's path under the user folder, which a song bundle follows to its takes. */
        fun settingOf(name: String): String = "voices/$name/$INDEX"

        /** The voice a setting names, or null for the built-in one. */
        fun nameOf(setting: String): String? =
            setting.takeIf { it.startsWith("voices/") && it.endsWith("/$INDEX") }
                ?.removePrefix("voices/")?.removeSuffix("/$INDEX")?.takeIf { it.isNotEmpty() && '/' !in it }

        /** Goes up whenever a voice is saved, so what was worked out from one can tell it's out of date. */
        var saves = 0
            private set

        /**
         * What the engine sings a voice from, a line per take that's fine: a
         * held vowel, "V|PHONE|path|holdFrom|holdTo", a diphthong,
         * "D|PHONE|path|holdFrom|holdTo|glideFrom|glideTo", or a consonant
         * between two vowels, "C|PHONE|VOWEL|path|from|to".
         */
        fun engineSpec(root: File, setting: String): String {
            val name = nameOf(setting) ?: return ""
            val folder = folderOf(root, name)
            val bank = load(folder) ?: return ""
            return VoicePrompts.all.filter { bank.done(it) }.mapNotNull { p ->
                val cut = bank.cuts[p.id] ?: return@mapNotNull null
                val path = File(folder, bank.takes[p.id] ?: return@mapNotNull null).absolutePath
                if (p.glides) "D|${p.sounds[0]}|$path|${cut.holdFrom}|${cut.holdTo}|${cut.glideFrom}|${cut.glideTo}"
                else if (p.held) "V|${p.sounds[0]}|$path|${cut.holdFrom}|${cut.holdTo}"
                else "C|${p.sounds[1]}|${p.sounds[0]}|$path|${cut.consonantFrom}|${cut.consonantTo}"
            }.joinToString("\n")
        }

        /** A voice's files under the user folder, for a song bundle: its index and its takes. */
        fun filesOf(root: File, setting: String): List<String> {
            val name = nameOf(setting) ?: return emptyList()
            val bank = load(folderOf(root, name)) ?: return emptyList()
            return listOf(setting) + bank.takes.values.map { "voices/$name/$it" }
        }

        fun load(folder: File): VoiceBank? = runCatching {
            json.decodeFromString(serializer(), File(folder, INDEX).readText())
        }.getOrNull()

        fun save(folder: File, bank: VoiceBank) {
            saves++
            folder.mkdirs()
            File(folder, INDEX).writeBytesSafely(json.encodeToString(serializer(), bank).encodeToByteArray())
        }

        /** Every recorded voice under [root], by name. */
        fun all(root: File): List<VoiceBank> =
            File(root, "voices").listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it) }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
}
