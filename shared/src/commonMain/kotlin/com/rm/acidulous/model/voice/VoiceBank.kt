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
 * picks up where it stopped.
 */
@Serializable
data class VoiceBank(
    val name: String,
    val note: Int = DEFAULT_NOTE,
    val takes: Map<String, String> = emptyMap(),
) {
    fun sung(prompt: Prompt): Boolean = prompt.id in takes

    /** How many of a stage's prompts have a take. */
    fun doneIn(stage: Int): Int = VoicePrompts.inStage(stage).count { sung(it) }

    /** The first prompt without a take, in order, or null when every one has one. */
    fun nextToSing(): Prompt? = VoicePrompts.all.firstOrNull { !sung(it) }

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
