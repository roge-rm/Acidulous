package com.rm.acidulous.io

// Files in the browser live in the engine's own Emscripten file system
// (globalThis.acid.FS), so a file the app writes is the same file the engine
// loads by that path. The page mounts browser storage under the app's folder
// before the app starts and saves it back when it changes (see web/app's
// index.html). Paths are Unix style.

private fun fsExists(p: String): Boolean = js("globalThis.acid.FS.analyzePath(p).exists")
private fun fsKind(p: String): Int = js(
    "(() => { try { const m = globalThis.acid.FS.stat(p).mode; const F = globalThis.acid.FS; " +
        "return F.isDir(m) ? 2 : F.isFile(m) ? 1 : 3; } catch (e) { return 0; } })()",
)
private fun fsSize(p: String): Double = js("(() => { try { return globalThis.acid.FS.stat(p).size; } catch (e) { return 0; } })()")
private fun fsModified(p: String): Double =
    js("(() => { try { return globalThis.acid.FS.stat(p).mtime.getTime(); } catch (e) { return 0; } })()")
private fun fsUnlink(p: String): Boolean = js("(() => { try { globalThis.acid.FS.unlink(p); return true; } catch (e) { return false; } })()")
private fun fsRmdir(p: String): Boolean = js("(() => { try { globalThis.acid.FS.rmdir(p); return true; } catch (e) { return false; } })()")
private fun fsMkdirs(p: String): Boolean = js("(() => { try { globalThis.acid.FS.mkdirTree(p); return true; } catch (e) { return false; } })()")
private fun fsRename(from: String, to: String): Boolean =
    js("(() => { try { globalThis.acid.FS.rename(from, to); return true; } catch (e) { return false; } })()")
/** The names in a folder, joined by a character no name has, without "." and "..". */
private fun fsList(p: String): String? = js(
    "(() => { try { return globalThis.acid.FS.readdir(p).filter(n => n !== '.' && n !== '..').join('\\u0000'); } " +
        "catch (e) { return null; } })()",
)
private fun fsReadText(p: String): String = js("globalThis.acid.FS.readFile(p, { encoding: 'utf8' })")
private fun fsWriteText(p: String, text: String): Unit = js("globalThis.acid.FS.writeFile(p, text)")
/** A file's bytes as a string of chars 0-255, so it crosses to Kotlin in one call. */
private fun fsReadLatin1(p: String): String = js(
    "(() => { const a = globalThis.acid.FS.readFile(p); let s = ''; " +
        "for (let i = 0; i < a.length; i += 8192) s += String.fromCharCode.apply(null, a.subarray(i, i + 8192)); return s; })()",
)
private fun fsWriteLatin1(p: String, s: String): Unit = js(
    "(() => { const a = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); " +
        "globalThis.acid.FS.writeFile(p, a); })()",
)
/** Tells the page something changed so it saves browser storage soon. */
private fun fsChanged(): Unit = js("globalThis.acidFsChanged && globalThis.acidFsChanged()")

internal fun latin1(bytes: ByteArray): String = CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
internal fun fromLatin1(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

/** Normalises a path like Java does: no doubled separators, none at the end. */
private fun normal(p: String): String {
    if (p.isEmpty()) return p
    val collapsed = p.replace(Regex("/+"), "/")
    return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
}

actual class File actual constructor(pathname: String) {
    internal val p: String = normal(pathname)

    actual constructor(parent: File?, child: String) : this(join(parent?.p, child))
    actual constructor(parent: String?, child: String) : this(join(parent?.let { normal(it) }, child))

    actual fun exists(): Boolean = p.isNotEmpty() && fsExists(p)
    actual fun delete(): Boolean = when (fsKind(p)) {
        0 -> false
        2 -> fsRmdir(p)
        else -> fsUnlink(p)
    }.also { if (it) fsChanged() }
    actual fun mkdirs(): Boolean = if (exists()) false else fsMkdirs(p).also { if (it) fsChanged() }
    actual fun createNewFile(): Boolean {
        if (exists()) return false
        fsWriteLatin1(p, "")
        fsChanged()
        return true
    }
    actual fun renameTo(dest: File): Boolean = fsRename(p, dest.p).also { if (it) fsChanged() }
    actual fun length(): Long = fsSize(p).toLong()
    actual fun lastModified(): Long = fsModified(p).toLong()
    actual fun listFiles(): Array<File>? = list()?.map { File(this, it) }?.toTypedArray()
    actual fun list(): Array<String>? {
        if (fsKind(p) != 2) return null
        val joined = fsList(p) ?: return null
        return if (joined.isEmpty()) emptyArray() else joined.split('\u0000').toTypedArray()
    }

    override fun equals(other: Any?): Boolean = other is File && other.p == p
    override fun hashCode(): Int = p.hashCode() xor 1234321
    override fun toString(): String = p

    private companion object {
        fun join(parent: String?, child: String): String = when {
            parent == null -> child
            parent.isEmpty() -> "/$child"
            parent.endsWith("/") -> parent + child
            else -> "$parent/$child"
        }
    }
}

actual val File.name: String get() = p.substringAfterLast('/')
actual val File.path: String get() = p
actual val File.absolutePath: String get() = if (p.startsWith("/")) p else "/$p"
actual val File.parentFile: File?
    get() {
        val i = p.lastIndexOf('/')
        return when {
            i < 0 || p == "/" -> null
            i == 0 -> File("/")
            else -> File(p.substring(0, i))
        }
    }
actual val File.isFile: Boolean get() = fsKind(p) == 1
actual val File.isDirectory: Boolean get() = fsKind(p) == 2
actual val File.extension: String get() = name.substringAfterLast('.', "")
actual val File.nameWithoutExtension: String get() = name.substringBeforeLast(".")

actual fun File.readText(): String = fsReadText(p)
actual fun File.writeText(text: String) {
    fsWriteText(p, text)
    fsChanged()
}
actual fun File.readBytes(): ByteArray = fromLatin1(fsReadLatin1(p))
actual fun File.writeBytes(array: ByteArray) {
    fsWriteLatin1(p, latin1(array))
    fsChanged()
}
actual fun File.copyTo(target: File, overwrite: Boolean): File {
    if (!exists()) throw NoSuchElementException("$this: the source file does not exist")
    if (target.exists()) {
        if (!overwrite) throw IllegalStateException("$target: the file already exists")
        if (!target.delete()) throw IllegalStateException("$target: could not be replaced")
    }
    if (isDirectory) target.mkdirs()
    else {
        target.parentFile?.mkdirs()
        target.writeBytes(readBytes())
    }
    return target
}
actual fun File.deleteRecursively(): Boolean {
    var all = true
    walk().toList().asReversed().forEach { if (!it.delete() && it.exists()) all = false }
    return all
}
actual fun File.resolve(relative: String): File = if (relative.startsWith("/")) File(relative) else File(this, relative)
actual fun File.listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()
actual fun File.walk(): Sequence<File> = sequence {
    if (!exists()) return@sequence
    val stack = ArrayDeque<File>().apply { add(this@walk) }
    while (stack.isNotEmpty()) {
        val f = stack.removeLast()
        yield(f)
        f.listFiles()?.let { children -> for (c in children.sortedByDescending { it.p }) stack.addLast(c) }
    }
}

actual val File.canonicalFile: File
    get() {
        val out = ArrayList<String>()
        for (part in absolutePath.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out.add(part)
        }
        return File("/" + out.joinToString("/"))
    }
actual fun File.relativeTo(base: File): File {
    val to = canonicalFile.p.split('/').filter { it.isNotEmpty() }
    val from = base.canonicalFile.p.split('/').filter { it.isNotEmpty() }
    var same = 0
    while (same < to.size && same < from.size && to[same] == from[same]) same++
    return File((List(from.size - same) { ".." } + to.drop(same)).joinToString("/"))
}
actual val File.invariantSeparatorsPath: String get() = p
actual val FILE_SEPARATOR: String get() = "/"

/** Writes beside it and renames over it, as on the JVM. It's only durable once the page saves browser storage. */
actual fun File.writeBytesSafely(bytes: ByteArray) {
    val tmp = File(parentFile, ".$name.tmp")
    try {
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) throw IllegalStateException("could not replace $name")
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

actual class ZipWriter actual constructor(private val out: File) {
    private val zip = Zip.Writer()
    actual fun add(name: String, bytes: ByteArray) = zip.add(name, bytes)
    actual fun addFile(name: String, file: File) = zip.add(name, file.readBytes())
    actual fun close() = out.writeBytes(zip.finish())
}

actual fun readZip(zip: File, each: (name: String, isDirectory: Boolean, bytes: () -> ByteArray) -> Unit) =
    Zip.read(zip.readBytes(), each)
