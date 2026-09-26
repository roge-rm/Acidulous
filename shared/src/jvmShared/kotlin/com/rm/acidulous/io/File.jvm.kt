@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER", "NOTHING_TO_INLINE")

package com.rm.acidulous.io

import kotlin.io.copyTo as kCopyTo
import kotlin.io.deleteRecursively as kDeleteRecursively
import kotlin.io.extension as kExtension
import kotlin.io.nameWithoutExtension as kNameWithoutExtension
import kotlin.io.readBytes as kReadBytes
import kotlin.io.readText as kReadText
import kotlin.io.resolve as kResolve
import kotlin.io.walk as kWalk
import kotlin.io.writeBytes as kWriteBytes
import kotlin.io.writeText as kWriteText

/**
 * java.io.File itself: see the expect in commonMain. Java's getters are
 * members, which win over these extensions, so `this.name` below is Java's;
 * Kotlin's own extensions are imported under other names, or `this.extension`
 * would be this one calling itself.
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
