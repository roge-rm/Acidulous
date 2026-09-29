package com.rm.acidulous.model.voice

import com.rm.acidulous.io.File
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

        fun load(folder: File): VoiceBank? = runCatching {
            json.decodeFromString(serializer(), File(folder, INDEX).readText())
        }.getOrNull()

        fun save(folder: File, bank: VoiceBank) {
            folder.mkdirs()
            File(folder, INDEX).writeBytesSafely(json.encodeToString(serializer(), bank).encodeToByteArray())
        }

        /** Every recorded voice under [root], by name. */
        fun all(root: File): List<VoiceBank> =
            File(root, "voices").listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it) }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
}
