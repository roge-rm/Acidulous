package com.rm.acidulous.model

/**
 * Helpers for writing the demo song: note lengths, factory patches by name,
 * and lanes.
 *
 * Patches and effect settings are looked up by name, so a misspelt one comes
 * back empty and the machine plays its defaults. `DemoSongTest` checks every
 * name in the demo resolves.
 */
internal object DemoKit {
    const val Q = PPQN          // quarter
    const val E = PPQN / 2      // eighth
    const val S = PPQN / 4      // sixteenth
    const val T = PPQN / 3      // eighth triplet
    const val BAR = 4 * PPQN

    fun fx(type: String, patchName: String): UnitSlot =
        UnitSlot(type, PatchStore.factory(PatchStore.effectKey(type)).firstOrNull { it.name == patchName }?.params ?: emptyMap())

    /**
     * A machine on a factory patch: its knobs, its settings (where a Nexus
     * patch keeps its graph) and the patch name, marked the same way loading
     * it from the panel does so the panel shows which patch it is.
     */
    fun machine(type: String, patchName: String): Machine {
        val p = PatchStore.factory(type).firstOrNull { it.name == patchName } ?: return Machine(type = type)
        val mark = mapOf(PatchMark.NAME to patchName, PatchMark.STAMP to PatchMark.stampOf(p.params))
        return Machine(type = type, params = p.params, settings = p.settings + mark)
    }

    fun clip(bars: Int, notes: List<Note>, block: Clip.() -> Clip = { this }): Clip =
        Clip(bars = bars, notes = notes.sortedBy { it.tick }).block()

    /** A lane that jumps from point to point, like a switch or a button. */
    fun steps(vararg points: Pair<Int, Float>) = Lane(points.map { LanePoint(it.first, it.second) }, linear = false)

    /** A lane that slides from point to point, like a knob being turned. */
    fun ramp(vararg points: Pair<Int, Float>) = Lane(points.map { LanePoint(it.first, it.second) })

    /**
     * A compressor that ducks this track under [track] (1-based). Hard and
     * fast, so it gets out of the kick's way and comes straight back. Values
     * are normalised parameter positions.
     */
    fun duckUnder(track: Int, depth: Float = 0.5f): UnitSlot = UnitSlot(
        "Compressor",
        mapOf(
            "threshold" to depth,                 // 0.5 is -30 dB
            "ratio" to 0.694f,                    // 8:1
            "attack" to 0f,                       // 0.1 ms
            "release" to 0.54f,                   // 120 ms
            SIDECHAIN_PARAM to track / (SIDECHAIN_STEPS - 1f),
        ),
    )

    /** One chord as notes, all at once. */
    fun chord(at: Int, length: Int, pitches: List<Int>, velocity: Int) =
        pitches.map { Note(at, length, it, velocity) }
}
