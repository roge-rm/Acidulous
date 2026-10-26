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

/**
 * The words from one note to the end of the clip, as a line: a syllable a
 * note, "hel-lo" for a word over two. Under it, the sounds each note will
 * sing, so a word said wrongly can be put right in brackets. With [only],
 * the selected notes, the line goes over just those, in the order they're
 * sung, and the others keep their words. With [single], the field holds
 * just the tapped note's syllable; more than one carries on over the notes
 * after it.
 */
@Composable
fun WordsDialog(clip: Clip, from: Int, accent: Accent, onDismiss: () -> Unit, onApply: (List<Note>) -> Unit, only: Set<Int> = emptySet(), single: Boolean = false) {
    val order = remember(clip, from, only, single) {
        val o = Lyrics.lineOrder(clip.notes, from, only)
        if (single) o.take(1) else o
    }
    var line by remember(clip, from) { mutableStateOf(Lyrics.gather(order.map { clip.notes[it].lyric })) }

    // The notes the line goes over, and the notes with it applied, from which
    // both the readout and the result come. One syllable on one note leaves
    // the rest alone; more carry on over the notes after it.
    fun overOrder() = if (single) Lyrics.editOrder(clip.notes, from, line) else order
    fun applied(): List<Note> = Lyrics.place(clip.notes, overOrder(), line)

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
            val shown = overOrder().map { sounds[it] }
            val upTo = shown.indexOfLast { it.isNotEmpty() } + 1
            Readout(shown.take(upTo).joinToString("  ·  ") { it.lowercase().ifEmpty { "_" } })
        }
    }
}
