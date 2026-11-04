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
import com.rm.acidulous.ui.Listening
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.model.choosing
import com.rm.acidulous.ui.theme.AcidulousTheme
import com.rm.acidulous.ui.theme.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Listening through a phrase on a real lane and engine: the button plays each
 * take in turn with the one playing lit, stopping changes nothing, and a tap
 * on a row keeps that take as one undo step. Pictures go in build/comp-rows.
 */
class ListenThroughTest {

    companion object {
        private val out = File("build/listen-through-test").absoluteFile
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

    /** Renders until [done] or about [ms] have passed. */
    private fun ImageComposeScene.until(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            render(System.nanoTime())
            if (done()) return true
            Thread.sleep(30)
        }
        return done()
    }

    @Test
    fun listenThroughPlaysEachTakeAndATapKeepsOne() {
        val original = song()
        EngineSync.sync(original)
        kotlinx.coroutines.runBlocking { EngineSync.loadsDone() }
        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            val tag = mode.name.lowercase()
            var current by mutableStateOf(original)
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
                val laneClosed = px(16f) + (h - px(16f)) / 8f
                it.tap(Offset(x(1.0), laneClosed))
                it.settle(20)
                val lanes = h - px(16f) - px(48f)
                val laneOpen = px(16f) + lanes / 8f
                // The middle phrase, then the button left of the ruler.
                it.tap(Offset(x(4.0), laneOpen))
                it.settle(10)
                val button = Offset(gutter / 2f, px(8f))
                it.tap(button)
                val session = Listening.session
                assertNotNull("listening started", session)
                assertEquals(listOf(0, 1, 2), session!!.plan.takes)
                assertTrue("the engine plays it", it.until(3000) { Listening.played(0) > 0 })
                it.settle(4)
                it.shot("listen-$tag-1-take1.png")
                // On to the second pass.
                val pass = session.plan.length.toLong()
                assertTrue("the second pass comes", it.until(6000) { Listening.played(0) >= pass + 4800 })
                it.settle(2)
                it.shot("listen-$tag-2-take2.png")
                // Stopping changes nothing and the engine stops.
                it.tap(button)
                assertNull(Listening.session)
                assertEquals(original, current)
                assertTrue("the engine stopped", it.until(2000) { Listening.played(0) == -1L })
                it.shot("listen-$tag-3-stopped.png")
                // Again, and a tap on take 2's row keeps it for the phrase.
                it.tap(button)
                val again = Listening.session!!
                val row0 = px(16f) + lanes / 4f + px(8f)
                it.tap(Offset(x(6.5), row0 + px(16f)))
                it.settle(10)
                assertNull("keeping ends listening", Listening.session)
                it.shot("listen-$tag-4-kept.png")
                val lane = current.tracks[0].clips["s-one"]!!.audio!!.lane(0)!!
                val want = original.tracks[0].clips["s-one"]!!.audio!!.lane(0)!!.choosing(1, again.plan.start, again.plan.end)
                assertEquals(want.pieces, lane.pieces)
                // One undo step puts it back.
                editor.undo(0)
                assertEquals(original.tracks[0].clips["s-one"], current.tracks[0].clips["s-one"])
            } finally {
                Listening.stop()
                scene.close()
            }
        }
    }
}
