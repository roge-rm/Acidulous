package com.rm.acidulous.res

import com.rm.acidulous.util.format

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

// The app's strings, looked up the same way as on Android.
//
// They're in Compose Multiplatform resources so the desktop build has them
// too, and these keep the existing calls (stringResource(id, args),
// resources.getString(id, args)) working the same. Compose's own formatter
// only handles `%1$s` and `%1$d`, but the strings use more of printf
// (`%.1f`, `%3$-8s`, `%8$03d`), so we fetch the raw string and format it
// with String.format, like Resources.getString did.

@Composable
fun stringResource(res: StringResource): String = org.jetbrains.compose.resources.stringResource(res)

@Composable
fun stringResource(res: StringResource, vararg args: Any?): String =
    org.jetbrains.compose.resources.stringResource(res).format(*args)

/**
 * The phone's wording, [touch], or [mouse] when the pointer is a mouse
 * (AppHost.usesMouse): "click" instead of "tap". The Android strings stay as
 * they are and the desktop versions are named with `_mouse`.
 */
@Composable
fun stringResource(touch: StringResource, mouse: StringResource): String =
    stringResource(if (com.rm.acidulous.AppHost.current.usesMouse) mouse else touch)

/**
 * The phone's wording, [phone], or the desktop version, [desktop], when the
 * text is about which device this is ("saved on this phone"). Named with
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
 * Strings outside a composition, replacing Android's `Resources`.
 *
 * Named and shaped the same so `resources.getString(...)` still works with
 * `val resources = AppStrings`. Blocking, because callers need the text now
 * and the lookup reads a small cached file.
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

// The lookups. Where a thread can wait, they wait for the resource read. The
// browser's single thread can't, so it reads them all before the app starts.
internal expect fun loadString(res: StringResource): String
internal expect fun loadPlural(res: PluralStringResource, count: Int): String
internal expect fun loadStringArray(res: StringArrayResource): List<String>
