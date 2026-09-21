package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the *generated* banks are well formed.
 *
 * `tools/bank_test.sh` asks the harder questions - does this patch make a
 * sound, does it name parameters the engine has - but it asks them of the
 * bank files, and it never sees the Kotlin. This asks the one question only
 * this side can: that what the generator wrote is what the app can read, and
 * that `PatchStore.factory` still reaches all of it.
 */
class FactoryBanksTest {

    /** Nexus asks the engine for its module list over JNI; there is none here. */
    private val machines = MachineUi.machineGroups.flatMap { it.machines }.filter { it != "Nexus" }

    @Test
    fun `every machine with a bank has patches, and they are reachable`() {
        var total = 0
        for (machine in machines) {
            val bank = PatchStore.factory(machine)
            if (bank.isEmpty()) continue // not written yet; tools/bank_test.sh names them
            total += bank.size
            assertEquals("$machine's patches say they belong to something else",
                emptyList<String>(), bank.map { it.machine }.filter { it != machine })
            assertEquals("$machine has two patches with one name",
                bank.size, bank.map { it.name }.toSet().size)
            assertEquals("$machine's first patch should be Init", "Init", bank.first().name)
            for (p in bank) {
                assertTrue("${machine}/${p.name} was not found by load()",
                    PatchStore.factory(machine).any { it.name == p.name })
            }
        }
        assertTrue("only $total patches in all - the generator wrote nothing", total > 100)
    }

    @Test
    fun `every value is finite and normalised`() {
        for (machine in machines) {
            for (p in PatchStore.factory(machine)) {
                for ((name, v) in p.params) {
                    assertTrue("${machine}/${p.name}: $name is $v", v.isFinite() && v in 0f..1f)
                }
            }
        }
    }

    @Test
    fun `Init sets nothing, so loading it is a reset`() {
        // The app fills every parameter a patch omits with the engine's own
        // default, so an empty patch is how "put this machine back" is spelt.
        for (machine in machines) {
            val bank = PatchStore.factory(machine)
            if (bank.isEmpty()) continue
            assertTrue("$machine's Init carries values", bank.first().params.isEmpty())
        }
    }

    /**
     * The generated file is only as current as the last run of the script.
     *
     * Forgetting to run it is silent - the app simply has fewer patches than
     * the bank files say, which is exactly what happened the first time an
     * effect bank was written. Counting `patch` lines is a crude comparison
     * and it catches the whole of that.
     */
    @Test
    fun `the generated banks are as new as the bank files`() {
        val dir = java.io.File("../tools/banks")
        assertTrue("no bank files at ${dir.absolutePath}", dir.isDirectory)
        val stale = mutableListOf<String>()
        for (file in dir.listFiles { f -> f.extension == "bank" }.orEmpty().sortedBy { it.name }) {
            val unit = file.nameWithoutExtension
            val inFile = file.readLines().count { it.trimStart().startsWith("patch ") }
            val generated = PatchStore.factory(unit).size
            if (inFile != generated) stale += "$unit: $inFile in the bank, $generated generated"
        }
        assertEquals("run tools/gen_patches.sh", emptyList<String>(), stale)
    }

    @Test
    fun `a patch with a range has a bottom below its top, on the keyboard`() {
        var ranged = 0
        for (machine in machines) {
            for (p in PatchStore.factory(machine)) {
                if (p.low < 0 && p.high < 0) continue
                ++ranged
                assertTrue("${machine}/${p.name}: range ${p.low}..${p.high}",
                    p.low in 0..127 && p.high in 0..127 && p.low < p.high)
            }
        }
        assertTrue("no patch carries a range - the generator dropped range=", ranged > 0)
    }

    @Test
    fun `a factory patch survives being saved and read back`() {
        val patch = PatchStore.factory("Reflux").first { it.params.isNotEmpty() }
        val text = SongStore.json.encodeToString(Patch.serializer(), patch)
        assertEquals(patch, SongStore.json.decodeFromString(Patch.serializer(), text))
    }
}
