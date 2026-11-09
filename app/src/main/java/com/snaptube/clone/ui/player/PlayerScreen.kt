package com.snaptube.clone.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.snaptube.clone.R
import com.snaptube.clone.player.AudioPlayerService
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    path: String,
    title: String,
    isAudio: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaUri = remember(path) {
        if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
    }
    val player = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    player.pause()
                    onBack()
                },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isAudio) stringResource(R.string.download_as_music)
                    else stringResource(R.string.download_as_video),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
            IconButton(
                onClick = {
                    val position = runCatching { player.currentPosition }.getOrDefault(0L)
                    runCatching {
                        AudioPlayerService.play(
                            context = context,
                            uri = mediaUri.toString(),
                            title = title,
                            positionMs = position
                        )
                        player.pause()
                    }
                    onBack()
                },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = stringResource(R.string.play_in_background),
                    tint = Color.White
                )
            }
        }
    }
}
