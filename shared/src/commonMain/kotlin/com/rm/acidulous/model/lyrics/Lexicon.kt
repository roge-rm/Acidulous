package com.rm.acidulous.model.lyrics

import kotlin.concurrent.Volatile

/**
 * The dictionary, once it's loaded. Loaded when a song first has a singer in
 * it, not at start: it's a megabyte and a half most songs never need. Until
 * then words are said from their spelling.
 */
object Lexicon {
    @Volatile var dictionary: Dictionary? = null
}
