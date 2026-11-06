package com.snaptube.clone.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.snaptube.clone.SnapTubeApp
import com.snaptube.clone.data.model.DownloadStatus
import com.snaptube.clone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        observeDownloads()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SnapTubeApp.CHANNEL_DOWNLOAD)
            .setContentTitle("SnapTube Downloader")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeDownloads() {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            DownloadManager.tasks.collectLatest { tasks ->
                val activeTasks = tasks.filter { it.status == DownloadStatus.DOWNLOADING }
                if (activeTasks.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }

                val current = activeTasks.first()
                val notification = NotificationCompat.Builder(this@DownloadForegroundService, SnapTubeApp.CHANNEL_DOWNLOAD)
                    .setContentTitle("Downloading: ${current.title}")
                    .setContentText("${current.progressPercent}% · ${current.readableSpeed}")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, current.progressPercent, false)
                    .setOngoing(true)
                    .build()

                val manager = ContextCompat.getSystemService(this@DownloadForegroundService, android.app.NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
