package com.rm.acidulous.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.rm.acidulous.model.BIAS_MACHINE
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.ClipAudio
import com.rm.acidulous.model.Machine
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Piece
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.StackTake
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.Track
import com.rm.acidulous.ui.AudioLanes
import com.rm.acidulous.ui.theme.AcidulousTheme
import com.rm.acidulous.ui.theme.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The comping rows on a real lane: tap a lane to show its takes, tap a phrase,
 * tap a take's row, drag along another. Pictures of each step go in
 * build/comp-rows.
 */
class CompRowsTest {

    companion object {
        private val out = File("build/comp-rows-test").absoluteFile
        private val user = File(out, "user")
        val shots = File("build/comp-rows").absoluteFile

        @BeforeClass @JvmStatic
        fun startEngine() {
            out.deleteRecursively()
            user.mkdirs()
            shots.mkdirs()
            EngineLinesTool.start(out, user)
        }

        @AfterClass @JvmStatic
        fun stopEngine() {
            com.rm.acidulous.engine.NativeEngine.stop()
            out.deleteRecursively()
        }
    }

    private val rate = 48000
    private val length = 8 * rate

    /** A sung take: lines over [lines] in seconds, from [at] seconds into the region, over quiet hiss. */
    private fun writeTake(name: String, at: Double, lines: List<Pair<Double, Double>>, hz: Double) {
        val frames = length - (at * rate).toInt()
        val r = kotlin.random.Random(name.hashCode())
        val data = java.nio.ByteBuffer.allocate(44 + frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(frames * 2)
        for (i in 0 until frames) {
            val t = at + i.toDouble() / rate
            var v = (r.nextDouble() * 2 - 1) * 0.004
            for ((a, b) in lines) if (t in a..b) {
                val edge = minOf(1.0, (t - a) / 0.02, (b - t) / 0.02)
                val dip = if (((t - a) % 0.4) > 0.32) 0.05 else 1.0
                v += 0.5 * edge * dip * (sin(2 * PI * hz * t) + 0.4 * sin(4 * PI * hz * t)) / 1.4
            }
            data.putShort((v * 32000).toInt().coerceIn(-32768, 32767).toShort())
        }
        File(user, "samples/$name").apply { parentFile.mkdirs() }.writeBytes(data.array())
    }

    private fun song(): Song {
        writeTake("a.wav", 0.0, listOf(0.5 to 2.5, 3.2 to 5.0, 5.8 to 7.5), 220.0)
        writeTake("b.wav", 0.0, listOf(0.6 to 2.4, 3.3 to 5.1, 5.9 to 7.4), 247.0)
        writeTake("c.wav", 3.0, listOf(3.4 to 5.0, 5.8 to 7.6), 262.0)
        val scene = Scene(id = "s-one", name = "One")
        val stack = TakeRef(
            file = "samples/a.wav", offset = 0, frames = length, bpm = 120f, ticks = 16 * PPQN,
            takes = listOf(
                StackTake("samples/a.wav", 0, 0, length),
                StackTake("samples/b.wav", 0, 0, length),
                StackTake("samples/c.wav", -3 * rate, 3 * rate, length),
            ),
            pieces = listOf(Piece(0, 3 * rate, 1), Piece(3 * rate, length, 2)),
        )
        val plain = TakeRef(file = "samples/b.wav", offset = 0, frames = length, bpm = 120f, ticks = 16 * PPQN)
        val clip = Clip(bars = 4, audio = ClipAudio(listOf(stack, plain)))
        return Song(
            name = "Comp", tempo = 120f, scenes = listOf(scene),
            tracks = listOf(Track(id = "t-tape", name = "Tape", machine = Machine(BIAS_MACHINE), clips = mapOf(scene.id to clip))),
        )
    }

    private val w = 900
    private val h = 560
    private val d = 2f
    private fun px(dp: Float) = dp * d
    private val gutter = px(34f)
    private fun x(seconds: Double) = gutter + (w - gutter) * (seconds / 8.0).toFloat()

    private fun ImageComposeScene.settle(times: Int = 12) {
        repeat(times) { render(System.nanoTime()); Thread.sleep(60) }
    }

    private fun ImageComposeScene.tap(at: Offset) {
        sendPointerEvent(PointerEventType.Press, at)
        render(System.nanoTime())
        sendPointerEvent(PointerEventType.Release, at)
        settle(6)
    }

    private fun ImageComposeScene.shot(name: String) {
        val png = render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(shots, name).writeBytes(png)
    }

    @Test
    fun tapsAndDragsWritePieces() {
        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            val tag = mode.name.lowercase()
            var current by mutableStateOf(song())
            val editor = SongEditor(current) { s, _ -> current = s }
            val scene = ImageComposeScene(w, h, Density(d)) {
                AcidulousTheme(mode) {
                    AudioLanes(
                        clip = current.tracks[0].clips["s-one"]!!, ticksPerBar = 4 * PPQN, cycleTicks = 16 * PPQN,
                        playheadTick = { null }, trackIndex = 0, sceneId = "s-one", editor = editor,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            try {
                val it = scene
                it.settle(20)
                it.shot("$tag-1-closed.png")
                // Lane 1's middle: the ruler, then four lanes.
                val laneClosed = px(16f) + (h - px(16f)) / 8f
                it.tap(Offset(x(1.0), laneClosed))
                it.settle(20)
                // Open: three 16 dp rows take their height from the lanes.
                val lanes = h - px(16f) - px(48f)
                val laneOpen = px(16f) + lanes / 8f
                it.tap(Offset(x(6.5), laneOpen))
                it.shot("$tag-2-phrase.png")
                // Take 1's row, in the middle phrase.
                val row0 = px(16f) + lanes / 4f + px(8f)
                it.tap(Offset(x(4.0), row0))
                it.settle(10)
                it.shot("$tag-3-chosen.png")
                val lane = current.tracks[0].clips["s-one"]!!.audio!!.lane(0)!!
                val middle = lane.pieces.first { p -> p.start <= 4 * rate && p.end > 4 * rate }
                assertEquals(0, middle.take)
                assertTrue("the phrase starts in the gap: ${middle.start}", middle.start in (2.5 * rate).toInt()..(3.2 * rate).toInt())
                assertTrue("and ends in the next: ${middle.end}", middle.end in (5.1 * rate).toInt()..(5.8 * rate).toInt())
                // Drag along take 2's row from 6 s to 7 s.
                val row1 = row0 + px(16f)
                it.sendPointerEvent(PointerEventType.Press, Offset(x(6.0), row1))
                for (s in 1..10) {
                    it.sendPointerEvent(PointerEventType.Move, Offset(x(6.0 + s / 10.0), row1))
                    it.render(System.nanoTime())
                }
                it.shot("$tag-4-dragging.png")
                it.sendPointerEvent(PointerEventType.Release, Offset(x(7.0), row1))
                it.settle(10)
                it.shot("$tag-5-dragged.png")
                val after = current.tracks[0].clips["s-one"]!!.audio!!.lane(0)!!
                val dragged = after.pieces.first { p -> p.start <= (6.5 * rate).toInt() && p.end > (6.5 * rate).toInt() }
                assertEquals(1, dragged.take)
                assertTrue("from about 6 s: ${dragged.start}", kotlin.math.abs(dragged.start - 6 * rate) < rate / 10)
                assertTrue("to about 7 s: ${dragged.end}", kotlin.math.abs(dragged.end - 7 * rate) < rate / 10)
                // Two edits, two undo steps.
                editor.undo(0)
                assertEquals(lane.pieces, current.tracks[0].clips["s-one"]!!.audio!!.lane(0)!!.pieces)
            } finally {
                scene.close()
            }
        }
    }
}
