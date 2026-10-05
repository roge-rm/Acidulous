package com.rm.acidulous

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * The app's language on Android: the one chosen in settings, or the system's
 * (on Android 13 and later, the one the system's app settings give it).
 *
 * The activity's context is made in that language before anything is drawn,
 * and the default locale is set to it, which is what the strings read.
 */
object AppLanguage {
    /** The tag chosen in settings, read from the settings file itself: UiPrefs isn't up yet. */
    private fun chosen(context: Context): String? {
        val name = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("language", null) ?: return null
        return com.rm.acidulous.ui.UiPrefs.Language.entries.firstOrNull { it.name == name }?.tag
    }

    private fun systemLocale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) {
            val own = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            if (own != null && !own.isEmpty) return own.get(0)
        }
        return Resources.getSystem().configuration.locales.get(0)
    }

    /** [base] in the app's language, for attachBaseContext. */
    fun wrap(base: Context): Context {
        val tag = chosen(base)
        if (tag == null) {
            Locale.setDefault(systemLocale(base))
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
