package com.rm.acidulous.model

import com.rm.acidulous.engine.EngineLineWriter
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.recordDry
import com.rm.acidulous.io.*

/**
 * The engine lines that build a song on a fresh engine, for a song zip's
 * engine.txt. Media under the user folder are written by their path in the
 * zip, which is the same relative path the song uses.
 *
 * Recorded dry like PresetEngine: EngineSync syncs the song against an empty
 * memory of its own and the calls go to the recording, never to the engine,
 * so a song playing carries on. Frozen clips aren't recorded, so a frozen
 * clip replays as its notes.
 *
 * Main thread only, since sync's pushes are.
 */
object SongEngine {
    class Recording(val text: String, val unportable: List<String>)

    fun record(song: Song, userRoot: File): Recording {
        val prefix = userRoot.absolutePath + FILE_SEPARATOR
        val writer = EngineLineWriter { path ->
            if (path.startsWith(prefix)) path.removePrefix(prefix).replace('\\', '/') else null
        }
        recordDry(writer) { EngineSync.scratch { m -> EngineSync.syncFresh(song, m) } }
        return Recording(writer.text(), writer.unportable())
    }
}
