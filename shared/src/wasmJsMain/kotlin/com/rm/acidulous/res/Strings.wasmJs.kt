package com.rm.acidulous.res

import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

// A page can't block its only thread waiting for a resource, so every string
// is loaded once before the app starts ([preloadStrings]) and looked up here
// after that. Plurals keep two forms, one and other: English puts only 1 in
// one, French 0 and 1 too.

private val strings = HashMap<String, String>()
private val arrays = HashMap<String, List<String>>()
private val plurals = HashMap<String, Pair<String, String>>()

internal actual fun loadString(res: StringResource): String = strings[res.key] ?: ""

internal actual fun loadPlural(res: PluralStringResource, count: Int): String {
    val one = if (androidx.compose.ui.text.intl.Locale.current.language == "fr") count == 0 || count == 1 else count == 1
    return plurals[res.key]?.let { if (one) it.first else it.second } ?: ""
}

internal actual fun loadStringArray(res: StringArrayResource): List<String> = arrays[res.key] ?: emptyList()

/** Loads every string before the app draws anything. */
@OptIn(ExperimentalResourceApi::class)
suspend fun preloadStrings() {
    for (res in Res.allStringResources.values) strings[res.key] = org.jetbrains.compose.resources.getString(res)
    for (res in Res.allStringArrayResources.values) arrays[res.key] = org.jetbrains.compose.resources.getStringArray(res)
    for (res in Res.allPluralStringResources.values) plurals[res.key] =
        org.jetbrains.compose.resources.getPluralString(res, 1) to org.jetbrains.compose.resources.getPluralString(res, 2)
}
