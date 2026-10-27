package com.rm.acidulous.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.StandardOpenOption

/**
 * One copy of the app at a time. The first takes a lock in the data folder
 * and listens on a socket beside it; a later launch finds the lock taken,
 * hands over the files it was opened with ([handOver]) and quits. Two copies
 * would fight over the sound card, the MIDI ports and the autosaved song.
 *
 * What goes over the socket is lines of text: "open <path>" for each file,
 * then "raise".
 */
internal object SingleInstance {
    /** Called on the socket's thread when another launch asks for the window. */
    @Volatile var onRaise: (() -> Unit)? = null
    /** Called on the socket's thread with the files another launch was opened with. */
    @Volatile var onOpen: ((List<File>) -> Unit)? = null

    // Held for as long as the app runs; the OS lets it go when the process ends, crash or not.
    private var lock: FileLock? = null

    /** True if this is the only copy, which then listens for the next; false if one is running. */
    fun claim(dir: File): Boolean {
        val channel = runCatching {
            FileChannel.open(File(dir, "running.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }.getOrNull() ?: return true
        val got = runCatching { channel.tryLock() }.getOrNull() ?: return false
        lock = got
        runCatching {
            val socket = socketIn(dir)
            java.nio.file.Files.deleteIfExists(socket)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
            val listener = Thread({
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { client.use { take(Channels.newInputStream(it).bufferedReader().readLines()) } }
                }
            }, "single instance")
            listener.isDaemon = true
            listener.start()
        }
        return true
    }

    private fun take(lines: List<String>) {
        val files = lines.filter { it.startsWith(OPEN) }.map { File(it.removePrefix(OPEN)) }.filter { it.isFile }
        if (files.isNotEmpty()) onOpen?.invoke(files)
        if (RAISE in lines) onRaise?.invoke()
    }

    /** Sends [files] to the copy that holds the lock in [dir] and asks for its window. */
    fun handOver(dir: File, files: List<File>) {
        val message = buildString {
            for (f in files) append(OPEN).append(f.absolutePath).append('\n')
            append(RAISE).append('\n')
        }.toByteArray()
        // The running copy may not be listening yet if both started at once.
        repeat(RAISE_TRIES) {
            val sent = runCatching {
                SocketChannel.open(UnixDomainSocketAddress.of(socketIn(dir))).use { channel ->
                    val buffer = ByteBuffer.wrap(message)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
            }.isSuccess
            if (sent) return
            Thread.sleep(RAISE_WAIT_MS)
        }
    }

    private fun socketIn(dir: File) = File(dir, "running.sock").toPath()

    private const val OPEN = "open "
    private const val RAISE = "raise"
    private const val RAISE_TRIES = 20
    private const val RAISE_WAIT_MS = 250L
}
