package com.rm.acidulous

import java.net.HttpURLConnection
import java.net.URL

/**
 * The newest release's version on GitHub, like "0.11.3", or null if it can't
 * be had (offline, a timeout, GitHub busy). Blocks for up to ten seconds, so
 * call it off the main thread. Android and the desktop share it.
 *
 * With [asset] set, a release is only reported once it lists a file of that
 * name, for a platform whose download is uploaded after the release.
 */
fun latestReleaseOnGitHub(userAgent: String, asset: ((String) -> String)? = null): String? = runCatching {
    val c = URL(RELEASES_API).openConnection() as HttpURLConnection
    c.connectTimeout = 5000
    c.readTimeout = 5000
    c.setRequestProperty("Accept", "application/vnd.github+json")
    c.setRequestProperty("User-Agent", userAgent)
    try {
        if (c.responseCode != 200) return@runCatching null
        val body = c.inputStream.bufferedReader().use { it.readText() }
        releaseVersion(body, asset)
    } finally {
        c.disconnect()
    }
}.getOrNull()

private const val RELEASES_API = "https://api.github.com/repos/roge-rm/Acidulous/releases/latest"

/** The version in a release's JSON, or null if [asset] is set and none of its files is named that. */
internal fun releaseVersion(body: String, asset: ((String) -> String)? = null): String? {
    val version = Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return null
    val wanted = asset?.invoke(version) ?: return version
    return version.takeIf { Regex("\"name\"\\s*:\\s*\"" + Regex.escape(wanted) + "\"").containsMatchIn(body) }
}
