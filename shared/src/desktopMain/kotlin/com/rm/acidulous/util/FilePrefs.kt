package com.rm.acidulous.util

import java.io.File
import java.util.Properties

/**
 * A [PrefStore] in a properties file, for the desktop. Read once; each apply
 * writes the whole file, which for a few dozen settings changed by hand is
 * nothing. Written beside itself and renamed over, so a crash mid-write
 * leaves the old settings rather than half of them.
 */
class FilePrefs(private val file: File) : PrefStore {
    private val values = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    override fun getBoolean(key: String, default: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun getInt(key: String, default: Int) = values.getProperty(key)?.toIntOrNull() ?: default
    override fun getFloat(key: String, default: Float) = values.getProperty(key)?.toFloatOrNull() ?: default
    override fun getString(key: String, default: String?): String? = values.getProperty(key) ?: default

    override fun edit(): PrefStore.Editor = object : PrefStore.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value.toString() }
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun apply() {
            synchronized(values) {
                for ((k, v) in changes) if (v == null) values.remove(k) else values.setProperty(k, v)
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".new")
                temp.outputStream().use { values.store(it, "Acidulous settings") }
                temp.renameTo(file)
            }
        }
    }
}
