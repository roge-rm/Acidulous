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
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Suggest, line up and blend on a real lane and engine, from the comp tools
 * under a lane's takes. The takes are a sung line with dirt: one clean, one
 * clipped, one late and noisy, and a darker double 30 ms behind on lane 2.
 * Pictures go in build/comp-rows (c5-*), and if the debug folder is there,
 * renders of the results go in its audition folder.
 */
class CompToolsTest {

    companion object {
        private val out = File("build/comp-tools-test").absoluteFile
        private val user = File(out, "user")
        val shots = File("build/comp-rows").absoluteFile
        private val audition = File("/srv/downloads/temp/debug/acidulous/audition")

        @BeforeClass @JvmStatic
        fun startEngine() {
            out.deleteRecursively()
            user.mkdirs()
            shots.mkdirs()
            EngineLinesTool.start(out, user)
        }

        @AfterClass @JvmStatic
        fun stopEngine() {
            NativeEngine.stop()
            out.deleteRecursively()
        }
    }

    private val rate = 48000
    private val length = 8 * rate
    private val lines = listOf(0.5 to 2.5, 3.25 to 5.0, 5.75 to 7.5)

    /**
     * A sung line: a new syllable every half second (on the song's beats),
     * over rumble and hiss. [late] in seconds, [drive] before a hard clip.
     */
    private fun writeTake(name: String, late: Double = 0.0, drive: Double = 1.0, hiss: Double = 0.003, dark: Boolean = false, hz: Double = 220.0) {
        val r = kotlin.random.Random(name.hashCode())
        val data = java.nio.ByteBuffer.allocate(44 + length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + length * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(length * 2)
        val notes = listOf(1.0, 1.122, 1.26, 1.335)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate - late
            var v = 0.0
            for ((a, b) in lines) if (t in a..b) {
                val into = (t - a) % 0.5
                val syllable = ((t - a) / 0.5).toInt()
                val edge = minOf(1.0, into / 0.008, (b - t) / 0.02, (0.46 - into).coerceAtLeast(0.0) / 0.02)
                phase += 2 * PI * hz * notes[syllable % notes.size] / rate
                var s = 0.0
                for (k in 1..6) s += (if (dark) 1.0 / (k * k) else 1.0 / k) * sin(phase * k)
                v += 0.25 * edge * s
            }
            v = (v * drive).coerceIn(-1.0, 1.0)
            v += 0.02 * sin(2 * PI * 45.0 * i / rate) + (r.nextDouble() * 2 - 1) * hiss
            data.putShort((v * 32000).toInt().coerceIn(-32768, 32767).toShort())
        }
        File(user, "samples/$name").apply { parentFile.mkdirs() }.writeBytes(data.array())
    }

    private fun song(): Song {
        writeTake("clean.wav")
        writeTake("clipped.wav", drive = 4.0)
        writeTake("late.wav", late = 0.03, hiss = 0.05)
        writeTake("double.wav", late = 0.03, dark = true, hz = 221.0)
        val scene = Scene(id = "s-one", name = "One")
        val stack = TakeRef(
            file = "samples/clipped.wav", offset = 0, frames = length, bpm = 120f, ticks = 16 * PPQN,
            takes = listOf(
                StackTake("samples/clipped.wav", 0, 0, length),
                StackTake("samples/late.wav", 0, 0, length),
                StackTake("samples/clean.wav", 0, 0, length),
            ),
            pieces = listOf(Piece(0, length, 0)),
        )
        val dbl = TakeRef(file = "samples/double.wav", offset = 0, frames = length, bpm = 120f, ticks = 16 * PPQN)
        val clip = Clip(bars = 4, audio = ClipAudio(listOf(stack, dbl)))
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

    private fun ImageComposeScene.until(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            render(System.nanoTime())
            if (done()) return true
            Thread.sleep(30)
        }
        return done()
    }

    private fun ImageComposeScene.shot(name: String) {
        val png = render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(shots, name).writeBytes(png)
    }

    /** Lane [only] of [song]'s cell rendered to the audition folder, if it's there. */
    private fun audition(song: Song, name: String, vararg only: Int) {
        if (!audition.isDirectory) return
        val clip = song.tracks[0].clips["s-one"]!!
        val kept = ClipAudio(clip.audio!!.lanes.mapIndexed { i, t -> if (i in only) t else null })
        val solo = song.copy(tracks = listOf(song.tracks[0].copy(clips = mapOf("s-one" to clip.copy(audio = kept)))))
        EngineSync.sync(solo)
        kotlinx.coroutines.runBlocking {
            EngineSync.loadsDone()
            // The new reel goes in on the audio thread's next block.
            kotlinx.coroutines.delay(300)
            val err = NativeEngine.compCell(0, solo.scenes[0].engineId, length, 120f, File(audition, name).absolutePath, FloatArray(1))
            assertEquals("", err)
        }
    }

    @Test
    fun suggestLineUpAndBlend() {
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
            fun lane(i: Int) = current.tracks[0].clips["s-one"]!!.audio!!.lane(i)!!
            try {
                val it = scene
                it.settle(20)
                it.tap(Offset(x(1.0), px(16f) + (h - px(16f)) / 8f))
                it.settle(30)
                // Three rows under lane 1, then the tools.
                val lanes = h - px(16f) - px(48f) - px(18f)
                val bar = px(16f) + lanes / 4f + px(48f) + px(9f)
                it.shot("c5-$tag-1-scores.png")
                // Suggest, the first tool.
                it.tap(Offset(gutter + px(20f), bar))
                assertTrue("suggest chose the clean take", it.until(8000) { lane(0).pieces.all { p -> p.take == 2 } })
                it.settle(10)
                it.shot("c5-$tag-2-suggested.png")
                if (mode == ThemeMode.Dark) audition(current, "comp-c5-suggested.wav", 0)

                // Blend, the last tool on a stack.
                it.tap(Offset(gutter + px(126f), bar))
                assertTrue("blend is on", it.until(3000) { lane(0).blend })
                it.settle(6)
                it.shot("c5-$tag-3-blend.png")
                if (mode == ThemeMode.Dark) audition(current, "comp-c5-blend.wav", 0)
                it.tap(Offset(gutter + px(126f), bar))
                assertTrue("and off again", it.until(3000) { !lane(0).blend })

                // Lane 2, one take: open it and line it up on lane 1.
                it.tap(Offset(x(1.0), px(16f) + lanes / 4f + px(48f) + px(18f) + lanes / 8f))
                it.settle(30)
                // One row under lane 2, then the tools.
                val lanes2 = h - px(16f) - px(16f) - px(18f)
                val bar2 = px(16f) + lanes2 / 2f + px(16f) + px(9f)
                it.shot("c5-$tag-4-double.png")
                it.tap(Offset(gutter + px(24f), bar2))
                assertTrue("the double moved", it.until(8000) { lane(1).pieces.isNotEmpty() })
                val shifts = lane(1).pieces.map { p -> p.shift }
                assertTrue("each phrase about 30 ms earlier: $shifts", shifts.all { s -> abs(s + 1440) <= 144 })
                it.settle(10)
                it.shot("c5-$tag-5-lined-up.png")
                if (mode == ThemeMode.Dark) audition(current, "comp-c5-lined-up.wav", 0, 1)
                // Tapping it again puts it back as it was.
                it.tap(Offset(gutter + px(24f), bar2))
                val was = original.tracks[0].clips["s-one"]!!.audio!!.lane(1)!!
                // The edit stores the lane's overview peaks, which the original lacked.
                it.until(3000) { lane(1).copy(peaks = was.peaks) == was }
                assertEquals("put back", was, lane(1).copy(peaks = was.peaks))
                assertFalse(lane(1).pieces.any { p -> p.shift != 0 })
            } finally {
                scene.close()
            }
        }
        if (audition.isDirectory) audition(original, "comp-c5-before.wav", 0, 1)
    }
}
