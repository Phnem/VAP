package com.phnem.vap.ui.onboarding

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/** Тема приложения: тёмная, светлая или как в системе. */
enum class ThemeChoice {
    SYSTEM, DARK, LIGHT;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        DARK -> true
        LIGHT -> false
    }

    companion object {
        fun parse(raw: String?): ThemeChoice = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** Язык приложения: русский, английский или язык системы ([tag] == null). */
enum class LanguageChoice(val tag: String?) {
    SYSTEM(null), RU("ru"), EN("en");

    companion object {
        fun parse(raw: String?): LanguageChoice = entries.firstOrNull { it.name == raw } ?: SYSTEM

        /** Что отметить на первом запуске, пока выбора нет: русский для русской системы, иначе английский. */
        fun suggested(systemLanguage: String): LanguageChoice =
            if (systemLanguage.lowercase().startsWith("ru")) RU else EN
    }
}

/** Выбор владельца с первого запуска. Хранится отдельно от настроек библиотеки. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("vap_app", Context.MODE_PRIVATE)

    var firstRunDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_RUN_DONE, value) }

    var theme: ThemeChoice
        get() = ThemeChoice.parse(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    var language: LanguageChoice
        get() = LanguageChoice.parse(prefs.getString(KEY_LANGUAGE, null))
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value.name) }

    private companion object {
        const val KEY_FIRST_RUN_DONE = "first_run_done"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
    }
}

/**
 * Язык интерфейса. До Android 13 системного выбора языка приложения нет, поэтому активности оборачивают
 * свой контекст в нужную локаль ([wrap] из `attachBaseContext`); на 13+ язык ещё и сообщается системе.
 */
object AppLocale {
    fun wrap(base: Context, choice: LanguageChoice): Context {
        val tag = choice.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Сохраняет выбор и пересоздаёт окно, чтобы весь текст сразу стал на новом языке. */
    fun change(activity: Activity, choice: LanguageChoice) {
        AppPrefs(activity).language = choice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Система сама пересоздаст активность.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags(choice.tag ?: "")
        } else {
            activity.recreate()
        }
    }
}
