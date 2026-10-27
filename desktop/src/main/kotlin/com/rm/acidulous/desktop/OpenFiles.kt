package com.rm.acidulous.desktop

import com.rm.acidulous.Doc
import com.rm.acidulous.Incoming
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Files opened with the app: named on its command line (a file manager on
 * Linux and Windows), sent by Finder on a Mac, or handed over by a second
 * launch (SingleInstance). Each goes to [Incoming] in turn, which takes one
 * at a time, and the app imports it as if it came from its own picker.
 */
internal object OpenFiles {
    private val waiting = ConcurrentLinkedQueue<File>()

    /** Set once this is the copy that runs, so files wait to be handed over until then. */
    @Volatile var running = false

    /** The files on the command line. Anything that isn't a file is left alone. */
    fun fromArgs(args: Array<String>): List<File> =
        args.filter { !it.startsWith("-") }.map { File(it).absoluteFile }.filter { it.isFile }

    fun add(files: List<File>) {
        if (files.isEmpty()) return
        waiting.addAll(files)
        if (running) EventQueue.invokeLater { next() }
    }

    /** Everything added so far, taken off the list: for a second launch to hand over. */
    fun drain(): List<File> = generateSequence { waiting.poll() }.toList()

    /** Gives [Incoming] the next file once the last is imported, with any dialog it opened closed. On the UI thread. */
    fun next() {
        if (running && Incoming.idle()) waiting.poll()?.let { Incoming.doc = Doc(it) }
    }
}
