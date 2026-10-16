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
 * so a song playing carries on. Every clip's notes are recorded, frozen or
 * not. With [frozenAudio], each frozen clip that plays as audio also gets a
 * MountFrozen naming its WAV under freeze/ in the zip (SongBundle.write puts
 * it there), so a host can play the audio or the notes.
 *
 * Main thread only, since sync's pushes are.
 */
object SongEngine {
    class Recording(val text: String, val unportable: List<String>)

    fun record(song: Song, userRoot: File, frozenAudio: Boolean = false): Recording {
        val prefix = userRoot.absolutePath + FILE_SEPARATOR
        val freezePrefix = EngineSync.freezeRoot?.let { it.absolutePath + FILE_SEPARATOR }
        val writer = EngineLineWriter { path ->
            when {
                path.startsWith(prefix) -> path.removePrefix(prefix).replace('\\', '/')
                freezePrefix != null && path.startsWith(freezePrefix) ->
                    SongBundle.FREEZE_DIR + path.removePrefix(freezePrefix).replace('\\', '/')
                else -> null
            }
        }
        recordDry(writer) {
            // A song starts with no freezes, whatever the host played before.
            com.rm.acidulous.engine.NativeEngine.clearFrozen()
            EngineSync.scratch { m -> EngineSync.syncFresh(song, m, frozenAudio) }
        }
        return Recording(writer.text(), writer.unportable())
    }
}
