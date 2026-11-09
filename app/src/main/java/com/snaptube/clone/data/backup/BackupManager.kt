package com.snaptube.clone.data.backup

import android.content.Context
import android.net.Uri
import com.snaptube.clone.data.prefs.AppPreferences
import com.snaptube.clone.download.DownloadManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Real JSON backup/restore for settings and download history.
 *
 * The exported document can be written anywhere through SAF (Downloads,
 * Drive, a USB stick, …) and imported on another install.
 */
object BackupManager {

    private const val BACKUP_VERSION = 1

    data class RestoreResult(
        val settingsRestored: Boolean,
        val historyRestored: Boolean,
        val languageChanged: Boolean,
        val themeChanged: Boolean
    )

    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "SnapTube Clone")
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put(
            "settings",
            JSONObject().apply {
                put("themeMode", AppPreferences.themeMode)
                put("downloadThreads", AppPreferences.downloadThreads)
                put("wifiOnly", AppPreferences.wifiOnly)
                put("rememberFormat", AppPreferences.rememberFormat)
                put("notificationsEnabled", AppPreferences.notificationsEnabled)
                put("languageTag", AppPreferences.languageTag)
                put("searchHistory", JSONArray(AppPreferences.searchHistory))
            }
        )

        root.put("history", JSONArray(DownloadManager.exportHistoryJson()))
        return root.toString(2)
    }

    fun restoreJson(json: String): RestoreResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw IOException("Invalid backup file")
        if (!root.has("settings") && !root.has("history")) {
            throw IOException("This file is not a SnapTube backup")
        }

        var languageChanged = false
        var themeChanged = false
        var settingsRestored = false

        root.optJSONObject("settings")?.let { settings ->
            settingsRestored = true
            if (settings.has("themeMode")) {
                val mode = settings.optInt("themeMode", AppPreferences.themeMode)
                if (mode != AppPreferences.themeMode) themeChanged = true
                AppPreferences.themeMode = mode
            }
            if (settings.has("downloadThreads")) {
                AppPreferences.downloadThreads = settings.optInt("downloadThreads", 4)
            }
            if (settings.has("wifiOnly")) {
                AppPreferences.wifiOnly = settings.optBoolean("wifiOnly", false)
            }
            if (settings.has("rememberFormat")) {
                AppPreferences.rememberFormat = settings.optBoolean("rememberFormat", false)
            }
            if (settings.has("notificationsEnabled")) {
                AppPreferences.notificationsEnabled = settings.optBoolean("notificationsEnabled", true)
            }
            if (settings.has("languageTag")) {
                val tag = settings.optString("languageTag")
                if (tag != AppPreferences.languageTag) languageChanged = true
                AppPreferences.languageTag = tag
            }
            settings.optJSONArray("searchHistory")?.let { array ->
                val history = buildList {
                    for (i in 0 until array.length()) {
                        val value = array.optString(i).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }
                if (history.isNotEmpty()) AppPreferences.searchHistory = history
            }
        }

        var historyRestored = false
        root.optJSONArray("history")?.let { history ->
            historyRestored = true
            DownloadManager.importHistory(history.toString())
        }

        return RestoreResult(
            settingsRestored = settingsRestored,
            historyRestored = historyRestored,
            languageChanged = languageChanged,
            themeChanged = themeChanged
        )
    }

    fun writeToUri(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "w").use { output ->
            if (output == null) throw IOException("Could not open the backup file")
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    fun readFromUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("Could not read the backup file")
    }
}
