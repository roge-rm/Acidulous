package com.rm.acidulous.res

import androidx.compose.runtime.Composable
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

// The app's strings, looked up the way Android looked them up.
//
// They moved from Android's resources to Compose Multiplatform's so that the
// desktop build can have them too, and these keep the calls the code already
// made - stringResource(id, args), resources.getString(id, args) - meaning
// what they meant. Compose's own formatter only knows `%1$s` and `%1$d`, and
// the strings use the rest of printf (`%.1f`, `%3$-8s`, `%8$03d`), so the raw
// string is fetched and String.format does the formatting, exactly as
// Resources.getString did.

@Composable
fun stringResource(res: StringResource): String = org.jetbrains.compose.resources.stringResource(res)

@Composable
fun stringResource(res: StringResource, vararg args: Any?): String =
    String.format(Locale.getDefault(), org.jetbrains.compose.resources.stringResource(res), *args)

@Composable
fun pluralStringResource(res: PluralStringResource, count: Int): String =
    org.jetbrains.compose.resources.pluralStringResource(res, count)

@Composable
fun pluralStringResource(res: PluralStringResource, count: Int, vararg args: Any?): String =
    String.format(Locale.getDefault(), org.jetbrains.compose.resources.pluralStringResource(res, count), *args)

@Composable
fun stringArrayResource(res: StringArrayResource): Array<String> =
    org.jetbrains.compose.resources.stringArrayResource(res).toTypedArray()

/**
 * The strings outside a composition: what `Resources` was for.
 *
 * Named and shaped like it on purpose - `resources.getString(...)` still reads
 * the same with `val resources = AppStrings` - and blocking, because every
 * caller wants the text now and the lookup is a read of a small cached file.
 */
object AppStrings {
    fun getString(res: StringResource): String = runBlocking { org.jetbrains.compose.resources.getString(res) }

    fun getString(res: StringResource, vararg args: Any?): String =
        String.format(Locale.getDefault(), getString(res), *args)

    fun getQuantityString(res: PluralStringResource, count: Int, vararg args: Any?): String {
        val raw = runBlocking { org.jetbrains.compose.resources.getPluralString(res, count) }
        return if (args.isEmpty()) raw else String.format(Locale.getDefault(), raw, *args)
    }

    fun getStringArray(res: StringArrayResource): Array<String> =
        runBlocking { org.jetbrains.compose.resources.getStringArray(res) }.toTypedArray()
}
