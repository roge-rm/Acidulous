package com.rm.acidulous.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.lyrics.Accent
import com.rm.acidulous.model.lyrics.Lexicon
import com.rm.acidulous.model.lyrics.Lyrics
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.stringResource

/** A singer's notes in the order they're sung. */
internal fun sungOrder(clip: Clip): List<Int> =
    clip.notes.indices.sortedWith(compareBy({ clip.notes[it].tick + clip.notes[it].nudge }, { clip.notes[it].pitch }))

/**
 * The words from one note to the end of the clip, as a line: a syllable a
 * note, "hel-lo" for a word over two. Under it, the sounds each note will
 * sing, so a word said wrongly can be put right in brackets. With [only],
 * the selected notes, the line goes over just those, in the order they're
 * sung, and the others keep their words.
 */
@Composable
fun WordsDialog(clip: Clip, from: Int, accent: Accent, onDismiss: () -> Unit, onApply: (List<Note>) -> Unit, only: Set<Int> = emptySet()) {
    val order = remember(clip, from, only) {
        sungOrder(clip).let { o -> if (only.isNotEmpty()) o.filter { it in only } else o.drop(o.indexOf(from).coerceAtLeast(0)) }
    }
    var line by remember(clip, from) { mutableStateOf(Lyrics.gather(order.map { clip.notes[it].lyric })) }

    // The notes with the line applied, from which both the readout and the result come.
    fun applied(): List<Note> {
        val pieces = Lyrics.spread(line.replace('\n', ' '))
        val notes = clip.notes.toMutableList()
        order.forEachIndexed { k, i -> notes[i] = notes[i].copy(lyric = pieces.getOrElse(k) { "" }) }
        return notes
    }

    PlainDialog(
        title = stringResource(Res.string.words_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = { onApply(applied()) },
    ) {
        val said = stringResource(Res.string.a11y_words_field)
        OutlinedTextField(
            value = line, onValueChange = { line = it },
            modifier = Modifier.typing() then Modifier.fillMaxWidth().semantics { contentDescription = said },
        )
        val notes = applied()
        val sounds = Lyrics.forNotes(notes, accent, Lexicon.dictionary)
        if (sounds != null) {
            val shown = order.map { sounds[it] }
            val upTo = shown.indexOfLast { it.isNotEmpty() } + 1
            Readout(shown.take(upTo).joinToString("  ·  ") { it.lowercase().ifEmpty { "_" } })
        }
    }
}
