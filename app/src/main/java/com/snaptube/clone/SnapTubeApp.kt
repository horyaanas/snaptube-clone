package com.snaptube.clone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.snaptube.clone.data.extractor.MediaRepository
import com.snaptube.clone.data.extractor.NewPipeDownloader
import com.snaptube.clone.data.prefs.AppPreferences
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class SnapTubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        AppPreferences.applyTheme()
        AppPreferences.applyLanguage()
        createNotificationChannels()
        initExtractor()
    }

    private fun initExtractor() {
        try {
            NewPipe.init(
                NewPipeDownloader(),
                Localization("en", "US"),
                ContentCountry("US")
            )
            MediaRepository.ensureInitialized()
        } catch (t: Throwable) {
            Log.e("SnapTubeApp", "Failed to initialise media extractor", t)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                getString(R.string.downloading),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.settings_notifications_desc)
                setShowBadge(false)
            }

            val playerChannel = NotificationChannel(
                CHANNEL_PLAYER,
                getString(R.string.play),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.play)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(downloadChannel)
            manager.createNotificationChannel(playerChannel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "snaptube_download_channel"
        const val CHANNEL_PLAYER = "snaptube_player_channel"
    }
}
