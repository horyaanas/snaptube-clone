package com.snaptube.clone.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.snaptube.clone.ui.MainActivity

/**
 * Background audio playback with real lock-screen / notification controls.
 *
 * Media3's [MediaSessionService] publishes the session to the system media
 * controls, so play/pause/seek keep working while the app is in the
 * background or the screen is locked.
 */
class AudioPlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setHandleAudioBecomingNoisy(true)
        }
        player = exoPlayer

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            val uri = intent.getStringExtra(EXTRA_URI)
            if (!uri.isNullOrBlank()) {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "SnapTube" }
                val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                val position = intent.getLongExtra(EXTRA_POSITION, 0L)
                val item = MediaItem.Builder()
                    .setUri(Uri.parse(uri))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .build()
                    )
                    .build()
                player?.apply {
                    setMediaItem(item)
                    prepare()
                    seekTo(position.coerceAtLeast(0L))
                    playWhenReady = true
                }
            }
        } else if (intent?.action == ACTION_STOP) {
            player?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady) {
            stopSelf()
        }
        // Keep playing when the task is removed while audio is active.
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.snaptube.clone.action.PLAY_AUDIO"
        const val ACTION_STOP = "com.snaptube.clone.action.STOP_AUDIO"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_POSITION = "extra_position"

        /** Hands audio playback to the foreground media session. */
        fun play(
            context: Context,
            uri: String,
            title: String,
            artist: String = "",
            positionMs: Long = 0L
        ) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_POSITION, positionMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
