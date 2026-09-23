package com.rm.acidulous.model

/**
 * What every demo song is written with: note lengths, factory patches by
 * name, and lanes.
 *
 * Patches and effect settings are looked up by name, so a misspelt one does
 * not fail - it comes back empty and the machine plays its defaults.
 * `DemoSongTest` checks every name in every demo resolves.
 */
internal object DemoKit {
    const val Q = PPQN          // quarter
    const val E = PPQN / 2      // eighth
    const val S = PPQN / 4      // sixteenth
    const val T = PPQN / 3      // eighth triplet
    const val BAR = 4 * PPQN

    fun patch(machine: String, name: String): Map<String, Float> =
        PatchStore.factory(machine).firstOrNull { it.name == name }?.params ?: emptyMap()

    fun fx(type: String, patchName: String): UnitSlot =
        UnitSlot(type, PatchStore.factory(PatchStore.effectKey(type)).firstOrNull { it.name == patchName }?.params ?: emptyMap())

    fun machine(type: String, patchName: String) = Machine(type = type, params = patch(type, patchName))

    fun clip(bars: Int, notes: List<Note>, block: Clip.() -> Clip = { this }): Clip =
        Clip(bars = bars, notes = notes.sortedBy { it.tick }).block()

    /** A lane that jumps from point to point, the way a switch or a button does. */
    fun steps(vararg points: Pair<Int, Float>) = Lane(points.map { LanePoint(it.first, it.second) }, linear = false)

    /** A lane that slides from point to point, the way a knob being turned does. */
    fun ramp(vararg points: Pair<Int, Float>) = Lane(points.map { LanePoint(it.first, it.second) })

    /**
     * A compressor that ducks this track under [track] (1-based): hard and
     * fast, so it gets out of the kick's way and comes straight back. Values
     * are the parameters' normalised positions.
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

    /**
     * A slow vibrato drawn as a bend, starting [delay] into a note of
     * [length] and widening to [depth] semitones - what a player does to a
     * held note, written down.
     */
    fun vibrato(length: Int, delay: Int, depth: Float, period: Int = E): Lane {
        val points = ArrayList<LanePoint>()
        points += LanePoint(0, Note.bendTo01(0f))
        points += LanePoint(delay, Note.bendTo01(0f))
        var t = delay
        var up = true
        while (t + period / 2 < length) {
            t += period / 2
            val grow = ((t - delay).toFloat() / (length - delay).coerceAtLeast(1)).coerceIn(0.3f, 1f)
            points += LanePoint(t, Note.bendTo01(if (up) depth * grow else -depth * grow))
            up = !up
        }
        points += LanePoint(length - 1, Note.bendTo01(0f))
        return Lane(points)
    }
}

/** A demo in the file menu's list. */
data class Demo(val name: String, val style: String, val build: () -> Song)

/**
 * The demos, in the order the menu lists them. The first is the one a first
 * run opens.
 */
object DemoSongs {
    val all: List<Demo> = listOf(
        Demo("Riddim", "dub · 74 bpm") { DemoSong.build() },
        Demo("Squelch", "acid house · 126 bpm") { AcidDemo.build() },
        Demo("Night Drive", "synthwave · 100 bpm") { SynthwaveDemo.build() },
        Demo("Cartridge", "chiptune · 150 bpm") { ChipDemo.build() },
        Demo("Lantern", "chamber · 72 bpm, in three") { ChamberDemo.build() },
    )
}
