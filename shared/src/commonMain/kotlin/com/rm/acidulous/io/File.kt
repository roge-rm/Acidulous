package com.rm.acidulous.io

/**
 * A file, as the shared code uses one: java.io.File on Android and the desktop
 * (an actual typealias - the very same class, so nothing there changes), and
 * on a file system the engine shares in a browser, where the samples it loads
 * by path are the files the app wrote.
 *
 * Only what the app uses is here. Java's own methods are members; what Kotlin
 * adds to java.io.File (readText, extension and the rest) are extensions,
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
