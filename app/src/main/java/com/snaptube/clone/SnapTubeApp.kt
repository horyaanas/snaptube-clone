package com.snaptube.clone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SnapTubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                "SnapTube Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress and status notifications"
            }

            val playerChannel = NotificationChannel(
                CHANNEL_PLAYER,
                "SnapTube Media Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background media playback controls"
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
