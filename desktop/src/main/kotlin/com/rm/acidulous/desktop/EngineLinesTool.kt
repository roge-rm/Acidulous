package com.rm.acidulous.desktop

import com.rm.acidulous.engine.EngineLineWriter
import com.rm.acidulous.engine.EngineLines
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Machine
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongBundle
import com.rm.acidulous.model.Track
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
 * Arguments: the song (squelch, the demo, or sampled, a pad playing a user
 * sample), seconds to render (0 for all of it), tail seconds and bits (32 is
 * float). The folder is the engineLines.out property. It writes
 * <song>.zip, <song>.txt and <song>-app.wav. tools/engine_replay.sh renders
 * the zip without a JVM, to compare.
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
        else -> { System.err.println("engine-lines: no song called $which"); exitProcess(2) }
    }
    val writer = EngineLinesTool.record(song, user)
    val lines = writer.text()
    for (path in writer.unportable()) System.err.println("engine-lines: not in the zip, so only on this machine: $path")
    File(out, "$which.txt").writeText(lines)
    val zip = File(out, "$which.zip")
    EngineLinesTool.writeZip(song, user, lines, zip)

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

    /**
     * What a sync of [song] sends to the engine, with media under [user] as
     * zip paths. EngineSync forgets what it sent first, or only what changed
     * since the last sync would be written.
     */
    fun record(song: Song, user: File): EngineLineWriter {
        EngineSync.forgetEngine()
        val userPath = user.absolutePath + File.separator
        val writer = EngineLineWriter { path -> if (path.startsWith(userPath)) path.removePrefix(userPath) else null }
        EngineLines.writer = writer
        try {
            EngineSync.sync(song)
            kotlinx.coroutines.runBlocking { EngineSync.loadsDone() }
        } finally {
            EngineLines.writer = null
        }
        return writer
    }

    /** The song zip as the app writes it, with engine.txt added. */
    fun writeZip(song: Song, user: File, lines: String, zip: File) {
        val plain = File(zip.parentFile, zip.name + ".plain")
        SongBundle.write(song, user, plain)
        ZipOutputStream(zip.outputStream()).use { z ->
            ZipInputStream(plain.inputStream()).use { from ->
                while (true) {
                    val e = from.nextEntry ?: break
                    z.putNextEntry(ZipEntry(e.name))
                    from.copyTo(z)
                    z.closeEntry()
                }
            }
            z.putNextEntry(ZipEntry("engine.txt"))
            z.write(lines.encodeToByteArray())
            z.closeEntry()
        }
        plain.delete()
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

    /** A short decaying tone as a 16-bit mono WAV at 48 kHz. */
    fun writeHit(file: File) {
        val rate = 48000
        val frames = rate / 4
        val data = java.nio.ByteBuffer.allocate(44 + frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(frames * 2)
        for (i in 0 until frames) {
            val v = sin(2 * PI * 220 * i / rate) * exp(-i / (rate * 0.05))
            data.putShort((v * 20000).toInt().toShort())
        }
        file.parentFile.mkdirs()
        file.writeBytes(data.array())
    }
}
