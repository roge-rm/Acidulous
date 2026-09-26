package com.rm.acidulous.util

/**
 * A map that keeps the [keep] entries used last: an access-ordered
 * LinkedHashMap trimming its eldest, which is a JVM class, done in the open.
 * Not locked; callers that share one lock it themselves.
 */
class LruMap<K, V>(private val keep: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > keep) map.remove(map.keys.first())
    }

    val size: Int get() = map.size
}
