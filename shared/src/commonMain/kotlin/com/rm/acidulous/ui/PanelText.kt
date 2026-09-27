package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import com.rm.acidulous.res.AppStrings

/**
 * Translates a panel's English word into the phone's language.
 *
 * The English word is the key into [PANEL_WORDS], which tools/panel_words.py
 * generates from the panels. A word with a number ("op 3") is looked up as
 * its template ("op %d"). Anything not in the table comes back unchanged.
 */
internal fun AppStrings.panelWord(english: String): String {
    PANEL_WORDS[english]?.let { return getString(it) }
    val numbers = NUMBER.findAll(english).map { it.value.toInt() }.toList()
    if (numbers.isNotEmpty()) {
        PANEL_WORDS[english.replace(NUMBER, "%d")]?.let { return getString(it, *numbers.toTypedArray()) }
    }
    return english
}

@Composable
internal fun panelWord(english: String): String = AppStrings.panelWord(english)

@Composable
internal fun panelWords(english: List<String>): List<String> {
    return english.map { AppStrings.panelWord(it) }
}

private val NUMBER = Regex("\\d+")
