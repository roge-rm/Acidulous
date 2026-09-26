package com.rm.acidulous.res

import com.rm.acidulous.util.format

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

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
    org.jetbrains.compose.resources.stringResource(res).format(*args)

/**
 * The phone's words, [touch], or [mouse]'s where the pointer is a mouse
 * (AppHost.usesMouse): "click" where the phone says "tap". Android is the
 * app's home and its strings stay as they are; the desktop's are their twins,
 * named with `_mouse`.
 */
@Composable
fun stringResource(touch: StringResource, mouse: StringResource): String =
    stringResource(if (com.rm.acidulous.AppHost.current.usesMouse) mouse else touch)

/**
 * The phone's words, [phone], or the desktop build's twin, [desktop], where
 * the words are about which device this is ("saved on this phone"). Named with
 * `_desktop`; see AppHost.onDesktop.
 */
@Composable
fun deviceStringResource(phone: StringResource, desktop: StringResource): String =
    stringResource(if (com.rm.acidulous.AppHost.current.onDesktop) desktop else phone)

@Composable
fun pluralStringResource(res: PluralStringResource, count: Int): String =
    org.jetbrains.compose.resources.pluralStringResource(res, count)

@Composable
fun pluralStringResource(res: PluralStringResource, count: Int, vararg args: Any?): String =
    org.jetbrains.compose.resources.pluralStringResource(res, count).format(*args)

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
    fun getString(res: StringResource): String = loadString(res)

    fun getString(res: StringResource, vararg args: Any?): String =
        getString(res).format(*args)

    fun getQuantityString(res: PluralStringResource, count: Int, vararg args: Any?): String {
        val raw = loadPlural(res, count)
        return if (args.isEmpty()) raw else raw.format(*args)
    }

    fun getStringArray(res: StringArrayResource): Array<String> =
        loadStringArray(res).toTypedArray()
}

// The lookups themselves. Where a thread may wait, they wait for the resource
// read; a browser's one thread may not, and reads them all before the app starts.
internal expect fun loadString(res: StringResource): String
internal expect fun loadPlural(res: PluralStringResource, count: Int): String
internal expect fun loadStringArray(res: StringArrayResource): List<String>
