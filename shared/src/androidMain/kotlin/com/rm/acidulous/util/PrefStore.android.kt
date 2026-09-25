package com.rm.acidulous.util

import android.content.SharedPreferences

/** SharedPreferences as a [PrefStore]: every call is the one it was. */
fun androidPrefs(p: SharedPreferences): PrefStore = object : PrefStore {
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun edit(): PrefStore.Editor = object : PrefStore.Editor {
        val e = p.edit()
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun apply() = e.apply()
    }
}
