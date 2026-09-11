package com.rm.acidulous.model

import com.rm.acidulous.engine.NativeEngine

/**
 * A Nexus patch, as the document stores it.
 *
 * Two kinds of thing live here and they behave differently. The **topology**
 * - which modules exist and what is wired to what - changes only when the
 * player adds or removes something, and changing it rebuilds the graph. The
 * **layout** - where the nodes sit on the canvas - changes constantly while
 * dragging and must never rebuild anything, which is why [topology] exists
 * and why the engine is only ever asked to reload when that string changes.
 *
 * A module's type is text, never a number, so adding module types in a later
 * version cannot scramble an existing patch. A slot whose type this build
 * does not know keeps its place and its cables rather than being dropped.
 *
 * Slot numbers are permanent. Deleting a module leaves a hole and the next
 * one takes the lowest free number: renumbering would silently repoint every
 * automation lane and every stored knob value at once.
 */
data class NexusModule(
    val slot: Int,
    val type: String,
    val poly: Boolean = true,
    val x: Float = 0f,
    val y: Float = 0f,
)

data class NexusCable(
    val fromSlot: Int, val fromPort: Int,
    val toSlot: Int, val toPort: Int,
    val modSlot: Int = -1, val modPort: Int = 0, val modAmount: Float = 0f,
)

data class NexusPatch(
    val modules: List<NexusModule> = emptyList(),
    val cables: List<NexusCable> = emptyList(),
) {
    /** What the engine is asked to build. Cable order fixes which depth parameters apply. */
    fun encode(): String = buildString {
        append("v|1\n")
        modules.sortedBy { it.slot }.forEach {
            append("m|%02d|%s|%s\n".format(it.slot, it.type, if (it.poly) "poly" else "mono"))
        }
        cables.forEach {
            append("c|%02d.%d|%02d.%d|1.0|1.0|%02d.%d|%.4f\n".format(
                it.fromSlot, it.fromPort, it.toSlot, it.toPort,
                if (it.modSlot < 0) 99 else it.modSlot, it.modPort, it.modAmount))
        }
        modules.sortedBy { it.slot }.forEach { append("p|%02d|%.0f|%.0f\n".format(it.slot, it.x, it.y)) }
    }

    /** The part the engine cares about: everything but where the boxes sit. */
    fun topology(): String = encode().lineSequence().filterNot { it.startsWith("p|") }.joinToString("\n")

    fun freeSlot(): Int = (0 until NEXUS_SLOTS).firstOrNull { slot -> modules.none { it.slot == slot } } ?: -1
    fun moduleAt(slot: Int): NexusModule? = modules.firstOrNull { it.slot == slot }

    companion object {
        fun decode(text: String?): NexusPatch {
            if (text.isNullOrBlank()) return NexusPatch()
            val modules = mutableListOf<NexusModule>()
            val cables = mutableListOf<NexusCable>()
            val positions = mutableMapOf<Int, Pair<Float, Float>>()
            for (line in text.lineSequence()) {
                val f = line.split('|')
                runCatching {
                    when (f.getOrNull(0)) {
                        "m" -> modules += NexusModule(f[1].toInt(), f[2], f.getOrNull(3) != "mono")
                        "c" -> {
                            val from = f[1].split('.')
                            val to = f[2].split('.')
                            val mod = f.getOrNull(5)?.split('.')
                            val modSlot = mod?.getOrNull(0)?.toIntOrNull() ?: -1
                            cables += NexusCable(
                                from[0].toInt(), from[1].toInt(), to[0].toInt(), to[1].toInt(),
                                if (modSlot >= NEXUS_SLOTS) -1 else modSlot,
                                mod?.getOrNull(1)?.toIntOrNull() ?: 0,
                                f.getOrNull(6)?.toFloatOrNull() ?: 0f,
                            )
                        }
                        "p" -> positions[f[1].toInt()] = f[2].toFloat() to f[3].toFloat()
                    }
                }
            }
            return NexusPatch(
                modules.map { m -> positions[m.slot]?.let { m.copy(x = it.first, y = it.second) } ?: m },
                cables,
            )
        }
    }
}

const val NEXUS_SLOTS = 16
const val NEXUS_CABLES = 24
const val NEXUS_KNOBS = 8

/** One kind of block, described by the engine rather than by a copy kept here. */
data class NexusModuleInfo(
    val name: String,
    val canPoly: Boolean,
    val canMono: Boolean,
    val knobs: List<String>,
    val defaults: List<Float>,
    val inputs: List<String>,
    val outputs: List<String>,
)

/**
 * The palette, read from the engine once.
 *
 * Every other machine in this app keeps a Kotlin list of labels aligned by
 * eye with a C++ enum. With thirty module types, eight knobs each and jacks
 * to name as well, that would be several hundred strings to keep in step by
 * hand. So this comes over the wire instead, and there is one copy of the
 * truth.
 */
object NexusPalette {
    val types: List<NexusModuleInfo> by lazy { parse(NativeEngine.nexusPalette()) }

    fun of(name: String): NexusModuleInfo? = types.firstOrNull { it.name == name }

    /** Everything a player can place: not the blank placeholder. */
    val placeable: List<NexusModuleInfo> get() = types.filter { it.name != "blank" }

    private fun parse(text: String): List<NexusModuleInfo> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 6) return@mapNotNull null
            val cap = f[1].toIntOrNull() ?: 3
            NexusModuleInfo(
                name = f[0],
                canPoly = (cap and 1) != 0,
                canMono = (cap and 2) != 0,
                knobs = f[2].split(','),
                defaults = f[3].split(',').map { it.toFloatOrNull() ?: 0f },
                inputs = f[4].split(',').filter { it.isNotEmpty() },
                outputs = f[5].split(',').filter { it.isNotEmpty() },
            )
        }
        .toList()
}

/** The parameter name for one slot knob, which never depends on what is in the slot. */
fun nexusKnob(slot: Int, knob: Int): String = "s%02d_p%d".format(slot, knob + 1)
fun nexusCableA(index: Int): String = "c%02d_a".format(index)
fun nexusCableB(index: Int): String = "c%02d_b".format(index)
