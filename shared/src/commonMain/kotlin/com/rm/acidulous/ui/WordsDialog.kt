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
 * sung, and the others keep their words.
 */
@Composable
fun WordsDialog(clip: Clip, from: Int, accent: Accent, onDismiss: () -> Unit, onApply: (List<Note>) -> Unit, only: Set<Int> = emptySet()) {
    val order = remember(clip, from, only) {
        // A chord is one place in the line, whichever of its notes was tapped or selected.
        fun start(i: Int) = clip.notes[i].tick + clip.notes[i].nudge
        Lyrics.sungOrder(clip.notes).let { o ->
            if (only.isNotEmpty()) {
                val chosen = only.filter { it in clip.notes.indices }.map { start(it) }.toSet()
                o.filter { start(it) in chosen }
            } else {
                val at = if (from in clip.notes.indices) start(from) else Int.MIN_VALUE
                o.filter { start(it) >= at }
            }
        }
    }
    var line by remember(clip, from) { mutableStateOf(Lyrics.gather(order.map { clip.notes[it].lyric })) }

    // The notes with the line applied, from which both the readout and the
    // result come. A chord's other notes sing no words of their own.
    fun applied(): List<Note> {
        val pieces = Lyrics.spread(line.replace('\n', ' '))
        val notes = clip.notes.toMutableList()
        order.forEachIndexed { k, i -> notes[i] = notes[i].copy(lyric = pieces.getOrElse(k) { "" }) }
        val starts = order.map { clip.notes[it].tick + clip.notes[it].nudge }.toSet()
        val sung = order.toSet()
        notes.indices.forEach { i ->
            if (i !in sung && notes[i].tick + notes[i].nudge in starts) notes[i] = notes[i].copy(lyric = "")
        }
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
