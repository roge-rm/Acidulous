package com.rm.acidulous.model

/**
 * A MIDI file made into a song: a track per part, cut into scenes.
 *
 * A file is one long timeline and a song here is scenes of clips no longer
 * than sixteen bars, so the timeline is cut every [sceneBars] bars. A scene
 * exactly like the one before it becomes a repeat of that one, so a file
 * that loops the same eight bars thirty times comes in as one scene played
 * thirty times rather than thirty scenes to scroll past.
 *
 * Drums are the one part whose notes mean something other than a pitch.
 * General MIDI keeps them on channel 10 with a fixed note for each sound, and
 * this app's drum machines put their sounds on notes of their own, so a
 * drum part going to one of them is moved sound by sound, by name.
 */
object MidiImport {

    /**
     * Where a part goes: drums to a drum machine, and anything whose General
     * MIDI instrument one of the modelled machines plays to that machine -
     * an organ part to the organ, a trumpet to the brass. Everything else,
     * and anything that did not say, to the poly synth.
     */
    fun defaultMachine(part: MidiFile.Part): String {
        if (part.channel == MidiFile.DRUM_CHANNEL) return "Hexbeat"
        return when (part.program?.let { it / 8 }) {
            2 -> "Manual"                  // organ
            3, 5 -> "Filament"             // guitar, strings
            7 -> "Brazen"                  // brass
            8, 9 -> "Timber"               // reed, pipe
            11 -> "Cumulus"                // pad
            else -> "Trinity"
        }
    }

    /**
     * The song. [machines] is one entry per part, a machine type or null to
     * leave the part out; parts past the sixteenth track are left out too.
     */
    fun build(name: String, parsed: MidiFile.Parsed, machines: List<String?>, sceneBars: Int): Song {
        val signature = parsed.signature ?: Signature()
        val tpb = signature.ticksPerBar
        val chosen = parsed.parts.indices
            .filter { machines.getOrNull(it) != null }
            .take(MAX_TRACKS)
        val tracks = chosen.map { i ->
            val part = parsed.parts[i]
            val type = machines[i]!!
            Track(
                id = newId("t"),
                name = part.name.take(24),
                machine = Machine(type),
            ) to notesFor(part, type)
        }
        val lanesOf = chosen.map { parsed.parts[it].lanes }
        val firstTempo = (parsed.tempo ?: 120f).coerceIn(20f, 300f)
        // The tempo in force at a tick: the file's own, where it changes.
        fun tempoAt(tick: Int): Float =
            (parsed.tempos.lastOrNull { it.first <= tick }?.second ?: firstTempo).coerceIn(20f, 300f)
        val end = tracks.flatMap { (_, notes) -> notes.map { it.tick + it.length } }.maxOrNull() ?: 0
        val bars = ((end + tpb - 1) / tpb).coerceAtLeast(1)
        val per = sceneBars.coerceIn(1, 16)

        data class Cut(val bars: Int, val clips: List<List<Note>>, val lanes: List<Map<String, Lane>>, val bpm: Float)
        val cuts = (0 until (bars + per - 1) / per).map { k ->
            val from = k * per * tpb
            val cutBars = minOf(per, bars - k * per)
            val to = from + cutBars * tpb
            val lanes = lanesOf.map { byName -> byName.mapNotNull { (name, pts) -> laneIn(name, pts, from, to)?.let { laneKey("performance", name) to it } }.toMap() }
            Cut(cutBars, lanes = lanes, bpm = tempoAt(from), clips = tracks.map { (_, notes) ->
                notes.filter { it.tick in from until to }
                    // A note held over the cut is cut with it: the next
                    // scene's clip starts fresh, as any clip does.
                    .map { it.copy(tick = it.tick - from, length = minOf(it.length, to - it.tick)) }
            })
        }

        val scenes = mutableListOf<Scene>()
        val clipsByTrack = tracks.map { mutableMapOf<String, Clip>() }
        var last: Cut? = null
        cuts.forEachIndexed { k, cut ->
            if (cut == last) {
                scenes[scenes.size - 1] = scenes.last().let { it.copy(repeat = it.repeat + 1) }
                return@forEachIndexed
            }
            last = cut
            // Named for the bar it starts on, so the file's own map of where
            // things happen can still be read off the song.
            // A tempo of its own where the file has moved away from its first.
            val scene = Scene(
                id = newId("s"), name = "bar ${k * per + 1}",
                tempo = cut.bpm.takeIf { kotlin.math.abs(it - firstTempo) > 0.01f }?.let { SceneTempo(it) },
            )
            scenes += scene
            cut.clips.forEachIndexed { t, notes ->
                if (notes.isNotEmpty()) clipsByTrack[t][scene.id] = Clip(bars = cut.bars, notes = notes, automation = cut.lanes[t])
            }
            // A scene is as long as its longest clip, so a stretch where
            // nothing plays needs an empty one to keep its length - or the
            // rest in the file would shrink to a bar.
            if (cut.clips.all { it.isEmpty() } && clipsByTrack.isNotEmpty()) {
                clipsByTrack[0][scene.id] = Clip(bars = cut.bars)
            }
        }

        return Song(
            name = name,
            tempo = firstTempo,
            signature = signature,
            tracks = tracks.mapIndexed { t, (track, _) -> track.copy(clips = clipsByTrack[t]) },
            scenes = scenes,
        )
    }

    /** A part's notes, moved onto the machine's own drum notes where it has them. */
    /**
     * One cut's share of a part's controller: its points between [from] and
     * [to], moved to the cut's start, and the value in force as the cut begins
     * at its first tick - a pedal already down, a wheel already moved. The
     * pedals rest up rather than holding their first value back to the top.
     * Null where the lane says nothing in this cut.
     */
    private fun laneIn(name: String, pts: List<LanePoint>, from: Int, to: Int): Lane? {
        val pedal = name in PEDALS
        val inside = pts.filter { it.tick in from until to }.map { it.copy(tick = it.tick - from) }
        val before = pts.lastOrNull { it.tick < from }?.value
        val start = when {
            inside.firstOrNull()?.tick == 0 -> null
            before != null -> LanePoint(0, before)
            pedal && inside.isNotEmpty() -> LanePoint(0, 0f)
            else -> null
        }
        val points = listOfNotNull(start) + inside
        // A pedal lane saying only "up" is nothing - unless the pedal came in
        // down, when "up" is the thing it says.
        if (points.isEmpty() || (pedal && points.all { it.value == 0f } && (before ?: 0f) == 0f)) return null
        return Lane(points, linear = !pedal)
    }

    private val PEDALS = setOf("sustain", "sostenuto", "soft")

    fun notesFor(part: MidiFile.Part, type: String): List<Note> {
        if (part.channel != MidiFile.DRUM_CHANNEL || MachineUi.kindOf(type) != MachineKind.Drums) return part.notes
        val byName = MachineUi.voicesOf(type).associate { it.name to it.note }
        // A machine whose pads are numbers rather than names has nothing to
        // match against, and keeps the notes as they were.
        if (GM_DRUMS.values.none { it in byName }) return part.notes
        return part.notes.mapNotNull { n -> GM_DRUMS[n.pitch]?.let { byName[it] }?.let { n.copy(pitch = it) } }
    }

    /** How many of a drum part's notes have no sound on this machine. */
    fun unmatched(part: MidiFile.Part, type: String): Int =
        if (part.channel != MidiFile.DRUM_CHANNEL || MachineUi.kindOf(type) != MachineKind.Drums) 0
        else part.notes.size - notesFor(part, type).size

    /** General MIDI's drum notes, by the name our drum machines give the nearest sound. */
    private val GM_DRUMS: Map<Int, String> = buildMap {
        put(35, "Kick"); put(36, "Kick")
        put(37, "Rim")
        put(38, "Snare"); put(40, "Snare")
        put(39, "Clap")
        put(41, "Low Tom"); put(43, "Low Tom"); put(45, "Low Tom")
        put(47, "Mid Tom"); put(48, "Mid Tom")
        put(50, "Hi Tom")
        put(42, "Closed Hat"); put(44, "Closed Hat"); put(54, "Closed Hat"); put(69, "Closed Hat"); put(70, "Closed Hat")
        put(46, "Open Hat")
        put(49, "Crash"); put(52, "Crash"); put(55, "Crash"); put(57, "Crash")
        put(51, "Ride"); put(53, "Ride"); put(59, "Ride")
        put(56, "Cowbell")
        put(75, "Clave"); put(76, "Clave"); put(77, "Clave")
    }

    private const val MAX_TRACKS = 16
}
