package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The phrase finder on sung takes made here, with the dirt a real one has:
 * a noise floor, rumble, a breath, a clipped take and a take that starts late
 * with room tone before the singing.
 */
class PhrasesTest {

    private val rate = ENGINE_RATE
    private val length = 8 * rate
    private fun s(seconds: Double) = (seconds * rate).toInt()

    /** Room: hiss about 50 dB down and rumble at 45 Hz about 30 dB down. */
    private fun room(n: Int, seed: Int, rumble: Boolean = true): FloatArray {
        val r = Random(seed)
        return FloatArray(n) { i ->
            val hiss = (r.nextFloat() * 2f - 1f) * 0.005f
            val low = if (rumble) 0.03f * sin(2.0 * PI * 45.0 * i / rate).toFloat() else 0f
            hiss + low
        }
    }

    /**
     * A sung line over [from, to) seconds: a few harmonics, a 20 ms fade at
     * each end and an 80 ms dip every 400 ms where a consonant would be.
     */
    private fun sing(into: FloatArray, from: Double, to: Double, at: Int = 0, amp: Float = 0.5f, hz: Double = 220.0) {
        val a = s(from) - at
        val b = s(to) - at
        val fade = s(0.02)
        for (i in maxOf(a, 0) until minOf(b, into.size)) {
            val t = (i - a).toDouble() / rate
            val edge = minOf(1.0, (i - a).toDouble() / fade, (b - i).toDouble() / fade)
            val dip = if ((t % 0.4) > 0.32) 0.03 else 1.0
            val x = sin(2 * PI * hz * t) + 0.5 * sin(4 * PI * hz * t) + 0.25 * sin(6 * PI * hz * t)
            into[i] += (amp * edge * dip * x / 1.75).toFloat()
        }
    }

    /** A breath: bright noise, 250 ms, rising and falling. */
    private fun breathe(into: FloatArray, from: Double, at: Int = 0, seed: Int = 7) {
        val r = Random(seed)
        val a = s(from) - at
        val n = s(0.25)
        for (i in 0 until n) {
            val j = a + i
            if (j !in into.indices) continue
            val shape = sin(PI * i / n).toFloat()
            into[j] += (r.nextFloat() * 2f - 1f) * 0.15f * shape
        }
    }

    private fun clip(x: FloatArray, at: Float) { for (i in x.indices) x[i] = x[i].coerceIn(-at, at) }

    private fun cutsOf(lane: TakeRef, files: Map<String, FloatArray>): List<Int> {
        val env = files.mapValues { envelopeOf(it.value, 32768) }
        return findPhrases(lane.phraseLevels { env[it] }, lane.frames)
    }

    private fun lane(vararg takes: StackTake) = TakeRef(
        file = takes[0].file, offset = takes[0].offset, frames = length, bpm = 120f, ticks = 3840,
        takes = takes.toList(), pieces = listOf(Piece(0, length, takes.size - 1)),
    )

    private fun assertCutIn(cuts: List<Int>, from: Double, to: Double) =
        assertTrue("no cut in $from..$to s: ${cuts.map { it.toDouble() / rate }}", cuts.any { it > s(from) && it < s(to) })

    /**
     * Three takes of three lines. Take two holds a note across the first gap
     * and take three starts late, at 3 s, with room tone before it sings. Two
     * of three are quiet in each gap, so both gaps cut.
     */
    @Test
    fun cutsWhereMostTakesAreQuiet() {
        val a = room(length, 1).also {
            sing(it, 0.5, 2.5); sing(it, 3.2, 5.0); sing(it, 5.8, 7.5)
        }
        val b = room(length, 2).also {
            sing(it, 0.55, 3.0, amp = 0.9f); sing(it, 3.25, 5.05, amp = 0.9f); sing(it, 5.85, 7.4, amp = 0.9f)
            clip(it, 0.6f)
        }
        val lateFrom = s(3.0)
        val c = room(length - lateFrom, 3).also {
            sing(it, 3.4, 5.0, at = lateFrom, amp = 0.3f); sing(it, 5.9, 7.6, at = lateFrom, amp = 0.3f)
        }
        val take = lane(
            StackTake("a", 0, 0, length),
            StackTake("b", 0, 0, length),
            StackTake("c", -lateFrom, lateFrom, length),
        )
        val cuts = cutsOf(take, mapOf("a" to a, "b" to b, "c" to c))
        assertEquals(listOf(0, length), listOf(cuts.first(), cuts.last()))
        assertEquals("three phrases: ${cuts.map { it.toDouble() / rate }}", 4, cuts.size)
        assertCutIn(cuts, 2.5, 3.2)
        assertCutIn(cuts, 5.05, 5.8)
    }

    /** A breath in the gap goes with the line after it; it isn't a phrase of its own. */
    @Test
    fun aBreathJoinsTheLineItLeadsInto() {
        val takes = (1..2).map { k ->
            room(length, 10 + k).also {
                sing(it, 0.5, 2.5); sing(it, 3.5, 6.0)
                breathe(it, 2.95, seed = k)
            }
        }
        val take = lane(StackTake("a", 0, 0, length), StackTake("b", 0, 0, length))
        val cuts = cutsOf(take, mapOf("a" to takes[0], "b" to takes[1]))
        assertEquals("two phrases: ${cuts.map { it.toDouble() / rate }}", 3, cuts.size)
        // Between the end of the first line and the breath.
        assertCutIn(cuts, 2.5, 2.95)
    }

    /** With two takes, one singing through a gap keeps it whole. */
    @Test
    fun aTieIsSound() {
        val a = room(length, 21).also { sing(it, 0.5, 2.5); sing(it, 3.5, 6.0) }
        val b = room(length, 22).also { sing(it, 0.5, 6.0) }
        val take = lane(StackTake("a", 0, 0, length), StackTake("b", 0, 0, length))
        assertEquals(listOf(0, length), cutsOf(take, mapOf("a" to a, "b" to b)))
    }

    /** Room tone alone, or a file not read yet, is one phrase. */
    @Test
    fun noSilencesIsOnePhrase() {
        val take = lane(StackTake("a", 0, 0, length), StackTake("b", 0, 0, length))
        assertEquals(listOf(0, length), cutsOf(take, mapOf("a" to room(length, 31), "b" to room(length, 32))))
        assertEquals(listOf(0, length), findPhrases(take.phraseLevels { null }, length))
    }

    @Test
    fun phraseAtFindsTheStretch() {
        val cuts = listOf(0, 100, 250, 400)
        assertEquals(0, phraseAt(cuts, 0))
        assertEquals(1, phraseAt(cuts, 100))
        assertEquals(2, phraseAt(cuts, 399))
        assertEquals(2, phraseAt(cuts, 999))
    }

    // --- Choosing and deleting ------------------------------------------------

    private val three = TakeRef(
        file = "a", offset = 0, frames = 1000, bpm = 120f, ticks = 3840,
        takes = listOf(StackTake("a", 0, 0, 1000), StackTake("b", 0, 0, 1000), StackTake("c", -400, 400, 1000)),
        pieces = listOf(Piece(0, 400, 1), Piece(400, 1000, 2)),
    )

    @Test
    fun choosingATakeForAPhraseWritesOnePieceAndJoinsNeighbours() {
        val next = three.choosing(1, 400, 700)
        assertEquals(listOf(Piece(0, 700, 1), Piece(700, 1000, 2)), next.pieces)
        // Only as far as the take has audio.
        assertEquals(listOf(Piece(0, 400, 1), Piece(400, 1000, 2)), three.choosing(2, 0, 400).pieces)
        assertEquals(listOf(Piece(0, 200, 1), Piece(200, 600, 0), Piece(600, 1000, 2)), three.choosing(0, 200, 600).pieces)
    }

    @Test
    fun deletingATakeFallsBackToTheTakeWithAudioThere() {
        val next = three.withoutTake(2)!!
        assertEquals(2, next.takes.size)
        // Take 1 was heard more, so it fills where take 2 was.
        assertEquals(listOf(Piece(0, 1000, 1)), next.pieces)
        // Take 0 goes: the next take becomes the region's file and pieces renumber.
        val first = three.withoutTake(0)!!
        assertEquals("b", first.file)
        assertEquals(listOf(Piece(0, 400, 0), Piece(400, 1000, 1)), first.pieces)
    }

    @Test
    fun deletingDownToOneWholeTakeIsAPlainLane() {
        val two = three.withoutTake(2)!!
        val one = two.withoutTake(0)!!
        assertTrue(one.takes.isEmpty())
        assertTrue(one.pieces.isEmpty())
        assertEquals("b", one.file)
        assertNull(one.withoutTake(0))
    }

    @Test
    fun deletingTheOnlyTakeWithAudioLeavesSilenceNotTakeZero() {
        val late = TakeRef(
            file = "a", offset = 0, frames = 1000, bpm = 120f, ticks = 3840,
            takes = listOf(StackTake("a", 0, 0, 300), StackTake("b", -500, 500, 1000)),
            pieces = listOf(Piece(500, 1000, 1)),
        )
        val next = late.withoutTake(1)!!
        assertEquals(listOf(Piece(0, 0, 0)), next.pieces)
        assertEquals(1, next.takes.size)
    }
}
