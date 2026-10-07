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

//
// Composed strings come from AppStrings' cache too. Compose's own lookup waits
// on a coroutine for each one the first time a screen is built, and an editor
// has a few hundred. A language change recreates the activity (it isn't in the
// manifest's configChanges), and the cache is by language anyway.

@Composable
fun stringResource(res: StringResource): String = AppStrings.getString(res)

@Composable
fun stringResource(res: StringResource, vararg args: Any?): String =
    AppStrings.getString(res).format(*args)

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
    FrenchTypography.ofLocale(org.jetbrains.compose.resources.pluralStringResource(res, count))

@Composable
fun pluralStringResource(res: PluralStringResource, count: Int, vararg args: Any?): String =
    FrenchTypography.ofLocale(org.jetbrains.compose.resources.pluralStringResource(res, count)).format(*args)

@Composable
fun stringArrayResource(res: StringArrayResource): Array<String> =
    org.jetbrains.compose.resources.stringArrayResource(res).map(FrenchTypography::ofLocale).toTypedArray()

/**
 * Strings outside a composition, replacing Android's `Resources`.
 *
 * Named and shaped the same so `resources.getString(...)` still works with
 * `val resources = AppStrings`. Blocking, because callers need the text now
 * and the lookup reads a small cached file.
 */
object AppStrings {
    /**
     * Each string as first looked up, by key and language. A lookup waits on a
     * coroutine even when the resource file is cached, and a screen asks for
     * hundreds (a drum grid's cells say what they are to TalkBack), which made
     * opening an editor slow. A language change is a new key.
     */
    private val strings = HashMap<String, String>()

    fun getString(res: StringResource): String {
        val key = androidx.compose.ui.text.intl.Locale.current.toLanguageTag() + '/' + res.key
        // France's French is Canada's with France's spacing; see FrenchTypography.
        return strings[key] ?: FrenchTypography.ofLocale(loadString(res)).also { strings[key] = it }
    }

    fun getString(res: StringResource, vararg args: Any?): String =
        getString(res).format(*args)

    fun getQuantityString(res: PluralStringResource, count: Int, vararg args: Any?): String {
        val raw = FrenchTypography.ofLocale(loadPlural(res, count))
        return if (args.isEmpty()) raw else raw.format(*args)
    }

    fun getStringArray(res: StringArrayResource): Array<String> =
        loadStringArray(res).map(FrenchTypography::ofLocale).toTypedArray()
}

// The lookups. Where a thread can wait, they wait for the resource read. The
// browser's single thread can't, so it reads them all before the app starts.
internal expect fun loadString(res: StringResource): String
internal expect fun loadPlural(res: PluralStringResource, count: Int): String
internal expect fun loadStringArray(res: StringArrayResource): List<String>
