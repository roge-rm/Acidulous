package com.rm.acidulous.model

import com.rm.acidulous.engine.EngineLineWriter
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.recordDry
import com.rm.acidulous.io.*

/**
 * The engine calls that apply a patch to a track, for a preset file's
 * engine/ entries: for a machine its mount, loads and parameters, for an
 * effect its mount, loads and parameters, and nothing else. Written for
 * rack 0 (and effect slot 0); the replayer can put them on another.
 *
 * Recorded dry. EngineSync works out what a fresh engine would be sent, from
 * empty memory it puts back afterwards, and the calls are written to the
 * recording and never reach the engine, so a song playing carries on. The
 * parameters the patch doesn't name are written at their defaults, so a patch
 * loaded over another sounds as saved.
 *
 * Main thread only, since it uses EngineSync's memory.
 */
object PresetEngine {
    class Recording(val text: String, val unportable: List<String>)

    /** The one-track song a patch is applied to. */
    fun songFor(patch: Patch): Song {
        val scene = Scene(id = "s-one", name = "One")
        val isEffect = patch.machine.startsWith(PatchStore.FX)
        val track = if (isEffect) {
            Track(
                id = "t-one", name = patch.name, machine = Machine(""),
                effects = listOf(UnitSlot(patch.machine.removePrefix(PatchStore.FX), patch.params, settings = patch.settings)),
            )
        } else {
            Track(id = "t-one", name = patch.name, machine = Machine(patch.machine, patch.params, patch.settings))
        }
        return Song(name = patch.name, scenes = listOf(scene), tracks = listOf(track))
    }

    fun record(patch: Patch, userRoot: File): Recording {
        val prefix = userRoot.absolutePath + FILE_SEPARATOR
        val writer = EngineLineWriter { path ->
            if (path.startsWith(prefix)) "media/" + path.removePrefix(prefix).replace('\\', '/') else null
        }
        val song = songFor(patch)
        val isEffect = patch.machine.startsWith(PatchStore.FX)
        writer.comment(if (isEffect) "target rack 0 slot 0" else "target rack 0")
        recordDry(writer) {
            EngineSync.scratch { m ->
                if (isEffect) {
                    EngineSync.ensureEffects(song, m)
                    EngineSync.ensureEffectFormulas(song, m)
                    val slot = song.tracks[0].effectAt(0)
                    val named = NativeEngine.effectParamInfo(slot.type).associate { it.name to it.defaultNormalized } + slot.params
                    EngineSync.pushSlot(0, effectUnit(0), slot.copy(params = named))
                } else {
                    EngineSync.ensureMachines(song, m)
                    EngineSync.ensureSamples(song, m)
                    EngineSync.ensureSampleMaps(song, m)
                    EngineSync.ensureNexusPatches(song, m)
                    EngineSync.ensureClouds(song, m)
                    EngineSync.ensureFormulas(song, m)
                    EngineSync.ensureTakes(song, m)
                    EngineSync.ensureVoices(song, m)
                    val machine = song.tracks[0].machine
                    EngineSync.pushMachineParams(0, NativeEngine.machineParamInfo(machine.type).associate { it.name to it.defaultNormalized } + machine.params)
                }
            }
        }
        return Recording(writer.text(), writer.unportable())
    }
}
