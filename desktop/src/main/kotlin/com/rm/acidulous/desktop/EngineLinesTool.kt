package com.rm.acidulous.desktop

import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.BIAS_MACHINE
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.ClipAudio
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Freeze
import com.rm.acidulous.model.Frozen
import com.rm.acidulous.model.Machine
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongBundle
import com.rm.acidulous.model.SongEngine
import com.rm.acidulous.model.withParam
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.withEffect
import com.rm.acidulous.model.withEffectParam
import com.rm.acidulous.model.Track
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Writes a song as a song zip with engine.txt, and renders it the way an
 * export does, with no window:
 *
 *     ./gradlew :desktop:engineLines --args="squelch"   into desktop/build/engine-lines
 *
 * Arguments: the song (squelch, the demo, sampled, a pad playing a user
 * sample, tape, that pad and an audio track playing a take, or frozen, that
 * pad and a bass line frozen here and zipped with its frozen audio), seconds to render (0 for all of it), tail seconds and bits (32 is
 * float). The folder is the engineLines.out property. It writes
 * <song>.zip, <song>.txt and <song>-app.wav. The zip is written as the app
 * writes it (SongBundle with SongEngine's dry recording), and the render is
 * of the song synced for real. tools/engine_replay.sh renders the zip without
 * a JVM, to compare.
 */
fun main(args: Array<String>) {
    val out = File(System.getProperty("engineLines.out") ?: "build/engine-lines").absoluteFile.apply { mkdirs() }
    val which = args.getOrElse(0) { "squelch" }
    val seconds = args.getOrNull(1)?.toFloat() ?: 0f
    val tail = args.getOrNull(2)?.toFloat() ?: 2f
    val bits = args.getOrNull(3)?.toInt() ?: 32
    val user = File(out, "user-$which").apply { deleteRecursively(); mkdirs() }

    EngineLinesTool.start(out, user)
    val song = when (which) {
        "squelch" -> DemoSong.build()
        "sampled" -> EngineLinesTool.sampledSong(user)
        "tape" -> EngineLinesTool.tapeSong(user)
        "macros" -> EngineLinesTool.macroSong()
        "frozen" -> kotlinx.coroutines.runBlocking { EngineLinesTool.frozenSong(user) }
        "tailover" -> kotlinx.coroutines.runBlocking {
            // The same scene change played live first, to compare with.
            val live = EngineLinesTool.tailSong()
            EngineSync.sync(live)
            EngineSync.loadsDone()
            EngineSync.pushForRender(live)
            Thread.sleep(50)
            val error = NativeEngine.renderSong(File(out, "tailover-live.wav").absolutePath, tail, 0, bits, 0, seconds)
            check(error.isEmpty()) { error }
            EngineLinesTool.freezeAll(live)
        }
        else -> { System.err.println("engine-lines: no song called $which"); exitProcess(2) }
    }
    val frozenAudio = which == "frozen" || which == "tailover"
    val recording = EngineLinesTool.record(song, user, frozenAudio)
    val lines = recording.text
    for (path in recording.unportable) System.err.println("engine-lines: not in the zip, so only on this machine: $path")
    File(out, "$which.txt").writeText(lines)
    val zip = File(out, "$which.zip")
    SongBundle.write(song, user, zip, lines, freezeRoot = if (frozenAudio) EngineSync.freezeRoot else null)

    // The song on the engine, as the app has it when you export.
    EngineSync.sync(song)
    kotlinx.coroutines.runBlocking { EngineSync.loadsDone() }
    // An export: every parameter back to what the song says, then the render.
    EngineSync.pushForRender(song)
    Thread.sleep(50)
    val wav = File(out, "$which-app.wav")
    val error = kotlinx.coroutines.runBlocking { NativeEngine.renderSong(wav.absolutePath, tail, 0, bits, 0, seconds) }
    NativeEngine.stop()
    if (error.isNotEmpty()) {
        System.err.println("engine-lines: render failed: $error")
        exitProcess(1)
    }
    println("engine-lines: ${lines.lines().size - 1} lines, ${zip.path}, ${wav.path}")
    exitProcess(0)
}

/** The pieces of the tool, shared with its tests (EngineLinesToolTest). */
object EngineLinesTool {
    /** The same start as the app's (App.kt), less the screen and settings. */
    fun start(out: File, user: File) {
        EngineSync.sampleRoot = user
        EngineSync.freezeRoot = File(out, "freeze").apply { mkdirs() }
        NativeEngine.setCacheRoot(File(out, "cache").apply { mkdirs() }.absolutePath)
        NativeEngine.prewarm()
        EngineSync.forgetEngine()
        check(NativeEngine.start()) { "the engine didn't start" }
    }

    /** The engine lines a song zip of [song] carries, recorded dry as the app does (SongEngine). */
    fun record(song: Song, user: File, frozenAudio: Boolean = false): SongEngine.Recording =
        SongEngine.record(song, user, frozenAudio)

    /**
     * [sampledSong] with a Reflux bass line on rack 1, frozen on the engine
     * as the app freezes (Freeze.render, less EngineAssets). Starts the
     * engine's sync, so call it after [start].
     */
    suspend fun frozenSong(user: File): Song {
        val base = sampledSong(user)
        val scene = base.scenes.single()
        val notes = listOf(Note(0, PPQN, 36, 100), Note(2 * PPQN, PPQN, 43, 120), Note(5 * PPQN, PPQN * 2, 31, 90))
        val clip = Clip(bars = 2, notes = notes)
        val bass = Track(id = "t-bass", name = "Bass", machine = Machine("Reflux"), clips = mapOf(scene.id to clip))
        val song = base.copy(name = "Frozen", tracks = base.tracks + bass)
        EngineSync.sync(song)
        EngineSync.loadsDone()
        val name = Freeze.nameFor(bass.id, scene.id)
        val file = File(EngineSync.freezeRoot!!, name)
        val r = NativeEngine.freezeClip(1, scene.engineId, file.absolutePath, 8f)
        check(r is NativeEngine.FreezeResult.Ok) { "the freeze failed: $r" }
        val frozen = Frozen(name, r.bpm, r.ticks, r.frames, r.peak, Freeze.voiceOf(bass), r.tail, Freeze.contentOf(song, bass, clip))
        return song.copy(tracks = listOf(song.tracks[0], bass.copy(clips = mapOf(scene.id to clip.copy(frozen = frozen)))))
    }

    /**
     * A bass through a long delay over two one-bar scenes. The first plays
     * to the end of its bar, the second waits a beat, so the start of the
     * second scene is only the first one's ring-out.
     */
    fun tailSong(): Song {
        val a = Scene(id = "s-a", name = "A")
        val b = Scene(id = "s-b", name = "B")
        val first = listOf(Note(0, PPQN / 2, 36, 120), Note(2 * PPQN, PPQN / 2, 43, 120), Note(3 * PPQN + PPQN / 2, PPQN / 2, 48, 127))
        val second = listOf(Note(PPQN, PPQN / 2, 31, 110), Note(3 * PPQN, PPQN / 2, 38, 110))
        val bass = Track(id = "t-bass", name = "Bass", machine = Machine("Reflux"), clips = mapOf(
            a.id to Clip(bars = 1, notes = first),
            b.id to Clip(bars = 1, notes = second),
        )).withEffect(0, "Delay").withEffectParam(0, "feedback", 0.8f).withEffectParam(0, "mix", 0.6f)
        return Song(name = "Tail over", tempo = 120f, loopSong = false, scenes = listOf(a, b), tracks = listOf(bass))
    }

    /** A Trinity with a named macro that opens its filter, held at full: the names travel as a line and the route is in the parameters. */
    fun macroSong(): Song {
        val a = Scene(id = "s-a", name = "A")
        val notes = listOf(Note(0, PPQN, 48, 110), Note(2 * PPQN, PPQN, 55, 110))
        val lead = Track(id = "t-lead", name = "Lead", machine = Machine("Trinity"), clips = mapOf(a.id to Clip(bars = 1, notes = notes)))
            .withSetting("macro1", "Open")
            .withParam("mac1", 1f).withParam("mac1_d1", 23f / 33f).withParam("mac1_a1", 0.95f)
        return Song(name = "Macros", tempo = 120f, loopSong = false, scenes = listOf(a), tracks = listOf(lead))
    }

    /** [song] with every clip frozen on the engine, the way [frozenSong] freezes. */
    suspend fun freezeAll(song: Song): Song {
        EngineSync.sync(song)
        EngineSync.loadsDone()
        var out = song
        song.tracks.forEachIndexed { i, t ->
            for (scene in song.scenes) {
                val clip = t.clips[scene.id] ?: continue
                val name = Freeze.nameFor(t.id, scene.id)
                val r = NativeEngine.freezeClip(i, scene.engineId, File(EngineSync.freezeRoot!!, name).absolutePath, 8f)
                check(r is NativeEngine.FreezeResult.Ok) { "the freeze failed: $r" }
                val f = Frozen(name, r.bpm, r.ticks, r.frames, r.peak, Freeze.voiceOf(t), r.tail, Freeze.contentOf(song, t, clip))
                out = out.copy(tracks = out.tracks.mapIndexed { j, tt ->
                    if (j != i) tt else tt.copy(clips = tt.clips + (scene.id to clip.copy(frozen = f)))
                })
            }
        }
        return out
    }

    /** One Forage pad playing samples/hit.wav, written into [user], four times a bar for two bars. */
    fun sampledSong(user: File): Song {
        writeHit(File(user, "samples/hit.wav"))
        val scene = Scene(id = "s-one", name = "One")
        val notes = (0 until 8).map { Note(it * PPQN, PPQN / 2, 36, if (it % 2 == 0) 120 else 80) }
        return Song(
            name = "Sampled",
            tempo = 120f,
            scenes = listOf(scene),
            tracks = listOf(
                Track(
                    id = "t-pad", name = "Pad",
                    machine = Machine("Forage", settings = mapOf("p00_sample" to "samples/hit.wav")),
                    clips = mapOf(scene.id to Clip(bars = 2, notes = notes)),
                ),
            ),
        )
    }

    /** [sampledSong] with an audio track playing samples/take.wav, a bar long, in lanes 1 and 3. */
    fun tapeSong(user: File): Song {
        val base = sampledSong(user)
        writeHit(File(user, "samples/take.wav"), seconds = 2f, hz = 330.0)
        val scene = base.scenes.single()
        val take = TakeRef(file = "samples/take.wav", offset = 0, frames = 96000, bpm = 120f, ticks = 4 * PPQN)
        val tape = Track(
            id = "t-tape", name = "Tape", machine = Machine(BIAS_MACHINE),
            clips = mapOf(scene.id to Clip(bars = 2, audio = ClipAudio(listOf(take, null, take.copy(offset = 12000, fadeIn = 480))))),
        )
        return base.copy(name = "Tape", tracks = base.tracks + tape)
    }

    /** A decaying tone, [seconds] long, as a 16-bit mono WAV at 48 kHz. */
    fun writeHit(file: File, seconds: Float = 0.25f, hz: Double = 220.0) {
        val rate = 48000
        val frames = (rate * seconds).toInt()
        val data = java.nio.ByteBuffer.allocate(44 + frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(frames * 2)
        for (i in 0 until frames) {
            val v = sin(2 * PI * hz * i / rate) * exp(-i / (rate * seconds * 0.2))
            data.putShort((v * 20000).toInt().toShort())
        }
        file.parentFile.mkdirs()
        file.writeBytes(data.array())
    }
}
