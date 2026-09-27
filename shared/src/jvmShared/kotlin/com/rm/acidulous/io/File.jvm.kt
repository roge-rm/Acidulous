@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER", "NOTHING_TO_INLINE")

package com.rm.acidulous.io

import kotlin.io.copyTo as kCopyTo
import kotlin.io.deleteRecursively as kDeleteRecursively
import kotlin.io.extension as kExtension
import kotlin.io.nameWithoutExtension as kNameWithoutExtension
import kotlin.io.invariantSeparatorsPath as kInvariantSeparatorsPath
import kotlin.io.readBytes as kReadBytes
import kotlin.io.relativeTo as kRelativeTo
import kotlin.io.readText as kReadText
import kotlin.io.resolve as kResolve
import kotlin.io.walk as kWalk
import kotlin.io.writeBytes as kWriteBytes
import kotlin.io.writeText as kWriteText

/**
 * Plain java.io.File (see the expect in commonMain). Java's getters are
 * members and win over these extensions, so `this.name` below is Java's.
 * Kotlin's own extensions are imported under other names so that, for
 * example, `this.extension` doesn't call itself.
 */
actual typealias File = java.io.File

actual val File.name: String get() = this.name
actual val File.path: String get() = this.path
actual val File.absolutePath: String get() = this.absolutePath
actual val File.parentFile: File? get() = this.parentFile
actual val File.isFile: Boolean get() = this.isFile
actual val File.isDirectory: Boolean get() = this.isDirectory
actual val File.extension: String get() = this.kExtension
actual val File.nameWithoutExtension: String get() = this.kNameWithoutExtension

actual fun File.readText(): String = this.kReadText()
actual fun File.writeText(text: String) = this.kWriteText(text)
actual fun File.readBytes(): ByteArray = this.kReadBytes()
actual fun File.writeBytes(array: ByteArray) = this.kWriteBytes(array)
actual fun File.copyTo(target: File, overwrite: Boolean): File = this.kCopyTo(target, overwrite)
actual fun File.deleteRecursively(): Boolean = this.kDeleteRecursively()
actual fun File.resolve(relative: String): File = this.kResolve(relative)
actual fun File.listFiles(filter: (File) -> Boolean): Array<File>? = this.listFiles(java.io.FileFilter { filter(it) })
actual fun File.walk(): Sequence<File> = this.kWalk()

actual val File.canonicalFile: File get() = this.canonicalFile
actual fun File.relativeTo(base: File): File = this.kRelativeTo(base)
actual val File.invariantSeparatorsPath: String get() = this.kInvariantSeparatorsPath
actual val FILE_SEPARATOR: String get() = java.io.File.separator

/**
 * Writes to a hidden file next to it, syncs it to disk, then renames it over
 * the original. A rename in the same directory is atomic, so a crash or full
 * disk mid-write leaves the old version intact.
 */
actual fun File.writeBytesSafely(bytes: ByteArray) {
    val tmp = File(parentFile, ".$name.tmp")
    try {
        java.io.FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(this)) throw java.io.IOException("could not replace $name")
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

actual class ZipWriter actual constructor(out: File) {
    private val zip = java.util.zip.ZipOutputStream(out.outputStream().buffered())
    actual fun add(name: String, bytes: ByteArray) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
    actual fun addFile(name: String, file: File) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        file.inputStream().buffered().use { it.kCopyTo(zip) }
        zip.closeEntry()
    }
    actual fun close() = zip.close()
}

actual fun readZip(zip: File, each: (name: String, isDirectory: Boolean, bytes: () -> ByteArray) -> Unit) {
    java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            each(entry.name, entry.isDirectory) { input.kReadBytes() }
            input.closeEntry()
        }
    }
}
