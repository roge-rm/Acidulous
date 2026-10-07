package com.rm.acidulous.model

import com.rm.acidulous.util.format

import com.rm.acidulous.engine.NativeEngine

/**
 * A Nexus patch, as the song stores it.
 *
 * The topology (which modules exist and what's wired to what) only changes
 * when the player adds or removes something, and changing it rebuilds the
 * graph. The layout (where nodes sit on the canvas) changes all the time
 * while dragging and must never rebuild anything. That's why [topology]
 * exists: the engine only reloads when that string changes.
 *
 * A module's type is stored as text, not a number, so new module types in a
 * later version can't scramble an existing patch. A slot with a type this
 * build doesn't know keeps its place and its cables.
 *
 * Slot numbers never change. Deleting a module leaves a hole and the next one
 * takes the lowest free number, since renumbering would repoint every
 * automation lane and stored knob value.
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
    /**
     * Text a module is programmed with, by slot: a formula module's
     * expression. Part of the topology, since changing it rebuilds the module.
     */
    val texts: Map<Int, String> = emptyMap(),
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
        // Everything after the slot is the text, `|` and all, since a formula
        // can use it. Older builds skip the line.
        texts.entries.sortedBy { it.key }.forEach { (slot, text) ->
            if (text.isNotBlank() && modules.any { it.slot == slot }) append("e|%02d|%s\n".format(slot, text.lines().joinToString(" ")))
        }
        modules.sortedBy { it.slot }.forEach { append("p|%02d|%.0f|%.0f\n".format(it.slot, it.x, it.y)) }
    }

    /** The part the engine cares about: everything except where the boxes sit. */
    fun topology(): String = encode().lineSequence().filterNot { it.startsWith("p|") }.joinToString("\n")

    fun freeSlot(): Int = (0 until NEXUS_SLOTS).firstOrNull { slot -> modules.none { it.slot == slot } } ?: -1
    fun moduleAt(slot: Int): NexusModule? = modules.firstOrNull { it.slot == slot }

    companion object {
        fun decode(text: String?): NexusPatch {
            if (text.isNullOrBlank()) return NexusPatch()
            val modules = mutableListOf<NexusModule>()
            val cables = mutableListOf<NexusCable>()
            val positions = mutableMapOf<Int, Pair<Float, Float>>()
            val texts = mutableMapOf<Int, String>()
            for (line in text.lineSequence()) {
                if (line.startsWith("e|")) {
                    val f = line.split('|', limit = 3)
                    val slot = f.getOrNull(1)?.toIntOrNull()
                    if (slot != null && f.size == 3) texts[slot] = f[2]
                    continue
                }
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
            // Modules with no `p|` line are laid out rather than left at the
            // origin. Patches written as text, like bank files, say what's
            // wired to what but not where the boxes go, and would otherwise
            // all stack on one point.
            //
            // With no positions at all (every factory patch) it's laid out
            // the way the fit button does, along the signal, for a square
            // window since the screen size isn't known here.
            if (positions.isEmpty()) return NexusPatch(modules, cables, texts).arranged(1f, NexusFaces::size)
            var placed = 0
            val stepX = modules.maxOfOrNull { NexusFaces.size(it).first }?.plus(20f) ?: 0f
            val patch = NexusPatch(
                modules.map { m ->
                    positions[m.slot]?.let { m.copy(x = it.first, y = it.second) } ?: run {
                        val i = placed++
                        m.copy(x = 60f + (i % 4) * stepX, y = 60f + (i / 4) * (FACE_H + 60f))
                    }
                },
                cables,
                texts,
            )
            // Modules were smaller boxes before they were faceplates, so a patch
            // placed by hand then can have them on top of each other now. That
            // one is laid out again; one that still fits keeps its places.
            return if (patch.overlaps(NexusFaces::size)) patch.arranged(1f, NexusFaces::size) else patch
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
 * The module palette, read from the engine once.
 *
 * With thirty module types, eight knobs each and named jacks, keeping a
 * Kotlin copy in step with the C++ by hand would be hundreds of strings, so
 * the engine sends it over instead.
 */
object NexusPalette {
    // Kept once the engine has answered. An empty answer, from an engine
    // that isn't up yet, is asked again next time instead of kept.
    private var loaded: List<NexusModuleInfo>? = null
    val types: List<NexusModuleInfo>
        get() = loaded ?: parse(NativeEngine.nexusPalette()).also { if (it.isNotEmpty()) loaded = it }

    fun of(name: String): NexusModuleInfo? = types.firstOrNull { it.name == name }

    /**
     * Like [of], but null when the engine can't be asked, as in the unit
     * tests, which don't load it. For working out sizes, where a plain
     * faceplate will do.
     */
    fun ofOrNull(name: String): NexusModuleInfo? = runCatching { of(name) }.getOrNull()

    /** Everything a player can place, i.e. not the blank placeholder. */
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

/**
 * What kind of thing a module is, used for colour on the canvas.
 *
 * With thirty module types all drawn the same, a patch is hard to read on a
 * phone. The engine doesn't use categories. This is just so you can see at a
 * glance where the sound starts, where it's shaped and what's moving it.
 */
enum class NexusFamily { Source, Shape, Mod, Time, Voice, Io }

/** Which family a module belongs to. */
fun nexusFamilyOf(type: String): NexusFamily = when (type) {
    "osc", "wtosc", "noise", "op", "audioin", "formula" -> NexusFamily.Source
    // The app's own instruments: a string, a tonewheel generator, a grain
    // cloud, a Leslie and a vocoder.
    "string", "wheels", "grain", "rotary", "bands",
    // ...and their horn, pipe, reeds and piano, and whole machines.
    "bore", "pipe", "reed", "jaw", "piano",
    "guitar", "mallets", "sitar", "drum", "pipes", "bird", "water" -> NexusFamily.Voice
    "filter", "vca", "mix", "math", "delay", "slew", "swell", "throat",
    // The insert effects, as modules.
    "reverb", "chorus", "phaser", "crush", "shift", "drive" -> NexusFamily.Shape
    "env", "lfo", "snh", "rand", "macro", "perf", "touch", "follow" -> NexusFamily.Mod
    "clock", "euclid", "prob", "quant", "logic" -> NexusFamily.Time
    else -> NexusFamily.Io // voice, out, scope, blank
}

/** The parameter name for one slot knob. It doesn't depend on what's in the slot. */
fun nexusKnob(slot: Int, knob: Int): String = "s%02d_p%d".format(slot, knob + 1)
fun nexusCableA(index: Int): String = "c%02d_a".format(index)
fun nexusCableB(index: Int): String = "c%02d_b".format(index)
