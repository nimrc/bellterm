package app.bellterm.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * The app's display language. [System] follows the device; the others pin
 * one of the languages Bellterm ships, by BCP 47 [tag].
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Chinese("zh-CN"),
    ;

    companion object {
        /** The language a stored or system-reported tag names, else [System]. */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.lowercase() ?: return System
            return entries.firstOrNull { it.tag?.substringBefore('-') == language } ?: System
        }
    }
}

/**
 * Applies the chosen [AppLanguage]. From Android 13 the system keeps the
 * per-app language, so it also shows under the app's settings there; before
 * that the choice is a preference applied to each context the app creates.
 */
object AppLanguages {
    private const val PREFS = "language"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.System else AppLanguage.fromTag(locales[0].toLanguageTag())
        } else {
            AppLanguage.fromTag(stored(context))
        }

    /** Switches the app to [language]; the activity is recreated to show it. */
    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
            activity.recreate()
        }
    }

    /**
     * [base] with the chosen language below Android 13, for attachBaseContext
     * of the application, activities, and services; unchanged otherwise.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = AppLanguage.fromTag(stored(base)).tag ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
