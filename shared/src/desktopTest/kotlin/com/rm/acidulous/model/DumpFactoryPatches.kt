package com.rm.acidulous.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes every factory patch out as text, for `audition seed` to turn into
 * bank files.
 *
 * The patches are normalised floats in Kotlin, and the ranges that turn them
 * into real units are ParamDef tables in C++. So this side lists them and the
 * C++ side converts them, which avoids hand-porting 124 patches and the
 * silent mistakes that would bring.
 *
 * Delete this once every machine's bank is a file.
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
            // Nexus's presets are built from NexusPalette, which asks the engine
            // for the module list over JNI, and there's no engine on a JVM. Its
            // bank has to be made on the device anyway.
            if (machine == "Nexus") continue
            val bank = PatchStore.factory(machine)
            out.append("# ").append(machine).append(' ').append(bank.size).append('\n')
            for (p in bank) {
                out.append("patch\t").append(machine).append('\t').append(p.name).append('\n')
                for ((name, value) in p.params.entries.sortedBy { it.key }) {
                    out.append("param\t").append(name).append('\t').append(value).append('\n')
                }
                for ((key, value) in p.settings.entries.sortedBy { it.key }) {
                    // One line per setting, so a value with a newline would break the
                    // format. None has one today.
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
