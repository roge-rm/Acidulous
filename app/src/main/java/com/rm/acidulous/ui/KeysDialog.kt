package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.R
import com.rm.acidulous.ui.theme.Acid

/**
 * Every shortcut and its keys, changeable the way a controller mapping is
 * learned: choose a key and press the new one.
 *
 * A window of its own rather than a Settings tab, because a tabbed window is
 * as tall as its tallest page and a list of twenty actions would have made
 * every page of Settings that tall. A key already in use moves to the action
 * it is given to - two actions on one key could never both run - and the
 * window says, under the row, which one lost it.
 */
@Composable
fun KeysDialog(onDismiss: () -> Unit) {
    val c = Acid.colors
    val bindings = UiPrefs.keyBindings
    // What is being learned: an action and which of its two keys.
    var learning by remember { mutableStateOf<Pair<KeyAction, Int>?>(null) }
    // The action that last took a key from another, and the one it took it from.
    var moved by remember { mutableStateOf<Pair<KeyAction, KeyAction>?>(null) }

    fun learn(action: KeyAction, slot: Int) {
        if (learning == action to slot) { learning = null; KeyHub.learning = null; return }
        learning = action to slot
        KeyHub.learning = { chord ->
            learning = null
            // Off whichever action had it.
            val from = UiPrefs.keyBindings.entries.firstOrNull { (a, cs) -> a != action && chord in cs }?.key
            if (from != null) UiPrefs.chooseKeys(from, UiPrefs.keyBindings[from].orEmpty() - chord)
            val now = UiPrefs.keyBindings[action].orEmpty().toMutableList()
            if (slot < now.size) now[slot] = chord else now += chord
            UiPrefs.chooseKeys(action, now.distinct())
            moved = from?.let { action to it }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { KeyHub.learning = null } }

    PlainDialog(
        title = stringResource(R.string.keys_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.done),
        spacing = 8.dp,
    ) {
        WindowCards {
            WindowCard(stringResource(R.string.keys_notes_card)) {
                SwitchGrid(
                    stringResource(R.string.keys_layout),
                    stringArrayResource(R.array.keys_layouts).toList(),
                    UiPrefs.noteLayout.ordinal,
                ) { UiPrefs.chooseNoteLayout(NoteLayout.entries[it]) }
                SwitchGrid(stringResource(R.string.keys_defaults), listOf(stringResource(R.string.keys_reset)), -1) {
                    UiPrefs.resetKeys()
                    moved = null
                }
            }
            for (group in KeyGroup.entries) {
                WindowCard(stringResource(group.label)) {
                    Column(Modifier.cardLine(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (action in KeyAction.entries.filter { it.group == group }) {
                            val chords = bindings[action].orEmpty()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(action.label), color = c.text, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                for (slot in 0 until 2) {
                                    val chord = chords.getOrNull(slot)
                                    // A second key is offered only once there is a first.
                                    if (chord == null && slot > chords.size) continue
                                    KeyChip(
                                        text = when {
                                            learning == action to slot -> stringResource(R.string.keys_press)
                                            chord != null -> chord.words()
                                            else -> "+"
                                        },
                                        waiting = learning == action to slot,
                                        said = stringResource(R.string.keys_chip_said, stringResource(action.label), chord?.words() ?: stringResource(R.string.keys_none)),
                                        onRemove = chord?.let { ch -> { UiPrefs.chooseKeys(action, chords - ch) } },
                                        removeLabel = stringResource(R.string.keys_remove),
                                    ) { learn(action, slot) }
                                }
                            }
                            // Under the row just pressed, where the eye is.
                            moved?.takeIf { it.first == action }?.let { (_, from) ->
                                Text(stringResource(R.string.keys_moved, stringResource(from.label)), color = c.accent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyChip(
    text: String,
    waiting: Boolean,
    said: String,
    onRemove: (() -> Unit)?,
    removeLabel: String,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Text(
        text, color = if (waiting) c.onAccent else c.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.width(104.dp).clip(RoundedCornerShape(4.dp))
            .background(if (waiting) c.accent else c.control)
            .clickable(onClick = onClick)
            .button(said, actions = listOfNotNull(onRemove?.let { action(removeLabel, it) }))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        maxLines = 1,
    )
}
