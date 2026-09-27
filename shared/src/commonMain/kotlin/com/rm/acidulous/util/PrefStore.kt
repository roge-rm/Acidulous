package com.rm.acidulous.util

/**
 * Where the app's settings live. Shaped like the part of Android's
 * SharedPreferences the app uses: SharedPreferences on Android
 * ([androidPrefs]), a properties file on desktop.
 */
interface PrefStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getFloat(key: String, default: Float): Float
    fun getString(key: String, default: String?): String?
    fun edit(): Editor

    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putString(key: String, value: String?): Editor
        fun remove(key: String): Editor
        /** Keeps the changes; like SharedPreferences.apply, it may write them later. */
        fun apply()
    }
}
