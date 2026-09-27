package com.rm.acidulous.io

/**
 * A file as the shared code uses it. On Android and desktop it's a typealias
 * for java.io.File, so nothing changes there. In a browser it's a file system
 * shared with the engine, so samples loaded by path are the files the app
 * wrote.
 *
 * Only what the app uses is here. Java's own methods are members, and what
 * Kotlin adds to java.io.File (readText, extension and so on) are extensions,
 * which on the JVM are Kotlin's own.
 */
expect class File(pathname: String) {
    constructor(parent: File?, child: String)
    constructor(parent: String?, child: String)

    fun exists(): Boolean
    fun delete(): Boolean
    fun mkdirs(): Boolean
    fun createNewFile(): Boolean
    fun renameTo(dest: File): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun listFiles(): Array<File>?
    fun list(): Array<String>?
}

expect val File.name: String
expect val File.path: String
expect val File.absolutePath: String
expect val File.parentFile: File?
expect val File.isFile: Boolean
expect val File.isDirectory: Boolean
expect val File.extension: String
expect val File.nameWithoutExtension: String

expect fun File.readText(): String
expect fun File.writeText(text: String)
expect fun File.readBytes(): ByteArray
expect fun File.writeBytes(array: ByteArray)
expect fun File.copyTo(target: File, overwrite: Boolean = false): File
expect fun File.deleteRecursively(): Boolean
expect fun File.resolve(relative: String): File
expect fun File.listFiles(filter: (File) -> Boolean): Array<File>?
/** Every file and folder under this one, this one first. */
expect fun File.walk(): Sequence<File>

expect val File.canonicalFile: File
expect fun File.relativeTo(base: File): File
expect val File.invariantSeparatorsPath: String
/** The separator between a folder and its contents: java.io.File.separator. */
expect val FILE_SEPARATOR: String

/**
 * Replace a file's contents so it's always either the old file or the new
 * one, never half of each: written next to it, synced, then renamed over it.
 */
expect fun File.writeBytesSafely(bytes: ByteArray)

/** Writes a zip, one entry at a time. */
expect class ZipWriter(out: File) {
    fun add(name: String, bytes: ByteArray)
    fun addFile(name: String, file: File)
    fun close()
}

/** Every entry of a zip in order: its name, whether it's a folder, and its bytes on request. */
expect suspend fun readZip(zip: File, each: suspend (name: String, isDirectory: Boolean, bytes: suspend () -> ByteArray) -> Unit)
