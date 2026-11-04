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

    @Test
    fun liningUpMovesEachPhraseOnItsOwn() {
        val out = lane().linedUpBy(cuts, floatArrayOf(-1440f, Float.NaN, 960.4f))
        assertTrue(out.linedUp)
        assertEquals(
            listOf(Piece(0, cuts[1], 0, -1440), Piece(cuts[1], cuts[2], 0), Piece(cuts[2], frames, 0, 960)),
            out.pieces,
        )
        // Never past the limit, whatever it's asked.
        assertEquals(-LINE_UP_LIMIT, lane().linedUpBy(cuts, floatArrayOf(-9000f, 0f, 0f)).pieces.first().shift)
        // A comp's pieces keep their takes, each phrase moving all of its pieces.
        val comp = lane().copy(pieces = listOf(Piece(0, ENGINE_RATE, 1), Piece(ENGINE_RATE, frames, 2)))
        assertEquals(
            listOf(Piece(0, ENGINE_RATE, 1, -100), Piece(ENGINE_RATE, cuts[1], 2, -100), Piece(cuts[1], frames, 2, -200)),
            comp.linedUpBy(cuts, floatArrayOf(-100f, -200f, -200f)).pieces,
        )
    }

    @Test
    fun puttingItBackIsTheLaneAsItWas() {
        val l = lane()
        val moved = l.linedUpBy(cuts, floatArrayOf(-500f, -500f, 300f))
        assertEquals(l, moved.unshifted())
        assertFalse(moved.unshifted().linedUp)
        // Lining up again starts from the takes' own time, not from the last shift.
        assertEquals(moved, moved.linedUpBy(cuts, floatArrayOf(-500f, -500f, 300f)))
    }

    @Test
    fun aOneTakeDoubleLinesUpAndComesBackPlain() {
        val plain = TakeRef(file = "samples/d.wav", offset = 100, frames = frames, bpm = 120f, ticks = 12 * PPQN)
        val moved = plain.linedUpBy(cuts, floatArrayOf(-1000f, -1200f, -900f))
        assertEquals(1, moved.takeCount)
        assertEquals(3, moved.pieces.size)
        assertEquals(plain, moved.unshifted())
        assertEquals(plain, plain.linedUpBy(cuts, floatArrayOf(0f, Float.NaN, 0f)))
    }

    @Test
    fun theSpecCarriesShiftsSpansAndTheBlend() {
        val root = File(System.getProperty("java.io.tmpdir"), "comp-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (n in listOf("a", "b", "c")) File(root, "samples").apply { mkdirs() }.let { File(it, "$n.wav").writeBytes(ByteArray(4)) }
            val moved = lane().linedUpBy(cuts, floatArrayOf(-1440f, 0f, 0f))
            val spec = buildString { appendRegion(1L, 0, moved, root) }
            assertTrue(spec, spec.contains("piece|1|0|0|${cuts[1]}|0|-1440\n"))
            assertTrue(spec, spec.contains("piece|1|0|${cuts[1]}|$frames|0\n"))
            assertFalse("no spans or blend unless blending", spec.contains("span|") || spec.contains("blend|"))
            val blended = buildString { appendRegion(1L, 2, lane().blending(true), root) }
            assertTrue(blended, blended.contains("span|1|2|1|0|$frames\n") && blended.endsWith("blend|1|2\n"))
            val score = lane().scoreSpec(cuts, root)
            assertTrue(score, score.startsWith("score|6000.0|-0.0|0,${cuts[1]},${cuts[2]},$frames\n") || score.startsWith("score|6000.0|0.0|"))
            assertTrue(score.contains("span|1|0|2|0|$frames"))
            val lineUp = moved.lineUpSpec(lane(), cuts, root)
            assertTrue(lineUp, lineUp.startsWith("lineup|$LINE_UP_LIMIT|"))
            assertFalse("the double is sent at its own time", lineUp.contains("|-1440"))
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
    fun anUnshiftedPieceSavesAsBefore() {
        val json = kotlinx.serialization.json.Json.encodeToString(Piece.serializer(), Piece(0, 10, 1))
        assertEquals("""{"start":0,"end":10,"take":1}""", json)
        val back = kotlinx.serialization.json.Json.decodeFromString(Piece.serializer(), """{"start":0,"end":10,"take":1,"shift":-5}""")
        assertEquals(-5, back.shift)
    }
}
