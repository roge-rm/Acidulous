package com.rm.acidulous.web

import com.rm.acidulous.util.PrefStore

private fun storageGet(key: String): String? = js("(() => { try { return localStorage.getItem(key); } catch (e) { return null; } })()")
private fun storageSet(key: String, value: String): Unit = js("(() => { try { localStorage.setItem(key, value); } catch (e) {} })()")
private fun storageRemove(key: String): Unit = js("(() => { try { localStorage.removeItem(key); } catch (e) {} })()")

/**
 * A [PrefStore] in the browser's local storage, one key per setting under
 * "acidulous.[name].". Values are read when asked for and apply() writes only
 * what changed.
 */
class LocalPrefs(name: String) : PrefStore {
    private val prefix = "acidulous.$name."

    override fun getBoolean(key: String, default: Boolean) = storageGet(prefix + key)?.toBooleanStrictOrNull() ?: default
    override fun getInt(key: String, default: Int) = storageGet(prefix + key)?.toIntOrNull() ?: default
    override fun getFloat(key: String, default: Float) = storageGet(prefix + key)?.toFloatOrNull() ?: default
    override fun getString(key: String, default: String?): String? = storageGet(prefix + key) ?: default

    override fun edit(): PrefStore.Editor = object : PrefStore.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value.toString() }
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun apply() {
            for ((k, v) in changes) if (v == null) storageRemove(prefix + k) else storageSet(prefix + k, v)
        }
    }
}
