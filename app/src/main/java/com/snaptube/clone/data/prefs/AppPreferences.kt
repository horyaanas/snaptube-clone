package com.snaptube.clone.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Lightweight persisted settings used across the app.
 */
object AppPreferences {

    private const val PREFS_NAME = "snaptube_prefs"

    private const val KEY_THEME = "theme_mode"
    private const val KEY_THREADS = "download_threads"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_REMEMBER_FORMAT = "remember_format"
    private const val KEY_LANGUAGE = "language_tag"
    private const val KEY_HISTORY = "search_history"
    private const val KEY_NOTIFICATIONS = "notifications"
    private const val KEY_DOWNLOAD_DIR = "download_dir"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = prefs

    var themeMode: Int
        get() = p().getInt(KEY_THEME, THEME_SYSTEM)
        set(value) {
            p().edit().putInt(KEY_THEME, value).apply()
            applyTheme(value)
        }

    var downloadThreads: Int
        get() = p().getInt(KEY_THREADS, 4).coerceIn(1, 8)
        set(value) = p().edit().putInt(KEY_THREADS, value.coerceIn(1, 8)).apply()

    var wifiOnly: Boolean
        get() = p().getBoolean(KEY_WIFI_ONLY, false)
        set(value) = p().edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var rememberFormat: Boolean
        get() = p().getBoolean(KEY_REMEMBER_FORMAT, false)
        set(value) = p().edit().putBoolean(KEY_REMEMBER_FORMAT, value).apply()

    var notificationsEnabled: Boolean
        get() = p().getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = p().edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var downloadDir: String
        get() = p().getString(KEY_DOWNLOAD_DIR, "") ?: ""
        set(value) = p().edit().putString(KEY_DOWNLOAD_DIR, value).apply()

    var languageTag: String
        get() = p().getString(KEY_LANGUAGE, "") ?: ""
        set(value) {
            p().edit().putString(KEY_LANGUAGE, value).apply()
            applyLanguage(value)
        }

    var searchHistory: List<String>
        get() = p().getString(KEY_HISTORY, "")
            .orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
        set(value) = p().edit()
            .putString(KEY_HISTORY, value.distinct().take(20).joinToString("\n"))
            .apply()

    fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchHistory = listOf(q) + searchHistory.filterNot { it.equals(q, ignoreCase = true) }
    }

    fun clearSearchHistory() {
        p().edit().remove(KEY_HISTORY).apply()
    }

    fun applyTheme(mode: Int = themeMode) {
        val nightMode = when (mode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun applyLanguage(tag: String = languageTag) {
        val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
