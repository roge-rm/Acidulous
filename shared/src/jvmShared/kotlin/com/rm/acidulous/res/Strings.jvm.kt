package com.rm.acidulous.res

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

internal actual fun loadString(res: StringResource): String =
    runBlocking { org.jetbrains.compose.resources.getString(res) }

internal actual fun loadPlural(res: PluralStringResource, count: Int): String =
    runBlocking { org.jetbrains.compose.resources.getPluralString(res, count) }

internal actual fun loadStringArray(res: StringArrayResource): List<String> =
    runBlocking { org.jetbrains.compose.resources.getStringArray(res) }
