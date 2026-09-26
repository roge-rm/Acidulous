package com.rm.acidulous.res

import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

// A browser's page cannot wait for a resource on its one thread, so every
// string is read once before the app starts ([preloadStrings]) and looked up
// from here after. Plurals are kept in English's two forms, one and other:
// the strings are English's.

private val strings = HashMap<String, String>()
private val arrays = HashMap<String, List<String>>()
private val plurals = HashMap<String, Pair<String, String>>()

internal actual fun loadString(res: StringResource): String = strings[res.key] ?: ""

internal actual fun loadPlural(res: PluralStringResource, count: Int): String =
    plurals[res.key]?.let { if (count == 1) it.first else it.second } ?: ""

internal actual fun loadStringArray(res: StringArrayResource): List<String> = arrays[res.key] ?: emptyList()

/** Every string, read before the app draws anything. */
@OptIn(ExperimentalResourceApi::class)
suspend fun preloadStrings() {
    for (res in Res.allStringResources.values) strings[res.key] = org.jetbrains.compose.resources.getString(res)
    for (res in Res.allStringArrayResources.values) arrays[res.key] = org.jetbrains.compose.resources.getStringArray(res)
    for (res in Res.allPluralStringResources.values) plurals[res.key] =
        org.jetbrains.compose.resources.getPluralString(res, 1) to org.jetbrains.compose.resources.getPluralString(res, 2)
}
