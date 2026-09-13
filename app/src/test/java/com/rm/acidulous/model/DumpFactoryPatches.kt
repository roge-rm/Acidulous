package com.rm.acidulous.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes every factory patch out as text, for `audition seed` to turn into
 * bank files.
 *
 * A one-way bridge, and a temporary one. The patches are normalised floats in
 * Kotlin and the ranges that give them meaning are ParamDef tables in C++, so
 * converting them back into readable units cannot happen on this side - but
 * enumerating them cannot happen on the other side either, because
 * `PatchStore.factory()` is Kotlin. So this half enumerates and the C++ half
 * converts.
 *
 * Hand-porting a hundred and twenty-four patches through normalised-to-unit
 * arithmetic would be a guaranteed source of silent transcription errors, and
 * a silent transcription error in a preset is invisible: it just sounds like a
 * patch somebody voiced badly.
 *
 * Delete this once every machine's bank is a file. Until then it is how the
 * banks get seeded from what already shipped.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests '*DumpFactoryPatches*'
 * Out: app/build/factory-dump.txt
 */
class DumpFactoryPatches {

    @Test
    fun `write every factory patch as text`() {
        val out = StringBuilder()
        var patches = 0
        for (machine in MachineUi.machineGroups.flatMap { it.machines }) {
            // Nexus's presets are built from NexusPalette, which asks the
            // engine for the module list over JNI - and there is no engine on
            // a JVM. Its bank is the one that has to be authored on the
            // device anyway: a module graph is not something to type into a
            // text file by hand.
            if (machine == "Nexus") continue
            val bank = PatchStore.factory(machine)
            out.append("# ").append(machine).append(' ').append(bank.size).append('\n')
            for (p in bank) {
                out.append("patch\t").append(machine).append('\t').append(p.name).append('\n')
                for ((name, value) in p.params.entries.sortedBy { it.key }) {
                    out.append("param\t").append(name).append('\t').append(value).append('\n')
                }
                for ((key, value) in p.settings.entries.sortedBy { it.key }) {
                    // One line, so a formula with newlines in it would break
                    // the format. None has any today; the guard says so if
                    // one ever does.
                    assertTrue("setting $key of ${p.name} spans lines", !value.contains('\n'))
                    out.append("set\t").append(key).append('\t').append(value).append('\n')
                }
                patches++
            }
        }
        val file = File("build/factory-dump.txt")
        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        println("wrote $patches patches to ${file.absolutePath}")
        assertTrue("no patches found at all", patches > 100)
    }
}
