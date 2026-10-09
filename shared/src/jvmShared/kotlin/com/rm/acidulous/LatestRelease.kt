package com.rm.acidulous

import java.net.HttpURLConnection
import java.net.URL

/**
 * The newest release's version on GitHub, like "0.11.3", or null if it can't
 * be had (offline, a timeout, GitHub busy). Blocks for up to ten seconds, so
 * call it off the main thread. Android and the desktop share it.
 */
fun latestReleaseOnGitHub(userAgent: String): String? = runCatching {
    val c = URL(RELEASES_API).openConnection() as HttpURLConnection
    c.connectTimeout = 5000
    c.readTimeout = 5000
    c.setRequestProperty("Accept", "application/vnd.github+json")
    c.setRequestProperty("User-Agent", userAgent)
    try {
        if (c.responseCode != 200) return@runCatching null
        val body = c.inputStream.bufferedReader().use { it.readText() }
        Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(body)?.groupValues?.get(1)
    } finally {
        c.disconnect()
    }
}.getOrNull()

private const val RELEASES_API = "https://api.github.com/repos/roge-rm/Acidulous/releases/latest"
