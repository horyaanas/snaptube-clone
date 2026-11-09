package com.snaptube.clone.ui.files

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.snaptube.clone.R
import com.snaptube.clone.data.model.DownloadStatus
import com.snaptube.clone.data.model.DownloadTask
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.download.DownloadManager
import com.snaptube.clone.ui.theme.SnapYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class MediaFile(
    val path: String,
    val title: String,
    val sizeBytes: Long,
    val isAudio: Boolean,
    val thumbnail: String,
    val task: DownloadTask?
)

private enum class FileTab(val labelRes: Int) {
    VIDEOS(R.string.download_as_video),
    AUDIO(R.string.download_as_music),
    ACTIVE(R.string.downloading)
}

@Composable
fun FilesScreen(
    onPlay: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var mediaFiles by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    val tasks by DownloadManager.tasks.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(tasks, refreshKey) {
        scanning = true
        mediaFiles = withContext(Dispatchers.IO) {
            buildMediaFiles(context, tasks)
        }
        scanning = false
    }

    val activeTasks = tasks.filter {
        it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.MERGING ||
            it.status == DownloadStatus.PAUSED ||
            it.status == DownloadStatus.FAILED
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.my_files),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry), tint = SnapYellow)
            }
        }

        TabRow(
            selectedTabIndex = selected,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = SnapYellow,
            indicator = { positions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(positions[selected]),
                    color = SnapYellow,
                    height = 3.dp
                )
            }
        ) {
            FileTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = {
                        val count = when (tab) {
                            FileTab.VIDEOS -> mediaFiles.count { !it.isAudio }
                            FileTab.AUDIO -> mediaFiles.count { it.isAudio }
                            FileTab.ACTIVE -> activeTasks.size
                        }
                        Text(
                            text = "${stringResource(tab.labelRes)} ($count)",
                            fontSize = 13.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) SnapYellow
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        when (FileTab.entries[selected]) {
            FileTab.VIDEOS -> FileList(
                files = mediaFiles.filter { !it.isAudio },
                emptyText = stringResource(R.string.empty_videos),
                scanning = scanning,
                onPlay = onPlay,
                onDelete = { file ->
                    file.task?.let { DownloadManager.deleteTask(it.id) } ?: File(file.path).delete()
                    refreshKey++
                }
            )

            FileTab.AUDIO -> FileList(
                files = mediaFiles.filter { it.isAudio },
                emptyText = stringResource(R.string.empty_audio),
                scanning = scanning,
                onPlay = onPlay,
                onDelete = { file ->
                    file.task?.let { DownloadManager.deleteTask(it.id) } ?: File(file.path).delete()
                    refreshKey++
                }
            )

            FileTab.ACTIVE -> if (activeTasks.isEmpty()) {
                EmptyState(stringResource(R.string.empty_downloads))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(activeTasks, key = { it.id }) { task ->
                        ActiveDownloadRow(task)
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    }
                }
            }
        }
    }
}

private fun buildMediaFiles(context: android.content.Context, tasks: List<DownloadTask>): List<MediaFile> {
    val completed = tasks.filter { it.status == DownloadStatus.COMPLETED }
    val byPath = completed.associateBy { it.targetFilePath }
    val result = mutableListOf<MediaFile>()

    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "downloads")

    val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
    files.forEach { file ->
        val extension = file.extension.lowercase()
        val isAudio = extension in setOf("m4a", "mp3", "opus", "ogg", "wav", "aac", "flac")
        val isVideo = extension in setOf("mp4", "webm", "mkv", "3gp", "mov")
        if (!isAudio && !isVideo) return@forEach

        val task = byPath[file.absolutePath]
        result += MediaFile(
            path = file.absolutePath,
            title = task?.title ?: file.nameWithoutExtension,
            sizeBytes = file.length(),
            isAudio = task?.format?.type == MediaType.AUDIO || (task == null && isAudio),
            thumbnail = task?.thumbnailUrl.orEmpty(),
            task = task
        )
    }

    // Completed tasks whose files may live elsewhere (custom folder).
    completed.forEach { task ->
        if (result.none { it.path == task.targetFilePath } && File(task.targetFilePath).exists()) {
            result += MediaFile(
                path = task.targetFilePath,
                title = task.title,
                sizeBytes = File(task.targetFilePath).length(),
                isAudio = task.format.type == MediaType.AUDIO,
                thumbnail = task.thumbnailUrl,
                task = task
            )
        }
    }

    return result.sortedByDescending { File(it.path).lastModified() }
}

@Composable
private fun FileList(
    files: List<MediaFile>,
    emptyText: String,
    scanning: Boolean,
    onPlay: (String, String, Boolean) -> Unit,
    onDelete: (MediaFile) -> Unit
) {
    when {
        scanning && files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = SnapYellow)
        }
        files.isEmpty() -> EmptyState(emptyText)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(files, key = { it.path }) { file ->
                MediaFileRow(file = file, onPlay = onPlay, onDelete = onDelete)
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
        }
    }
}

@Composable
private fun MediaFileRow(
    file: MediaFile,
    onPlay: (String, String, Boolean) -> Unit,
    onDelete: (MediaFile) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay(file.path, file.title, file.isAudio) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 52.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (file.thumbnail.isNotBlank() && !file.isAudio) {
                AsyncImage(
                    model = file.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (file.isAudio) Icons.Default.MusicNote else Icons.Default.Movie,
                    contentDescription = null,
                    tint = SnapYellow,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(readableSize(file.sizeBytes))
                    file.task?.format?.qualityLabel?.let { append(" · $it") }
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onPlay(file.path, file.title, file.isAudio) },
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SnapYellow)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.play),
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )
        }

        IconButton(onClick = { onDelete(file) }, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ActiveDownloadRow(task: DownloadTask) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 52.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (task.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = task.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Movie, contentDescription = null, tint = SnapYellow)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = task.progressPercent / 100f,
                color = SnapYellow,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(task.progressPercent)
                    append("%")
                    if (task.format.qualityLabel.isNotBlank()) append(" · ${task.format.qualityLabel}")
                    when (task.status) {
                        DownloadStatus.MERGING -> append(" · merging")
                        DownloadStatus.PAUSED -> append(" · ${task.status.name.lowercase()}")
                        DownloadStatus.FAILED -> task.errorMessage?.let { append(" · $it") }
                        else -> if (task.readableSpeed.isNotBlank()) append(" · ${task.readableSpeed}")
                    }
                },
                fontSize = 11.sp,
                color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(6.dp))

        when (task.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.MERGING -> {
                IconButton(onClick = { DownloadManager.pauseTask(task.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = stringResource(R.string.pause),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            else -> {
                IconButton(onClick = { DownloadManager.resumeTask(task.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.resume),
                        tint = SnapYellow
                    )
                }
            }
        }

        IconButton(onClick = { DownloadManager.cancelTask(task.id) }, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}

private fun readableSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}
