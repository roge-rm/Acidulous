package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The faceplates: nothing on a panel lands on anything else, and the
 * mapping rings drawn round knobs in MIDI learn never touch each other.
 */
class NexusFaceTest {
    /** Every mix of knobs and jacks a module can have, and then some. */
    private val faces = buildList {
        for (knobs in 0..NEXUS_KNOBS) for (ins in 0..4) for (outs in 0..8) {
            add(Triple(knobs, ins, outs) to nexusFace(knobs, ins, outs))
        }
        add(Triple(1, 2, 2) to nexusFace(1, 2, 2, scope = true))
    }

    @Test
    fun mappingRingsNeverOverlap() {
        for ((what, face) in faces) {
            val k = face.knobs
            for (i in k.indices) for (j in i + 1 until k.size) {
                val d = hypot(k[i].x - k[j].x, k[i].y - k[j].y)
                assertTrue("$what: rings $i and $j overlap", k[i].ring + k[j].ring <= d)
            }
        }
    }

    @Test
    fun mappingRingsStandClearOfTheirKnobsAndLabels() {
        for ((what, face) in faces) for (knob in face.knobs) {
            assertTrue("$what: ring inside the value arc", knob.ring > knob.arc)
            assertTrue("$what: ring runs into the label", knob.ring <= knob.arc + FACE_LABEL_GAP)
        }
    }

    @Test
    fun everythingIsOnThePanelAndClearOfEverythingElse() {
        for ((what, face) in faces) {
            // Knob arcs and sockets as circles; a socket's nut is 6.6 across.
            val circles = face.knobs.map { Triple(it.x, it.y, it.arc) } +
                (face.inputs + face.outputs).map { Triple(it.x, it.y, 6.6f) }
            for ((x, y, r) in circles) {
                assertTrue("$what: off the panel at $x,$y", x - r >= 0f && x + r <= face.w && y - r >= FACE_HEADER && y + r <= face.h)
            }
            for (i in circles.indices) for (j in i + 1 until circles.size) {
                val (ax, ay, ar) = circles[i]
                val (bx, by, br) = circles[j]
                assertTrue("$what: $i and $j touch", hypot(ax - bx, ay - by) >= ar + br)
            }
            // The knobs and their labels end before the jacks start.
            val lastKnob = face.knobs.maxOfOrNull { it.y + it.arc + 10f } ?: 0f
            val firstJack = (face.inputs + face.outputs).minOfOrNull { it.y - 6.6f } ?: face.h
            assertTrue("$what: knobs run into the jacks", lastKnob <= firstJack)
        }
    }

    @Test
    fun allPanelsAreOneHeightAndBiggerModulesAreWider() {
        assertTrue(faces.all { it.second.h == FACE_H })
        assertTrue(nexusFace(2, 2, 1).w < nexusFace(6, 3, 1).w)
    }

    @Test
    fun aPatchFromBeforeFaceplatesIsSpreadOutOnlyIfItNowOverlaps() {
        // Placed for the old 150 by 92 boxes: on top of each other now.
        val old = NexusPatch.decode("v|1\nm|0|osc\nm|1|vca\np|00|0|0\np|01|0|130")
        assertTrue(!old.overlaps(NexusFaces::size))
        // Far enough apart to keep their places.
        val apart = NexusPatch.decode("v|1\nm|0|osc\nm|1|vca\np|00|0|0\np|01|600|0")
        assertEquals(600f, apart.moduleAt(1)!!.x, 0f)
    }
}
