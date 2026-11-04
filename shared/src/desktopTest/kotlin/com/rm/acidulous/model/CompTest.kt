package com.rm.acidulous.model

import com.rm.acidulous.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suggest, line up and blend, on the model side. The listening is tools/bias_test.cpp's. */
class CompTest {

    private val frames = 6 * ENGINE_RATE
    private val cuts = listOf(0, 2 * ENGINE_RATE, 4 * ENGINE_RATE, frames)

    /** Three takes over the whole lane, take 0 heard everywhere. */
    private fun lane() = TakeRef(
        file = "samples/a.wav", offset = 0, frames = frames, bpm = 120f, ticks = 12 * PPQN,
        takes = listOf(
            StackTake("samples/a.wav", 0, 0, frames),
            StackTake("samples/b.wav", 0, 0, frames),
            StackTake("samples/c.wav", 0, 0, frames),
        ),
        pieces = listOf(Piece(0, frames, 0)),
    )

    /** Scores as the engine sends them: takes, phrases, then five numbers per take per phrase. */
    private fun scores(vararg byTake: FloatArray): TakeScores {
        val phrases = byTake[0].size
        val raw = FloatArray(2 + byTake.size * phrases * 5)
        raw[0] = byTake.size.toFloat()
        raw[1] = phrases.toFloat()
        for ((k, row) in byTake.withIndex()) for ((p, v) in row.withIndex()) raw[2 + (k * phrases + p) * 5] = v
        return TakeScores.of(raw)!!
    }

    @Test
    fun suggestPicksTheBestTakeInEveryPhrase() {
        val s = scores(
            floatArrayOf(50f, 90f, 60f),
            floatArrayOf(80f, 40f, Float.NaN),
            floatArrayOf(70f, 60f, 61f),
        )
        val out = lane().suggested(cuts, s)
        assertEquals(
            listOf(Piece(0, cuts[1], 1), Piece(cuts[1], cuts[2], 0), Piece(cuts[2], frames, 2)),
            out.pieces,
        )
    }

    @Test
    fun aTieKeepsWhatIsHeardSoSuggestingAgainChangesNothing() {
        val s = scores(floatArrayOf(80f, 80f, 80f), floatArrayOf(80.3f, 40f, 40f), floatArrayOf(10f, 10f, 10f))
        val once = lane().suggested(cuts, s)
        assertEquals(listOf(Piece(0, frames, 0)), once.pieces)
        assertEquals(once, once.suggested(cuts, s))
    }

    @Test
    fun aPhraseNoTakeSingsKeepsWhatItHad() {
        val nan = Float.NaN
        val s = scores(floatArrayOf(nan, 10f, 10f), floatArrayOf(nan, 90f, 10f), floatArrayOf(nan, 10f, 10f))
        val out = lane().suggested(cuts, s)
        assertEquals(listOf(Piece(0, cuts[1], 0), Piece(cuts[1], cuts[2], 1), Piece(cuts[2], frames, 0)), out.pieces)
    }

    @Test
    fun scoresForAnotherStackChangeNothing() {
        val s = scores(floatArrayOf(10f, 10f, 10f), floatArrayOf(90f, 90f, 90f))
        val l = lane()
        assertSame(l, l.suggested(cuts, s))
        assertNull(TakeScores.of(floatArrayOf(3f, 3f, 1f)))
    }

    /** The engine's answer for one word per phrase: start, end and shift each. */
    private fun perPhrase(vararg shifts: Float): FloatArray =
        FloatArray(shifts.size * 3) { i -> if (i % 3 == 2) shifts[i / 3] else cuts[i / 3 + i % 3].toFloat() }

    @Test
    fun liningUpMovesEachWordOnItsOwn() {
        val out = lane().linedUpBy(perPhrase(-1440f, Float.NaN, 960.4f), 1)
        assertTrue(out.linedUp)
        assertEquals(1, out.lead)
        assertEquals(
            listOf(Piece(0, cuts[1], 0, -1440), Piece(cuts[1], cuts[2], 0), Piece(cuts[2], frames, 0, 960)),
            out.pieces,
        )
        // Never past the limit, whatever it's asked.
        assertEquals(-LINE_UP_LIMIT, lane().linedUpBy(perPhrase(-9000f, 0f, 0f), 1).pieces.first().shift)
        // A comp's pieces keep their takes, each word moving the part of a piece under it.
        val comp = lane().copy(pieces = listOf(Piece(0, ENGINE_RATE, 1), Piece(ENGINE_RATE, frames, 2)))
        assertEquals(
            listOf(Piece(0, ENGINE_RATE, 1, -100), Piece(ENGINE_RATE, cuts[1], 2, -100), Piece(cuts[1], frames, 2, -200)),
            comp.linedUpBy(perPhrase(-100f, -200f, -200f), 1).pieces,
        )
        // Words inside a phrase: the piece is cut at each and each keeps its own shift.
        val words = floatArrayOf(0f, 30000f, -480f, 30000f, 41000f, 720f, 41000f, 50000f, 0f, 50000f, frames.toFloat(), -240f)
        assertEquals(
            listOf(Piece(0, 30000, 0, -480), Piece(30000, 41000, 0, 720), Piece(41000, 50000, 0), Piece(50000, frames, 0, -240)),
            lane().linedUpBy(words, 2).pieces,
        )
        // Where the engine sent no word, the take plays at its own time.
        assertEquals(
            listOf(Piece(0, 1000, 0), Piece(1000, 2000, 0, 50), Piece(2000, frames, 0)),
            lane().linedUpBy(floatArrayOf(1000f, 2000f, 50f), 2).pieces,
        )
    }

    @Test
    fun puttingItBackIsTheLaneAsItWas() {
        val l = lane()
        val words = floatArrayOf(0f, 30000f, -480f, 30000f, 41000f, 720f, 41000f, 50000f, 0f, 50000f, frames.toFloat(), -240f)
        val moved = l.linedUpBy(words, 1)
        assertEquals(l, moved.unshifted())
        assertFalse(moved.unshifted().linedUp)
        // Lining up again starts from the takes' own time, not from the last shift.
        assertEquals(moved, moved.linedUpBy(words, 1))
        // A comp comes back to its own pieces.
        val comp = lane().copy(pieces = listOf(Piece(0, ENGINE_RATE, 1), Piece(ENGINE_RATE, frames, 2)))
        assertEquals(comp, comp.linedUpBy(words, 1).unshifted())
    }

    @Test
    fun aOneTakeDoubleLinesUpAndComesBackPlain() {
        val plain = TakeRef(file = "samples/d.wav", offset = 100, frames = frames, bpm = 120f, ticks = 12 * PPQN)
        val moved = plain.linedUpBy(perPhrase(-1000f, -1200f, -900f), 0)
        assertEquals(1, moved.takeCount)
        assertEquals(3, moved.pieces.size)
        assertEquals(plain, moved.unshifted())
        assertEquals(plain, plain.linedUpBy(perPhrase(0f, Float.NaN, 0f), 0).unshifted())
    }

    @Test
    fun aBlendLinesUpEveryTakeAndComesBack() {
        val blend = lane().blending(true)
        // Three takes: none, two words, one word that doesn't move.
        val raw = floatArrayOf(3f, 0f, 2f, 0f, 1000f, -300f, 1000f, 9000f, 120.4f, 1f, 0f, frames.toFloat(), 0f)
        val lined = blend.blendLinedUpBy(raw)
        assertTrue(lined.blendLinedUp)
        assertEquals(emptyList<Nudge>(), lined.takes[0].nudges)
        assertEquals(listOf(Nudge(0, 1000, -300), Nudge(1000, 9000, 120)), lined.takes[1].nudges)
        assertEquals(emptyList<Nudge>(), lined.takes[2].nudges)
        assertEquals(blend, lined.blendUnlined())
        // An answer for another stack changes nothing.
        assertSame(blend, blend.blendLinedUpBy(floatArrayOf(2f, 0f, 0f)))
        // A new pass on the stack hasn't been lined up, so the blend starts over.
        val pass = TakeRef(file = "samples/e.wav", offset = 0, frames = frames, bpm = 120f, ticks = 12 * PPQN)
        val more = lined.stacked(pass)
        assertFalse(more.blendLinedUp)
        assertTrue(more.takes.all { it.nudges.isEmpty() })
        // A trim moves the nudges with the audio and keeps what it hides.
        val trimmed = lined.copy(offset = 500, frames = frames - 500).reframed(lined)
        assertEquals(listOf(Nudge(-500, 500, -300), Nudge(500, 8500, 120)), trimmed.takes[1].nudges)
    }

    /** Suggest and line up work on a copy off the main thread; an edit or a new lead in the meantime voids the answer. */
    @Test
    fun anAnswerOnlyLandsOnTheLanesItWasWorkedOutFrom() {
        val lead = TakeRef(file = "samples/d.wav", offset = 0, frames = frames, bpm = 120f, ticks = 12 * PPQN)
        val clip = Clip(bars = 3).withTake(0, lane()).withTake(1, lead)
        val analysed = clip.audio!!.lane(0)!!
        assertTrue(clip.stillAsAnalysed(0, analysed, 1, lead))
        assertTrue(clip.stillAsAnalysed(0, analysed, -1, null))
        // A new shape alone doesn't matter.
        assertTrue(clip.withTake(0, analysed.copy(peaks = listOf(0.5f))).stillAsAnalysed(0, analysed, 1, lead))
        // The lane was edited.
        assertFalse(clip.withTake(0, analysed.choosing(1, 0, 2 * ENGINE_RATE)).stillAsAnalysed(0, analysed, 1, lead))
        assertFalse(clip.withTake(0, null).stillAsAnalysed(0, analysed, -1, null))
        // The lead was.
        assertFalse(clip.withTake(1, lead.copy(offset = 100)).stillAsAnalysed(0, analysed, 1, lead))
        assertFalse(clip.withTake(1, null).stillAsAnalysed(0, analysed, 1, lead))
    }

    @Test
    fun spreadStaysInRange() {
        assertEquals(1f, lane().spreading(3f).spread)
        assertEquals(0f, lane().spreading(-1f).spread)
        assertEquals(0.4f, lane().spreading(0.4f).spread)
    }

    @Test
    fun pitchIsJudgedInTheTracksTuning() {
        assertEquals("", pitchSpec(null, 0))
        assertEquals("", pitchSpec(Tunings.EQUAL, 3))
        val nineteen = Tunings.builtIn.first { it.name == "19 equal" }
        val spec = pitchSpec(nineteen, 0)
        val (ref, period, steps) = spec.split(':')
        assertEquals(261.6256, ref.toDouble(), 0.001)
        assertEquals(1200.0, period.toDouble(), 0.01)
        assertEquals(19, steps.split(',').size)
        assertEquals("0.0", steps.split(',').first())
        // In A, A keeps 440.
        assertEquals(440.0, pitchSpec(nineteen, 9).split(':')[0].toDouble(), 0.001)
        // A track's own tuning wins over the song's.
        val song = Song(name = "s", tuning = nineteen, tracks = listOf(Track("a", "a", Machine(type = BIAS_MACHINE)), Track("b", "b", Machine(type = BIAS_MACHINE), tuning = Tunings.builtIn.first { it.name == "24 equal" })))
        assertEquals(19, song.pitchSpecFor(song.tracks[0]).split(':')[2].split(',').size)
        assertEquals(24, song.pitchSpecFor(song.tracks[1]).split(':')[2].split(',').size)
    }

    @Test
    fun aLaneLinedUpOnAnotherIsItsDouble() {
        val lead = TakeRef(file = "samples/d.wav", offset = 0, frames = frames, bpm = 120f, ticks = 12 * PPQN)
        val clip = Clip().withTake(0, lead).withTake(1, lane().linedUpBy(perPhrase(-10f, -10f, -10f), 0))
        assertEquals(lead, clip.leadOf(1))
        assertNull(clip.leadOf(0))
        // Its blend lines up on the lead; a lane that doubles nothing lines up on its own comp.
        assertEquals(lead, clip.blendReference(1))
        val own = Clip().withTake(1, lane().copy(pieces = listOf(Piece(0, frames, 2))).blending(true))
        val ref = own.blendReference(1)!!
        assertFalse(ref.blend)
        assertEquals(listOf(Piece(0, frames, 2)), ref.pieces)
        // Taking the line-up back means it doubles nothing.
        assertNull(clip.withTake(1, clip.audio!!.lane(1)!!.unshifted()).leadOf(1))
    }

    @Test
    fun theSpecCarriesShiftsSpansAndTheBlend() {
        val root = File(System.getProperty("java.io.tmpdir"), "comp-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (n in listOf("a", "b", "c")) File(root, "samples").apply { mkdirs() }.let { File(it, "$n.wav").writeBytes(ByteArray(4)) }
            val moved = lane().linedUpBy(perPhrase(-1440f, 0f, 0f), 1)
            val spec = buildString { appendRegion(1L, 0, moved, root) }
            assertTrue(spec, spec.contains("piece|1|0|0|${cuts[1]}|0|-1440\n"))
            assertTrue(spec, spec.contains("piece|1|0|${cuts[1]}|$frames|0\n"))
            assertFalse("no spans or blend unless blending", spec.contains("span|") || spec.contains("blend|"))
            val blended = buildString { appendRegion(1L, 2, lane().blending(true), root) }
            assertTrue(blended, blended.contains("span|1|2|1|0|$frames\n") && blended.endsWith("blend|1|2\n"))
            val score = lane().scoreSpec(cuts, root)
            assertTrue(score, score.startsWith("score|6000.0|-0.0|0,${cuts[1]},${cuts[2]},$frames||0\n") || score.startsWith("score|6000.0|0.0|"))
            assertTrue(score.contains("span|1|0|2|0|$frames"))
            assertFalse("no lead unless it has one", score.contains("\n2|0|"))
            // With a tuning and a lead that starts a beat later.
            val lead = TakeRef(file = "samples/c.wav", offset = 0, frames = frames, bpm = 120f, ticks = 12 * PPQN, startTick = PPQN)
            val led = lane().scoreSpec(cuts, root, "261.6:1200.0:0.0,700.0", lead)
            assertTrue(led, led.lines().first().endsWith("|261.6:1200.0:0.0,700.0|-${ENGINE_RATE / 2}"))
            assertTrue(led, led.contains("\n2|0|"))
            val lineUp = moved.lineUpSpec(lane(), cuts, root)
            assertTrue(lineUp, lineUp.startsWith("lineup|$LINE_UP_LIMIT|0,${cuts[1]},${cuts[2]},$frames|0\n"))
            assertFalse("the double is sent at its own time", lineUp.contains("|-1440"))
            // A lined-up, spread blend sends its nudges and spread before it blends.
            val lined = lane().blending(true).blendLinedUpBy(floatArrayOf(3f, 0f, 1f, 100f, 9000f, -300f, 0f)).spreading(0.5f)
            val sent = buildString { appendRegion(1L, 0, lined, root) }
            assertTrue(sent, sent.endsWith("nudge|1|0|1|100|9000|-300\nspread|1|0|0.5\nblend|1|0\n"))
            assertFalse("nothing extra while the blend is off", buildString { appendRegion(1L, 0, lined.blending(false), root) }.contains("nudge|"))
            val up = lane().blending(true).blendLineUpSpec(lane(), cuts, root)
            assertTrue(up, up.startsWith("blendup|$LINE_UP_LIMIT|") && up.contains("\n2|0|") && up.contains("span|2|0|2|"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyAStackBlends() {
        val plain = TakeRef(file = "samples/d.wav", offset = 0, frames = frames, bpm = 120f, ticks = 12 * PPQN)
        assertFalse(plain.blending(true).blend)
        assertTrue(lane().blending(true).blend)
        assertEquals(lane(), lane().blending(true).blending(false))
    }

    @Test
    fun theNewSettingsSaveOnlyWhenSet() {
        val json = kotlinx.serialization.json.Json
        val plain = json.encodeToString(TakeRef.serializer(), lane())
        assertFalse(plain, plain.contains("spread") || plain.contains("lead") || plain.contains("blendLinedUp") || plain.contains("nudges"))
        val set = lane().blending(true).blendLinedUpBy(floatArrayOf(3f, 0f, 1f, 100f, 9000f, -300f, 0f)).spreading(0.5f).copy(lead = 2)
        assertEquals(set, json.decodeFromString(TakeRef.serializer(), json.encodeToString(TakeRef.serializer(), set)))
    }

    @Test
    fun anUnshiftedPieceSavesAsBefore() {
        val json = kotlinx.serialization.json.Json.encodeToString(Piece.serializer(), Piece(0, 10, 1))
        assertEquals("""{"start":0,"end":10,"take":1}""", json)
        val back = kotlinx.serialization.json.Json.decodeFromString(Piece.serializer(), """{"start":0,"end":10,"take":1,"shift":-5}""")
        assertEquals(-5, back.shift)
    }
}
